package com.localfirst.assistant.ui.genui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfirst.assistant.genui.DesignTokens
import com.localfirst.assistant.ui.AppIcons

/** FRIDAY's design tokens for interface replies: the catalog's names mapped onto the app theme. */
internal object GenUiTokens {
    fun spacing(value: Any?, default: Dp = 0.dp): Dp = when (value) {
        is Number -> (value.toDouble() * DesignTokens.SPACING_UNIT_DP).dp
        is String -> value.removeSuffix("px").removeSuffix("dp").trim().toDoubleOrNull()?.let { if (value.endsWith("px")) (it / 1.5).dp else (it * DesignTokens.SPACING_UNIT_DP).dp } ?: default
        else -> default
    }

    fun shape(value: Any?, default: Dp = 14.dp): Shape = when (value) {
        "none" -> RoundedCornerShape(0.dp)
        "sm" -> RoundedCornerShape(6.dp)
        "md" -> RoundedCornerShape(10.dp)
        "lg" -> RoundedCornerShape(14.dp)
        "xl" -> RoundedCornerShape(20.dp)
        "full" -> CircleShape
        is Number -> RoundedCornerShape(value.toDouble().dp)
        is String -> value.removeSuffix("px").toDoubleOrNull()?.let { RoundedCornerShape((it / 1.5).dp) } ?: RoundedCornerShape(default)
        else -> RoundedCornerShape(default)
    }

    /** A length: a number of dp, a token, "full", or a pixel string. Null means wrap content. */
    fun length(value: Any?): Dp? = when (value) {
        is Number -> value.toDouble().dp
        "xs" -> 80.dp; "sm" -> 140.dp; "md" -> 220.dp; "lg" -> 300.dp; "xl" -> 380.dp; "2xl" -> 460.dp; "3xl" -> 560.dp
        is String -> value.removeSuffix("px").toDoubleOrNull()?.let { (it / 1.5).dp }
        else -> null
    }

    fun textSize(value: Any?, default: TextUnit): TextUnit = when (value) {
        "xs" -> 12.sp; "sm" -> 14.sp; "md" -> 16.sp; "lg" -> 20.sp; "xl" -> 24.sp; "2xl" -> 30.sp; "3xl" -> 36.sp
        is Number -> value.toDouble().sp
        else -> default
    }

    private fun hex(text: String): Color? = runCatching {
        val h = text.removePrefix("#")
        val full = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 4 -> h.map { "$it$it" }.joinToString(""); else -> h }
        val argb = when (full.length) { 6 -> "FF$full"; 8 -> full.substring(6) + full.substring(0, 6); else -> return null }
        Color(argb.toLong(16))
    }.getOrNull()

    private val palette = mapOf(
        "red" to (0xFFE5484D to 0xFFFF6369), "orange" to (0xFFE5733C to 0xFFFF8B52), "amber" to (0xFFD9930D to 0xFFFFB224),
        "yellow" to (0xFFC9A300 to 0xFFF5D90A), "lime" to (0xFF5C9A1F to 0xFF99D52A), "green" to (0xFF30A46C to 0xFF4CC38A),
        "teal" to (0xFF12A594 to 0xFF0AC5B3), "cyan" to (0xFF05A2C2 to 0xFF00C2D7), "blue" to (0xFF0091FF to 0xFF52A9FF),
        "indigo" to (0xFF3E63DD to 0xFF849DFF), "violet" to (0xFF6E56CF to 0xFF9E8CFC), "purple" to (0xFF8E4EC6 to 0xFFBF7AF0),
        "pink" to (0xFFD6409F to 0xFFF65CB6), "gray" to (0xFF8B8D98 to 0xFFA0A3AE),
    )

    fun color(value: Any?, scheme: ColorScheme, accent: Color, dark: Boolean): Color? {
        val name = (value as? String)?.trim() ?: return null
        return when (name) {
            "text" -> scheme.onSurface
            "muted" -> scheme.onSurfaceVariant
            "subtle" -> scheme.outline
            "accent", "primary" -> accent
            "success" -> if (dark) Color(0xFF91BCA0) else Color(0xFF37684A)
            "warning" -> if (dark) Color(0xFFE5BC78) else Color(0xFF7D591F)
            "danger", "error" -> scheme.error
            "info" -> if (dark) Color(0xFF8FB8F2) else Color(0xFF2B5C9E)
            "surface" -> scheme.surface
            "card" -> scheme.surfaceContainerHigh
            "border" -> scheme.outlineVariant
            "inverse" -> scheme.inverseSurface
            else -> palette[name]?.let { Color(if (dark) it.second else it.first) }
                ?: if (name.startsWith("#")) hex(name) else rgb(name)
        }
    }

    private fun rgb(text: String): Color? {
        val m = Regex("rgba?\\(([^)]*)\\)").find(text) ?: return null
        val parts = m.groupValues[1].split(',', ' ', '/').filter { it.isNotBlank() }.mapNotNull { it.trim().removeSuffix("%").toFloatOrNull() }
        if (parts.size < 3) return null
        return Color(parts[0] / 255f, parts[1] / 255f, parts[2] / 255f, parts.getOrElse(3) { 1f }.let { if (it > 1) it / 100f else it })
    }

    /** Background and content colors for a tone: neutral, accent, success, warning, danger, info. */
    fun tone(tone: String?, scheme: ColorScheme, accent: Color, dark: Boolean): Pair<Color, Color> {
        val base = when (tone) {
            "accent" -> accent
            "success", "warning", "danger", "info" -> color(tone, scheme, accent, dark)!!
            else -> return scheme.surfaceContainerHigh to scheme.onSurface
        }
        return base.copy(alpha = if (dark) .20f else .13f) to base
    }

    private val icons: Map<String, ImageVector> by lazy {
        mapOf(
            "check" to Icons.Filled.Check, "done" to Icons.Filled.Check, "check-circle" to Icons.Filled.CheckCircle, "success" to Icons.Filled.CheckCircle,
            "close" to Icons.Filled.Close, "x" to Icons.Filled.Close, "cancel" to Icons.Filled.Close,
            "add" to Icons.Filled.Add, "plus" to Icons.Filled.Add, "remove" to Icons.Filled.Close,
            "star" to Icons.Filled.Star, "heart" to Icons.Filled.Favorite, "favorite" to Icons.Filled.Favorite, "heart-outline" to Icons.Filled.FavoriteBorder,
            "search" to Icons.Filled.Search, "home" to Icons.Filled.Home, "settings" to Icons.Filled.Settings, "gear" to Icons.Filled.Settings,
            "info" to Icons.Filled.Info, "warning" to Icons.Filled.Warning, "alert" to Icons.Filled.Warning, "error" to Icons.Filled.Warning,
            "arrow-right" to Icons.AutoMirrored.Filled.ArrowForward, "arrow-forward" to Icons.AutoMirrored.Filled.ArrowForward, "next" to Icons.AutoMirrored.Filled.ArrowForward,
            "arrow-left" to Icons.AutoMirrored.Filled.ArrowBack, "back" to Icons.AutoMirrored.Filled.ArrowBack,
            "chevron-down" to Icons.Filled.KeyboardArrowDown, "chevron-up" to Icons.Filled.KeyboardArrowUp,
            "calendar" to Icons.Filled.DateRange, "date" to Icons.Filled.DateRange, "event" to Icons.Filled.DateRange,
            "place" to Icons.Filled.LocationOn, "location" to Icons.Filled.LocationOn, "pin" to Icons.Filled.LocationOn, "map" to Icons.Filled.LocationOn,
            "phone" to Icons.Filled.Call, "call" to Icons.Filled.Call, "mail" to Icons.Filled.Email, "email" to Icons.Filled.Email,
            "person" to Icons.Filled.Person, "user" to Icons.Filled.Person, "account" to Icons.Filled.AccountCircle, "face" to Icons.Filled.Face,
            "cart" to Icons.Filled.ShoppingCart, "shopping-cart" to Icons.Filled.ShoppingCart, "shop" to Icons.Filled.ShoppingCart,
            "share" to Icons.Filled.Share, "edit" to Icons.Filled.Edit, "pencil" to Icons.Filled.Edit, "delete" to Icons.Filled.Delete, "trash" to Icons.Filled.Delete,
            "play" to Icons.Filled.PlayArrow, "refresh" to Icons.Filled.Refresh, "reload" to Icons.Filled.Refresh, "lock" to Icons.Filled.Lock,
            "menu" to Icons.Filled.Menu, "list" to Icons.AutoMirrored.Filled.List, "send" to Icons.AutoMirrored.Filled.Send,
            "bell" to Icons.Filled.Notifications, "notification" to Icons.Filled.Notifications, "like" to Icons.Filled.ThumbUp, "thumbs-up" to Icons.Filled.ThumbUp,
            "tool" to Icons.Filled.Build, "build" to Icons.Filled.Build, "wrench" to Icons.Filled.Build,
            "image" to AppIcons.Image, "photo" to AppIcons.Image, "computer" to AppIcons.Computer, "laptop" to AppIcons.Computer,
            "folder" to AppIcons.Folder, "copy" to AppIcons.ContentCopy, "mic" to AppIcons.Mic, "volume" to AppIcons.VolumeUp,
            "trending-up" to AppIcons.ArrowUpward, "arrow-up" to AppIcons.ArrowUpward, "phone-android" to AppIcons.PhoneUse, "smartphone" to AppIcons.PhoneUse,
        )
    }

    /** Emoji for icon names Material core lacks, so weather and everyday icons still read well. */
    private val emoji = mapOf(
        "sun" to "☀️", "sunny" to "☀️", "clear" to "☀️", "cloud" to "☁️", "cloudy" to "☁️", "partly-cloudy" to "⛅", "rain" to "🌧️",
        "storm" to "⛈️", "snow" to "❄️", "wind" to "💨", "fog" to "🌫️", "moon" to "🌙", "bolt" to "⚡", "fire" to "🔥", "music" to "🎵",
        "clock" to "🕒", "time" to "🕒", "timer" to "⏱️", "gift" to "🎁", "car" to "🚗", "plane" to "✈️", "flight" to "✈️", "train" to "🚆",
        "food" to "🍽️", "restaurant" to "🍽️", "coffee" to "☕", "book" to "📖", "money" to "💵", "dollar" to "💲", "trending-down" to "📉",
        "chart" to "📊", "link" to "🔗", "download" to "⬇️", "upload" to "⬆️", "pause" to "⏸️", "globe" to "🌐", "tag" to "🏷️", "flag" to "🚩",
        "camera" to "📷", "video" to "🎬", "lightbulb" to "💡", "idea" to "💡", "rocket" to "🚀", "trophy" to "🏆", "leaf" to "🍃", "paw" to "🐾",
    )

    fun icon(name: String?): ImageVector? = name?.lowercase()?.replace('_', '-')?.let { icons[it] ?: icons[it.removeSuffix("-outline")] }
    fun emoji(name: String?): String? = name?.lowercase()?.replace('_', '-')?.let { emoji[it] }
}
