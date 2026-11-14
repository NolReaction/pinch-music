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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import music.pinch.core.Chart
import music.pinch.resources.Res
import music.pinch.resources.studio_marble
import org.jetbrains.compose.resources.imageResource

private val WallLight = Color(0xffc7c6e0)
private val WallMid = Color(0xffa5a9ca)
private val WallBase = Color(0xff747c9e)
private val BlueSteel = Color(0xff65718d)
private val MetalEdge = Color(0xffd6d9e7)
private val KeySlate = Color(0xff444762)
private val KeyInk = Color(0xff242a45)
private val Lavender = Color(0xffab8ae9)
private val Plasma = Color(0xfff6bcff)
private const val WallZ = -1.55f

/** Perspective camera tilted down at the front of the hanging instruments. */
private class StageLens(size: Size, target: StagePoint) {
    private val origin = Offset(size.width * .5f, size.height * .54f)
    private val aim = StagePoint(target.x * .22f, target.y - .37f, .28f)
    private val focal = min(size.width / 6.1f, size.height / 7.4f)
    private fun depth(p: StagePoint) = .222f * (p.x-aim.x) - .390f * (p.y-aim.y) + .894f * (p.z-aim.z)
    fun scale(p: StagePoint) = focal * 18f / max(4f, 18f - depth(p))
    fun project(p: StagePoint): Offset {
        val x = p.x-aim.x
        val y = p.y-aim.y
        val z = p.z-aim.z
        val scale = scale(p)
        return origin + Offset(.9705f*x - .241f*z, .094f*x + .9208f*y + .379f*z)*scale
    }
    fun depthOf(p: StagePoint) = depth(p)
}

private val tongue = List(28) { i ->
    val a = (i * 2.0 * PI / 28).toFloat()
    val x = cos(a)
    val z = sin(a)
    // A gently tapered percussion tongue with rounded shoulders.
    val u = abs(x).pow(.68f) * (if (x < 0) -1f else 1f)
    val v = abs(z).pow(.70f) * (if (z < 0) -1f else 1f)
    Offset(u * (1.00f - .13f * v), v * .53f)
}

private class KeyMesh(val key: StageKey) {
    val top = tongue.map { key.point(it.x, 0f, it.y) }
    val base = top.map { it + StagePoint(0f, .12f, 0f) }
    val shoulder = top.map { it + StagePoint(0f, .044f, 0f) }
    val face = tongue.map { key.point(it.x * .949f, -.004f, it.y * .89f) }
    val shadow = top.map { shadowOnWall(it) }
    val rods = listOf(-.67f,.67f).map { x ->
        val p = key.point(x, .095f, -.18f)
        listOf(p, p + StagePoint(0f, .25f, -.12f), StagePoint(p.x, p.y + .49f, WallZ + .062f))
    }
    val rivets = listOf(-.67f,.67f).map { key.point(it, -.008f, -.24f) }
    val slotStart = key.point(-.44f,-.008f,.27f)
    val slotEnd = key.point(.44f,-.008f,.27f)
}

private fun shadowOnWall(p: StagePoint): StagePoint {
    val d = p.z - WallZ
    return StagePoint(p.x + .24f * d, p.y + .25f * d, WallZ)
}

private fun outline(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    close()
}

private fun DrawScope.glow(center: Offset, r: Float, colour: Color) {
    drawCircle(Brush.radialGradient(listOf(colour,colour.copy(alpha=0f)),center,r),r,center)
}

private fun DrawScope.drawSupports(mesh: KeyMesh, lens: StageLens) {
    val unit = lens.scale(mesh.key.center)
    for (rod in mesh.rods) {
        val socket = lens.project(rod.last())
        val radius = .093f * unit
        val gleam = socket - Offset(radius * .38f, radius * .42f)
        glow(socket + Offset(radius*.35f,radius*.5f),radius*2f,Color(0x65454366))
        drawCircle(Brush.radialGradient(listOf(Color(0xfff5f1ff),BlueSteel,KeyInk),gleam,radius*1.29f),radius,socket)
        val joints = rod.map(lens::project)
        for (i in 1 until joints.size) {
            val a = joints[i-1]
            val b = joints[i]
            drawLine(KeyInk.copy(alpha=.78f),a,b,unit*.062f,StrokeCap.Round)
            drawLine(BlueSteel,a,b,unit*.042f,StrokeCap.Round)
            val light = Offset(-unit*.010f,-unit*.009f)
            drawLine(MetalEdge,a+light,b+light,unit*.014f,StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawKey(mesh: KeyMesh, lens: StageLens, incoming: Float, strike: Float) {
    val center = lens.project(mesh.key.center)
    val unit = lens.scale(mesh.key.center)
    val top = mesh.top.map(lens::project)
    val base = mesh.base.map(lens::project)
    val shoulder = mesh.shoulder.map(lens::project)
    val contour = outline(top)
    val tint = if (mesh.key.pitch % 5 == 0) Color(0xff88c4e3) else Lavender
    val energy = max(strike, incoming*.55f)
    if (energy > .015f) glow(center,1.37f*unit,tint.copy(alpha=.28f*energy))
    // The tongue is a convex extrusion. Fill its single front-facing silhouette;
    // the top covers the upper half, and the gradient models the changing side normal.
    val right = top.indices.maxBy { top[it].x }
    val left = top.indices.minBy { top[it].x }
    if (right != left) {
        val indices = (0..((left-right+top.size)%top.size)).map { (right+it)%top.size }
        val side = Path().apply {
            moveTo(top[indices.first()].x,top[indices.first()].y)
            for (i in indices.drop(1)) lineTo(top[i].x,top[i].y)
            for (i in indices.asReversed()) lineTo(base[i].x,base[i].y)
            close()
        }
        drawPath(side,Brush.linearGradient(
            listOf(lerp(BlueSteel,tint,energy*.48f),lerp(KeyInk,tint,energy*.56f),KeyInk),
            center-Offset(unit,0f),center+Offset(unit,0f)))
    }
    drawPath(outline(shoulder),lerp(BlueSteel,tint,energy).copy(alpha=.7f),style=Stroke(unit*.014f))
    val c1 = lerp(KeySlate,tint,energy*.79f)
    val c2 = lerp(Color(0xff30364f),Plasma,strike*.85f + incoming*.10f)
    drawPath(contour,Brush.linearGradient(listOf(c1,c2),center+Offset(-unit,-unit*.3f),center+Offset(unit,unit*.4f)))
    drawPath(contour,lerp(Color(0xff9198b5),Plasma,energy),style=Stroke(unit*(.017f+.013f*energy)))
    drawPath(outline(mesh.face.map(lens::project)),MetalEdge.copy(alpha=.14f),style=Stroke(unit*.011f))
    val a = lens.project(mesh.slotStart)
    val b = lens.project(mesh.slotEnd)
    drawLine(lerp(KeyInk,tint,incoming*.7f),a,b,unit*.026f,StrokeCap.Round)
    for (r in mesh.rivets) {
        val p = lens.project(r)
        drawOval(KeyInk,p-Offset(.045f,.021f)*unit,Size(unit*.09f,unit*.042f))
        drawLine(MetalEdge.copy(alpha=.77f),p-Offset(unit*.026f,0f),p+Offset(unit*.011f,-unit*.009f),unit*.012f)
    }
    if (strike > .01f) {
        val radius = (1f-strike)*.70f + .14f
        val ring = List(30) { n ->
            val a = n*2.0*PI/30
            lens.project(mesh.key.point(cos(a).toFloat()*radius,-.015f,sin(a).toFloat()*radius*.69f))
        }
        drawPath(outline(ring),Color(0xffffddfc).copy(alpha=.80f*strike),style=Stroke(unit*.025f))
    }
}

@Composable
fun MarbleStage(chart: Chart, positionMs: Long, modifier: Modifier = Modifier) {
    val path = remember(chart) { MarblePath(chart) }
    val meshes = remember(path) { path.keys.map(::KeyMesh) }
    val marble = imageResource(Res.drawable.studio_marble)
    Canvas(modifier.clipToBounds()) {
        if (size.minDimension <= 1f) return@Canvas
        val frame = path.frame(positionMs)
        val lens = StageLens(size,frame.target)
        drawRect(Brush.linearGradient(listOf(WallLight, WallMid, WallBase),Offset(size.width*.19f,0f),Offset(size.width*.82f,size.height*1.1f)))
        val pool = Offset(size.width*.23f,size.height*.48f)
        glow(pool,size.maxDimension*.60f,Color(0x35e2d6fa))
        val horizon = (frame.anchor-7).coerceAtLeast(0)..(frame.anchor+8).coerceAtMost(meshes.lastIndex)
        val visible = horizon.filter { abs(lens.project(meshes[it].key.center).y - size.height*.5f) < size.height*.65f + lens.scale(meshes[it].key.center) }
        for (i in visible) {
            val mesh = meshes[i]
            val unit = lens.scale(mesh.key.center)
            val shadow = outline(mesh.shadow.map(lens::project))
            drawPath(shadow,Color(0x18444970),style=Stroke(unit*.17f))
            drawPath(shadow,Color(0x20434769),style=Stroke(unit*.07f))
            drawPath(shadow,Color(0x26474a70))
        }
        val cast = lens.project(shadowOnWall(frame.marble))
        glow(cast,lens.scale(frame.marble)*.47f,Color(0x5b514265))

        // Sort the marble with the keys. Give its landing face precedence on contact.
        val sphereDepth = lens.depthOf(frame.marble)
        val marblePos = lens.project(frame.marble)
        val rr = lens.scale(frame.marble)*MarbleRadius
        fun drawMarble() {
            val d = max(1,(2*rr).roundToInt())
            drawImage(marble,dstOffset=IntOffset((marblePos.x-rr).roundToInt(),(marblePos.y-rr).roundToInt()),dstSize=IntSize(d,d))
        }
        var drewMarble = false
        val ordered = visible.sortedBy { lens.depthOf(meshes[it].key.center) }
        for (i in ordered) {
            val mesh = meshes[i]
            if (!drewMarble && lens.depthOf(mesh.key.center) > sphereDepth && i != frame.anchor && i != frame.next) {
                drawMarble()
                drewMarble = true
            }
            drawSupports(mesh,lens)
            val elapsed = positionMs-mesh.key.atMs
            val strike = if (elapsed in 0..360L) (1f-elapsed/360f).pow(1.8f) else 0f
            val incoming = if (frame.next==i) .25f+.75f*frame.progress else 0f
            drawKey(mesh,lens,incoming,strike)
            // Tight contact shadow follows the ball on the two neighboring instrument faces.
            if (i==frame.anchor || i==frame.next) {
                val dx=frame.marble.x-mesh.key.center.x
                val dz=frame.marble.z-mesh.key.center.z
                val affinity=(1f-dx*dx/.92f-dz*dz/.23f).coerceIn(0f,1f)
                if (affinity>.03f) {
                    val p=lens.project(StagePoint(frame.marble.x,mesh.key.center.y-.014f,frame.marble.z))
                    val r=rr*(.73f+frame.lift*.24f)
                    drawOval(KeyInk.copy(alpha=.26f*affinity/(1f+frame.lift)),Offset(p.x-r,p.y-r*.32f),Size(2*r,r*.64f))
                }
            }
        }
        if (!drewMarble) drawMarble()
    }
}
