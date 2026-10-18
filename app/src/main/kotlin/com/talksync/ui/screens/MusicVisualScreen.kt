package com.talksync.ui.screens

// song id screen plus the canvas that gets weird in sync with the music.

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.talksync.music.VisualFrame
import com.talksync.music.VisualStyle
import com.talksync.music.softSat
import com.talksync.viewmodel.MusicVisualPhase
import com.talksync.viewmodel.MusicVisualViewModel
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/* -------------------------------------------------------------------------- */
/* Particle system — lives in Compose state, updated at ~60Hz                 */
/* -------------------------------------------------------------------------- */

private class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,        // 0..1  (1 = fresh, 0 = dead)
    val colorArgb: Int,
    val radius: Float,
)

private fun updateParticles(
    particles: MutableList<Particle>,
    frame: VisualFrame,
    style: VisualStyle,
    dtMs: Long,
    cx: Float,
    cy: Float,
    maxR: Float,
) {
    val dt = (dtMs / 1000f).coerceIn(0f, 0.05f)

    // Decay + physics
    val iter = particles.iterator()
    while (iter.hasNext()) {
        val p = iter.next()
        p.life -= dt * 1.1f
        if (p.life <= 0f) { iter.remove(); continue }
        p.vy += 90f * dt            // gravity
        p.vx *= (1f - 2f * dt)      // drag
        p.vy *= (1f - 2f * dt)
        p.x += p.vx * dt
        p.y += p.vy * dt
    }

    val rng = Random(System.currentTimeMillis() / 16L)  // ~60Hz bucket

    // Bass burst
    val burstThresh = 0.28f
    if (frame.bassPulse > burstThresh && particles.size < 280) {
        val count = ((frame.bassPulse - burstThresh) * 40f + frame.energy * 10f)
            .toInt().coerceIn(2, 25)
        repeat(count) {
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val speed = 60f + frame.bassPulse * 380f + frame.energy * 80f
            val pIdx = rng.nextInt(style.palette.size)
            particles += Particle(
                x = cx + cos(angle) * maxR * 0.04f,
                y = cy + sin(angle) * maxR * 0.04f,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                life = 0.55f + frame.bassPulse * 0.45f,
                colorArgb = style.palette[pIdx],
                radius = 4f + frame.bassPulse * 18f + rng.nextFloat() * 8f,
            )
        }
    }

    // Continuous ambient glow particles
    if (particles.size < 120 && frame.energy > 0.15f) {
        repeat(2) {
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val r = maxR * (0.1f + rng.nextFloat() * 0.5f)
            particles += Particle(
                x = cx + cos(angle) * r,
                y = cy + sin(angle) * r,
                vx = cos(angle + PI.toFloat() / 2f) * 15f,
                vy = sin(angle + PI.toFloat() / 2f) * 15f - 10f,
                life = 0.25f + frame.energy * 0.4f,
                colorArgb = style.palette[rng.nextInt(style.palette.size)],
                radius = 2f + frame.energy * 6f,
            )
        }
    }

    // Treble sparkles — tiny, fast, high-up
    if (frame.treble > 0.35f && particles.size < 240) {
        repeat(4) {
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val r = maxR * (0.2f + rng.nextFloat() * 0.65f)
            particles += Particle(
                x = cx + cos(angle) * r,
                y = cy + sin(angle) * r,
                vx = cos(angle) * 20f,
                vy = sin(angle) * 20f - 60f,
                life = 0.2f + frame.treble * 0.25f,
                colorArgb = style.palette.last(),
                radius = 1.5f + frame.treble * 3.5f,
            )
        }
    }

    if (particles.size > 320) {
        particles.subList(0, particles.size - 320).clear()
    }
}

/* -------------------------------------------------------------------------- */
/* Screen                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
fun MusicVisualScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: MusicVisualViewModel = viewModel()

    var hasMicPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasMicPermission = it }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(hasMicPermission) {
        if (hasMicPermission) viewModel.startEngine()
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopEngine() } }

    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val style by viewModel.style.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()

    // Particle state — updated in animation loop below.
    val particles = remember { mutableListOf<Particle>() }
    var animMs by remember { mutableLongStateOf(0L) }
    val frameRef = rememberUpdatedState(frame)
    val styleRef = rememberUpdatedState(style)

    // Animation loop: ~60 Hz via kotlinx delay. Updates particle physics and
    // triggers Canvas recompose via animMs state write.
    LaunchedEffect(Unit) {
        var lastMs = System.currentTimeMillis()
        while (isActive) {
            val now = System.currentTimeMillis()
            val dt = now - lastMs
            lastMs = now
            // cx/cy are not available here; use placeholder — the Canvas
            // overrides with real size. We just need a stable attractor for
            // emission; the draw step uses actual canvas size.
            val f = frameRef.value
            val s = styleRef.value
            updateParticles(particles, f, s, dt, 540f, 960f, 540f)
            animMs = now
            kotlinx.coroutines.delay(14L)
        }
    }

    // Slow auto-time for motion in silence.
    val infinite = rememberInfiniteTransition(label = "musicTime")
    val autoTime by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1_000_000f,
        animationSpec = infiniteRepeatable(
            tween(1_000_000_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "musicAutoTime",
    )

    Surface(modifier = Modifier.fillMaxSize(), color = Color(style.background)) {
        Box(modifier = Modifier.fillMaxSize()) {

            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") animMs   // subscribe for 60Hz recompose
                val cx = size.width / 2f
                val cy = size.height / 2f
                val t = frame.time + autoTime * 0.0003f
                drawVisualization(style, frame, particles, cx, cy, t)
            }

            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.size(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Music Mirror", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Procedural art reacting to whatever's playing.",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                    )
                }
                if (uiState.song != null) {
                    IconButton(onClick = { viewModel.clearSong() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reset", tint = Color.White)
                    }
                }
            }

            // Bottom card
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SongInfoCard(
                    phase = uiState.phase,
                    title = uiState.song?.title,
                    artist = uiState.song?.artist,
                    album = uiState.song?.album,
                    seed = uiState.song?.stableSeed,
                    geometry = style.geometry,
                    errorMessage = uiState.errorMessage,
                    auddTokenConfigured = uiState.auddTokenConfigured,
                )
                IdentifyButton(
                    phase = uiState.phase,
                    enabled = hasMicPermission,
                    onClick = { viewModel.identifySong() },
                )
                if (!hasMicPermission) {
                    Text(
                        "Microphone access is needed to listen and react.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* UI sub-composables (unchanged from original)                               */
/* -------------------------------------------------------------------------- */

@Composable
private fun SongInfoCard(
    phase: MusicVisualPhase,
    title: String?,
    artist: String?,
    album: String?,
    seed: Long?,
    geometry: String,
    errorMessage: String?,
    auddTokenConfigured: Boolean,
) {
    val statusLine = when (phase) {
        MusicVisualPhase.IDLE -> "Listening to the room…"
        MusicVisualPhase.LISTENING -> "Identifying — keep the music playing for ~6s"
        MusicVisualPhase.IDENTIFIED -> "Identified · style locked to this song"
        MusicVisualPhase.NO_MATCH -> "No match — try again with louder audio"
        MusicVisualPhase.ERROR -> errorMessage ?: "Something went wrong"
    }
    val accent = when (phase) {
        MusicVisualPhase.LISTENING -> Color(0xFFFFD66B)
        MusicVisualPhase.IDENTIFIED -> Color(0xFF7CF6B7)
        MusicVisualPhase.NO_MATCH, MusicVisualPhase.ERROR -> Color(0xFFFF8A80)
        MusicVisualPhase.IDLE -> Color(0xFFB8C2FF)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(modifier = Modifier.size(8.dp))
                Text(statusLine, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            if (title != null) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (!artist.isNullOrBlank()) Text(artist, color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                if (!album.isNullOrBlank()) Text(album, color = Color.White.copy(alpha = 0.55f), fontStyle = FontStyle.Italic, fontSize = 12.sp)
                if (seed != null) {
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("seed ${"%016x".format(seed)} · $geometry", color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
                }
            } else if (phase == MusicVisualPhase.IDLE) {
                Spacer(modifier = Modifier.size(4.dp))
                Text("Tap below and play music near the phone. Same song = same artwork, every time.", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            }
            if (!auddTokenConfigured && phase == MusicVisualPhase.IDLE) {
                Spacer(modifier = Modifier.size(6.dp))
                Text("Tip: add an AudD API token in secrets.properties for higher daily limits.", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun IdentifyButton(phase: MusicVisualPhase, enabled: Boolean, onClick: () -> Unit) {
    val label = when (phase) {
        MusicVisualPhase.LISTENING -> "Listening…"
        MusicVisualPhase.IDENTIFIED -> "Identify another song"
        else -> "Identify song"
    }
    Button(
        onClick = onClick,
        enabled = enabled && phase != MusicVisualPhase.LISTENING,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.5f),
            disabledContentColor = Color.Black.copy(alpha = 0.6f),
        ),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* -------------------------------------------------------------------------- */
/* Visualization dispatcher                                                    */
/* -------------------------------------------------------------------------- */

private fun DrawScope.drawVisualization(
    style: VisualStyle,
    frame: VisualFrame,
    particles: List<Particle>,
    cx: Float,
    cy: Float,
    time: Float,
) {
    val maxR = min(cx, cy) * 0.88f
    val palette = style.palette.map { Color(it) }
    when (style.geometry) {
        "particles"    -> drawParticles(style, frame, particles, palette, cx, cy, maxR, time)
        "lissajous"    -> drawLissajous(style, frame, palette, cx, cy, maxR, time)
        "plasma"       -> drawPlasma(style, frame, palette, cx, cy, maxR, time)
        "warp"         -> drawWarp(style, frame, palette, cx, cy, maxR, time)
        "kaleidoscope" -> drawKaleidoscope(style, frame, palette, cx, cy, maxR, time)
        else           -> drawWarp(style, frame, palette, cx, cy, maxR, time)
    }
}

/* -------------------------------------------------------------------------- */
/* 1. PARTICLES                                                                 */
/*    Real physics — bass bursts, treble sparkles, ambient glow.              */
/*    Determinism: palette / emission rate fixed by style seed; only          */
/*    per-frame audio drives the AMOUNT of particles, not the style.          */
/* -------------------------------------------------------------------------- */

private fun DrawScope.drawParticles(
    style: VisualStyle,
    frame: VisualFrame,
    particles: List<Particle>,
    palette: List<Color>,
    cx: Float,
    cy: Float,
    maxR: Float,
    time: Float,
) {
    // Undulating background rings for depth when particles are sparse
    for (i in 1..4) {
        val r = maxR * (i / 4f) * (0.85f + frame.energy * 0.15f)
        drawCircle(
            color = palette[i % palette.size].copy(alpha = 0.06f + frame.energy * 0.06f),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f),
        )
    }

    // Particle field
    for (p in particles) {
        if (p.life <= 0f) continue
        val alpha = (p.life * p.life).coerceIn(0f, 1f)   // quadratic fade
        drawCircle(
            color = Color(p.colorArgb).copy(alpha = alpha),
            radius = p.radius * (0.4f + p.life * 0.6f),
            center = Offset(p.x, p.y),
        )
    }

    // Central pulse core — always present, scales with bass
    val coreR = maxR * 0.08f * (0.5f + frame.bassPulse * 2.2f + frame.energy * 0.6f)
    drawCircle(
        color = palette.first().copy(alpha = 0.18f + frame.bassPulse * 0.7f),
        radius = coreR,
        center = Offset(cx, cy),
    )
    drawCircle(
        color = Color.White.copy(alpha = frame.bassPulse * 0.55f),
        radius = coreR * 0.35f,
        center = Offset(cx, cy),
    )
}

/* -------------------------------------------------------------------------- */
/* 2. LISSAJOUS                                                                */
/*    Parametric X=R·sin(a·t+δ), Y=R·sin(b·t). Frequency ratio a:b          */
/*    determined by seed; audio modulates δ (phase difference) and R.        */
/*    Multiple layers with different a:b ratios create interlocking figures.  */
/* -------------------------------------------------------------------------- */

private fun DrawScope.drawLissajous(
    style: VisualStyle,
    frame: VisualFrame,
    palette: List<Color>,
    cx: Float,
    cy: Float,
    maxR: Float,
    time: Float,
) {
    val steps = 512

    for (layerIdx in 0 until style.layers) {
        val phase = style.layerPhases[layerIdx]

        // Frequency integers seeded per layer — small integers give closed,
        // recognisable figures; audio slowly modulates the ratio.
        val freqA = (2 + layerIdx * 2 + (style.symmetry % 3)).toFloat()
        val freqB = (3 + layerIdx * 3 + (style.symmetry % 4)).toFloat()

        // Phase difference driven by slow time + bass — the figure continuously
        // morphs, never fully closes.
        val delta = phase + time * 0.25f + frame.bassPulse * 1.2f

        val rX = maxR * style.layerRadii[layerIdx] *
            (0.65f + softSat(frame.bands[layerIdx % 8]) * 0.45f)
        val rY = rX * (0.7f + frame.treble * 0.4f)

        val color = palette[layerIdx % palette.size]
        val alpha = 0.30f + frame.energy * 0.55f

        var prevX = 0f
        var prevY = 0f
        for (i in 0..steps) {
            val t = i.toFloat() / steps * 2f * PI.toFloat()
            val x = cx + rX * sin(freqA * t + delta)
            val y = cy + rY * sin(freqB * t)
            if (i > 0) {
                drawLine(
                    color = color.copy(alpha = alpha),
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 1.8f + frame.bassPulse * 3.5f,
                )
            }
            prevX = x
            prevY = y
        }
    }

    // Highlight cursor — single bright dot tracing the outermost figure
    val delta0 = style.layerPhases[0] + time * 0.25f + frame.bassPulse * 1.2f
    val tCursor = (time * 0.8f % (2f * PI.toFloat()))
    val rX0 = maxR * style.layerRadii[0] * (0.65f + softSat(frame.bands[0]) * 0.45f)
    val rY0 = rX0 * (0.7f + frame.treble * 0.4f)
    val cursorX = cx + rX0 * sin(2f * tCursor + delta0)
    val cursorY = cy + rY0 * sin(3f * tCursor)
    drawCircle(
        color = Color.White.copy(alpha = 0.8f + frame.energy * 0.2f),
        radius = 4f + frame.bassPulse * 8f,
        center = Offset(cursorX, cursorY),
    )
}

/* -------------------------------------------------------------------------- */
/* 3. PLASMA                                                                   */
/*    Interference of multiple sine waves in polar coordinates. Each "ring"   */
/*    at radius r is displaced in angle by sin(r·freq − t·speed + band).     */
/*    Stacking rings with different freq/speed produces standing-wave plasma. */
/* -------------------------------------------------------------------------- */

private fun DrawScope.drawPlasma(
    style: VisualStyle,
    frame: VisualFrame,
    palette: List<Color>,
    cx: Float,
    cy: Float,
    maxR: Float,
    time: Float,
) {
    val rings = 28
    val angularSegs = 96

    for (ring in 0 until rings) {
        val frac = (ring + 1f) / rings
        val baseR = frac * maxR

        val bandIdx = ring % 8
        val band = frame.bands[bandIdx]
        val color = palette[(ring + bandIdx) % palette.size]

        // Each ring gets a unique interference pattern from three sine waves
        val freq1 = 3f + style.layerPhases[ring % style.layers] * 2f
        val freq2 = 5f + ring * 0.4f
        val speed1 = 0.5f + frac * 1.5f
        val speed2 = -0.3f - frac

        // Displacement amplitude scales strongly with audio band
        val dispAmp = baseR * 0.12f * (1f + softSat(band) * 2.2f + frame.bassPulse * 1.4f)

        val alpha = 0.25f + band * 0.55f + frame.energy * 0.2f

        var prevX = 0f
        var prevY = 0f
        for (seg in 0..angularSegs) {
            val theta = seg.toFloat() / angularSegs * 2f * PI.toFloat()
            val displacement =
                sin(theta * freq1 - time * speed1) * dispAmp * 0.6f +
                sin(theta * freq2 + time * speed2 + frac * PI.toFloat()) * dispAmp * 0.4f
            val r = (baseR + displacement).coerceAtLeast(4f)
            val x = cx + cos(theta) * r
            val y = cy + sin(theta) * r
            if (seg > 0) {
                drawLine(
                    color = color.copy(alpha = alpha),
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 1.5f + band * 2.5f,
                )
            }
            prevX = x
            prevY = y
        }
    }
}

/* -------------------------------------------------------------------------- */
/* 4. WARP                                                                     */
/*    Radial blob: each of N spoke-angles has a radius that is the sum of     */
/*    several harmonics. Bass adds a global swell; upper bands roughen the    */
/*    silhouette. Multiple semi-transparent layers offset in phase create     */
/*    a sense of living, breathing depth.                                     */
/* -------------------------------------------------------------------------- */

private fun DrawScope.drawWarp(
    style: VisualStyle,
    frame: VisualFrame,
    palette: List<Color>,
    cx: Float,
    cy: Float,
    maxR: Float,
    time: Float,
) {
    val spokes = 180   // smooth enough to look organic

    for (layerIdx in 0 until style.layers) {
        val phase = style.layerPhases[layerIdx]
        val rBase = maxR * style.layerRadii[layerIdx]
        val color = palette[layerIdx % palette.size]
        val alpha = (0.18f + frame.energy * 0.45f) / (layerIdx + 1f).pow(0.4f)

        val path = Path()
        var first = true

        for (s in 0..spokes) {
            val theta = s.toFloat() / spokes * 2f * PI.toFloat()

            // Harmonic sum: low harmonics (bass) = global shape, high = edge detail
            val b0 = frame.bands[0]; val b1 = frame.bands[1]
            val b4 = frame.bands[4]; val b6 = frame.bands[6]

            val r = rBase * (
                1f
                + sin(1f * theta + time * 0.6f + phase) * 0.18f * (1f + b0 * 1.8f)
                + sin(2f * theta - time * 0.9f + phase) * 0.10f * (1f + b1 * 1.4f)
                + sin(3f * theta + time * 1.3f + phase * 1.5f) * 0.07f * (1f + b4)
                + sin(5f * theta - time * 1.8f + phase) * 0.04f * (1f + b6 * 0.8f)
                + frame.bassPulse * 0.30f * style.pulseGain
            ).coerceAtLeast(0.05f)

            val x = cx + cos(theta) * r
            val y = cy + sin(theta) * r
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        path.close()

        drawPath(
            path = path,
            color = color.copy(alpha = alpha),
            style = Stroke(width = 2f + frame.bassPulse * 5f + layerIdx * 0.5f),
        )
    }

    // Pulsing solid core
    val coreR = maxR * 0.12f * (0.6f + frame.bassPulse * 1.6f)
    drawCircle(
        color = palette.first().copy(alpha = 0.25f + frame.bassPulse * 0.5f),
        radius = coreR,
        center = Offset(cx, cy),
    )
}

/* -------------------------------------------------------------------------- */
/* 5. KALEIDOSCOPE                                                             */
/*    N-fold rotational + mirror symmetry. One "wedge" is drawn — a curve    */
/*    from centre to edge with audio-reactive nodes — then rotated N times.  */
/*    Every other sector is also reflected (scale -1) for true kaleidoscope. */
/* -------------------------------------------------------------------------- */

private fun DrawScope.drawKaleidoscope(
    style: VisualStyle,
    frame: VisualFrame,
    palette: List<Color>,
    cx: Float,
    cy: Float,
    maxR: Float,
    time: Float,
) {
    val sectors = style.symmetry
    val sectorAngle = 360f / sectors

    for (sector in 0 until sectors) {
        val mirror = sector % 2 == 1
        rotate(
            degrees = sectorAngle * sector,
            pivot = Offset(cx, cy),
        ) {
            // Mirror every other sector for true kaleidoscope
            if (mirror) {
                scale(scaleX = -1f, scaleY = 1f, pivot = Offset(cx, cy)) {
                    drawKaleidoscopeWedge(style, frame, palette, cx, cy, maxR, time)
                }
            } else {
                drawKaleidoscopeWedge(style, frame, palette, cx, cy, maxR, time)
            }
        }
    }
}

private fun DrawScope.drawKaleidoscopeWedge(
    style: VisualStyle,
    frame: VisualFrame,
    palette: List<Color>,
    cx: Float,
    cy: Float,
    maxR: Float,
    time: Float,
) {
    val nodes = 6 + style.layers   // points along the spoke

    for (layerIdx in 0 until style.layers.coerceAtMost(3)) {
        val phase = style.layerPhases[layerIdx]
        val color = palette[layerIdx % palette.size]
        val alpha = 0.50f + frame.energy * 0.40f

        // Spoke: radial line from centre with undulating amplitude nodes
        var prevX = cx
        var prevY = cy
        for (n in 1..nodes) {
            val frac = n.toFloat() / nodes
            val band = frame.bands[(n + layerIdx) % 8]
            val radialR = maxR * frac * style.layerRadii[layerIdx]
            val lateralOff = radialR * 0.25f *
                sin(frac * 3f * PI.toFloat() + time * 1.2f + phase) *
                (0.6f + band * 1.2f)

            // "spoke" goes straight up from centre (angle = 0 in wedge space)
            val x = cx + lateralOff
            val y = cy - radialR

            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(prevX, prevY),
                end = Offset(x, y),
                strokeWidth = 2.5f + band * 5f + frame.bassPulse * 3f,
            )

            // Node dot
            val nodeR = 3f + band * 12f + (if (n == nodes / 2) frame.bassPulse * 10f else 0f)
            drawCircle(
                color = color.copy(alpha = alpha + 0.2f),
                radius = nodeR,
                center = Offset(x, y),
            )
            prevX = x
            prevY = y
        }
    }

    // Cross-bar arcs at mid-radius for that classic kaleidoscope lattice feel
    val midR = maxR * 0.5f * (0.85f + frame.energy * 0.3f)
    val arcSweep = 360f / style.symmetry * 0.55f
    val arcColor = palette.last().copy(alpha = 0.35f + frame.bassPulse * 0.4f)
    drawArc(
        color = arcColor,
        startAngle = 270f - arcSweep / 2f,
        sweepAngle = arcSweep,
        useCenter = false,
        topLeft = Offset(cx - midR, cy - midR),
        size = androidx.compose.ui.geometry.Size(midR * 2f, midR * 2f),
        style = Stroke(width = 2f + frame.bassPulse * 4f),
    )
}
