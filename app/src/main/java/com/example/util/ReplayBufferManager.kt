package com.example.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.UseCaseGroup
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.LinkedList

data class ReplayResult(val displayPath: String, val playablePath: String)

class ReplayBufferManager(private val context: Context) {
    private val TAG = "ReplayBufferManager"

    // Data class to represent each recorded segment in our buffer
    data class VideoSegment(val file: File, val durationMs: Long)

    // Flow for buffer duration setting (30, 60 or 120 seconds)
    private val _bufferLimitSeconds = MutableStateFlow(60)
    val bufferLimitSeconds: StateFlow<Int> = _bufferLimitSeconds.asStateFlow()

    // Flow for the current total active buffer duration in milliseconds
    private val _currentBufferDurationMs = MutableStateFlow(0L)
    val currentBufferDurationMs: StateFlow<Long> = _currentBufferDurationMs.asStateFlow()

    // Flow representing whether buffering/recording is active
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    // Flow representing whether a save operation is in progress
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // Flow representing a user-facing diagnostic status message
    private val _statusMessage = MutableStateFlow("Pronto para gravar")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // Queue of segments currently in the sliding window buffer
    private val segmentQueue = LinkedList<VideoSegment>()

    // CameraX video capture reference
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    
    // Coroutine scope for managing segment timeouts and status updates
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var segmentJob: Job? = null

    // Target segment length in milliseconds
    private val targetSegmentDurationMs = 5000L

    // Tracks if we are currently stopping a segment intentionally to finalize a merge
    private var isFinalizingForSave = false
    private var pendingSaveDeferred: CompletableDeferred<Boolean>? = null

    fun setBufferLimitSeconds(seconds: Int) {
        if (seconds == 30 || seconds == 60 || seconds == 120) {
            _bufferLimitSeconds.value = seconds
            Log.d(TAG, "Buffer limit changed to $seconds seconds")
            trimBuffer()
        }
    }

    fun setVideoCapture(capture: VideoCapture<Recorder>) {
        this.videoCapture = capture
        if (_isBuffering.value && activeRecording == null) {
            Log.d(TAG, "VideoCapture set while buffering was active. Starting segment recording.")
            startNextSegment()
        }
    }

    /**
     * Starts the continuous circular recording buffer
     */
    fun startBuffering() {
        if (_isBuffering.value) {
            if (videoCapture != null && activeRecording == null) {
                startNextSegment()
            }
            return
        }
        _isBuffering.value = true
        _statusMessage.value = "Iniciando buffering..."
        Log.d(TAG, "Starting circular buffering loop...")
        startNextSegment()
    }

    /**
     * Stops the buffering loop and clears the buffer files
     */
    fun stopBuffering() {
        _isBuffering.value = false
        segmentJob?.cancel()
        activeRecording?.stop()
        activeRecording = null
        clearAllSegments()
        _statusMessage.value = "Gravação parada e limpa."
        Log.d(TAG, "Buffering stopped and buffer cleared.")
    }

    /**
     * Records the next 5-second segment
     */
    private fun startNextSegment() {
        val capture = videoCapture
        if (capture == null || !_isBuffering.value || isFinalizingForSave) {
            val reason = when {
                capture == null -> "Aguardando câmera..."
                !_isBuffering.value -> "Buffering inativo"
                isFinalizingForSave -> "Finalizando para salvar"
                else -> "Aguardando"
            }
            _statusMessage.value = "Buffer suspenso: $reason"
            Log.d(TAG, "Cannot start next segment. captureIsNull=${capture == null}, isBuffering=${_isBuffering.value}, isSaving=$isFinalizingForSave")
            return
        }

        // Create a temporary file in cache directory
        val tempFile = File(context.cacheDir, "replay_segment_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(tempFile).build()

        try {
            val pendingRecording = capture.output.prepareRecording(context, outputOptions)
            
            // Enable audio if permission is granted
            val audioPermissionGranted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (audioPermissionGranted) {
                pendingRecording.withAudioEnabled()
            }

            val recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    val actualDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                    handleSegmentFinished(tempFile, actualDurationMs, event.error)
                }
            }

            activeRecording = recording
            _statusMessage.value = "Gravando trecho..."

            // Schedule this segment to stop after the target duration (5 seconds)
            segmentJob?.cancel()
            segmentJob = scope.launch {
                delay(targetSegmentDurationMs)
                if (activeRecording != null) {
                    Log.d(TAG, "Segment limit reached (5s). Stopping current segment to cycle buffer.")
                    activeRecording?.stop()
                }
            }

        } catch (e: Exception) {
            _statusMessage.value = "Falha ao gravar: ${e.message}"
            Log.e(TAG, "Error starting segment recording", e)
            _isBuffering.value = false
        }
    }

    /**
     * Called when a segment finishes recording
     */
    private fun handleSegmentFinished(file: File, durationMs: Long, errorCode: Int) {
        Log.d(TAG, "Segment finalized. Duration: ${durationMs}ms, error code: $errorCode, Path: ${file.absolutePath}")
        
        if (errorCode == VideoRecordEvent.Finalize.ERROR_NONE || errorCode == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            if (durationMs > 200) { // Keep only valid segments
                synchronized(segmentQueue) {
                    segmentQueue.add(VideoSegment(file, durationMs))
                    trimBuffer()
                }
                val currentSeconds = _currentBufferDurationMs.value / 1000L
                _statusMessage.value = "Trecho gravado. Buffer: ${currentSeconds}s / ${bufferLimitSeconds.value}s"
            } else {
                file.delete()
                _statusMessage.value = "Trecho muito curto descartado."
            }
        } else {
            // Delete file if error occurred
            file.delete()
            _statusMessage.value = "Erro no trecho: código de erro $errorCode"
        }

        // Check if we were stopping this segment to finalize a save
        if (isFinalizingForSave) {
            isFinalizingForSave = false
            pendingSaveDeferred?.complete(true)
        } else {
            // Continue continuous buffering
            if (_isBuffering.value) {
                startNextSegment()
            }
        }
    }

    /**
     * Trims older segments that fall outside the configured sliding window.
     * We keep segments up to a total duration of at least the buffer limit.
     */
    private fun trimBuffer() {
        val limitMs = _bufferLimitSeconds.value * 1000L
        synchronized(segmentQueue) {
            var totalDurationMs = segmentQueue.sumOf { it.durationMs }
            
            // While we have at least 2 segments and removing the oldest segment
            // still keeps us above or very close to the limit, we discard the oldest.
            while (segmentQueue.size > 1 && (totalDurationMs - segmentQueue.first().durationMs) >= limitMs) {
                val oldest = segmentQueue.removeFirst()
                oldest.file.delete()
                totalDurationMs -= oldest.durationMs
                Log.d(TAG, "Discarded oldest segment: ${oldest.file.name}. Remaining buffer: ${totalDurationMs / 1000}s")
            }
            
            _currentBufferDurationMs.value = totalDurationMs
        }
    }

    /**
     * Clears and deletes all stored segments in cache
     */
    private fun clearAllSegments() {
        synchronized(segmentQueue) {
            for (segment in segmentQueue) {
                segment.file.delete()
            }
            segmentQueue.clear()
            _currentBufferDurationMs.value = 0L
        }
    }

    /**
     * Concatenates the current sliding buffer and saves it as an MP4 to the user's gallery in "Replay Futebol"
     *
     * @return ReplayResult containing gallery path and direct playable cache path if successful, null otherwise
     */
    suspend fun saveCurrentReplay(): ReplayResult? = withContext(Dispatchers.IO) {
        if (_isSaving.value) throw Exception("Já existe um salvamento em progresso.")
        _isSaving.value = true

        try {
            // 1. Force finalize the current active segment to capture up-to-the-second video
            val currentActive = activeRecording
            if (currentActive != null && _isBuffering.value) {
                Log.d(TAG, "Stopping active segment to finalize before saving...")
                isFinalizingForSave = true
                val deferred = CompletableDeferred<Boolean>()
                pendingSaveDeferred = deferred
                
                // Stop the active recording
                currentActive.stop()
                
                // Wait for it to finalize (up to 3 seconds safety timeout)
                withTimeoutOrNull(3000) {
                    deferred.await()
                }
            }

            // 2. Extract segments list
            val segmentsToMerge = synchronized(segmentQueue) {
                segmentQueue.map { it.file }
            }

            if (segmentsToMerge.isEmpty()) {
                Log.e(TAG, "No segments in buffer to save!")
                _isSaving.value = false
                // Resume buffering
                withContext(Dispatchers.Main) {
                    if (_isBuffering.value) startNextSegment()
                }
                throw Exception("Buffer vazio. Aguarde pelo menos 5 segundos para que o primeiro trecho seja gravado.")
            }

            Log.d(TAG, "Merging ${segmentsToMerge.size} segments...")
            
            // Create a temporary file for the merged video
            val tempMergedFile = File(context.cacheDir, "merged_replay_${System.currentTimeMillis()}.mp4")
            val success = VideoMerger.mergeVideos(segmentsToMerge, tempMergedFile)
            
            if (!success) {
                Log.e(TAG, "Merging failed!")
                tempMergedFile.delete()
                _isSaving.value = false
                withContext(Dispatchers.Main) {
                    if (_isBuffering.value) startNextSegment()
                }
                throw Exception("Falha ao mesclar trechos de vídeo (VideoMerger falhou).")
            }

            // 3. Move/save the merged file to the public Gallery folder "Replay Futebol"
            val savedUriStr = saveVideoToGallery(tempMergedFile)

            // Keep a local copy for immediate preview inside the app!
            val previewFile = File(context.cacheDir, "last_replay_preview.mp4")
            try {
                tempMergedFile.copyTo(previewFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy preview file", e)
            }

            tempMergedFile.delete()

            // 4. Resume continuous buffering immediately so user can keep capturing
            withContext(Dispatchers.Main) {
                _isSaving.value = false
                if (_isBuffering.value) {
                    startNextSegment()
                }
            }

            if (savedUriStr != null) {
                return@withContext ReplayResult(savedUriStr, previewFile.absolutePath)
            } else {
                throw Exception("Falha ao mover arquivo final para a galeria pública.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving replay", e)
            _isSaving.value = false
            withContext(Dispatchers.Main) {
                if (_isBuffering.value) startNextSegment()
            }
            throw e
        }
    }

    /**
     * Saves a temporary video file into the public MediaStore collection
     */
    private fun saveVideoToGallery(file: File): String? {
        val fileName = "Replay_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Replay Futebol")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videoUri = resolver.insert(collection, contentValues) ?: throw Exception("Falha ao criar registro no banco de mídia (insert retornou null).")

        try {
            resolver.openOutputStream(videoUri)?.use { outStream ->
                file.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, contentValues, null, null)
            }

            Log.d(TAG, "Saved video to MediaStore: $videoUri")
            return "Movies/Replay Futebol/$fileName"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write video to gallery MediaStore", e)
            try {
                resolver.delete(videoUri, null, null)
            } catch (ignored: Exception) {}
            
            // Fallback for Android 9 or lower if MediaStore write failed
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Replay Futebol")
                    publicDir.mkdirs()
                    val destFile = File(publicDir, fileName)
                    file.copyTo(destFile, overwrite = true)
                    Log.d(TAG, "Saved video to legacy file path: ${destFile.absolutePath}")
                    return "Movies/Replay Futebol/$fileName"
                } catch (le: Exception) {
                    Log.e(TAG, "Legacy save fallback failed too", le)
                    throw Exception("Escrita no MediaStore falhou: ${e.message} e gravação legada falhou: ${le.message}")
                }
            }
            throw Exception("Falha ao gravar arquivo de mídia: ${e.message}")
        }
    }
}
