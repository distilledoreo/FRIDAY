package com.localfirst.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import com.localfirst.assistant.voice.VoicePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp

/** The F is constructed separately: menu strokes translate into its arms, never deform text. */
@Composable
internal fun FridayBrand(progress:Float,onClick:()->Unit,modifier:Modifier=Modifier,foreground:Color=MaterialTheme.colorScheme.onBackground) {
    val p=progress.coerceIn(0f,1f)
    Row(modifier.height(56.dp).width(180.dp).clickable(role=Role.Button,onClick=onClick)
         .clearAndSetSemantics { contentDescription=if(p>.5f)"FRIDAY. Close navigation"else "FRIDAY. Open navigation"; role=Role.Button;onClick { onClick();true } },verticalAlignment=Alignment.CenterVertically) {
        Canvas(Modifier.width(43.dp).height(36.dp)) {
            val f=26.dp.toPx(); val top=10.dp.toPx(); val middle=18.dp.toPx(); val bottom=27.dp.toPx(); val width=1.35.dp.toPx()
            drawLine(foreground,Offset(f,top),Offset(f,bottom),width)
            drawLine(foreground.copy(alpha=1-p),Offset(f,top),Offset(f+11.dp.toPx(),top),width)
            drawLine(foreground.copy(alpha=1-p),Offset(f,middle),Offset(f+8.dp.toPx(),middle),width)
            val x=f*p
            drawLine(foreground,Offset(x,14.dp.toPx()+(top-14.dp.toPx())*p),Offset(x+(10+p).dp.toPx(),14.dp.toPx()+(top-14.dp.toPx())*p),width)
            drawLine(foreground,Offset(x,21.dp.toPx()+(middle-21.dp.toPx())*p),Offset(x+(10-2*p).dp.toPx(),21.dp.toPx()+(middle-21.dp.toPx())*p),width)
        }
        Text("RIDAY",fontWeight=FontWeight.Light,fontSize=(20f/androidx.compose.ui.platform.LocalDensity.current.fontScale).sp,letterSpacing=(3.5f/androidx.compose.ui.platform.LocalDensity.current.fontScale).sp,color=foreground)
    }
}

/**
 * FRIDAY's ambient light: a warm ridge of light with a soft bloom, light spilling below it and a
 * few twinkling specks. Plain layered drawing (no blur effect), so it looks the same everywhere;
 * it animates slowly, faster while listening or thinking, and stops when the app is hidden or
 * motion is reduced.
 */
@Composable
internal fun FridayAmbient(voicePhase:VoicePhase?,level:Float,thinking:Boolean,modifier:Modifier=Modifier) {
    val palette=LocalFridayPalette.current
    val owner=LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,_ -> visible=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer);onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val active=voicePhase!=null&&voicePhase!=VoicePhase.PAUSED||thinking
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible,palette.reducedMotion,active) {
        if(visible&&!palette.reducedMotion)while(isActive) { delay(if(active)40 else 66);phase=(phase+if(active).035f else .012f)%(2f*PI.toFloat()*8f) }
    }
    val intensity by animateFloatAsState(
        when {
            voicePhase==VoicePhase.LISTENING -> .85f+.35f*level.coerceIn(0f,1f)
            voicePhase==VoicePhase.SPEAKING -> .95f
            active -> .8f
            else -> .72f
        },tween(palette.transitionMillis),label="ambient intensity")
    val dark=palette.dark
    val accent=palette.accent
    // A light, warm core reads as glowing on dark; on light, the accent itself carries the line.
    val core=if(dark)lerp(accent,Color.White,.55f)else lerp(accent,Color.White,.15f)
    Canvas(modifier.semantics { /* decorative; no screen-reader node or input */ }) {
        val w=size.width;val h=size.height
        val peak=Offset(w*.47f,h*.36f)
        val breathe=sin(phase*.5f)*.012f
        fun ridge(lift:Float,spread:Float):Path=Path().apply {
            moveTo(w*(.13f-spread),peak.y+h*(.075f+lift))
            cubicTo(w*.29f,peak.y+h*(.052f+lift),w*(.37f-spread*.2f),peak.y-h*(.004f+breathe-lift),peak.x,peak.y+h*lift)
            cubicTo(w*(.57f+spread*.2f),peak.y+h*(.004f+breathe+lift),w*.69f,peak.y+h*(.044f+lift),w*(.88f+spread),peak.y+h*(.072f+lift))
        }
        val main=ridge(0f,0f)
        val alongRidge={a:Float -> Brush.horizontalGradient(
            0f to Color.Transparent,.24f to accent.copy(alpha=a*.35f),.46f to accent.copy(alpha=a),.62f to accent.copy(alpha=a*.6f),.86f to Color.Transparent,
            startX=w*.1f,endX=w*.92f)}
        // Warm haze behind the ridge.
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha=(if(dark).20f else .16f)*intensity),accent.copy(alpha=(if(dark).06f else .05f)*intensity),Color.Transparent),peak+Offset(0f,h*.05f),w*.62f))
        // Light spilling down from the ridge.
        val spill=Path().apply { addPath(main);lineTo(w*.95f,peak.y+h*.3f);lineTo(w*.05f,peak.y+h*.3f);close() }
        clipPath(spill) {
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha=(if(dark).26f else .2f)*intensity),accent.copy(alpha=(if(dark).08f else .06f)*intensity),Color.Transparent),peak+Offset(0f,h*.01f),w*.36f))
        }
        // Bloom: wide faint strokes narrowing to a bright core.
        for(i in 18 downTo 1) drawPath(main,alongRidge((if(dark).042f else .034f)*intensity*(1.5f-i/18f)),style=Stroke((i*i*.22f+2f).dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))
        drawPath(ridge(.022f,.05f),alongRidge(.1f*intensity),style=Stroke(.8.dp.toPx()))
        drawPath(main,alongRidge(.9f*intensity.coerceAtMost(1f)),style=Stroke(2.2.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))
        drawPath(main,Brush.horizontalGradient(0f to Color.Transparent,.3f to core.copy(alpha=.0f),.44f to core.copy(alpha=.95f),.52f to core.copy(alpha=.7f),.66f to Color.Transparent,startX=w*.1f,endX=w*.92f),style=Stroke(1.2.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))
        // Specks: mostly above the ridge, a few twinkling with a soft halo.
        for(index in 0 until 34) {
            val seed=(index*7919)%997/997f
            val x=w*(.14f+(index*37%71)/100f)
            val baseY=peak.y-h*(.17f*((index*53%89)/89f))+h*.05f*((index*29%13)/13f)
            val y=baseY+sin(phase*.7f+index)*2.5.dp.toPx()
            val twinkle=.55f+.45f*sin(phase*1.3f+index*1.7f)
            val radius=(.7f+1.5f*seed).dp.toPx()
            val a=(if(dark).55f else .45f)*twinkle*intensity.coerceAtMost(1f)*(if(index%3==0)1f else .6f)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=a*.35f),Color.Transparent),Offset(x,y),radius*5f),radius*5f,Offset(x,y))
            drawCircle(if(index%4==0)core.copy(alpha=a) else accent.copy(alpha=a),radius,Offset(x,y))
        }
    }
}
