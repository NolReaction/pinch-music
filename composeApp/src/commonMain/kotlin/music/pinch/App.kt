package music.pinch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import music.pinch.ui.StageFrames
import music.pinch.ui.LocalStageFrames
import music.pinch.ui.GameScreen
import music.pinch.ui.LaunchSignature

// The UI claims and dismisses the mark once per process. The audio worker can skip a stale start.
internal object LaunchPresentation {
    private var claimed = false
    var visible by mutableStateOf(true)
        private set
    fun claim(): Boolean {
        if (claimed || !visible) return false
        claimed = true
        return true
    }
    fun dismiss() { visible = false }
}

@Composable
internal expect fun PlaybackFrames(onFrame: (presentationUptimeMs: Long) -> Unit)

@Composable
fun App(platform: Platform, backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    val session = remember(platform, scope) { Session(platform, scope) }
    DisposableEffect(session) { onDispose { session.dispose() } }
    val media = session.media
    val stageFrames = remember(session) { StageFrames() }
    var shownMs by remember(media) { mutableLongStateOf(session.positionMs) }
    if (media != null && session.state.scene != Scene.Paused) {
        PlaybackFrames { target ->
            if (session.media === media && session.state.scene != Scene.Paused) {
                session.observe()
                shownMs = session.presentationPosition(target)
                stageFrames.render?.invoke()
            }
        }
    }
    backHandler(session.state.scene != Scene.Menu) {
        when (session.state.scene) {
            Scene.CountIn, Scene.Playing, Scene.Resuming -> session.pause()
            else -> session.menu()
        }
    }
    val positionMs = remember(session, media) { {
        when (session.state.scene) {
            Scene.Playing, Scene.CountIn -> shownMs
            else -> session.positionMs
        }
    } }
    val ownsLaunch = remember { LaunchPresentation.claim() }
    val launchMenu = ownsLaunch && session.state.scene == Scene.Menu
    val showLaunch = launchMenu && LaunchPresentation.visible
    var firstTouch by remember { mutableStateOf(false) }
    if (launchMenu) DisposableEffect(platform) {
        onDispose {
            LaunchPresentation.dismiss()
            platform.stopLaunchSound()
        }
    }
    // A slow audio device may still be finishing the third note after the visual fade.
    val menuTouch = if (launchMenu && !firstTouch) Modifier.pointerInput(platform) {
        awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial) }
        firstTouch = true
        LaunchPresentation.dismiss()
        platform.stopLaunchSound()
    } else Modifier
    Box(Modifier.fillMaxSize().then(menuTouch)) {
        CompositionLocalProvider(LocalStageFrames provides stageFrames) {
            GameScreen(session.state, session, positionMs, session.stage)
        }
        if (showLaunch) {
            val phase = remember { Animatable(0f) }
            LaunchedEffect(platform) {
                withFrameMillis { }
                platform.firstMenuFrame()
                if (!LaunchPresentation.visible) return@LaunchedEffect
                val gain = platform.volume
                if (gain > 0f) platform.playLaunchSound(gain)
                phase.animateTo(1f, tween(1000, easing = LinearEasing))
                LaunchPresentation.dismiss()
            }
            LaunchSignature { phase.value }
        }
    }
}
