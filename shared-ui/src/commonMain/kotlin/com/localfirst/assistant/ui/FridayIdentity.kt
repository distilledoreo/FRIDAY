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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import com.localfirst.assistant.voice.VoicePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.pow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import kotlin.math.PI
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp

/** The closed menu icon's proportions, relative to the top line: middle and bottom line lengths, middle height (share of cap height), faint stem. */
data class BrandShape(val middle:Float,val bottom:Float,val middleHeight:Float=.5f,val ghostStem:Float=0f) {
    // Chosen from a side-by-side comparison: reads as an F first, while three distinct lines still say "menu".
    companion object { val DEFAULT=BrandShape(.89f,.38f,.507f) }
}

/**
 * The F is the menu button. Closed, three lines stand in for it (the bottom one shorter); as the
 * sidebar opens ([progress] 0 to 1) the top and middle lines become the F's arms and the bottom line
 * swings up into its stem. The lines land exactly on the font's own F (measured proportions below)
 * and hand over to the real glyph at the end, so the open word is simply "FRIDAY" in one typeface.
 */
@Composable
fun FridayBrand(progress:Float,onClick:()->Unit,modifier:Modifier=Modifier,foreground:Color=MaterialTheme.colorScheme.onBackground,closed:BrandShape=BrandShape.DEFAULT) {
    val p=progress.coerceIn(0f,1f)
    val density=androidx.compose.ui.platform.LocalDensity.current
    val scale=density.fontScale
    val measurer=androidx.compose.ui.text.rememberTextMeasurer()
    val style=androidx.compose.ui.text.TextStyle(fontWeight=FontWeight.Light,fontSize=(22f/scale).sp,letterSpacing=(5f/scale).sp,color=foreground)
    // The whole word with an invisible F keeps R–Y exactly where the font puts them; the F is drawn separately.
    val word=remember(style,measurer) {
        measurer.measure(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color=Color.Transparent)) { append("F") };append("RIDAY")
        },style)
    }
    val glyph=remember(style,measurer) { measurer.measure("F",style) }
    val em=with(density) { (22f/scale).sp.toPx() }
    Row(modifier.height(56.dp).clickable(role=Role.Button,onClick=onClick)
         .clearAndSetSemantics { contentDescription=if(p>.5f)"FRIDAY. Close navigation"else "FRIDAY. Open navigation"; role=Role.Button;onClick { onClick();true } }
         .padding(start=8.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Canvas(Modifier.height(56.dp).width(with(density) { word.size.width.toDp() })) {
            val origin=Offset(0f,center.y-word.size.height/2f)
            val baseline=origin.y+word.firstBaseline
            // Light F proportions, in ems: stem x .09–.15, cap height .71, top arm to .53 (.05 thick),
            // middle arm to .4825 (.0525 thick) centred at .507 of cap height.
            val cap=.71f*em
            val stemLeft=.09f*em;val stemWidth=.06f*em
            val topRight=.53f*em
            val line=.055f*em
            val reveal=((p-.82f)/.18f).coerceIn(0f,1f).let { it*it*(3f-2f*it) }
            val lines=foreground.copy(alpha=foreground.alpha*(1f-reveal))
            val butt=androidx.compose.ui.graphics.StrokeCap.Butt
            fun mix(a:Float,b:Float)=a+(b-a)*p
            // Top line becomes the top arm.
            val topY=baseline-cap+mix(line,.05f*em)/2f
            drawLine(lines,Offset(stemLeft,topY),Offset(topRight,topY),mix(line,.05f*em),butt)
            // Middle line becomes the slightly shorter middle arm.
            val midY=baseline-cap*mix(closed.middleHeight,.507f)
            drawLine(lines,Offset(stemLeft,midY),Offset(mix(stemLeft+(topRight-stemLeft)*closed.middle,.4825f*em),midY),mix(line,.0525f*em),butt)
            // Bottom line (shorter) swings up around its left end into the full-height stem.
            val angle=-PI.toFloat()/2f*p
            val length=mix((topRight-stemLeft)*closed.bottom,cap)
            // An optional faint stem hints at the F while closed.
            if(closed.ghostStem>0f&&p<1f)drawLine(lines.copy(alpha=lines.alpha*closed.ghostStem*(1f-p)),Offset(stemLeft+stemWidth/2f,baseline),Offset(stemLeft+stemWidth/2f,baseline-cap),stemWidth,butt)
            val pivot=Offset(mix(stemLeft,stemLeft+stemWidth/2f),baseline-mix(line/2f,0f))
            drawLine(lines,pivot,pivot+Offset(length*kotlin.math.cos(angle),length*sin(angle)),mix(line,stemWidth),butt)
            drawText(word,topLeft=origin)
            if(reveal>0f)drawText(glyph,topLeft=origin,alpha=reveal)
        }
    }
}

/**
 * FRIDAY's ambient light: a warm ridge of light with a soft bloom, light spilling below it and a
 * few twinkling specks. Plain layered drawing (no blur effect), so it looks the same everywhere;
 * it animates slowly, faster while listening or thinking, and stops when the app is hidden or
 * motion is reduced.
 */
@Composable
fun FridayAmbient(voicePhase:VoicePhase?,level:Float,thinking:Boolean,modifier:Modifier=Modifier,fixedTime:Float?=null) {
    val palette=LocalFridayPalette.current
    val visible=rememberAppVisible()
    val active=voicePhase!=null&&voicePhase!=VoicePhase.PAUSED||thinking
    // Animation time in seconds, advanced every frame; faster while listening or thinking. An infinite-animation
    // frame clock, so test harnesses (which pause infinite animations) can still reach idle.
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible,palette.reducedMotion,active,fixedTime) {
        if(fixedTime==null&&visible&&!palette.reducedMotion) {
            val speed=if(active)1.8f else 1f
            var last=withInfiniteAnimationFrameNanos { it }
            while(isActive) withInfiniteAnimationFrameNanos { now -> clock=(clock+(now-last)/1e9f*speed)%3600f;last=now }
        }
    }
    val time=fixedTime?:clock
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
    BoxWithConstraints(modifier.semantics { /* decorative; no screen-reader node or input */ }) {
        // Windows bigger than a phone, or a platform that can't dither, get the blurred bloom where blur is available.
        val soft=blurAvailable&&(minOf(maxWidth,maxHeight)>BIG_WINDOW.dp||!gradientsDithered)
        // [layer]: ALL_LAYERS draws everything at once (phones), otherwise BASE_LAYER, one SOFT_BLOOM index, or TOP_LAYER.
        fun DrawScope.light(layer:Int) {
            val w=size.width;val h=size.height
            val unit=ambientUnit(size)
            // Where gradients aren't dithered (the browser) or the window is big, faint gradients show their
            // 8-bit steps as rings and contour lines; this draws them smoothly instead. Phones keep the original.
            val refined=minOf(w,h)>BIG_WINDOW.dp.toPx()||!gradientsDithered
            // A smooth falloff through the original three stops (a0 at the centre, aHalf halfway, none at the
            // edge), fading to transparent accent rather than transparent black.
            fun glow(a0:Float,aHalf:Float):List<Color> =
                if(!refined)listOf(accent.copy(alpha=a0),accent.copy(alpha=aHalf),Color.Transparent)
                else { val power=kotlin.math.ln(aHalf/a0)/kotlin.math.ln(.5f);List(24) { i -> accent.copy(alpha=a0*(1f-i/23f).pow(power)) } }
            val tau=2f*PI.toFloat()
            fun wave(period:Float,offset:Float=0f)=sin(time*tau/period+offset)
            // The ridge drifts and undulates on slow, unrelated rhythms so it never looks mechanical.
            val peak=Offset(w*(.47f+.03f*wave(11f)),h*(.36f+.01f*wave(7f)))
            val left=.022f*wave(6f);val right=.022f*wave(8.5f,1.3f);val ends=.012f*wave(9.5f,2.1f)
            val breath=1f+.14f*wave(5f)
            fun ridge(lift:Float,spread:Float):Path=Path().apply {
                moveTo(w*(.13f-spread),peak.y+h*(.075f+lift+ends))
                cubicTo(w*.29f,peak.y+h*(.052f+lift+left),w*(.37f-spread*.2f),peak.y-h*(.004f-lift),peak.x,peak.y+h*lift)
                cubicTo(w*(.57f+spread*.2f),peak.y+h*(.004f+lift),w*.69f,peak.y+h*(.044f+lift+right),w*(.88f+spread),peak.y+h*(.072f+lift-ends))
            }
            val main=ridge(0f,0f)
            val alongRidge={a:Float -> if(!refined)Brush.horizontalGradient(
                0f to Color.Transparent,.24f to accent.copy(alpha=a*.35f),.46f to accent.copy(alpha=a),.62f to accent.copy(alpha=a*.6f),.86f to Color.Transparent,
                startX=w*.1f,endX=w*.92f)
                else Brush.horizontalGradient(*smoothStops(RIDGE_KEYS) { accent.copy(alpha=a*it) },startX=w*.1f,endX=w*.92f)}
            if(layer==ALL_LAYERS||layer==BASE_LAYER) {
                // Warm haze behind the ridge.
                fill(Brush.radialGradient(glow((if(dark).20f else .16f)*intensity*breath,(if(dark).06f else .05f)*intensity),peak+Offset(0f,h*.05f),w*.62f),refined)
                // Light spilling down from the ridge.
                val spillRadius=w*.36f
                val spill=Path().apply { addPath(main);lineTo(w*.95f,maxOf(peak.y+h*.3f,peak.y+spillRadius));lineTo(w*.05f,maxOf(peak.y+h*.3f,peak.y+spillRadius));close() }
                clipPath(spill) {
                    fill(Brush.radialGradient(glow((if(dark).26f else .2f)*intensity*breath,(if(dark).08f else .06f)*intensity),peak+Offset(0f,h*.01f),spillRadius),refined)
                }
            }
            val bloom=(if(dark).042f else .034f)*intensity*breath
            // Bloom: wide faint strokes narrowing to a bright core. Where it can, the bloom is instead a few
            // blurred strokes fitted to the same falloff, drawn in their own layers: stacked strokes leave a
            // step at every edge, which big windows spread far enough apart to see.
            if(layer==ALL_LAYERS) for(i in 18 downTo 1) drawPath(main,alongRidge(bloom*(1.5f-i/18f)),style=Stroke((i*i*.22f+2f).dp.toPx()*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round))
            if(layer>=0) { val (_,width,strength)=SOFT_BLOOM[layer];stroke(main,alongRidge(bloom*strength),width.dp.toPx()*unit);return }
            if(layer==BASE_LAYER) return
            drawPath(ridge(.022f,.05f),alongRidge(.1f*intensity),style=Stroke(.8.dp.toPx()*unit))
            drawPath(main,alongRidge(.9f*intensity.coerceAtMost(1f)),style=Stroke(2.2.dp.toPx()*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round))
            // The brightest part of the line glides back and forth, and a small glint travels along it.
            val c=.42f+.1f*wave(9f,.7f)
            drawPath(main,Brush.horizontalGradient(0f to Color.Transparent,(c-.14f) to core.copy(alpha=0f),c to core.copy(alpha=.95f),(c+.08f) to core.copy(alpha=.7f),(c+.22f) to Color.Transparent,1f to Color.Transparent,startX=w*.1f,endX=w*.92f),style=Stroke(1.2.dp.toPx()*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round))
            val glide=(time/7f)%1f
            val g=.12f+.76f*glide;val glow=sin(PI.toFloat()*glide)
            drawPath(main,Brush.horizontalGradient(0f to Color.Transparent,(g-.05f).coerceAtLeast(.001f) to Color.Transparent,g to core.copy(alpha=.9f*glow),(g+.05f).coerceAtMost(.999f) to Color.Transparent,1f to Color.Transparent,startX=w*.1f,endX=w*.92f),style=Stroke(2.6.dp.toPx()*unit,cap=androidx.compose.ui.graphics.StrokeCap.Round))
            // Specks: mostly above the ridge, a few twinkling with a soft halo.
            for(index in 0 until 34) {
                fun hash(n:Float)=((sin(n)*43758.547f)%1f+1f)%1f
                val seed=hash(index*12.9898f);val spread=hash(index*78.233f);val start=hash(index*39.425f)
                // Each speck rises slowly from near the ridge, fading in and out, with a slight sideways sway.
                val rise=(time/(9f+seed*9f)+start)%1f
                val x=w*(.12f+.76f*spread)+sin(time*.5f+index)*4.dp.toPx()*unit
                val y=peak.y+h*.05f-h*(.04f+.22f*rise)-h*.03f*seed
                val twinkle=(.55f+.45f*sin(time*2.4f+index*1.7f))*sin(PI.toFloat()*rise)
                val radius=(.7f+1.5f*seed).dp.toPx()*unit
                val a=(if(dark).55f else .45f)*twinkle*intensity.coerceAtMost(1f)*(if(index%3==0)1f else .6f)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=a*.35f),Color.Transparent),Offset(x,y),radius*5f),radius*5f,Offset(x,y))
                drawCircle(if(index%4==0)core.copy(alpha=a) else accent.copy(alpha=a),radius,Offset(x,y))
            }
        }
        if(!soft) Canvas(Modifier.fillMaxSize()) { light(ALL_LAYERS) }
        else {
            Canvas(Modifier.fillMaxSize()) { light(BASE_LAYER) }
            SOFT_BLOOM.forEachIndexed { index,(sigma,_,_) ->
                Canvas(Modifier.fillMaxSize().graphicsLayer {
                    // Blur radius from sigma, the inverse of Skia's sigma = radius × 0.57735 + 0.5.
                    val radius=((sigma.dp.toPx()*ambientUnit(size)-.5f)/.57735f).coerceAtLeast(.1f)
                    renderEffect=androidx.compose.ui.graphics.BlurEffect(radius,radius,androidx.compose.ui.graphics.TileMode.Decal)
                }) { light(index) }
            }
            Canvas(Modifier.fillMaxSize()) { light(TOP_LAYER) }
        }
    }
}

private const val ALL_LAYERS=-1
private const val BASE_LAYER=-2
private const val TOP_LAYER=-3

/**
 * The bloom as blurred strokes, (sigma dp, width dp, strength relative to one stacked layer), fitted to the
 * stacked strokes' falloff from the line to within a few percent.
 */
private val SOFT_BLOOM=listOf(Triple(2f,2f,12.73f),Triple(7f,21f,5.19f),Triple(16f,32f,6.49f))

/** Where the ridge's glow sits along it, as (position, share of full strength), eased smoothly between. */
private val RIDGE_KEYS=listOf(0f to 0f,.24f to .35f,.46f to 1f,.62f to .6f,.86f to 0f,1f to 0f)

/** Many gradient stops easing (cosine) between [keys], so the gradient has no corners to show as bands. */
private fun smoothStops(keys:List<Pair<Float,Float>>,color:(Float)->Color):Array<Pair<Float,Color>> =
    (0..48).map { n ->
        val t=n/48f
        val k=keys.indexOfLast { it.first<=t }.coerceIn(0,keys.size-2)
        val (x0,y0)=keys[k];val (x1,y1)=keys[k+1]
        val f=((t-x0)/(x1-x0)).coerceIn(0f,1f)
        t to color(y0+(y1-y0)*(1f-kotlin.math.cos(PI.toFloat()*f))/2f)
    }.toTypedArray()

/**
 * Fills the area with [brush], dithered when [dithered]: Skia then adds a little noise as it draws the
 * gradient, so a faint one spread over a big window shades smoothly instead of in 8-bit steps.
 */
private fun DrawScope.fill(brush:Brush,dithered:Boolean) {
    if(!dithered||brush !is androidx.compose.ui.graphics.ShaderBrush) { drawRect(brush);return }
    drawIntoCanvas { it.drawRect(0f,0f,size.width,size.height,ditheredPaint(brush.createShader(size))) }
}

/** Strokes [path] with [brush] and round caps, dithered, for the bloom's soft strokes. */
private fun DrawScope.stroke(path:Path,brush:Brush,width:Float) {
    if(brush !is androidx.compose.ui.graphics.ShaderBrush) { drawPath(path,brush,style=Stroke(width,cap=androidx.compose.ui.graphics.StrokeCap.Round));return }
    drawIntoCanvas { it.drawPath(path,ditheredPaint(brush.createShader(size)).apply {
        style=androidx.compose.ui.graphics.PaintingStyle.Stroke;strokeWidth=width;strokeCap=androidx.compose.ui.graphics.StrokeCap.Round
    }) }
}

private fun ditheredPaint(shader:androidx.compose.ui.graphics.Shader)=androidx.compose.ui.graphics.Paint().apply {
    isAntiAlias=true;this.shader=shader;dither()
}

/** Whether the platform dithers gradients itself (Android does; the browser's Skia doesn't unless asked). */
internal expect val gradientsDithered:Boolean

/** Asks the platform's paint to dither what it draws. */
internal expect fun androidx.compose.ui.graphics.Paint.dither()

/** Whether the bloom is drawn as blurred strokes (the browser) rather than stacked ones. */
internal expect val blurAvailable:Boolean

/** The width, in dp, the ambient light was designed at; larger windows scale its strokes up from here. */
private const val PHONE_WIDTH=411f

/** Windows whose shorter side is past this many dp (tablets, desktops) draw the light larger and smoother; phones never are. */
private const val BIG_WINDOW=600f

/**
 * How much the light's lines, bloom, specks and sway scale: 1 on phones, and on bigger windows in
 * proportion to a phone's width, so a desktop gets a phone's proportions instead of hairlines.
 */
private fun androidx.compose.ui.unit.Density.ambientUnit(size:androidx.compose.ui.geometry.Size):Float {
    val side=minOf(size.width,size.height)
    return if(side<=BIG_WINDOW.dp.toPx())1f else (side/PHONE_WIDTH.dp.toPx()).coerceAtMost(8f)
}
