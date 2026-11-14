package com.audeo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Audeo 디자인 시스템(claude.ai 디자인 시스템 "Audeo")의 색 토큰.
 * 낮(day)이 기본이고, 차량이 밤 모드면 night로 바뀝니다. 이름은 tokens.json과 같습니다.
 */
@Immutable
data class AudeoColors(
    val ground: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val dialogLine: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val brand: Color,
    val onBrand: Color,
    val brandInk: Color,
    val brandSoft: Color,
    val caution: Color,
    val cautionSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDanger: Color,
    val mapLand: Color,
    val shadow: Color,
    val isNight: Boolean,
) {
    /** 남은 경로선. tokens.json에서도 brand-ink의 별칭입니다. */
    val route: Color get() = brandInk
}

val DayColors = AudeoColors(
    ground = Color(0xFFF2F3F5),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE7E9ED),
    line = Color(0xFFCDD2D9),
    dialogLine = Color(0xFF8D95A0),
    ink = Color(0xFF0E1013),
    inkMuted = Color(0xFF525A65),
    inkFaint = Color(0xFF7E8590),
    brand = Color(0xFF2347F5),
    onBrand = Color(0xFFFFFFFF),
    brandInk = Color(0xFF1F3FD8),
    brandSoft = Color(0xFFE3E9FF),
    caution = Color(0xFF8A5600),
    cautionSoft = Color(0xFFFFF0D4),
    danger = Color(0xFFB8261A),
    dangerSoft = Color(0xFFFDE4E0),
    onDanger = Color(0xFFFFFFFF),
    mapLand = Color(0xFFE6E8EC),
    shadow = Color(0x290E1013),
    isNight = false,
)

val NightColors = AudeoColors(
    ground = Color(0xFF0A0B0D),
    surface = Color(0xFF131519),
    surface2 = Color(0xFF1C1F24),
    line = Color(0xFF2B3038),
    dialogLine = Color(0xFF5B6370),
    ink = Color(0xFFF3F4F6),
    inkMuted = Color(0xFFA0A7B2),
    inkFaint = Color(0xFF6E7581),
    brand = Color(0xFF2347F5),
    onBrand = Color(0xFFFFFFFF),
    brandInk = Color(0xFF8FA6FF),
    brandSoft = Color(0xFF141C45),
    caution = Color(0xFFF2B33D),
    cautionSoft = Color(0xFF2E2410),
    danger = Color(0xFFFF7A6E),
    dangerSoft = Color(0xFF36150F),
    onDanger = Color(0xFF2A0A06),
    mapLand = Color(0xFF0F1114),
    shadow = Color(0x99000000),
    isNight = true,
)

private val LocalAudeoColors = staticCompositionLocalOf { DayColors }

object Audeo {
    val colors: AudeoColors
        @Composable @ReadOnlyComposable get() = LocalAudeoColors.current
}

/** 낮/밤은 차량의 UI 모드(조도 센서·전조등)를 따릅니다. */
@Composable
fun AudeoTheme(night: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (night) NightColors else DayColors
    val material = if (night) {
        darkColorScheme(
            primary = colors.brandInk,
            onPrimary = colors.ground,
            background = colors.ground,
            surface = colors.surface,
            onSurface = colors.ink,
            onSurfaceVariant = colors.inkMuted,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.brand,
            onPrimary = colors.onBrand,
            background = colors.ground,
            surface = colors.surface,
            onSurface = colors.ink,
            onSurfaceVariant = colors.inkMuted,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalAudeoColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
