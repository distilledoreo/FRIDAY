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

/** Only two paths and a small fixed particle set; no blur, bitmap, GL view or permanent frame loop. */
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
        if(visible&&!palette.reducedMotion)while(isActive) { delay(if(active)50 else 200);phase=(phase+if(active).028f else .012f)%6.283185f }
    }
    val intensity by animateFloatAsState(if(voicePhase==VoicePhase.LISTENING).36f+.2f*level.coerceIn(0f,1f)else if(active).40f else .28f,tween(palette.transitionMillis),label="ambient intensity")
    Canvas(modifier.semantics { /* decorative; no screen-reader node or input */ }) {
        val center=Offset(size.width*.5f,size.height*.39f)
        val accent=palette.accent
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha=intensity*.30f),accent.copy(alpha=.015f),Color.Transparent),center,size.width*.43f))
        fun flow(delta:Float):Path=Path().apply {
            moveTo(size.width*.12f,center.y+size.height*.075f)
            cubicTo(size.width*.32f,center.y-size.height*(.07f+sin(phase+delta)*.007f),size.width*.53f,center.y-size.height*.05f,size.width*.63f,center.y+size.height*.009f)
            cubicTo(size.width*.76f,center.y+size.height*.054f,size.width*.83f,center.y+size.height*.081f,size.width*.91f,center.y+size.height*.092f)
        }
        val path=flow(0f)
        // Soft layers use translucent strokes rather than a high-cost blur or an opaque band.
        for(layer in 1..6)drawPath(path,Brush.linearGradient(listOf(Color.Transparent,accent.copy(alpha=intensity*.018f),Color.Transparent),Offset(size.width*.20f,center.y),Offset(size.width*.8f,center.y)),style=Stroke((layer*4).dp.toPx()))
        drawPath(path,Brush.linearGradient(listOf(Color.Transparent,accent.copy(alpha=intensity*.46f),Color.Transparent),Offset(size.width*.15f,center.y),Offset(size.width*.85f,center.y)),style=Stroke(1.dp.toPx()))
        drawPath(flow(1.3f),accent.copy(alpha=intensity*.10f),style=Stroke(.55.dp.toPx()))
        for(index in 0 until 18) {
            val x=size.width*(.16f+(index*37%67)/100f)
            val y=center.y+size.height*((index*17%33-14)/150f)+sin(phase+index)*3.dp.toPx()
            drawCircle(accent.copy(alpha=intensity*(.16f+(index%4)*.07f)),if(index%5==0)1.dp.toPx()else .6.dp.toPx(),Offset(x,y))
        }
    }
}
