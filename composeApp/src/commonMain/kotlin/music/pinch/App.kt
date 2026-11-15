package music.pinch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameMillis
import music.pinch.ui.GameScreen

@Composable
fun App(platform: Platform, backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    val session = remember(platform, scope) { Session(platform, scope) }
    DisposableEffect(session) { onDispose { session.close() } }
    val media = session.media
    LaunchedEffect(session, media, session.state.scene) {
        // A frame schedules an observation. The monotonic audio transport supplies the musical time.
        if (media != null && session.state.scene != Scene.Paused) {
            while (session.media === media && session.state.scene != Scene.Paused) {
                withFrameMillis { session.observe() }
            }
        }
    }
    backHandler(session.state.scene != Scene.Menu) {
        when (session.state.scene) {
            Scene.Playing, Scene.Resuming -> session.pause()
            else -> session.menu()
        }
    }
    val positionMs = remember(session) { { session.positionMs } }
    GameScreen(session.state, session, positionMs)
}
