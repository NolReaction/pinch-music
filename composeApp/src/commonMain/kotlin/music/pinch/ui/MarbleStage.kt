package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import music.pinch.core.Chart
import music.pinch.resources.Res
import music.pinch.resources.studio_marble
import org.jetbrains.compose.resources.imageResource

private val WallLight = Color(0xffdbd3e4)
private val WallMid = Color(0xffa0a9c6)
private val WallBase = Color(0xff757f9e)
private val Gunmetal = Color(0xff303948)
private val Steel = Color(0xff78879b)
private val Chrome = Color(0xffe0e9ef)
private val Teal = Color(0xff6da8a7)
private val Inlay = Color(0xffcea9ff)
private val Flash = Color(0xffffebce)
private const val WallZ = -1.55f

private class StageLens(size: Size, target: StagePoint) {
    private val origin = Offset(size.width * .5f, size.height * .40f)
    private val aim = StagePoint(target.x * .23f, target.y - .13f, .35f)
    private val focal = min(size.width / 6.65f, size.height / 8.1f)

    fun depth(p: StagePoint) = .222f * (p.x - aim.x) - .390f * (p.y - aim.y) + .894f * (p.z - aim.z)
    fun scale(p: StagePoint) = focal * 18f / max(4f, 18f - depth(p))
    fun project(p: StagePoint): Offset {
        val x = p.x - aim.x
        val y = p.y - aim.y
        val z = p.z - aim.z
        return origin + Offset(.9705f * x - .241f * z, .094f * x + .9208f * y + .379f * z) * scale(p)
    }
}

private fun outline(points: List<Offset>, closeShape: Boolean = true): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    if (closeShape) close()
}

private fun circle(n: Int, point: (Float, Float) -> StagePoint) = List(n) { i ->
    val a = (2 * PI * i / n).toFloat()
    point(cos(a), sin(a))
}

private fun shadowOnWall(p: StagePoint): StagePoint {
    val d = p.z - WallZ
    return StagePoint(p.x + .22f * d, p.y + .25f * d, WallZ)
}

private class PanelMesh(val key: StageKey) {
    private val width = when (key.panel) {
        PanelType.Gate -> .67f
        PanelType.Tongue -> .83f
        PanelType.Drum -> .56f
    }
    private val depth = when (key.panel) {
        PanelType.Gate -> .30f
        PanelType.Tongue -> .46f
        PanelType.Drum -> .52f
    }
    val thickness = if (key.panel == PanelType.Drum) .23f else .115f
    val top = circle(24) { c, s ->
        fun shoulder(v: Float) = abs(v).pow(.63f) * if (v < 0) -1f else 1f
        when (key.panel) {
            PanelType.Drum -> key.point(width * c, 0f, depth * s)
            PanelType.Tongue -> key.point(width * shoulder(c) * (1f - .15f * s), 0f, depth * s)
            PanelType.Gate -> key.point(width * shoulder(c), 0f, depth * shoulder(s))
        }
    }
    val bottom = top.map { it + StagePoint(0f, thickness, 0f) }
    val shadow = top.map(::shadowOnWall)
    val mounts = listOf(-width * .69f, width * .69f).map { x ->
        val start = key.point(x, thickness * .82f, -.10f)
        listOf(start, start + StagePoint(0f, .20f, -.15f), StagePoint(start.x, start.y + .32f, WallZ + .045f))
    }
    val rivets = listOf(-width * .73f, width * .73f).map { key.point(it, 0f, -.10f) }
}

private class RailMesh(val run: CourseRun) {
    val stations = if (run.railEnd == 0f || run.durationMs <= 0) emptyList() else {
        val count = ceil(run.durationMs * run.railEnd / 125f).toInt().coerceIn(2, 512)
        (0..count).map { run.point(it * run.railEnd / count) }
    }
}

private fun DrawScope.glow(p: Offset, radius: Float, tint: Color) {
    drawCircle(Brush.radialGradient(listOf(tint, tint.copy(alpha = 0f)), p, radius), radius, p)
}

/** Wall joinery stays in world coordinates so the eye can read the continuous descent. */
private fun DrawScope.drawWall(lens: StageLens, target: StagePoint) {
    drawRect(Brush.linearGradient(listOf(WallLight, WallMid, WallBase),
        Offset(size.width * .08f, 0f), Offset(size.width * .92f, size.height)))
    glow(Offset(size.width * .2f, size.height * .25f), size.maxDimension * .63f, Color(0x5cf6e7ef))
    val courseLevel = floor(target.y / 5f).toInt()
    for (level in courseLevel - 2..courseLevel + 2) {
        val y = level * 5f
        val a = lens.project(StagePoint(-6f, y, WallZ))
        val b = lens.project(StagePoint(6f, y, WallZ))
        drawLine(Color(0x1a41506c), a, b, 1f)
        for (x in listOf(-2.84f, 2.84f)) {
            val bolt = StagePoint(x, y + .18f, WallZ)
            val p = lens.project(bolt)
            val r = lens.scale(bolt) * .041f
            drawCircle(Color(0x54515e77), r, p)
            drawCircle(Color(0x94e4e7ed), r * .44f, p - Offset(r * .25f, r * .35f))
        }
    }
}

private fun DrawScope.drawRail(mesh: RailMesh, lens: StageLens, targetY: Float) {
    // A long instrumental rest can span many bars. Only project the visible part of its track.
    val stations = mesh.stations.filter { abs(it.y - targetY) < 11f }
    if (stations.size < 2) return
    val unit = lens.scale(stations[stations.size / 2])
    val width = .265f
    fun edge(sign: Float, y: Float, z: Float) = stations.map {
        lens.project(it + StagePoint(sign * width, y, z))
    }
    val left = edge(-1f, 0f, .035f)
    val right = edge(1f, 0f, -.035f)
    val bed = outline(left + right.asReversed())
    val shade = stations.map { lens.project(shadowOnWall(it)) }
    drawPath(outline(shade, false), Color(0x2449576b), style = Stroke(unit * .29f))
    val sill = edge(1f, .072f, -.035f)
    drawPath(outline(right + sill.asReversed()), Steel)
    drawPath(bed, Color(0xff455261))
    val inner = stations.map { lens.project(it + StagePoint(0f, .006f, 0f)) }
    drawPath(outline(inner, false), if (mesh.run.type == RunType.Bank) Teal else Gunmetal, style = Stroke(unit * .115f))
    // Brushed cross-ties visibly divide a long slope into lengths as the ball rolls past.
    for (station in mesh.stations.indices step 4) {
        if (station == 0 || station == mesh.stations.lastIndex) continue
        val joint = mesh.stations[station]
        if (abs(joint.y - targetY) >= 11f) continue
        val a = lens.project(joint + StagePoint(-width, .018f, .035f))
        val b = lens.project(joint + StagePoint(width, .018f, -.035f))
        drawLine(Gunmetal, a, b, unit * .044f)
        drawLine(Chrome.copy(alpha = .72f), a, b, unit * .012f)
        if (station % 12 == 0) {
            val bolt = lens.project(StagePoint(joint.x + .24f, joint.y + .19f, WallZ))
            drawLine(Steel, bolt, b, unit * .035f)
            drawCircle(Steel, unit * .065f, bolt)
            drawCircle(Chrome, unit * .024f, bolt)
        }
    }
    for (edge in listOf(left, right)) {
        val path = outline(edge, false)
        drawPath(path, Gunmetal, style = Stroke(unit * .088f))
        drawPath(path, Steel, style = Stroke(unit * .054f))
        drawPath(path, Chrome, style = Stroke(unit * .019f))
    }
    if (mesh.run.type == RunType.Drop) {
        val last = mesh.stations.last()
        if (abs(last.y - targetY) < 10f) {
            drawLine(Chrome, left.last(), right.last(), unit * .054f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawMounts(mesh: PanelMesh, lens: StageLens) {
    val unit = lens.scale(mesh.key.center)
    for (rod in mesh.mounts) {
        val socket = lens.project(rod.last())
        val r = .078f * unit
        drawCircle(Steel, r, socket)
        drawCircle(Chrome, r * .56f, socket - Offset(r * .16f, r * .22f))
        val shaft = outline(rod.map(lens::project), false)
        drawPath(shaft, Gunmetal, style = Stroke(unit * .059f))
        drawPath(shaft, Steel, style = Stroke(unit * .037f))
        drawPath(shaft, Chrome.copy(alpha = .68f), style = Stroke(unit * .013f))
    }
}

private fun DrawScope.drawPanel(mesh: PanelMesh, lens: StageLens, incoming: Float, strike: Float) {
    val key = mesh.key
    val p = lens.project(key.center)
    val unit = lens.scale(key.center)
    val top = mesh.top.map(lens::project)
    val base = mesh.bottom.map(lens::project)
    val body = when (key.panel) {
        PanelType.Gate -> Color(0xff435e67)
        PanelType.Tongue -> Color(0xff504d68)
        PanelType.Drum -> Color(0xff97695d)
    }
    val energy = max(incoming * .60f, strike)
    if (energy > .02f) glow(p, unit * 1.04f, Inlay.copy(alpha = .35f * energy))
    drawPath(outline(base), Gunmetal)
    // The filled sides share a silhouette with the top even at oblique camera angles.
    for (i in top.indices) {
        val j = (i + 1) % top.size
        if (top[j].x < top[i].x) {
            drawPath(outline(listOf(top[i], top[j], base[j], base[i])), lerp(body, Gunmetal, .45f))
        }
    }
    val face = outline(top)
    drawPath(face, Brush.linearGradient(listOf(lerp(body, Inlay, energy * .73f), lerp(Gunmetal, body, .74f)),
        p - Offset(unit * .6f, unit * .2f), p + Offset(unit * .6f, unit * .2f)))
    drawPath(face, lerp(Steel, Flash, energy), style = Stroke(unit * .025f))
    if (energy > .02f) {
        val a = lens.project(key.point(-.29f, mesh.thickness * .58f, .30f))
        val b = lens.project(key.point(.29f, mesh.thickness * .58f, .30f))
        drawLine(lerp(Inlay, Flash, strike).copy(alpha = .30f + energy * .70f), a, b, unit * .025f, StrokeCap.Round)
    }
    for (rivet in mesh.rivets) {
        val r = lens.project(rivet)
        drawOval(Gunmetal, r - Offset(.046f, .021f) * unit, Size(unit * .092f, unit * .042f))
        drawLine(Chrome, r - Offset(unit * .023f, unit * .006f), r + Offset(unit * .014f, 0f), unit * .011f)
    }
    // The fixed bullseye marks a playable contact. Cool, parallel rails carry the marble automatically.
    fun ring(radius: Float) = outline(circle(24) { c, s -> key.point(c * radius, -.007f, s * radius) }.map(lens::project))
    val bullseye = ring(.205f)
    drawPath(bullseye, Gunmetal)
    drawPath(bullseye, lerp(Inlay.copy(alpha = .76f), Flash, energy), style = Stroke(unit * .032f))
    drawPath(ring(.073f), lerp(Inlay, Flash, energy))
    if (incoming > 0f) {
        drawPath(ring(.22f + (1f - incoming) * .26f), Inlay.copy(alpha = .45f + incoming * .5f),
            style = Stroke(unit * .025f))
    }
    if (strike > 0f) {
        drawPath(ring(.22f + (1f - strike) * .35f), Flash.copy(alpha = strike), style = Stroke(unit * .039f))
    }
}

@Composable
fun MarbleStage(chart: Chart, positionMs: Long, modifier: Modifier = Modifier) {
    val path = remember(chart) { MarblePath(chart) }
    val panels = remember(path) { path.keys.map(::PanelMesh) }
    val rails = remember(path) { path.spans.map(::RailMesh) }
    val marble = imageResource(Res.drawable.studio_marble)
    Canvas(modifier.clipToBounds()) {
        if (size.minDimension <= 1f) return@Canvas
        val frame = path.frame(positionMs)
        val lens = StageLens(size, frame.target)
        drawWall(lens, frame.target)
        val horizon = (frame.anchor - 13).coerceAtLeast(0)..(frame.anchor + 16).coerceAtMost(panels.lastIndex)
        val visible = horizon.filter {
            val sy = lens.project(panels[it].key.center).y
            sy > -size.height * .14f && sy < size.height * 1.14f
        }
        for (i in visible) {
            val mesh = panels[i]
            val shade = outline(mesh.shadow.map(lens::project))
            val unit = lens.scale(mesh.key.center)
            drawPath(shade, Color(0x17435167), style = Stroke(unit * .12f))
            drawPath(shade, Color(0x26435167))
            drawMounts(mesh, lens)
        }
        val radius = lens.scale(frame.marble) * MarbleRadius
        glow(lens.project(shadowOnWall(frame.marble)), radius * 1.38f, Color(0x60515a73))
        val currentSpan = frame.anchor + 1
        val railRange = (currentSpan - 14).coerceAtLeast(0)..(currentSpan + 16).coerceAtMost(rails.lastIndex)
        for (i in railRange.reversed()) drawRail(rails[i], lens, frame.target.y)
        for (i in visible.sortedBy { lens.depth(panels[it].key.center) }) {
            val mesh = panels[i]
            val elapsed = positionMs - mesh.key.atMs
            val strike = if (elapsed in 0..260L) 1f - elapsed / 260f else 0f
            val incoming = if (frame.next == i) .20f + .80f * (1f + elapsed / 920f).coerceIn(0f, 1f) else 0f
            drawPanel(mesh, lens, incoming, strike)
        }
        val center = lens.project(frame.marble)
        // On a rail the contact shadow is tight. It opens out across a drop or a rebound.
        val foot = lens.project(frame.marble + StagePoint(0f, MarbleRadius + frame.lift, 0f))
        val shadowWidth = radius * (.64f + min(frame.lift, .6f) * .5f)
        drawOval(Gunmetal.copy(alpha = .29f / (1f + 4f * frame.lift)),
            foot - Offset(shadowWidth, shadowWidth * .23f), Size(2 * shadowWidth, shadowWidth * .46f))
        val diameter = max(1, (2f * radius).roundToInt())
        drawImage(marble, dstOffset = IntOffset((center.x-radius).roundToInt(), (center.y-radius).roundToInt()),
            dstSize = IntSize(diameter, diameter))
        // A small brass registration mark rolls with the shell while the environment reflection stays fixed.
        val front = cos(frame.rollAngle)
        if (front > 0f) {
            val mark = center + Offset(sin(frame.rollAngle) * radius * .79f, radius * .27f)
            drawOval(Flash.copy(alpha = .9f), mark - Offset(radius * .07f * front, radius * .055f),
                Size(radius * .14f * front, radius * .11f))
        }
    }
}
