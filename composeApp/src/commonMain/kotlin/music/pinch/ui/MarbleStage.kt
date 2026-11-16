package music.pinch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CompletableDeferred
import music.pinch.PanelFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import music.pinch.core.Chart
import music.pinch.resources.Res
import music.pinch.resources.studio_marble
import org.jetbrains.compose.resources.imageResource

private const val WallZ = -1.06f
private const val WireRadius = .018f
private const val WireSpacing = .092f
private const val Corners = 16
private const val Levels = 4
private const val LightSteps = 12
private const val ShadeSteps = 12
private const val VisibleLimit = 49

private val BlackNickel = Color(0xff414a50)
private val Steel = Color(0xff879496)
private val Silver = Color(0xffd7e0df)
private val PinLight = Color(0xfff6eadb)
private val PinShade = Color(0xff242231)
private val WallShadow = Color(0xff3e5058)
private val ShadowFeather = Brush.radialGradient(listOf(
    WallShadow.copy(alpha=.31f), WallShadow.copy(alpha=.18f), WallShadow.copy(alpha=.045f),
    WallShadow.copy(alpha=0f)
), Offset.Zero, 1f)

private class Camera(size: Size, target: StagePoint) {
    private val ox = size.width * .50f
    private val oy = size.height * .468f
    private val focus = min(size.width / 4.83f, size.height / 5.95f)
    private val x = target.x
    private val y = target.y
    private val z = target.z
    fun depth(p: StagePoint) = .238f*(p.x-x) - .402f*(p.y-y) + .884f*(p.z-z)
    fun unit(p: StagePoint) = focus * 19f / max(6f, 19f - depth(p))
    fun project(p: StagePoint): Offset {
        val dx = p.x-x
        val dy = p.y-y
        val dz = p.z-z
        val u = unit(p)
        return Offset(ox + (.966f*dx - .260f*dz)*u, oy + (.105f*dx + .916f*dy + .388f*dz)*u)
    }
}

/** Fixed material tables keep lighting transitions cheap even on dense runs of short notes. */
internal class Finish(rest: Color, tint: Color) {
    val tops = Array(LightSteps+1) { i -> lerp(rest, tint, i / LightSteps.toFloat()) }
    val bevels = Array(LightSteps+1) { i -> lerp(lerp(rest, PinLight, .31f), lerp(tint, PinLight, .33f), i / LightSteps.toFloat()) }
    val faces = Array(LightSteps+1) { i ->
        lerp(lerp(rest, BlackNickel, .39f), lerp(tint, BlackNickel, .30f), i / LightSteps.toFloat())
    }
    // Indexed by [light][edge normal][bevel/body/bottom]. Building these tables once
    // also avoids repeated colour-space conversions in the polygon draw loop.
    val sides = Array((LightSteps+1)*(ShadeSteps+1)*3) { i ->
        val light = i / (3*(ShadeSteps+1))
        val shade = (i / 3 % (ShadeSteps+1)) / ShadeSteps.toFloat()
        when (i%3) {
            0 -> lerp(faces[light], bevels[light], .32f+.64f*shade)
            1 -> lerp(lerp(rest, BlackNickel, .64f), faces[light], .24f + .76f * shade)
            else -> lerp(BlackNickel, faces[light], .32f + .48f*shade)
        }
    }
    val sideArgb = IntArray(sides.size) { sides[it].toArgb() }
}

/** Each bevel has one colour on its four vertices. Upload all visible sides with one
 * canvas command, leaving the outline, rivets, membrane and contact effects antialiased. */
private class PanelSides {
    private val quads = (Levels - 1) * Corners
    private val points = List(4 * quads) { Offset.Zero }
    private val vertices = Vertices(VertexMode.Triangles, points, points, List(4 * quads) { Color.White }, List(6 * quads) { k ->
            val i = 4 * (k / 6)
            i + when (k % 6) { 0, 3 -> 0; 1 -> 1; 2, 4 -> 2; else -> 3 }
        })
    private val paint = Paint().apply { color = Color.White }
    private var count = 0

    fun add(x: FloatArray, y: FloatArray, a: Int, b: Int, c: Int, d: Int, color: Int) {
        val v = count * 4
        val xy = vertices.positions
        val offset = v * 2
        xy[offset] = x[a]
        xy[offset + 1] = y[a]
        xy[offset + 2] = x[b]
        xy[offset + 3] = y[b]
        xy[offset + 4] = x[c]
        xy[offset + 5] = y[c]
        xy[offset + 6] = x[d]
        xy[offset + 7] = y[d]
        vertices.colors.fill(color, v, v+4)
        count++
    }

    fun draw(scope: DrawScope) {
        if (count > 0) {
            // Vertices is reused across keys. Trailing faces must have zero area.
            vertices.positions.fill(0f, count * 8)
            scope.drawContext.canvas.drawVertices(vertices, BlendMode.Modulate, paint)
        }
        count = 0
    }
}

// Construct the palette and geometry off the display thread with the course.
internal class OrbitMaterials(val theme: StageTheme) {
    private val pigment = stageColor(theme.panel)
    private val light = stageColor(theme.light)
    val wood = Finish(lerp(Color(0xffa36d43), pigment, .12f), Color(0xffe9bf82))
    val drum = Finish(lerp(pigment, Steel, .32f), lerp(pigment, light, .75f))
    val fork = Finish(lerp(Silver, pigment, .13f), Color(0xfff7f8ed))
    val membrane = Array(LightSteps + 1) { i ->
        lerp(lerp(pigment, light, .62f), light, i / LightSteps.toFloat())
    }
    val trim = lerp(stageColor(theme.accent), Silver, .42f)
}

private val HitColor = stageColor(0x6FF1AC)
private val MissColor = stageColor(0xFF494C)

private class Room(size: Size, theme: StageTheme) {
    // The wall has no fine detail. Bake its stationary light, fill and vignette
    // at a bounded resolution; the live camera still projects all moving shadows.
    private val downsample = min(1f, 420f / size.maxDimension)
    private val width = max(1, (size.width * downsample).roundToInt())
    private val height = max(1, (size.height * downsample).roundToInt())
    val background = ImageBitmap(width, height).also { image ->
        val viewport = Size(width.toFloat(), height.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, BitmapCanvas(image), viewport) {
            StageBackdrop(viewport, theme).draw(this)
        }
        image.prepareToDraw()
    }
    val sheen = Brush.linearGradient(
        listOf(Color(0x310e151d), Color(0x047c878b), Color(0x3ef8f9f3), Color.Transparent),
        Offset(0f, size.height*.26f), Offset(size.width, size.height*.49f)
    )
}

private fun shadow(p: StagePoint): StagePoint {
    val distance = p.z - WallZ
    return StagePoint(p.x - distance*.92f, p.y + distance*.34f, WallZ)
}

// The fork is one machined piece. Its two free ends start beyond the strike point.
private val ForkProfile = arrayOf(
    Offset(.92f,-1f), Offset(1f,-.85f), Offset(1f,-.55f), Offset(.92f,-.43f),
    Offset(.22f,-.43f), Offset(.14f,-.29f), Offset(.14f,.29f), Offset(.22f,.43f),
    Offset(.92f,.43f), Offset(1f,.55f), Offset(1f,.85f), Offset(.92f,1f),
    Offset(-.18f,.85f), Offset(-1f,.46f), Offset(-1f,-.46f), Offset(-.18f,-.85f)
)
private val Circle = Array(Corners) { i ->
    val angle = (i.toFloat()/Corners - .125f)*2f*PI.toFloat()
    Offset(cos(angle), sin(angle))
}

internal class Bar(val key: StageKey, index: Int, private val materials: OrbitMaterials) {
    val finish = when (key.shape) {
        PanelShape.WoodBar -> materials.wood
        PanelShape.Drum -> materials.drum
        PanelShape.Fork -> materials.fork
    }
    private val length = key.halfLength
    private val width = key.halfWidth
    val thickness = when (key.shape) {
        PanelShape.WoodBar -> .105f
        PanelShape.Drum -> .16f
        PanelShape.Fork -> .085f
    }
    private val chamfer = min(.030f, width*.22f)
    private val ringHeight = floatArrayOf(0f, chamfer*.68f, thickness-chamfer*.7f, thickness)
    private val ringInset = floatArrayOf(chamfer, 0f, 0f, chamfer*.65f)

    private fun contour(i: Int, halfX: Float, halfZ: Float): Offset {
        val theme = materials.theme
        return when (key.shape) {
            PanelShape.Fork -> {
                val p = ForkProfile[i]
                val neck = if (theme.cutCorners && p.x > .5f) .84f else 1f
                Offset(p.x * halfX, p.y * halfZ * neck)
            }
            PanelShape.Drum -> {
                val p = if (theme.cutCorners && i % 2 != 0)
                    (Circle[i - 1] + Circle[(i + 1) % Corners]) * .5f else Circle[i]
                Offset(p.x * halfX, p.y * halfZ)
            }
            PanelShape.WoodBar -> {
                val angle = (i/4 - 1 + (i%4)/3f) * (PI/2).toFloat()
                val radius = min(halfX, halfZ) * theme.cornerFraction
                val sx = if (i < 8) 1 else -1
                val sz = if (i in 4..11) 1 else -1
                // The bevel styles join quarter-circle endpoints with a flat cut.
                val nx = cos(angle)
                val nz = sin(angle)
                val cut = if (theme.cutCorners) kotlin.math.abs(nx) + kotlin.math.abs(nz) else 1f
                Offset(sx*(halfX-radius) + radius*nx/cut, sz*(halfZ-radius) + radius*nz/cut)
            }
        }
    }
    val mesh = Array(Levels*Corners) { n ->
        val level = n/Corners
        val v = contour(n%Corners, length-ringInset[level], width-ringInset[level])
        key.point(v.x, ringHeight[level], v.y)
    }

    private fun ring(rx: Float, rz: Float) = Array(Corners) { i ->
        key.point(rx * Circle[i].x, 0f, rz * Circle[i].y)
    }
    val skin = if (key.shape == PanelShape.Drum) ring(length*.79f, width*.79f) else emptyArray()
    // Fine curved fibers follow the long axis. These world-space paths are
    // prepared with the bar, so neither the grain nor its phase swims on camera moves.
    val woodGrain = if (key.shape == PanelShape.WoodBar) Array(7) { row ->
        Array(12) { sample ->
            val t = sample / 11f
            val x = length * (-.80f + 1.60f * t)
            val z = width * ((row - 3) * .19f + .045f * sin(t * 5.2f + row * .8f + index))
            key.point(x, 0f, z)
        }
    } else emptyArray()
    val depthAxis = key.point(0f,0f,1f) - key.center
    val shading = IntArray(Corners) { i ->
        val a = mesh[i+Corners]
        val b = mesh[(i+1)%Corners+Corners]
        val dx = b.x-a.x
        val dz = b.z-a.z
        ((.49f + (.44f*dx + .21f*dz) / max(.0001f, kotlin.math.sqrt(dx*dx+dz*dz))).coerceIn(.1f, .94f)*ShadeSteps).roundToInt()
    }
    val shadowLeft = shadow(key.point(-length, thickness*.55f, 0f))
    val shadowRight = shadow(key.point(length, thickness*.55f, 0f))
    val shadowPenumbra = .13f + (key.center.z-WallZ)*.077f
    val pins = when (key.shape) {
        PanelShape.Fork -> arrayOf(key.point(-length*.72f, 0f, 0f))
        PanelShape.Drum -> emptyArray()
        PanelShape.WoodBar -> arrayOf(key.point(-length*.64f, 0f, 0f), key.point(length*.64f, 0f, 0f))
    }
    private val mountSpacing = length * if (key.shape == PanelShape.Fork) .19f else .43f
    private val mountX = if (key.shape == PanelShape.Fork) -length*.27f else 0f
    val stemLength = .09f + (index%4)*.025f
    val anchors = arrayOf(key.point(mountX-mountSpacing, thickness-.01f, 0f), key.point(mountX+mountSpacing, thickness-.01f, 0f))
    val lower = Array(2) { n -> anchors[n] + StagePoint(0f,stemLength,-.055f) }
    val join = key.point(mountX,thickness+stemLength,-.055f)
    val knee = StagePoint(join.x, join.y+.035f, max(WallZ+.055f,join.z-.28f))
    val socket = StagePoint(knee.x, knee.y, WallZ)
    // Wall braces belong to the rear lane. A brace across the front lane
    // would cross the path of the descending ball on the opposite turn.
    val wallBracket = index%5 == 1 && key.center.z <= .80f
    val bridge = index%3 != 1 || wallBracket
}

internal class Rail(run: CourseRun) {
    val stations = if (run.railEnd<=0f || run.durationMs<=0L) emptyArray() else {
        val samples = ceil(run.durationMs*run.railEnd/85f).toInt().coerceIn(3, 240)
        Array(samples+1) { i -> run.point(run.railEnd*i/samples) }
    }
    // Twin cylindrical wires are tangent to the sphere on either side of its
    // lowest point; their orientations follow the rail on banked curves.
    private val height = MarbleRadius - sqrt((MarbleRadius+WireRadius)*(MarbleRadius+WireRadius) - WireSpacing*WireSpacing)
    private val normals = Array(stations.size) { i -> run.railNormal(run.railEnd*i/stations.lastIndex) }
    private val offset = Array(stations.size) { i ->
        val along = stations[min(stations.lastIndex,i+1)] - stations[max(0,i-1)]
        normals[i].cross(along).unit() * WireSpacing
    }
    val left = Array(stations.size) { i -> stations[i] + normals[i]*height - offset[i] }
    val right = Array(stations.size) { i -> stations[i] + normals[i]*height + offset[i] }
}

private val orbitMaterials = StageTheme.entries.map { theme -> lazy { OrbitMaterials(theme) } }

/** The preparation worker builds the opening view. Later bands are filled as they enter the camera. */
internal class OrbitMeshes(val path: MarblePath, theme: StageTheme = StageTheme.Stereo) {
    val materials = orbitMaterials[theme.ordinal].value
    private val bars = arrayOfNulls<Bar>(path.keys.size)
    private val rails = arrayOfNulls<Rail>(path.spans.size)

    init {
        for (i in 0..min(bars.lastIndex, VisibleLimit / 2)) barAt(i)
        for (i in 0..min(rails.lastIndex, VisibleLimit / 2 + 1)) {
            if (path.spans[i].railEnd > 0f) railAt(i)
        }
    }

    fun barAt(i: Int) = bars[i] ?: Bar(path.keys[i], i, materials).also { bars[i] = it }
    fun railAt(i: Int) = rails[i] ?: Rail(path.spans[i]).also { rails[i] = it }
}

internal class MarbleScene(private val meshes: OrbitMeshes, private val marbleAtlas: ImageBitmap, private val theme: StageTheme) {
    private val path = meshes.path
    private val accent = stageColor(theme.accent)
    private val outline = Path()
    private val detail = Path()
    private val projectedX = FloatArray(Levels*Corners)
    private val projectedY = FloatArray(Levels*Corners)
    private val sides = if (supportsPanelMesh) PanelSides() else null
    private val rods = IntArray(Corners / 2)
    private val sorted = IntArray(VisibleLimit)
    private val depths = FloatArray(VisibleLimit)
    private var roomSize = Size.Zero
    private var room: Room? = null

    private fun DrawScope.barShadow(bar: Bar, camera: Camera) {
        val l = camera.project(bar.shadowLeft)
        val r = camera.project(bar.shadowRight)
        val length = r-l
        val center = (l+r)*.5f
        val u = camera.unit(bar.join)
        val blur = u*bar.shadowPenumbra
        val major = .5f*length.getDistance() + blur
        val minor = u*.11f + blur
        withTransform({
            translate(center.x, center.y)
            rotate(atan2(length.y,length.x)*57.29578f, Offset.Zero)
            scale(major,minor,Offset.Zero)
        }) { drawCircle(ShadowFeather, 1f, Offset.Zero) }
        for (i in bar.anchors.indices) {
            val a = camera.project(bar.anchors[i])
            val b = camera.project(bar.lower[i])
            drawLine(BlackNickel, a,b, u*.041f, StrokeCap.Round)
            drawLine(Silver, a-Offset(u*.009f,0f), b-Offset(u*.009f,0f), u*.010f)
        }
        if (bar.bridge) {
            val a = camera.project(bar.lower[0])
            val b = camera.project(bar.lower[1])
            drawLine(BlackNickel,a,b,u*.030f,StrokeCap.Round)
            drawLine(Steel, a-Offset(0f,.008f*u), b-Offset(0f,.008f*u), u*.012f)
        }
        if (bar.wallBracket) {
            val s = camera.project(bar.socket)
            val elbow = camera.project(bar.knee)
            val join = camera.project(bar.join)
            drawCircle(Steel, u*.040f, s)
            drawCircle(PinLight.copy(alpha=.52f), u*.015f, s-Offset(.009f*u,.009f*u))
            drawLine(Steel, s,elbow, u*.023f, StrokeCap.Round)
            drawLine(BlackNickel, elbow,join, u*.037f, StrokeCap.Round)
            drawLine(Steel, elbow-Offset(0f,u*.009f), join-Offset(0f,u*.009f), u*.012f)
        }
    }

    private fun DrawScope.rail(rail: Rail, camera: Camera) {
        if (rail.stations.isEmpty()) return
        val u = camera.unit(rail.stations[rail.stations.size/2])
        for (side in 0..1) {
            val points = if (side==0) rail.left else rail.right
            outline.rewind()
            var started = false
            for (point in points) {
                val p = camera.project(point)
                if (p.y < -u*2 || p.y > size.height+u*2) {
                    started = false
                } else {
                    if (started) outline.lineTo(p.x,p.y) else outline.moveTo(p.x,p.y)
                    started = true
                }
            }
            drawPath(outline, BlackNickel, style=Stroke(u*(2*WireRadius)))
            drawPath(outline, Silver.copy(alpha=.64f), style=Stroke(u*.013f))
        }
        for (i in 3 until rail.stations.lastIndex step 8) {
            val p = camera.project(rail.left[i])
            if (p.y in 0f..size.height) drawLine(Steel,p,camera.project(rail.right[i]),u*.023f)
        }
    }

    private fun detailLoop(points: Array<StagePoint>, camera: Camera) {
        detail.rewind()
        for (i in points.indices) {
            val p = camera.project(points[i])
            if (i == 0) detail.moveTo(p.x,p.y) else detail.lineTo(p.x,p.y)
        }
        detail.close()
    }

    private fun DrawScope.bar(
        bar: Bar, camera: Camera, age: Long, light: Float, incoming: Float,
        result: PanelFeedback?, timeMs: Long, marble: StagePoint,
    ) {
        val unit = camera.unit(bar.key.center)
        // The fork flexes at its far tips. Its solid heel, the actual contact
        // point and the mounting hardware remain fixed on the rail plane.
        val flex = if (bar.key.shape == PanelShape.Fork && age in 1..360) {
            val remaining = 1f-age/360f
            (1f-cos(age*.056f))*.016f*remaining*remaining
        } else 0f
        val tipMotion = if (flex > 0f) bar.depthAxis*flex else null
        for (n in projectedX.indices) {
            val vertex = bar.mesh[n]
            val p = if (tipMotion == null) vertex else when (n%Corners) {
                in 0..3 -> vertex + tipMotion
                in 8..11 -> vertex - tipMotion
                else -> vertex
            }
            val point = camera.project(p)
            projectedX[n] = point.x
            projectedY[n] = point.y
        }
        // A backing bracket or shadow can be visible even after its cap has left
        // the viewport. The four corner rings bound every face and its feedback stroke.
        val margin = unit * .20f + 2f
        if (projectedX.all { it < -margin } || projectedX.all { it > size.width + margin } ||
            projectedY.all { it < -margin } || projectedY.all { it > size.height + margin }) return
        val step = (light * LightSteps).roundToInt().coerceIn(0, LightSteps)
        val finish = bar.finish
        val shadeBase = step*(ShadeSteps+1)
        var rodCount = 0
        for (level in Levels-2 downTo 0) {
            val a = level*Corners
            val b = a+Corners
            for (i in 0 until Corners) {
                val j = (i+1)%Corners
                val px = projectedX[a+i]
                val py = projectedY[a+i]
                val qx = projectedX[a+j]
                val qy = projectedY[a+j]
                val ux = projectedX[b+i]
                val uy = projectedY[b+i]
                if ((qx-px)*(uy-py) - (qy-py)*(ux-px) >= 0f) continue
                val shade = (shadeBase + bar.shading[i]) * 3 + level
                if (sides != null) {
                    sides.add(projectedX, projectedY, a+i, a+j, b+j, b+i, finish.sideArgb[shade])
                } else {
                    // Hardware Canvas on Android 7–9 supports antialiased paths.
                    outline.rewind()
                    outline.moveTo(px, py)
                    outline.lineTo(qx, qy)
                    outline.lineTo(projectedX[b+j], projectedY[b+j])
                    outline.lineTo(ux, uy)
                    outline.close()
                    drawPath(outline, finish.sides[shade])
                }
                if (bar.key.shape == PanelShape.Drum && level == 1 && i % 2 == 0) rods[rodCount++] = i
            }
        }
        sides?.draw(this)
        for (n in 0 until rodCount) {
            val a = Corners + rods[n]
            val b = Corners + (rods[n]+1) % Corners
            val top = Offset((projectedX[a]+projectedX[b])*.5f, (projectedY[a]+projectedY[b])*.5f)
            val bottom = Offset((projectedX[a+Corners]+projectedX[b+Corners])*.5f,
                (projectedY[a+Corners]+projectedY[b+Corners])*.5f)
            drawLine(Silver, top, bottom, unit*.012f, StrokeCap.Round, alpha=.78f)
        }
        // A steeply banked key can rise above the camera. Hide its rivets when
        // the underside is visible instead of painting the top over the sides.
        val front = (projectedX[10]-projectedX[0])*(projectedY[14]-projectedY[0]) -
            (projectedY[10]-projectedY[0])*(projectedX[14]-projectedX[0]) > 0f
        val start = if (front) 0 else (Levels-1)*Corners
        outline.rewind()
        outline.moveTo(projectedX[start], projectedY[start])
        for (i in 1 until Corners) outline.lineTo(projectedX[start+i], projectedY[start+i])
        outline.close()
        if (!front) {
            drawPath(outline, finish.faces[step])
            result?.let { mark(it, timeMs, unit) }
            return
        }
        drawPath(outline, finish.tops[step])
        room?.let { drawPath(outline, it.sheen) }
        if (bar.skin.isNotEmpty()) {
            detailLoop(bar.skin, camera)
            drawPath(detail, meshes.materials.membrane[step])
            if (age in 1..260) {
                val progress = age/260f
                val center = camera.project(bar.key.center)
                scale(.10f+.88f*progress, center) {
                    drawPath(detail, PinLight, alpha=.78f*(1f-progress), style=Stroke(unit*.023f))
                }
            }
        }
        if (bar.woodGrain.isNotEmpty()) {
            bar.woodGrain.forEachIndexed { row, fiber ->
                detail.rewind()
                fiber.forEachIndexed { i, point ->
                    val p = camera.project(point)
                    if (i == 0) detail.moveTo(p.x, p.y) else detail.lineTo(p.x, p.y)
                }
                drawPath(detail, if (row % 3 == 0) PinLight else PinShade,
                    alpha = if (row % 3 == 0) .20f else .17f,
                    style = Stroke(unit * .006f, cap = StrokeCap.Round))
            }
        }

        val normal = bar.key.normal
        val relative = marble-bar.key.center
        val height = relative.x*normal.x + relative.y*normal.y + relative.z*normal.z
        if (height in MarbleRadius-.012f..MarbleRadius+.48f &&
            relative.x*relative.x+relative.y*relative.y+relative.z*relative.z < 1.1f) {
            val foot = camera.project(marble - normal*height)
            val gap = max(0f, height-MarbleRadius)
            val spot = unit*(MarbleRadius*.73f + gap*.26f)
            // The contact darkening only falls on the upper surface that receives it.
            clipPath(outline) {
                drawOval(WallShadow,foot-Offset(spot,spot*.32f),Size(spot*2,spot*.64f),
                    alpha=.38f*(1-gap/.48f))
            }
        }
        drawPath(outline, accent, alpha = .28f + .64f * incoming, style = Stroke(unit * (.012f + .016f * incoming)))
        result?.let { mark(it, timeMs, unit) }
        val radius = unit*.032f
        for (pin in bar.pins) {
            val p = camera.project(pin)
            drawOval(PinShade, p-Offset(radius,.16f*radius), Size(2.1f*radius,1.2f*radius))
            drawOval(meshes.materials.trim, p-Offset(radius,.68f*radius), Size(2f*radius,1.23f*radius))
            drawOval(PinLight, p-Offset(radius*.57f,radius*.71f), Size(radius*.93f,radius*.43f))
        }
    }

    private fun DrawScope.mark(result: PanelFeedback, timeMs: Long, unit: Float) {
        val intensity = feedbackStrength(timeMs - result.shownAtMs)
        if (intensity <= 0f) return
        val color = if (result.hit) HitColor else MissColor
        drawPath(outline, color, alpha = .38f * intensity)
        drawPath(outline, color, alpha = intensity, style = Stroke(unit * .041f))
    }

    private fun DrawScope.marble(camera: Camera, frame: MarbleFrame) {
        drawStudioMarble(marbleAtlas, camera.project(frame.marble), camera.unit(frame.marble) * MarbleRadius, frame.rollAngle)
    }

    fun DrawScope.draw(timeMs: Long, feedback: List<PanelFeedback>, showWindow: Boolean = false) {
        if (size.minDimension<1f) return
        if (roomSize!=size) {
            roomSize=size
            room=Room(size, theme)
        }
        val settings = room ?: return
        drawImage(settings.background, dstSize=IntSize(size.width.roundToInt(),size.height.roundToInt()))
        val frame = path.frame(timeMs)
        val camera = Camera(size,frame.target)
        val cue = if (showWindow) landingCueIndex(timeMs, frame.anchor, path.keys.size, feedback) {
            path.keys[it].atMs
        } else -1
        val start = max(0,frame.anchor-VisibleLimit/2)
        val end = min(path.keys.lastIndex,frame.anchor+VisibleLimit/2)
        var count = 0
        for (i in start..end) {
            val key = path.keys[i]
            val p = camera.project(key.center)
            val pad = camera.unit(key.center)*1.6f
            if (p.x < -pad || p.x > size.width+pad || p.y < -pad || p.y > size.height+pad) continue
            val depth = camera.depth(key.center)
            var n=count
            while (n>0 && depths[n-1]>depth) {
                depths[n]=depths[n-1]
                sorted[n]=sorted[n-1]
                n--
            }
            depths[n]=depth
            sorted[n]=i
            count++
        }
        for (n in 0 until count) {
            val bar = meshes.barAt(sorted[n])
            barShadow(bar,camera)
        }
        val wallContact = camera.project(shadow(frame.marble))
        val wallR = camera.unit(frame.marble)*MarbleRadius*1.58f
        withTransform({
            translate(wallContact.x, wallContact.y)
            scale(wallR, wallR*.88f, Offset.Zero)
        }) { drawCircle(ShadowFeather,1f,Offset.Zero) }
        for (i in start..min(path.spans.lastIndex, end + 1)) {
            val run = path.spans[i]
            if (run.railEnd <= 0f) continue
            val guide = meshes.railAt(i)
            rail(guide, camera)
        }
        val marbleDepth=camera.depth(frame.marble)
        var marbleDrawn=false
        for (n in 0 until count) {
            val i=sorted[n]
            val bar = meshes.barAt(i)
            if (!marbleDrawn && depths[n]>marbleDepth+.13f && i!=frame.anchor) {
                marble(camera,frame)
                marbleDrawn=true
            }
            val age=timeMs-bar.key.atMs
            val light=if (age in 0..270) {
                val phase=1f-age/270f
                phase*phase
            } else 0f
            val incoming = if (i == frame.next && age in -540..-1) 1f + age / 540f else 0f
            val result = feedback.lastOrNull { it.effectsVisible && it.noteAtMs == bar.key.atMs }
            // The wall mounts are fixed. Only the struck plate travels on its springs.
            val travel = panelCompression(age, path.keys.getOrNull(i + 1)?.let { it.atMs - bar.key.atMs })
            val face = camera.project(bar.key.center)
            val offset = if (travel == 0f) Offset.Zero else camera.project(bar.key.center - bar.key.normal * travel) - face
            if (offset != Offset.Zero) drawContext.transform.translate(offset.x, offset.y)
            bar(bar, camera, age, light, incoming, result, timeMs, frame.marble)
            if (i == cue) drawLandingCue(face, camera.unit(bar.key.center) * MarbleRadius, landingPhase(timeMs, bar.key.atMs))
            if (showWindow && result != null) {
                val progress = sparkPhase(timeMs, result)
                if (progress >= 0f) drawLandingSparks(face, camera.unit(bar.key.center) * MarbleRadius, progress)
            }
            if (offset != Offset.Zero) drawContext.transform.translate(-offset.x, -offset.y)
        }
        if (!marbleDrawn) marble(camera,frame)
    }
}

@Composable
fun MarbleStage(chart: Chart, positionMs: () -> Long, modifier: Modifier = Modifier) {
    val meshes = remember(chart) { OrbitMeshes(MarblePath(chart)) }
    MarbleStage(meshes, positionMs, modifier, emptyList(), StageTheme.Stereo, imageResource(Res.drawable.studio_marble)) {}
}

@Composable
internal fun MarbleStage(meshes: OrbitMeshes, positionMs: () -> Long, modifier: Modifier,
    feedback: List<PanelFeedback>, theme: StageTheme, atlas: ImageBitmap, showWindow: Boolean = false, onPresented: () -> Unit,
) {
    val scene = remember(meshes, atlas, theme) { MarbleScene(meshes, atlas, theme) }
    val presented = remember(scene) { CompletableDeferred<Unit>() }
    val callback = rememberUpdatedState(onPresented)
    LaunchedEffect(presented) {
        presented.await()
        withFrameNanos { }
        callback.value()
    }
    Canvas(modifier.clipToBounds()) {
        if (size.minDimension >= 1f) {
            with(scene) { draw(positionMs(), feedback, showWindow) }
            if (!presented.isCompleted) presented.complete(Unit)
        }
    }
}

@Composable
fun MarbleStage(chart: Chart, positionMs: Long, modifier: Modifier = Modifier) {
    MarbleStage(chart, { positionMs }, modifier)
}
