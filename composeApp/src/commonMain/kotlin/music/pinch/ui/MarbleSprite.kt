package music.pinch.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import music.pinch.resources.Res
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt

/** Use the same baked lighting and angular frames in each projection. */
internal fun DrawScope.drawStudioMarble(atlas: ImageBitmap, center: Offset, radius: Float, angle: Float) {
    val columns = 6
    val rows = 4
    val turns = angle / (2f * PI.toFloat())
    val tile = floor((turns - floor(turns)) * (rows * columns)).toInt().coerceIn(0, rows * columns - 1)
    val width = atlas.width / columns
    val height = atlas.height / rows
    val diameter = (2f * radius).roundToInt().coerceAtLeast(1)
    drawImage(atlas, srcOffset = IntOffset(tile % columns * width, tile / columns * height),
        srcSize = IntSize(width, height),
        dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
        dstSize = IntSize(diameter, diameter))
}

/** Read and decode the atlas off the presentation thread while the selected course warms up. */
@Composable
internal fun rememberOrbitAtlas(enabled: Boolean): State<Result<ImageBitmap>?> =
    produceState<Result<ImageBitmap>?>(null, enabled) {
        if (!enabled || value?.isSuccess == true) return@produceState
        withFrameNanos { }
        value = withContext(Dispatchers.Default) {
            try {
                Result.success(Res.readBytes("drawable/studio_marble.png").decodeToImageBitmap())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
