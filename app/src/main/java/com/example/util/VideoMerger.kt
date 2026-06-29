package com.example.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoMerger {
    private const val TAG = "VideoMerger"

    /**
     * Merges multiple MP4 video files into a single MP4 file using demuxing/muxing.
     * This processes extremely fast as it does not re-encode video/audio streams.
     *
     * @param inputFiles List of MP4 files to merge.
     * @param outputFile The destination MP4 file.
     * @return true if successful, false otherwise.
     */
    fun mergeVideos(inputFiles: List<File>, outputFile: File): Boolean {
        if (inputFiles.isEmpty()) return false
        
        // Ensure parent directory of output file exists
        outputFile.parentFile?.mkdirs()
        
        if (inputFiles.size == 1) {
            return try {
                inputFiles[0].copyTo(outputFile, overwrite = true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy single file to destination", e)
                false
            }
        }

        var muxer: MediaMuxer? = null
        val extractors = ArrayList<MediaExtractor>()
        
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // First pass: inspect the first file to set up tracks
            val firstFile = inputFiles[0]
            val firstExtractor = MediaExtractor().apply { setDataSource(firstFile.absolutePath) }
            extractors.add(firstExtractor)
            
            val trackCount = firstExtractor.trackCount
            val trackMap = HashMap<Int, Int>() // Maps input track index of 1st file to output track index
            val trackFormats = HashMap<Int, MediaFormat>()
            
            for (i in 0 until trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    firstExtractor.selectTrack(i)
                    val outTrackIndex = muxer.addTrack(format)
                    trackMap[i] = outTrackIndex
                    trackFormats[i] = format
                }
            }
            
            muxer.start()
            
            // Track presentation timestamp offsets and last written timestamp for each track to keep things linear
            val lastPtsMap = HashMap<Int, Long>()
            val offsetMap = HashMap<Int, Long>()
            
            for (outTrack in trackMap.values) {
                offsetMap[outTrack] = 0L
                lastPtsMap[outTrack] = 0L
            }
            
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024) // 2MB buffer for high quality frames
            val bufferInfo = MediaCodec.BufferInfo()
            
            // Process each file sequentially
            for (fileIndex in inputFiles.indices) {
                val file = inputFiles[fileIndex]
                val extractor = if (fileIndex == 0) {
                    firstExtractor
                } else {
                    val ext = MediaExtractor().apply { setDataSource(file.absolutePath) }
                    extractors.add(ext)
                    ext
                }
                
                val fileTrackCount = extractor.trackCount
                val fileTrackMap = HashMap<Int, Int>() // Maps current file's track index to output track index
                
                for (i in 0 until fileTrackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    
                    // Match track by type (video or audio) with first file's tracks
                    for ((firstInTrack, outTrack) in trackMap) {
                        val firstFormat = trackFormats[firstInTrack]
                        val firstMime = firstFormat?.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.substringBefore('/') == firstMime.substringBefore('/')) {
                            extractor.selectTrack(i)
                            fileTrackMap[i] = outTrack
                            break
                        }
                    }
                }
                
                // Keep track of the maximum timestamp seen in the current file to update next offset
                val fileMaxPtsMap = HashMap<Int, Long>()
                for (outTrack in trackMap.values) {
                    fileMaxPtsMap[outTrack] = 0L
                }
                
                while (true) {
                    val sampleTrackIndex = extractor.sampleTrackIndex
                    if (sampleTrackIndex < 0) break
                    
                    val outTrackIndex = fileTrackMap[sampleTrackIndex]
                    if (outTrackIndex == null) {
                        extractor.advance()
                        continue
                    }
                    
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    
                    val presentationTimeUs = extractor.sampleTime
                    val flags = extractor.sampleFlags
                    
                    val offset = offsetMap[outTrackIndex] ?: 0L
                    val newPresentationTimeUs = presentationTimeUs + offset
                    
                    // Ensure the timestamp is strictly increasing to avoid Mp4 muxing errors
                    val lastPts = lastPtsMap[outTrackIndex] ?: 0L
                    val finalPts = if (newPresentationTimeUs <= lastPts) {
                        lastPts + 1000 // force forward by 1ms
                    } else {
                        newPresentationTimeUs
                    }
                    
                    bufferInfo.set(0, sampleSize, finalPts, flags)
                    muxer.writeSampleData(outTrackIndex, buffer, bufferInfo)
                    
                    lastPtsMap[outTrackIndex] = finalPts
                    
                    if (presentationTimeUs > (fileMaxPtsMap[outTrackIndex] ?: 0L)) {
                        fileMaxPtsMap[outTrackIndex] = presentationTimeUs
                    }
                    
                    extractor.advance()
                }
                
                // Update offsets for the next file
                for (outTrack in trackMap.values) {
                    val maxFilePts = fileMaxPtsMap[outTrack] ?: 0L
                    // Gap spacing: approx 33 milliseconds spacing
                    offsetMap[outTrack] = (lastPtsMap[outTrack] ?: 0L) + 33000L
                }
            }
            
            Log.d(TAG, "Successfully merged ${inputFiles.size} segments into ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception while merging videos", e)
            return false
        } finally {
            for (extractor in extractors) {
                try {
                    extractor.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing extractor", e)
                }
            }
            try {
                muxer?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping muxer", e)
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing muxer", e)
            }
        }
    }
}
