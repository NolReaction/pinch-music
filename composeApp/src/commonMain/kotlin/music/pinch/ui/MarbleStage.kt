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
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
private const val AtlasColumns = 6
private const val AtlasRows = 4

private val BlackNickel = Color(0xff222332)
private val Steel = Color(0xff68697b)
private val Silver = Color(0xffc3c6d1)
private val PinLight = Color(0xfff6eadb)
private val PinShade = Color(0xff242231)
private val WallShadow = Color(0xff171b2c)
private val ShadowFeather = Brush.radialGradient(listOf(
    WallShadow.copy(alpha=.39f), WallShadow.copy(alpha=.23f), WallShadow.copy(alpha=.06f),
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
private class Finish(val tint: Color) {
    val tops = Array(LightSteps+1) { i -> lerp(Color(0xff242535), tint, i / LightSteps.toFloat()) }
    val bevels = Array(LightSteps+1) { i -> lerp(Color(0xff696979), lerp(tint, PinLight, .33f), i / LightSteps.toFloat()) }
    val faces = Array(LightSteps+1) { i ->
        lerp(Color(0xff141622), lerp(tint, Color.Black, .42f), i / LightSteps.toFloat())
    }
    // Indexed by [light][edge normal][bevel/body/bottom]. Building these tables once
    // also avoids repeated colour-space conversions in the polygon draw loop.
    val sides = Array((LightSteps+1)*(ShadeSteps+1)*3) { i ->
        val light = i / (3*(ShadeSteps+1))
        val shade = (i / 3 % (ShadeSteps+1)) / ShadeSteps.toFloat()
        when (i%3) {
            0 -> lerp(faces[light], bevels[light], .32f+.64f*shade)
            1 -> lerp(Color(0xff080c14), faces[light], shade)
            else -> lerp(BlackNickel, faces[light], .52f*shade)
        }
    }
    val emission = Brush.radialGradient(listOf(tint.copy(alpha=.36f), tint.copy(alpha=.16f), tint.copy(alpha=0f)), Offset.Zero, 1f)
}

private val Gold = Finish(Color(0xffffc139))
private val Accents = arrayOf(
    Finish(Color(0xfffa738a)), Finish(Color(0xffff8b5c)), Finish(Color(0xffbba6fa)),
    Finish(Color(0xff78c7e7)), Finish(Color(0xffe58bb6))
)

private class Room(size: Size) {
    // The wall has no fine detail. Bake its stationary light, fill and vignette
    // at a bounded resolution; the live camera still projects all moving shadows.
    private val downsample = min(1f, 420f / size.maxDimension)
    private val width = max(1, (size.width * downsample).roundToInt())
    private val height = max(1, (size.height * downsample).roundToInt())
    val background = ImageBitmap(width, height).also { image ->
        val viewport = Size(width.toFloat(), height.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, BitmapCanvas(image), viewport) {
            drawRect(Brush.linearGradient(
                listOf(Color(0xff252c42), Color(0xff454a60), Color(0xff686071), Color(0xff414656)),
                Offset(viewport.width*.05f, 0f), Offset(viewport.width*.92f, viewport.height)
            ))
            drawRect(Brush.radialGradient(
                listOf(Color(0x38627b9d), Color(0x18576d90), Color(0x00576d90)),
                Offset(viewport.width*.09f, viewport.height*.24f), viewport.maxDimension*.68f
            ))
            drawRect(Brush.radialGradient(
                listOf(Color.Transparent, Color(0x00202637), Color(0x4b11182a)),
                Offset(viewport.width*.48f, viewport.height*.47f), viewport.maxDimension*.66f
            ))
        }
        image.prepareToDraw()
    }
    val sheen = Brush.linearGradient(
        listOf(Color(0x237d899b), Color(0x035a6277), Color(0x137a6e80), Color.Transparent),
        Offset(0f, size.height*.26f), Offset(size.width, size.height*.49f)
    )
}

private fun shadow(p: StagePoint): StagePoint {
    val distance = p.z - WallZ
    return StagePoint(p.x + distance*.17f, p.y + distance*.20f, WallZ)
}

/** Four rounded contours form a continuous top chamfer, piano-black body and underside. */
private class Bar(val key: StageKey, index: Int) {
    val finish = if (key.playable) Gold else Accents[(index / 12 + key.midi / 5) % Accents.size]
    private val length = key.halfLength
    private val width = key.halfWidth
    val thickness = min(.18f, width*1.45f)
    private val chamfer = min(.045f, width*.30f)
    private val ringHeight = floatArrayOf(0f, chamfer*.68f, thickness-chamfer*.7f, thickness)
    private val ringInset = floatArrayOf(chamfer, 0f, 0f, chamfer*.65f)
    private val sinCorner = FloatArray(Corners) { sin(cornerAngle(it)) }
    private val cosCorner = FloatArray(Corners) { cos(cornerAngle(it)) }
    private fun cornerAngle(i: Int) = (i/4 - 1 + (i%4)/3f) * (PI/2).toFloat()
    val mesh = Array(Levels*Corners) { n ->
        val level = n/Corners
        val i = n%Corners
        val halfX = length - ringInset[level]
        val halfZ = width - ringInset[level]
        val radius = min(halfZ*.40f, .075f)
        val sx = if (i/4 == 0 || i/4 == 1) 1 else -1
        val sz = if (i/4 == 1 || i/4 == 2) 1 else -1
        key.point(sx*(halfX-radius) + radius*cosCorner[i], ringHeight[level],
            sz*(halfZ-radius) + radius*sinCorner[i])
    }
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
    val pins = arrayOf(key.point(-length*.61f, -.010f, 0f), key.point(length*.61f, -.010f, 0f))
    val mountSpacing = length*.43f
    val stemLength = .09f + (index%4)*.025f
    val anchors = arrayOf(key.point(-mountSpacing, thickness-.01f, 0f), key.point(mountSpacing, thickness-.01f, 0f))
    val lower = Array(2) { n -> anchors[n] + StagePoint(0f,stemLength,-.055f) }
    val socket = shadow(key.point(0f,thickness-.02f,0f))
    val join = key.point(0f,thickness+stemLength,-.055f)
    val knee = StagePoint(join.x, join.y+.035f, max(WallZ+.055f,join.z-.28f))
    val incomingA = key.point(-length*.76f, chamfer*.7f, width)
    val incomingB = key.point(length*.76f, chamfer*.7f, width)
    // Wall braces belong to the rear lane. A brace across the front lane
    // would cross the path of the descending ball on the opposite turn.
    val wallBracket = index%5 == 1 && key.center.z <= .80f
    val bridge = index%3 != 1 || wallBracket
}

private class Rail(run: CourseRun) {
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

internal class MarbleScene(chart: Chart, private val marbleAtlas: ImageBitmap) {
    private val path = MarblePath(chart)
    private val bars = Array(path.keys.size) { i -> Bar(path.keys[i], i) }
    private val rails = path.spans.map(::Rail)
    private val outline = Path()
    private val projectedX = FloatArray(Levels*Corners)
    private val projectedY = FloatArray(Levels*Corners)
    private val sorted = IntArray(VisibleLimit)
    private val depths = FloatArray(VisibleLimit)
    private var roomSize = Size.Zero
    private var room: Room? = null

    private fun DrawScope.halo(p: Offset, radius: Float, brush: Brush, strength: Float) {
        if (radius<1f || strength<=.01f) return
        translate(p.x, p.y) {
            scale(radius, radius*.82f, Offset.Zero) { drawCircle(brush, 1f, Offset.Zero, alpha=strength) }
        }
    }

    private fun DrawScope.barShadow(bar: Bar, camera: Camera) {
        val l = camera.project(bar.shadowLeft)
        val r = camera.project(bar.shadowRight)
        val length = r-l
        val center = (l+r)*.5f
        val u = camera.unit(bar.join)
        val blur = u*bar.shadowPenumbra
        val major = .5f*length.getDistance() + blur
        val minor = u*.11f + blur
        translate(center.x, center.y) {
            rotate(atan2(length.y,length.x)*57.29578f, Offset.Zero) {
                scale(major,minor,Offset.Zero) { drawCircle(ShadowFeather, 1f, Offset.Zero) }
            }
        }
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
            outline.reset()
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

    private fun DrawScope.bar(bar: Bar, camera: Camera, light: Float, incoming: Float, marble: StagePoint) {
        val unit = camera.unit(bar.key.center)
        for (n in projectedX.indices) {
            val point = camera.project(bar.mesh[n])
            projectedX[n] = point.x
            projectedY[n] = point.y
        }
        val step = (light * LightSteps).roundToInt().coerceIn(0, LightSteps)
        val finish = bar.finish
        val shadeBase = step*(ShadeSteps+1)
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
                outline.reset()
                outline.moveTo(px, py)
                outline.lineTo(qx, qy)
                outline.lineTo(projectedX[b+j], projectedY[b+j])
                outline.lineTo(ux, uy)
                outline.close()
                drawPath(outline, finish.sides[(shadeBase + bar.shading[i])*3 + level])
            }
        }
        // A steeply banked key can rise above the camera. Hide its rivets when
        // the underside is visible instead of painting the top over the sides.
        val front = (projectedX[4]-projectedX[0])*(projectedY[12]-projectedY[0]) -
            (projectedY[4]-projectedY[0])*(projectedX[12]-projectedX[0]) > 0f
        val start = if (front) 0 else (Levels-1)*Corners
        outline.reset()
        outline.moveTo(projectedX[start], projectedY[start])
        for (i in 1 until Corners) outline.lineTo(projectedX[start+i], projectedY[start+i])
        outline.close()
        if (!front) {
            drawPath(outline, finish.faces[step])
            return
        }
        drawPath(outline, finish.tops[step])
        room?.let { drawPath(outline, it.sheen) }

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
                    alpha=.48f*(1-gap/.48f))
            }
        }
        // A quiet brass edge identifies the next manual accent among the automatic keys.
        if (incoming>.01f) drawLine(Gold.tint.copy(alpha=.45f+.5f*incoming),
            camera.project(bar.incomingA), camera.project(bar.incomingB), unit*(.012f+.014f*incoming), StrokeCap.Round)
        val radius = unit*.032f
        for (pin in bar.pins) {
            val p = camera.project(pin)
            drawOval(PinShade, p-Offset(radius,.16f*radius), Size(2.1f*radius,1.2f*radius))
            drawOval(Steel, p-Offset(radius,.68f*radius), Size(2f*radius,1.23f*radius))
            drawOval(PinLight, p-Offset(radius*.57f,radius*.71f), Size(radius*.93f,radius*.43f))
        }
    }

    private fun DrawScope.marble(camera: Camera, frame: MarbleFrame) {
        val p = camera.project(frame.marble)
        val unit = camera.unit(frame.marble)
        val radius = unit*MarbleRadius
        val turns = frame.rollAngle/(2*PI.toFloat())
        val tile = floor((turns-floor(turns))*(AtlasRows*AtlasColumns)).toInt().coerceIn(0,AtlasRows*AtlasColumns-1)
        val w = marbleAtlas.width/AtlasColumns
        val h = marbleAtlas.height/AtlasRows
        val diameter = max(1,(2f*radius).roundToInt())
        drawImage(marbleAtlas,srcOffset=IntOffset((tile%AtlasColumns)*w,(tile/AtlasColumns)*h),
            srcSize=IntSize(w,h),dstOffset=IntOffset((p.x-radius).roundToInt(),(p.y-radius).roundToInt()),
            dstSize=IntSize(diameter,diameter))
    }

    fun DrawScope.draw(timeMs: Long) {
        if (size.minDimension<1f) return
        if (roomSize!=size) {
            roomSize=size
            room=Room(size)
        }
        val settings = room ?: return
        drawImage(settings.background, dstSize=IntSize(size.width.roundToInt(),size.height.roundToInt()))
        val frame = path.frame(timeMs)
        val camera = Camera(size,frame.target)
        val start = max(0,frame.anchor-VisibleLimit/2)
        val end = min(bars.lastIndex,frame.anchor+VisibleLimit/2)
        var count = 0
        var nextManual = -1
        for (i in start..end) {
            val bar=bars[i]
            val p=camera.project(bar.key.center)
            val pad=camera.unit(bar.key.center)*1.6f
            if (p.x < -pad || p.x > size.width+pad || p.y < -pad || p.y > size.height+pad) continue
            if (nextManual<0 && i>frame.anchor && bar.key.playable) nextManual=i
            val depth=camera.depth(bar.key.center)
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
            val bar=bars[sorted[n]]
            barShadow(bar,camera)
            val age=timeMs-bar.key.atMs
            if (age in 0..290) {
                val t=1f-age/290f
                halo(camera.project(shadow(bar.key.center)), camera.unit(bar.key.center)*1.74f,
                    bar.finish.emission,t*t)
            }
        }
        val wallContact = camera.project(shadow(frame.marble))
        val wallR = camera.unit(frame.marble)*MarbleRadius*1.58f
        translate(wallContact.x,wallContact.y) {
            scale(wallR,wallR*.88f,Offset.Zero) { drawCircle(ShadowFeather,1f,Offset.Zero) }
        }
        for (i in max(0,start)..min(rails.lastIndex,end+1)) rail(rails[i],camera)
        val marbleDepth=camera.depth(frame.marble)
        var marbleDrawn=false
        for (n in 0 until count) {
            val i=sorted[n]
            val bar=bars[i]
            if (!marbleDrawn && depths[n]>marbleDepth+.13f && i!=frame.anchor) {
                marble(camera,frame)
                marbleDrawn=true
            }
            val age=timeMs-bar.key.atMs
            val light=if (age in 0..270) {
                val phase=1f-age/270f
                phase*phase
            } else 0f
            val incoming=if (i==nextManual && age in -650..-1) (1f+age/650f) else 0f
            bar(bar,camera,light,incoming,frame.marble)
        }
        if (!marbleDrawn) marble(camera,frame)
    }
}

@Composable
fun MarbleStage(chart: Chart, positionMs: () -> Long, modifier: Modifier = Modifier) {
    val atlas = imageResource(Res.drawable.studio_marble)
    val scene = remember(chart, atlas) { MarbleScene(chart,atlas) }
    Canvas(modifier.clipToBounds()) { with(scene) { draw(positionMs()) } }
}

@Composable
fun MarbleStage(chart: Chart, positionMs: Long, modifier: Modifier = Modifier) {
    MarbleStage(chart,{ positionMs },modifier)
}
