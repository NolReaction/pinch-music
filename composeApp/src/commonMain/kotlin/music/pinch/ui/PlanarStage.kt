package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import music.pinch.core.Chart
import music.pinch.resources.Res
import music.pinch.resources.studio_marble
import org.jetbrains.compose.resources.imageResource

private val Graphite = Color(0xff18232c)
private val Anodized = Color(0xff455968)
private val SteelLip = Color(0xff82919b)
private val PanelBlack = Color(0xff0b1921)
private val Amber = Color(0xffffc66e)
private val Aqua = Color(0xff69f4d5)
private val Ice = Color(0xffc2f8fb)
private val Diode = Color(0xff76d7ff)
private val Void = Color(0xff061019)

private fun glow(tint: Color) = Brush.radialGradient(listOf(
    tint.copy(alpha = .71f), tint.copy(alpha = .22f), tint.copy(alpha = .04f), tint.copy(alpha = 0f)
), Offset.Zero, 1f)

private val BlueGlow = glow(Diode)
private val AmberGlow = glow(Amber)
private val EchoGlow = glow(Aqua)
private val ImpactShade = glow(Color(0xff020a12))
private val AccentOutline = Stroke(.022f)
private val SpringStroke = Stroke(.027f, cap = StrokeCap.Round)
private const val Mount = 2.5f

// Oblique parallel projection. X and Y are the playing plane, depth reveals the
// back edge, end cap and extrusion of each instrument without banking the playing surface.
private fun surface(x: Float, y: Float, z: Float) = Offset(x - z * .16f, y + z * .43f)

private fun scaleFor(width: Float, height: Float) = min(width / 5.65f, height / 6.10f)

private fun DrawScope.bloom(center: Offset, rx: Float, ry: Float, brush: Brush, strength: Float) {
    if (strength <= .008f) return
    translate(center.x, center.y) {
        scale(rx, ry, Offset.Zero) { drawCircle(brush, 1f, Offset.Zero, alpha = strength.coerceAtMost(1f)) }
    }
}

/** Stationary cabinet illumination is baked once per viewport. The fixtures scroll with the course. */
private class Cabinet(size: Size) {
    private val sampleScale = min(1f, 600f / size.maxDimension)
    private val width = max(1, (size.width * sampleScale).roundToInt())
    private val height = max(1, (size.height * sampleScale).roundToInt())
    val image = ImageBitmap(width, height).also { bitmap ->
        val viewport = Size(width.toFloat(), height.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, BitmapCanvas(bitmap), viewport) {
            val unit = scaleFor(viewport.width, viewport.height)
            val w = viewport.width
            val h = viewport.height
            drawRect(Brush.linearGradient(listOf(Color(0xff172b36), Color(0xff32424a), Color(0xff172735)),
                Offset(w * .02f, h * .16f), Offset(w * 1.06f, h * .93f)))
            drawRect(Brush.radialGradient(listOf(Color(0x595c9c9e), Color(0x125c9c9e), Color.Transparent),
                Offset(.17f * w, .36f * h), .64f * h))
            drawRect(Brush.radialGradient(listOf(Color(0x315f6a99), Color.Transparent),
                Offset(w, .71f * h), .54f * h))
            // Dampened felt on the back wall, with two recessed seams running along the chassis.
            for (direction in listOf(-1, 1)) {
                val shoulder = w * .5f + direction * Mount * unit
                val seam = shoulder - direction * .35f * unit
                drawLine(Color(0x35202d34), Offset(seam, 0f), Offset(seam, h), max(1f, .022f * unit))
                drawLine(Color(0x194fa7a7), Offset(seam + unit * .018f, 0f),
                    Offset(seam + unit * .018f, h), max(.5f, unit * .012f))
                val outer = shoulder + direction * .11f * unit
                val edge = if (direction < 0) 0f else w
                val lo = min(outer, edge)
                drawRect(Color(0xff13222a), Offset(lo, 0f), Size(abs(outer - edge), h))
                // A broad soft shadow separates each side post from the wall behind it.
                val inside = shoulder - direction * unit * .28f
                val shadowStart = min(inside, shoulder)
                val shades = if (direction < 0) listOf(Color(0x80202c37), Color.Transparent)
                    else listOf(Color.Transparent, Color(0x80202c37))
                drawRect(Brush.horizontalGradient(shades, shadowStart, shadowStart + unit * .28f),
                    Offset(shadowStart, 0f), Size(unit * .28f, h))
                val railLo = shoulder - .12f * unit
                drawRect(Brush.horizontalGradient(listOf(Color(0xff13232d), Color(0xff43545c),
                    Color(0xff6b797c), Color(0xff30414b), Color(0xff15232c)), railLo, railLo + unit * .24f),
                    Offset(railLo, 0f), Size(unit * .24f, h))
                val lineX = shoulder + direction * .055f * unit
                drawLine(Color(0xff172930), Offset(lineX, 0f), Offset(lineX, h), .030f * unit)
            }
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x08081720), Color(0x9909121c)),
                Offset(.48f * w, h * .48f), .76f * viewport.maxDimension))
        }
        bitmap.prepareToDraw()
    }
}

private fun polygon(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    close()
}

private class ShelfMesh(panel: PlanarPanel, val hasRepeats: Boolean) {
    val led = panel.ledXs.isNotEmpty()
    private val left = min(panel.outerX, panel.tipX)
    private val right = max(panel.outerX, panel.tipX)
    val halfDepth = if (led) .28f else if (hasRepeats) .24f else .21f
    val thickness = if (led) .17f else .12f
    private val cut = .081f
    private val corners = listOf(
        Offset(left + cut, -halfDepth), Offset(right - cut, -halfDepth),
        Offset(right, -halfDepth + cut), Offset(right, halfDepth - cut),
        Offset(right - cut, halfDepth), Offset(left + cut, halfDepth),
        Offset(left, halfDepth - cut), Offset(left, -halfDepth + cut)
    )
    val deck = polygon(corners.map { surface(it.x, 0f, it.y) })
    val face = polygon(listOf(surface(left + cut, 0f, halfDepth), surface(right - cut, 0f, halfDepth),
        surface(right - cut, thickness, halfDepth), surface(left + cut, thickness, halfDepth)))
    // Visible vertical faces from the direction of the parallel projection.
    val chamfers = buildList {
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val dx = b.x - a.x
            val dz = b.y - a.y
            if (.16f * dz - dx <= 0f || (a.y == halfDepth && b.y == halfDepth)) continue
            val path = polygon(listOf(surface(a.x, 0f, a.y), surface(b.x, 0f, b.y),
                surface(b.x, thickness, b.y), surface(a.x, thickness, a.y)))
            add(path to if (dz > 0f) Color(0xff3b505b) else Color(0xff1d2c34))
        }
    }
    private val topTint = when {
        led -> listOf(Color(0xff70909c), Color(0xff486977), Color(0xff344f62))
        hasRepeats -> listOf(Color(0xffc49c88), Color(0xffae8477), Color(0xff8f6f67))
        panel.left -> listOf(Color(0xffacb6bb), Color(0xff8c9ba3), Color(0xff637884))
        else -> listOf(Color(0xffb2aeb6), Color(0xff9294a5), Color(0xff706f83))
    }
    val finish = Brush.verticalGradient(topTint, -halfDepth * .43f, halfDepth * .43f)
    val faceFinish = Brush.verticalGradient(listOf(Color(0xff263a46), Color(0xff344a55), Color(0xff101e26)),
        halfDepth * .43f, halfDepth * .43f + thickness)
    val rimLeft = surface(left + cut, 0f, halfDepth)
    val rimRight = surface(right - cut, 0f, halfDepth)
    val channelLeft = surface(left + .13f, thickness * .50f, halfDepth)
    val channelRight = surface(right - .13f, thickness * .50f, halfDepth)
    val root = panel.outerX
    val tip = panel.tipX
    val inward = if (panel.left) 1f else -1f
    val socketX = panel.outerX + inward * .026f
    val underRoot = Offset(socketX, .43f)
    val underKey = Offset(root + (tip - root) * .45f, thickness + .06f)
    val hasSpring = hasRepeats && !led
    val springX = root + (tip - root) * .25f
    val spring = if (hasSpring) Path().apply {
        val base = springX
        moveTo(base, .14f)
        repeat(5) { i -> lineTo(base + (if (i % 2 == 0) .07f else -.07f), .17f + .025f * i) }
        lineTo(base, .31f)
    } else null
}

internal class PlanarScene(chart: Chart, private val marbleAtlas: ImageBitmap) {
    private val course = PlanarPath(chart)
    private val meshes = course.panels.map { ShelfMesh(it, it.firstAttack != it.lastAttack) }
    private var cabinetSize = Size.Zero
    private var cabinet: Cabinet? = null

    private fun firstVisible(minY: Float): Int {
        var low = 0
        var high = course.panels.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (course.panels[mid].topY < minY) low = mid + 1 else high = mid
        }
        return low
    }

    private fun recentPanel(cueMs: Long?, timeMs: Long): Int {
        if (cueMs == null || timeMs - cueMs !in -195L..480L) return -1
        val hit = course.attacks.binarySearchBy(cueMs) { it.atMs }
        return if (hit < 0) -1 else course.attacks[hit].panelIndex
    }

    private fun DrawScope.fasteners(top: Float, bottom: Float) {
        for (station in floor(top / .58f).toInt()..ceil(bottom / .58f).toInt()) {
            val y = station * .58f
            for (side in -1..1 step 2) {
                val p = Offset(side * Mount, y)
                if (station % 4 == 0) {
                    drawCircle(PanelBlack, .035f, p)
                    drawCircle(SteelLip, .023f, p - Offset(.003f, .004f))
                    drawLine(Graphite, p - Offset(.013f, -.009f), p + Offset(.013f, -.009f), .009f)
                } else {
                    drawCircle(Color(0xff24343b), .019f, p)
                }
            }
        }
    }

    private fun DrawScope.fixture(mesh: ShelfMesh, compression: Float) {
        val x = mesh.socketX
        drawLine(Void, mesh.underRoot, mesh.underKey, .090f, StrokeCap.Round)
        drawLine(Anodized, mesh.underRoot - Offset(0f, .014f), mesh.underKey - Offset(0f, .014f),
            .044f, StrokeCap.Round)
        drawRoundRect(Graphite, Offset(x - .098f, -.12f), Size(.196f, .63f), CornerRadius(.04f))
        drawRoundRect(Anodized, Offset(x - .074f, -.095f), Size(.148f, .58f), CornerRadius(.035f))
        drawCircle(PanelBlack, .040f, Offset(x, .40f))
        drawCircle(SteelLip, .024f, Offset(x, .39f))
        mesh.spring?.let {
            // The stationary key drives a telescoping sprung plunger underneath.
            val seat = Offset(mesh.springX, .31f - .17f * compression)
            drawLine(PanelBlack, Offset(mesh.springX, .39f), seat, .075f, StrokeCap.Round)
            scale(1f, 1f - compression, Offset(mesh.springX, .14f)) { drawPath(it, SteelLip, style = SpringStroke) }
            drawLine(Color(0xffc3a592), seat - Offset(.083f, 0f), seat + Offset(.083f, 0f), .032f, StrokeCap.Round)
        }
    }

    private fun DrawScope.pickup(x: Float, accent: Boolean, echo: Boolean, light: Float) {
        val p = surface(x, 0f, -.01f)
        val rx = if (accent || echo) .105f else .080f
        val rz = .060f
        if (light > 0f) bloom(p, .34f, .15f, if (echo) EchoGlow else if (accent) AmberGlow else BlueGlow, light)
        val tint = if (echo) Aqua else if (accent) Amber else SteelLip
        drawOval(PanelBlack, p - Offset(rx, rz), Size(2f * rx, 2f * rz))
        drawOval(tint, p - Offset(rx, rz), Size(2f * rx, 2f * rz), style = Stroke(.017f))
        if (echo) {
            drawOval(tint, p - Offset(rx * .54f, rz * .54f), Size(rx * 1.08f, rz * 1.08f),
                style = Stroke(.013f))
        } else {
            drawLine(tint, p - Offset(.045f, 0f), p + Offset(.045f, 0f), .015f, StrokeCap.Round)
        }
    }

    private fun DrawScope.instrument(
        index: Int, frame: PlanarFrame, timeMs: Long, hitPanel: Int, hitMs: Long?,
        echoPanel: Int, echoMs: Long?, ledTapMs: Long?
    ) {
        val panel = course.panels[index]
        val mesh = meshes[index]
        val last = frame.attackIndex.coerceIn(panel.firstAttack, panel.lastAttack)
        val next = min(last + 1, panel.lastAttack)
        val strike = course.attacks[last]
        val coming = course.attacks[next]
        val mark = if (last != next && timeMs - strike.atMs > coming.atMs - timeMs) coming else strike
        val age = timeMs - strike.atMs
        val contact = if (age in 0L..260L) 1f - age / 260f else 0f
        val hit = when (index) {
            echoPanel -> echoMs
            hitPanel -> hitMs
            else -> null
        }
        val feedback = if (hit == null) 0f else 1f - (timeMs - hit).coerceAtLeast(0L) / 480f
        val cue = if (mark.playable) Amber else if (mark.isEcho) Aqua else Diode
        val approach = if (mark.playable && mark.atMs - timeMs in 0L..460L)
            1f - (mark.atMs - timeMs) / 460f else 0f

        bloom(Offset((mesh.root + mesh.tip) * .5f + .14f, .38f),
            abs(mesh.tip - mesh.root) * .54f, .21f, ImpactShade, .78f)
        val compression = if (mesh.hasSpring && age in 1L..320L)
            .32f * (1f - cos(age / 26f)) * (1f - age / 320f) else 0f
        fixture(mesh, compression)
        for ((path, color) in mesh.chamfers) drawPath(path, color)
        drawPath(mesh.face, mesh.faceFinish)
        drawPath(mesh.deck, mesh.finish)
        if (contact > 0f) drawPath(mesh.deck, cue, alpha = contact * contact * .55f)
        if (mark.playable) drawPath(mesh.deck, Amber, alpha = .55f + .40f * approach, style = AccentOutline)
        drawLine(if (mark.playable) Amber else SteelLip, mesh.rimLeft, mesh.rimRight, .018f)
        drawLine(PanelBlack, mesh.channelLeft, mesh.channelRight, .059f, StrokeCap.Round)
        if (mesh.led) {
            ledBank(panel, mesh, timeMs, ledTapMs, frame.rollingPanel == index)
        } else {
            val ferrule = (mesh.tip - mesh.root) * .20f
            val a = mesh.channelRight.x - mesh.channelLeft.x
            val socket = mesh.root + ferrule
            drawLine(Anodized, Offset(socket, mesh.channelLeft.y),
                Offset(socket + mesh.inward * abs(a) * .42f, mesh.channelLeft.y), .023f, StrokeCap.Round)
            if (mesh.hasSpring) {
                // Three tuned tongues are let into the copper key. The piezo pickup sits at the tip.
                for (i in 0..2) {
                    val x = mesh.root + mesh.inward * (.29f + .10f * i)
                    val back = surface(x, 0f, -.125f)
                    val front = surface(x, 0f, .105f)
                    drawLine(Graphite, back, front, .034f, StrokeCap.Round, alpha = .80f)
                    val pulseAge = age - i * 30L
                    if (pulseAge in 0L..180L) drawLine(Amber, back, front, .023f, StrokeCap.Round,
                        alpha = 1f - pulseAge / 180f)
                }
            }
        }
        val body = frame.marble
        val gap = panel.topY - body.y - MarbleRadius
        if (gap in -.015f..1.2f && body.x in min(mesh.tip, mesh.root) - .2f..max(mesh.tip, mesh.root) + .2f) {
            clipPath(mesh.deck) {
                bloom(surface(body.x, 0f, .012f), .18f + gap * .14f, .08f + gap * .035f,
                    ImpactShade, .90f - .28f * gap)
            }
        }
        pickup(mark.x, mark.playable, mark.isEcho, max(contact * .65f, approach * .62f))
        if (feedback > 0f) {
            val tint = if (index == echoPanel) Aqua else Amber
            drawPath(mesh.deck, tint, alpha = feedback, style = Stroke(.029f))
            bloom(Offset(mesh.tip - mesh.inward * .11f, .03f), .45f, .18f,
                if (index == echoPanel) EchoGlow else AmberGlow, feedback)
        }
    }

    private fun DrawScope.ledBank(
        panel: PlanarPanel, mesh: ShelfMesh, timeMs: Long, tapMs: Long?, rolling: Boolean,
    ) {
        val points = panel.ledXs
        val times = panel.ledTimesMs
        val spacing = if (points.size > 1) abs(points[0] - points[1]) else .20f
        val width = min(.19f, spacing * .81f)
        // Each crossed cell keeps its charge until the last crossing. The leading lens
        // burns white-blue, leaving a softer, readable blue bar underneath the marble.
        val decay = (1f - (timeMs - times.last()).coerceAtLeast(0L) / 540f).coerceAtLeast(0f)
        val held = if (rolling) .55f else .55f * decay
        val railStart = surface(points.first(), mesh.thickness * .50f, mesh.halfDepth)
        val railEnd = surface(points.last(), mesh.thickness * .50f, mesh.halfDepth)
        drawLine(Color(0xff264657), railStart, railEnd, .077f, StrokeCap.Round)
        for (j in points.indices) {
            val age = timeMs - times[j]
            val caught = times[j] == tapMs && age in -100L..450L
            val power = when {
                caught -> max(held, 1f - age.coerceAtLeast(0L) / 450f)
                age >= 0L -> max(held, 1f - age / 390f)
                else -> 0f
            }
            val x = points[j]
            val lamp = surface(x, mesh.thickness * .50f, mesh.halfDepth)
            val tint = if (caught) Aqua else Diode
            if (power > 0f) {
                bloom(lamp, if (caught) .40f else .25f, .14f, if (caught) EchoGlow else BlueGlow, power)
            }
            val left = lamp - Offset(width * .50f, 0f)
            val right = lamp + Offset(width * .50f, 0f)
            drawLine(Color(0xff4a7284), left, right, .052f, StrokeCap.Round)
            if (power > 0f) {
                drawLine(tint, left, right, .060f, StrokeCap.Round, alpha = power)
                if (power > .6f) drawLine(Ice, left, right, .025f, StrokeCap.Round, alpha = power)
            }
            // Diffusing inserts in the deck sit directly under the rolling sphere.
            val back = surface(x, 0f, -.15f)
            val front = surface(x, 0f, .145f)
            drawLine(Graphite, back, front, width * .60f, StrokeCap.Round)
            drawLine(if (caught) Ice else Diode, back, front, width * .35f, StrokeCap.Round,
                alpha = .30f + .70f * power)
        }
    }

    fun DrawScope.draw(timeMs: Long, hitMs: Long? = null, collectedEchoMs: Long? = null, ledTapMs: Long? = null) {
        if (size.minDimension < 1f) return
        if (cabinetSize != size) {
            cabinetSize = size
            cabinet = Cabinet(size)
        }
        cabinet?.let { drawImage(it.image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
        val frame = course.frame(timeMs)
        val unit = scaleFor(size.width, size.height)
        val ox = size.width * .5f
        val oy = size.height * .46f
        val top = frame.cameraY - oy / unit - .8f
        val bottom = frame.cameraY + (size.height - oy) / unit + .6f
        val first = firstVisible(top)
        val hitPanel = recentPanel(hitMs, timeMs)
        val echoPanel = recentPanel(collectedEchoMs, timeMs)
        withTransform({
            translate(ox, oy)
            scale(unit, unit, Offset.Zero)
            translate(0f, -frame.cameraY)
        }) {
            fasteners(top, bottom)
            val ball = frame.marble
            bloom(Offset(ball.x + .23f, ball.y + .37f), .33f, .26f, ImpactShade, .75f)
            for (i in first until meshes.size) {
                val y = course.panels[i].topY
                if (y > bottom) break
                translate(0f, y) { instrument(i, frame, timeMs, hitPanel, hitMs, echoPanel, collectedEchoMs, ledTapMs) }
            }
        }
        drawStudioMarble(marbleAtlas, Offset(ox + frame.marble.x * unit, oy + (frame.marble.y - frame.cameraY) * unit),
            unit * MarbleRadius, frame.rollAngle)
    }
}

@Composable
fun PlanarStage(
    chart: Chart, positionMs: () -> Long, modifier: Modifier = Modifier,
    hitMs: Long? = null, collectedEchoMs: Long? = null, ledTapMs: Long? = null,
) {
    val atlas = imageResource(Res.drawable.studio_marble)
    val scene = remember(chart, atlas) { PlanarScene(chart, atlas) }
    Canvas(modifier.clipToBounds()) { with(scene) { draw(positionMs(), hitMs, collectedEchoMs, ledTapMs) } }
}
