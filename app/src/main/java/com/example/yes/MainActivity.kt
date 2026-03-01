package com.example.yes

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.yes.ui.theme.YesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YesTheme {
                FlappyBirdGame(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

data class Pipe(var x: Float, val gapY: Float, var scored: Boolean = false)
data class Cloud(var x: Float, val y: Float, val scale: Float, val speed: Float)

enum class GameState {
    STARTING, PLAYING, GAME_OVER
}

// Reusable Constants
private val PipeGreen = Color(0xFF4CAF50)
private val PipeDarkGreen = Color(0xFF388E3C)
private val PipeLightGreen = Color(0xFF81C784)
private val PipeOutlineColor = Color(0xFF1B5E20)
private val BirdYellow = Color(0xFFFFEB3B)
private val BirdDarkYellow = Color(0xFFFBC02D)
private val BirdOutlineColor = Color(0xFF422100)
private val BeakColor = Color(0xFFFF9800)

@Composable
fun FlappyBirdGame(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("game_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // Manage sound state
    var isMuted by remember { mutableStateOf(sharedPreferences.getBoolean("is_muted", false)) }
    
    // Efficiently manage ToneGenerator to prevent glitches
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }
    DisposableEffect(Unit) {
        onDispose {
            toneGenerator.release()
        }
    }

    var gameState by remember { mutableStateOf(GameState.STARTING) }
    var birdY by remember { mutableFloatStateOf(0f) }
    var birdVelocity by remember { mutableFloatStateOf(0f) }
    val gravity = 0.6f
    val jumpStrength = -14f
    var isPaused by remember { mutableStateOf(false) }
    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { 
        mutableIntStateOf(sharedPreferences.getInt("high_score", 0)) 
    }
    
    val pipes = remember { mutableStateListOf<Pipe>() }
    val clouds = remember { mutableStateListOf<Cloud>() }
    val pipeWidth = 160f
    val pipeGap = 420f
    val birdRadius = 38f

    var screenHeight by remember { mutableFloatStateOf(0f) }
    var screenWidth by remember { mutableFloatStateOf(0f) }

    val interactionSource = remember { MutableInteractionSource() }

    // Animations for performance and smoothness
    val infiniteTransition = rememberInfiniteTransition(label = "game_animations")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val menuBirdHover by infiniteTransition.animateFloat(
        initialValue = -25f,
        targetValue = 25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "menu_hover"
    )

    val wingFlapOffset by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wing_flap"
    )

    // Pre-created Brushes and Paths for performance (no allocations in draw loop)
    val titleGradient = remember {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFFFEB3B), Color(0xFFFFC107)))
    }
    val skyGradient = remember {
        Brush.verticalGradient(listOf(Color(0xFFB3E5FC), Color(0xFF81D4FA), Color(0xFF4FC3F7)))
    }
    val pipeBrush = remember(pipeWidth) {
        Brush.linearGradient(
            colors = listOf(PipeDarkGreen, PipeGreen, PipeLightGreen, PipeGreen),
            start = Offset(0f, 0f),
            end = Offset(pipeWidth, 0f)
        )
    }
    val birdBrush = remember(birdRadius) {
        Brush.radialGradient(
            colors = listOf(BirdYellow, BirdDarkYellow),
            center = Offset(-birdRadius/3, -birdRadius/3),
            radius = birdRadius * 1.5f
        )
    }
    val beakPath = remember(birdRadius) {
        Path().apply {
            moveTo(birdRadius * 0.75f, -birdRadius * 0.1f)
            lineTo(birdRadius * 1.35f, birdRadius * 0.1f)
            lineTo(birdRadius * 0.75f, birdRadius * 0.3f)
            close()
        }
    }

    LaunchedEffect(gameState, isPaused) {
        var lastTime = withFrameMillis { it }
        while (true) {
            withFrameMillis { time ->
                val deltaTime = (time - lastTime) / 16f
                lastTime = time

                if (screenWidth <= 0f || screenHeight <= 0f) return@withFrameMillis

                // Logic updates
                if (gameState == GameState.STARTING || (gameState == GameState.PLAYING && !isPaused)) {
                    val globalSpeedMultiplier = if (gameState == GameState.STARTING) 0.8f else (1f + (score / 10) * 0.1f).coerceAtMost(2.0f)

                    // Spawn/Update clouds
                    if (clouds.size < 8 && Random.nextFloat() < 0.015f) {
                        clouds.add(Cloud(screenWidth + 300f, Random.nextFloat() * screenHeight * 0.7f, 0.8f + Random.nextFloat() * 1.2f, 1.5f + Random.nextFloat() * 2.5f))
                    }
                    val cloudIterator = clouds.listIterator()
                    while (cloudIterator.hasNext()) {
                        val cloud = cloudIterator.next()
                        cloud.x -= cloud.speed * deltaTime * globalSpeedMultiplier
                        if (cloud.x < -500f) cloudIterator.remove()
                    }

                    // Update pipes
                    if (gameState == GameState.PLAYING && !isPaused) {
                        if (pipes.isEmpty() || pipes.last().x < screenWidth - 650f) {
                            pipes.add(Pipe(screenWidth + 200f, Random.nextFloat() * (screenHeight * 0.4f) + screenHeight * 0.2f))
                        }
                        val pipeIterator = pipes.listIterator()
                        while (pipeIterator.hasNext()) {
                            val pipe = pipeIterator.next()
                            pipe.x -= 7f * deltaTime * globalSpeedMultiplier
                            if (!pipe.scored && pipe.x + pipeWidth < 200f) {
                                score++
                                pipe.scored = true

                                // Optimized: Play high-pitch sound in a separate coroutine to avoid frame drops
                                if (!isMuted) {
                                    scope.launch(Dispatchers.Default) {
                                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 60)
                                    }
                                }

                                if (score > highScore) {
                                    highScore = score
                                    scope.launch(Dispatchers.IO) {
                                        sharedPreferences.edit().putInt("high_score", score).apply()
                                    }
                                }
                            }
                            if (pipe.x < -pipeWidth) pipeIterator.remove()
                        }
                    } else if (gameState == GameState.STARTING) {
                        if (pipes.isEmpty()) pipes.add(Pipe(screenWidth + 200f, screenHeight / 2 - pipeGap / 2))
                        pipes.forEach { pipe ->
                            pipe.x -= 7f * deltaTime * globalSpeedMultiplier
                            if (pipe.x < -pipeWidth) pipe.x = screenWidth + 200f
                        }
                    }
                }

                if (gameState == GameState.PLAYING && !isPaused) {
                    birdVelocity += gravity * deltaTime
                    birdY += birdVelocity * deltaTime
                    if (birdY !in 0f..screenHeight) gameState = GameState.GAME_OVER
                    pipes.forEach { pipe ->
                        if (200f + birdRadius * 0.7f > pipe.x && 200f - birdRadius * 0.7f < pipe.x + pipeWidth) {
                            if (birdY - birdRadius * 0.7f < pipe.gapY || birdY + birdRadius * 0.7f > pipe.gapY + pipeGap) gameState = GameState.GAME_OVER
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(skyGradient)
            .onSizeChanged { size ->
                screenWidth = size.width.toFloat()
                screenHeight = size.height.toFloat()
                if (birdY == 0f) birdY = screenHeight / 2
            }
            .clickable(interactionSource = interactionSource, indication = null) {
                when (gameState) {
                    GameState.STARTING -> { birdY = screenHeight / 2; birdVelocity = 0f; pipes.clear(); score = 0; gameState = GameState.PLAYING }
                    GameState.PLAYING -> if (!isPaused) birdVelocity = jumpStrength
                    GameState.GAME_OVER -> {}
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw Background Elements
            clouds.forEach { drawCloud(it.x, it.y, it.scale) }
            pipes.forEach { drawPipe(it.x, it.gapY, pipeWidth, pipeGap, pipeBrush) }

            // Draw Bird
            val displayBirdY = if (gameState == GameState.STARTING) screenHeight / 2 + menuBirdHover else birdY
            val displayBirdX = if (gameState == GameState.STARTING) screenWidth / 2 else 200f
            
            drawBird(
                x = displayBirdX, y = displayBirdY, radius = birdRadius,
                velocity = birdVelocity, isStarting = gameState == GameState.STARTING,
                brush = birdBrush, beakPath = beakPath, wingOffset = wingFlapOffset
            )
        }

        // Mute Button for Main Menu
        if (gameState == GameState.STARTING) {
            IconButton(
                onClick = { 
                    isMuted = !isMuted 
                    sharedPreferences.edit().putBoolean("is_muted", isMuted).apply()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 50.dp, start = 20.dp)
            ) {
                Icon(
                    painter = painterResource(id = if (isMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off),
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // UI Overlays
        if (gameState == GameState.STARTING) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 250.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "FLAPPY\nMOHO",
                        style = TextStyle(
                            fontSize = 72.sp, fontWeight = FontWeight.Black, brush = titleGradient,
                            shadow = Shadow(color = Color.Black.copy(alpha = 0.4f), offset = Offset(6f, 6f), blurRadius = 8f)
                        ),
                        lineHeight = 75.sp, textAlign = TextAlign.Center
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(100.dp)
                            .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .border(3.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(60.dp))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(text = "TAP TO START", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.3f), offset = Offset(3f, 3f))))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "BEST SCORE: $highScore", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f))
                }
            }
        }

        if (gameState == GameState.PLAYING || gameState == GameState.GAME_OVER) {
            Column(modifier = Modifier.align(Alignment.TopCenter).padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "$score", fontSize = 80.sp, fontWeight = FontWeight.Black, style = TextStyle(brush = titleGradient, shadow = Shadow(color = Color.Black.copy(alpha = 0.25f), offset = Offset(5f, 5f), blurRadius = 6f)))
                Text(text = "BEST: $highScore", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f), style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.25f), offset = Offset(2f, 2f))))
            }
        }

        if (gameState == GameState.PLAYING) {
            IconButton(onClick = { isPaused = !isPaused }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 20.dp)) {
                Icon(painter = painterResource(id = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause), contentDescription = if (isPaused) "Resume" else "Pause", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }

        if (isPaused && gameState == GameState.PLAYING) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                // Mute Button for Paused Screen
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = { 
                            isMuted = !isMuted 
                            sharedPreferences.edit().putBoolean("is_muted", isMuted).apply()
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 50.dp, start = 20.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = if (isMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off),
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "PAUSED", fontSize = 60.sp, fontWeight = FontWeight.Black, color = Color.White, style = TextStyle(shadow = Shadow(Color.Black, offset = Offset(4f, 4f), blurRadius = 8f)))
                    Spacer(modifier = Modifier.height(40.dp))
                    PauseMenuButton("RESUME", Color(0xFF4CAF50)) { isPaused = false }
                    Spacer(modifier = Modifier.height(16.dp))
                    PauseMenuButton("RESTART", Color(0xFFFF9800)) { birdY = screenHeight / 2; birdVelocity = 0f; pipes.clear(); score = 0; isPaused = false }
                    Spacer(modifier = Modifier.height(16.dp))
                    PauseMenuButton("MAIN MENU", Color(0xFF2196F3)) { pipes.clear(); clouds.clear(); gameState = GameState.STARTING; isPaused = false }
                }
            }
        }

        if (gameState == GameState.GAME_OVER) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "GAME OVER", style = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.Black, brush = titleGradient, shadow = Shadow(Color.Black, offset = Offset(5f, 5f), blurRadius = 10f)))
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(modifier = Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)).border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp)).padding(horizontal = 40.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "SCORE", fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                            Text(text = "$score", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Color(0xFFFFEB3B))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "BEST", fontSize = 16.sp, color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                            Text(text = "$highScore", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                    PauseMenuButton("RESTART", Color(0xFF4CAF50)) { birdY = screenHeight / 2; birdVelocity = 0f; pipes.clear(); score = 0; gameState = GameState.PLAYING; isPaused = false }
                    Spacer(modifier = Modifier.height(16.dp))
                    PauseMenuButton("MAIN MENU", Color(0xFF2196F3)) { pipes.clear(); clouds.clear(); gameState = GameState.STARTING; isPaused = false }
                }
            }
        }
    }
}

@Composable
fun PauseMenuButton(text: String, color: Color, onClick: () -> Unit) {
    Box(modifier = Modifier.width(200.dp).background(color, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

fun DrawScope.drawCloud(x: Float, y: Float, scale: Float) {
    val cloudColor = Color.White.copy(alpha = 0.75f)
    val baseWidth = 140f * scale
    val baseHeight = 50f * scale
    translate(x, y) {
        drawCircle(color = cloudColor, radius = baseHeight * 0.7f, center = Offset(baseWidth * 0.25f, baseHeight * 0.4f))
        drawCircle(color = cloudColor, radius = baseHeight * 0.9f, center = Offset(baseWidth * 0.5f, baseHeight * 0.3f))
        drawCircle(color = cloudColor, radius = baseHeight * 0.6f, center = Offset(baseWidth * 0.75f, baseHeight * 0.5f))
        drawRoundRect(color = cloudColor, topLeft = Offset(baseWidth * 0.1f, baseHeight * 0.5f), size = Size(baseWidth * 0.8f, baseHeight * 0.4f), cornerRadius = CornerRadius(baseHeight * 0.2f))
    }
}

fun DrawScope.drawBird(x: Float, y: Float, radius: Float, velocity: Float, isStarting: Boolean, brush: Brush, beakPath: Path, wingOffset: Float) {
    val rotation = if (isStarting) 0f else (velocity * 2.2f).coerceIn(-25f, 45f)
    translate(x, y) {
        rotate(degrees = rotation, pivot = Offset.Zero) {
            drawCircle(brush = brush, radius = radius, center = Offset.Zero)
            drawCircle(color = BirdOutlineColor, radius = radius, center = Offset.Zero, style = Stroke(width = 3.5f))

            // Wings
            drawArc(color = Color.White, startAngle = 150f, sweepAngle = 120f, useCenter = true, topLeft = Offset(-radius * 1.1f, -radius * 0.4f + wingOffset), size = Size(radius * 1.1f, radius * 0.8f))
            drawArc(color = BirdOutlineColor, startAngle = 150f, sweepAngle = 120f, useCenter = true, topLeft = Offset(-radius * 1.1f, -radius * 0.4f + wingOffset), size = Size(radius * 1.1f, radius * 0.8f), style = Stroke(width = 3f))

            // Eyes
            drawCircle(color = Color.White, radius = radius * 0.35f, center = Offset(radius * 0.4f, -radius * 0.3f))
            drawCircle(color = BirdOutlineColor, radius = radius * 0.35f, center = Offset(radius * 0.4f, -radius * 0.3f), style = Stroke(width = 2.5f))
            drawCircle(color = Color.Black, radius = radius * 0.12f, center = Offset(radius * 0.55f, -radius * 0.3f))

            // Beak
            drawPath(path = beakPath, color = BeakColor)
            drawPath(path = beakPath, color = BirdOutlineColor, style = Stroke(width = 3f))
        }
    }
}

fun DrawScope.drawPipe(x: Float, gapY: Float, width: Float, gap: Float, brush: Brush) {
    translate(x, 0f) {
        // Top Pipe
        drawRoundRect(brush = brush, topLeft = Offset(0f, -100f), size = Size(width, gapY + 100f), cornerRadius = CornerRadius(8f))
        drawRoundRect(brush = brush, topLeft = Offset(-10f, gapY - 50f), size = Size(width + 20f, 50f), cornerRadius = CornerRadius(6f))
        drawRoundRect(color = PipeOutlineColor, topLeft = Offset(-10f, gapY - 50f), size = Size(width + 20f, 50f), cornerRadius = CornerRadius(6f), style = Stroke(width = 4f))

        // Bottom Pipe
        drawRoundRect(brush = brush, topLeft = Offset(0f, gapY + gap), size = Size(width, size.height - (gapY + gap) + 100f), cornerRadius = CornerRadius(8f))
        drawRoundRect(brush = brush, topLeft = Offset(-10f, gapY + gap), size = Size(width + 20f, 50f), cornerRadius = CornerRadius(6f))
        drawRoundRect(color = PipeOutlineColor, topLeft = Offset(-10f, gapY + gap), size = Size(width + 20f, 50f), cornerRadius = CornerRadius(6f), style = Stroke(width = 4f))
    }
}
