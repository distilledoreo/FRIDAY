package com.localfirst.assistant.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfirst.assistant.settings.AppearanceSettings
import com.localfirst.assistant.settings.ThemeMode

object FridaySpacing { val tiny=4.dp; val small=8.dp; val medium=16.dp; val large=24.dp; val generous=32.dp; val touch=48.dp }
data class FridayPalette(val dark: Boolean, val accent: Color, val reducedMotion: Boolean) {
    val warning get()=if(dark)Color(0xFFE5BC78)else Color(0xFF7D591F)
    val success get()=if(dark)Color(0xFF91BCA0)else Color(0xFF37684A)
    val transitionMillis get() = if (reducedMotion) 0 else 220
}
val LocalFridayPalette = staticCompositionLocalOf { FridayPalette(true, Color(0xFFE89980), false) }
val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }
val LocalSaveAppearance = staticCompositionLocalOf<(AppearanceSettings) -> Unit> { {} }

fun contrast(first: Color, second: Color): Float {
    val a=first.luminance(); val b=second.luminance()
    return (maxOf(a,b)+.05f)/(minOf(a,b)+.05f)
}
/** Preserve the chosen hue while making interactive text readable on each theme. */
fun readableAccent(accent: Color, background: Color): Color {
    val target=if(background.luminance()>.5f) Color.Black else Color.White
    for(step in 0..100) { val color=lerp(accent,target,step/100f); if(contrast(color,background)>=4.5f)return color }
    return target
}
fun fridayColors(dark: Boolean, accent: Color): ColorScheme {
    val background=if(dark)Color(0xFF111113)else Color(0xFFF6F6F4)
    val text=if(dark)Color(0xFFF2F1EE)else Color(0xFF202023)
    val surface=if(dark)Color(0xFF1B1B1E)else Color.White
    val secondary=if(dark)Color(0xFFA9A8AD)else Color(0xFF67666C)
    val interactive=readableAccent(accent,background)
    val onAccent=if(contrast(Color.Black,interactive)>contrast(Color.White,interactive))Color.Black else Color.White
    val base=if(dark)darkColorScheme()else lightColorScheme()
    return base.copy(
        primary=interactive,onPrimary=onAccent,primaryContainer=surface,onPrimaryContainer=text,
        secondary=secondary,onSecondary=background,secondaryContainer=if(dark)Color(0xFF242427)else Color(0xFFEDEDEA),onSecondaryContainer=text,
        tertiary=interactive,onTertiary=onAccent,tertiaryContainer=surface,onTertiaryContainer=text,
        background=background,onBackground=text,surface=surface,onSurface=text,
        surfaceVariant=surface,onSurfaceVariant=secondary,surfaceTint=Color.Transparent,
        surfaceDim=background,surfaceBright=if(dark)Color(0xFF2D2D31)else Color.White,
        surfaceContainerLowest=background,surfaceContainerLow=if(dark)Color(0xFF17171A)else Color(0xFFF9F9F7),
        surfaceContainer=surface,surfaceContainerHigh=if(dark)Color(0xFF242427)else Color(0xFFF0F0ED),
        surfaceContainerHighest=if(dark)Color(0xFF2A2A2E)else Color(0xFFE8E8E4),
        outline=if(dark)Color(0xFF737279)else Color(0xFF89888D),outlineVariant=if(dark)Color(0xFF303034)else Color(0xFFDEDED9),
        error=if(dark)Color(0xFFFFB4AB)else Color(0xFFBA1A1A),onError=if(dark)Color(0xFF690005)else Color.White,
        errorContainer=if(dark)Color(0xFF3B2022)else Color(0xFFFFEDEA),onErrorContainer=if(dark)Color(0xFFFFDAD6)else Color(0xFF690005),
    )
}

private val FridayTypography=Typography(
    headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=30.sp,lineHeight=38.sp),
    headlineMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=26.sp,lineHeight=34.sp),
    headlineSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=22.sp,lineHeight=30.sp),
    titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=20.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=16.sp,lineHeight=24.sp),
    titleSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=21.sp),
    bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=25.sp),
    bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
    bodySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=19.sp),
    labelLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=13.sp,lineHeight=20.sp),
    labelMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=12.sp,lineHeight=18.sp),
    labelSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=11.sp,lineHeight=16.sp),
)

@Composable
fun AssistantTheme(preferences: AppearanceSettings=AppearanceSettings(), onPreferencesChange:(AppearanceSettings)->Unit={},content:@Composable ()->Unit) {
    val resolver=LocalContext.current.contentResolver
    fun systemReduced()=runCatching { Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f }.getOrDefault(false)
    var systemReduced by remember { mutableStateOf(systemReduced()) }
    DisposableEffect(resolver) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange:Boolean) { systemReduced=systemReduced() } }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val dark=when(preferences.theme){ThemeMode.DARK->true;ThemeMode.LIGHT->false;ThemeMode.SYSTEM->isSystemInDarkTheme()}
    val accent=Color(0xFF000000L or (AppearanceSettings.accent(preferences.accentHex)?:AppearanceSettings.DEFAULT_ACCENT).toLong(16))
    val palette=FridayPalette(dark,accent,preferences.reducedMotion||systemReduced)
    CompositionLocalProvider(LocalFridayPalette provides palette,LocalAppearance provides preferences,LocalSaveAppearance provides onPreferencesChange) {
        MaterialTheme(colorScheme=fridayColors(dark,accent),typography=FridayTypography,
            shapes=Shapes(extraSmall=RoundedCornerShape(6.dp),small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(8.dp),large=RoundedCornerShape(12.dp),extraLarge=RoundedCornerShape(16.dp)),content=content)
    }
}
