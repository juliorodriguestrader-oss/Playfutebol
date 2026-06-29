package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ReplayBufferManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var replayBufferManager: ReplayBufferManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        replayBufferManager = ReplayBufferManager(this)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0C0E0D)
                ) {
                    ReplayAppScreen(replayBufferManager = replayBufferManager)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        replayBufferManager.stopBuffering()
    }
}

@Composable
fun ReplayAppScreen(replayBufferManager: ReplayBufferManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State flows from the Buffer Manager
    val isBuffering by replayBufferManager.isBuffering.collectAsState()
    val isSaving by replayBufferManager.isSaving.collectAsState()
    val currentBufferMs by replayBufferManager.currentBufferDurationMs.collectAsState()
    val bufferLimitSeconds by replayBufferManager.bufferLimitSeconds.collectAsState()

    // Sleek Accent Colors
    val sleekAccent = Color(0xFFB1F203) // Neon lime/yellow-green
    val sleekBgDark = Color(0xFF0F172A) // Dark obsidian blue/slate
    val sleekPanelBg = Color(0xFF1E293B).copy(alpha = 0.4f)

    // Camera/Audio permissions state
    var permissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Permission launcher for Camera and Audio
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraOk = results[Manifest.permission.CAMERA] ?: false
        val audioOk = results[Manifest.permission.RECORD_AUDIO] ?: false
        permissionsGranted = cameraOk
        audioPermissionGranted = audioOk
        if (cameraOk) {
            replayBufferManager.startBuffering()
        } else {
            Toast.makeText(context, "Permissão de câmera é necessária para o app funcionar.", Toast.LENGTH_LONG).show()
        }
    }

    // Effect to auto-request permissions on startup
    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        } else {
            replayBufferManager.startBuffering()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (permissionsGranted) {
            // Fullscreen Camera Preview
            CameraPreviewContainer(
                onVideoCaptureReady = { capture ->
                    replayBufferManager.setVideoCapture(capture)
                    if (permissionsGranted) {
                        replayBufferManager.startBuffering()
                    }
                }
            )

            // Crosshair overlay and Grid Lines for a professional camera feel
            CameraTechOverlay()

            // Soft cinematic gradient overlays (scrim)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.5f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
            )

            // UI Elements Layout (Z-Index 10)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                
                // 1. TOP BAR: Rec Status, Timecode, Metadata, and Storage Info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Column: Pulsing REC status + Monospace Timecode
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Pulsing Dot
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse_dot")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulseAlpha"
                            )

                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isBuffering) Color(0xFFFF3B30).copy(alpha = pulseAlpha) // Red rec dot
                                        else Color.DarkGray
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "REC BUFFER ACTIVE",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(2.dp))

                        // Timecode Clock (e.g. 00:59 or 00:30 dynamically matches seconds)
                        val bufferedSecs = (currentBufferMs / 1000).toInt()
                        val formattedSeconds = String.format("%02d", bufferedSecs)
                        Text(
                            text = "00:$formattedSeconds",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = (-1).sp
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        // Tech Specs label
                        Text(
                            text = "HD • 60 FPS • H.264 • " + (if (audioPermissionGranted) "AAC" else "MUTED"),
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Right Column: Storage info + Settings Icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "STORAGE",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "12.4 GB FREE",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Settings Icon styled to match HTML (rounded-2xl blur border)
                        IconButton(
                            onClick = {
                                // Toggle settings dialog
                                Toast.makeText(context, "Acesse as configurações do buffer abaixo!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Configurações",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // 2. MIDDLE VIEWPORT: Left-side vertical level indicator + layout spacer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Vertical level indicator block matching Design HTML precisely
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 16.dp)
                    ) {
                        val progress = (currentBufferMs.toFloat() / (bufferLimitSeconds * 1000f)).coerceIn(0f, 1f)
                        
                        // Vertical Progress Bar
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(120.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(progress)
                                    .background(sleekAccent)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Vertical Rotated Text for professional look
                        Text(
                            text = "BUFFER LEVEL",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier
                                .rotate(-90f)
                                .padding(vertical = 12.dp)
                                .width(80.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // 3. BOTTOM PANEL: Sleek Selector pills & Save Replay Button
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    
                    // A. Sleek Buffer Selector Pills (Direct, visual, beautiful)
                    Row(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(30, 60, 120).forEach { limit ->
                            val isSelected = limit == bufferLimitSeconds
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (isSelected) sleekAccent else Color.Transparent
                                    )
                                    .clickable {
                                        replayBufferManager.setBufferLimitSeconds(limit)
                                        Toast.makeText(context, "Buffer ajustado para $limit segundos", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "${limit}S",
                                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // B. Main primary Save Button
                    val interactionScale = remember { Animatable(1f) }
                    
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(130.dp)
                            .scale(interactionScale.value)
                            .testTag("save_replay_button")
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                            .clickable(enabled = !isSaving) {
                                scope.launch {
                                    interactionScale.animateTo(
                                        0.92f,
                                        animationSpec = tween(800, easing = FastOutSlowInEasing)
                                    )
                                    interactionScale.animateTo(
                                        1.0f,
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                    )

                                    val savedFilePath = replayBufferManager.saveCurrentReplay()
                                    if (savedFilePath != null) {
                                        Toast
                                            .makeText(
                                                context,
                                                "Replay de Futebol salvo! Verifique a pasta: $savedFilePath",
                                                Toast.LENGTH_LONG
                                            )
                                            .show()
                                    } else {
                                        Toast
                                            .makeText(
                                                context,
                                                "Erro ao salvar replay. Verifique as permissões.",
                                                Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    }
                                }
                            }
                    ) {
                        // Pulsing outer glow ring
                        val pulseTransition = rememberInfiniteTransition(label = "btn_pulse")
                        val outerRingPulse by pulseTransition.animateFloat(
                            initialValue = 0.85f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = FastOutLinearInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "ring_pulse"
                        )

                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .scale(outerRingPulse)
                                .border(2.dp, sleekAccent.copy(alpha = 0.15f), CircleShape)
                        )

                        // Inner circular button
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(sleekAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Save Icon",
                                    tint = Color.Black,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "SAVE REPLAY",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = (-0.2).sp
                                )
                            }
                        }
                    }

                    // C. Status Footer bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                        )
                        Text(
                            text = "READY TO CAPTURE / REPLAY FUTEBOL FOLDER",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        } else {
            // Permissions welcome setup
            OnboardingPermissionScreen(
                onRequestPermissions = {
                    permissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }
            )
        }

        // Overlay Saving Screen Indicator
        AnimatedVisibility(
            visible = isSaving,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    CircularProgressIndicator(
                        color = sleekAccent,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "CRIANDO REPLAY INSTANTÂNEO",
                        color = sleekAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recortando e salvando os últimos $bufferLimitSeconds segundos...",
                        color = Color.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "O replay será gravado na sua galeria na pasta 'Replay Futebol'.",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun CameraTechOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Subtle tech grid lines
        val linePaintColor = Color.White.copy(alpha = 0.05f)
        
        // Horizontal third lines
        drawLine(
            color = linePaintColor,
            start = androidx.compose.ui.geometry.Offset(0f, height / 3),
            end = androidx.compose.ui.geometry.Offset(width, height / 3),
            strokeWidth = 1f
        )
        drawLine(
            color = linePaintColor,
            start = androidx.compose.ui.geometry.Offset(0f, height * 2 / 3),
            end = androidx.compose.ui.geometry.Offset(width, height * 2 / 3),
            strokeWidth = 1f
        )

        // Vertical third lines
        drawLine(
            color = linePaintColor,
            start = androidx.compose.ui.geometry.Offset(width / 3, 0f),
            end = androidx.compose.ui.geometry.Offset(width / 3, height),
            strokeWidth = 1f
        )
        drawLine(
            color = linePaintColor,
            start = androidx.compose.ui.geometry.Offset(width * 2 / 3, 0f),
            end = androidx.compose.ui.geometry.Offset(width * 2 / 3, height),
            strokeWidth = 1f
        )

        // Center crosshair circular guidelines
        val center = androidx.compose.ui.geometry.Offset(width / 2, height / 2)
        drawCircle(
            color = Color.White.copy(alpha = 0.1f),
            radius = 32.dp.toPx(),
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.2f),
            radius = 2.dp.toPx(),
            center = center
        )
    }
}

@Composable
fun CameraPreviewContainer(
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )

                onVideoCaptureReady(videoCapture)

            } catch (e: Exception) {
                android.util.Log.e("CameraPreviewContainer", "Failed to bind camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun OnboardingPermissionScreen(onRequestPermissions: () -> Unit) {
    val sleekAccent = Color(0xFFB1F203)
    val sleekBgDark = Color(0xFF0F172A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0E0D))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        // Subtle background grid to match the main screen
        CameraTechOverlay()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚽",
                fontSize = 80.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "REPLAY INSTANTÂNEO",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp
            )
            Text(
                text = "FUTEBOL",
                color = sleekAccent,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Grave partidas continuamente sem ocupar espaço no seu celular! Salve instantaneamente os últimos lances incríveis e gols com um único toque.",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = sleekAccent,
                    contentColor = Color(0xFF0C0E0D)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("grant_permissions_button")
            ) {
                Text(
                    text = "CONCEDER PERMISSÃO",
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    letterSpacing = 0.5.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Necessita de permissão de Câmera e Microfone.",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
