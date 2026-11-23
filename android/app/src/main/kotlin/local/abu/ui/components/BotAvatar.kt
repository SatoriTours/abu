package local.abu.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import local.abu.data.BotAvatar as AvatarPreset

// Original Abu folded-cloth silhouette, drawn in the approved 140×140 SVG coordinate system.
private val Cloth = Path().apply {
    moveTo(43f, 34f)
    quadraticTo(29f, 16f, 33f, 9f)
    quadraticTo(53f, 11f, 64f, 28f)
    cubicTo(102f, 24f, 121f, 47f, 119f, 78f)
    quadraticTo(118f, 117f, 78f, 123f)
    cubicTo(40f, 132f, 15f, 113f, 14f, 80f)
    quadraticTo(12f, 46f, 43f, 34f)
    close()
}
private val Fold = Path().apply { moveTo(35f, 13f); quadraticTo(43f, 26f, 53f, 32f) }
private val Stitch = Path().apply {
    moveTo(96f, 103f); lineTo(98.1f, 101.1f)
    moveTo(101f, 97.7f); lineTo(102.7f, 95.5f)
    moveTo(105.5f, 90.6f); lineTo(106.6f, 88f)
}
private val Smile = Path().apply { moveTo(64f, 87f); quadraticTo(68f, 90f, 72f, 86f) }
private val Tag = Path().apply { moveTo(17f, 83f); lineTo(10f, 84f); lineTo(11f, 97f); lineTo(19f, 95f); close() }
private val Thread = Color(0xFF262231)
private data class FabricStyle(val outline: Path, val color: Color, val shade: Color) {
    val brush = Brush.radialGradient(0f to color, 0.60f to color, 1f to shade,
        center = Offset(45f,35f), radius = 126f)
}
private val Leaf = Path().apply {
    moveTo(39f,29f); quadraticTo(27f,13f,35f,9f); quadraticTo(55f,12f,62f,28f)
    lineTo(92f,29f); quadraticTo(115f,30f,117f,55f); lineTo(120f,96f)
    quadraticTo(119f,122f,94f,123f); lineTo(40f,122f); quadraticTo(16f,121f,16f,98f)
    lineTo(15f,58f); quadraticTo(16f,33f,39f,29f); close()
}
private val Flame = Path().apply {
    moveTo(67f,16f); quadraticTo(82f,8f,83f,18f); quadraticTo(82f,30f,93f,40f)
    quadraticTo(123f,64f,118f,91f); quadraticTo(113f,121f,77f,125f)
    quadraticTo(39f,130f,20f,106f); quadraticTo(4f,80f,25f,57f)
    quadraticTo(48f,36f,67f,16f); close()
}
private val Wave = Path().apply {
    moveTo(75f,15f); cubicTo(90f,3f,87f,19f,91f,30f)
    cubicTo(95f,50f,120f,60f,120f,89f); cubicTo(119f,119f,97f,126f,71f,126f)
    cubicTo(38f,128f,16f,115f,16f,88f); cubicTo(15f,58f,52f,42f,75f,15f); close()
}
private val Fluff = Path().apply {
    moveTo(43f,34f); quadraticTo(31f,14f,39f,11f); quadraticTo(57f,13f,65f,31f)
    cubicTo(101f,26f,121f,49f,123f,79f); quadraticTo(131f,110f,101f,117f)
    quadraticTo(87f,128f,69f,119f); quadraticTo(46f,130f,29f,116f)
    quadraticTo(4f,103f,14f,77f); quadraticTo(12f,47f,43f,34f); close()
}
private val Fabrics = mapOf(
    AvatarPreset.ABU to FabricStyle(Cloth, Color(0xFFB5A5FA), Color(0xFF8B79BE)),
    AvatarPreset.LEAF to FabricStyle(Leaf, Color(0xFF8EC9B3), Color(0xFF548F7A)),
    AvatarPreset.FLAME to FabricStyle(Flame, Color(0xFFF3BD83), Color(0xFFB78051)),
    AvatarPreset.WAVE to FabricStyle(Wave, Color(0xFF8DB5E9), Color(0xFF587CB2)),
    AvatarPreset.FLUFF to FabricStyle(Fluff, Color(0xFFD7A3BC), Color(0xFFA56F8E)),
)
internal val AvatarPreset.label get() = when (this) {
    AvatarPreset.ABU -> "雾紫布耳"
    AvatarPreset.LEAF -> "薄荷方布"
    AvatarPreset.FLAME -> "暖杏火苗"
    AvatarPreset.WAVE -> "晴蓝水滴"
    AvatarPreset.FLUFF -> "藕粉云朵"
}

@Composable
internal fun BotAvatar(
    size: Dp, modifier: Modifier = Modifier, avatar: AvatarPreset = AvatarPreset.ABU,
    animate: Boolean = false, working: Boolean = false, completed: String? = null,
) {
    val fabric = Fabrics.getValue(avatar)
    val shift = remember { Animatable(0f) }
    val eyes = remember { Animatable(1f) }
    val turn = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    if (animate) {
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
        var seenCompletion by remember { mutableStateOf(completed) }
        LaunchedEffect(foreground, working) {
            shift.snapTo(0f)
            turn.snapTo(0f)
            eyes.snapTo(1f)
            if (!foreground) return@LaunchedEffect
            while (ValueAnimator.areAnimatorsEnabled()) {
                if (working) {
                    turn.animateTo(-3f, tween(900))
                    turn.animateTo(3f, tween(900))
                    delay(100)
                } else {
                    shift.animateTo(-2.5f, tween(2100))
                    shift.animateTo(0f, tween(2100))
                    eyes.animateTo(0.14f, tween(75))
                    eyes.animateTo(1f, tween(110))
                    delay(1000)
                }
            }
            shift.snapTo(0f)
            turn.snapTo(0f)
        }
        LaunchedEffect(foreground, completed) {
            pop.snapTo(1f)
            if (completed == seenCompletion) return@LaunchedEffect
            seenCompletion = completed
            if (foreground && completed != null && ValueAnimator.areAnimatorsEnabled()) {
                pop.animateTo(1.12f, tween(150))
                pop.animateTo(1f, tween(210))
            }
        }
    }

    Canvas(modifier.size(size)) {
        if (this.size.minDimension <= 0f) return@Canvas
        val scale = this.size.minDimension / 140f
        withTransform({
            scale(scale, scale, Offset.Zero)
            translate(0f, shift.value)
            rotate(turn.value, Offset(70f, 90f))
            scale(pop.value, pop.value, Offset(70f, 118f))
        }) {
            withTransform({ translate(0f, 5f) }) { drawPath(fabric.outline, Thread.copy(alpha = 0.16f)) }
            drawPath(fabric.outline, fabric.brush)
            if (avatar != AvatarPreset.FLAME && avatar != AvatarPreset.WAVE) {
                drawPath(Fold, Color.White.copy(alpha = 0.36f), style = Stroke(2f, cap = StrokeCap.Round))
            }
            drawPath(Stitch, Color.White.copy(alpha = 0.49f), style = Stroke(2f, cap = StrokeCap.Round))
            drawPath(Tag, fabric.color)
            drawLine(Thread.copy(alpha = 0.50f), Offset(12f, 87f), Offset(16f, 87f), 1.5f)
            drawLine(Thread.copy(alpha = 0.50f), Offset(12f, 91f), Offset(16f, 91f), 1.5f)
            drawOval(Color.White.copy(alpha = 0.17f), Offset(38f, 81.5f), Size(10f, 5f))
            withTransform({
                translate(turn.value * 0.65f, 0f)
                scale(1f, eyes.value, Offset(67f, 73f))
            }) {
                for (x in listOf(48f, 76f)) {
                    drawRoundRect(Thread, Offset(x, 64f), Size(10f, 17f), CornerRadius(5f))
                    drawCircle(Color.White.copy(alpha = 0.8f), 1.5f, Offset(x + 3f, 68f))
                }
            }
            drawPath(Smile, Thread.copy(alpha = 0.6f), style = Stroke(2f, cap = StrokeCap.Round))
        }
    }
}
