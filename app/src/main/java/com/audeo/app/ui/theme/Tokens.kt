package com.audeo.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 글자·간격·모양 토큰. Pleos Connect는 160dpi라 1dp = 1px이고, 값은 디자인 시스템과 같습니다.
 *
 * 글꼴: 디자인은 IBM Plex Sans KR(한글)·Barlow(숫자)·IBM Plex Mono(편명)이지만,
 * Pleos에는 Google Play 서비스가 없어 내려받는 글꼴을 쓸 수 없습니다. 글꼴 파일을 res/font에 넣기 전까지는
 * 시스템 글꼴을 쓰고, 숫자는 tabular-nums로 폭을 고정합니다.
 */
object AudeoType {
    private const val TNUM = "tnum"

    val eta = TextStyle(fontSize = 128.sp, lineHeight = 128.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM)
    val figure = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.01).em, fontFeatureSettings = TNUM)
    val figureSm = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM)
    val display = TextStyle(fontSize = 56.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val title = TextStyle(fontSize = 40.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold)
    val bodyLg = TextStyle(fontSize = 32.sp, lineHeight = 46.sp, fontWeight = FontWeight.Normal)
    val body = TextStyle(fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
    val bodySm = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.01.em)
    val code = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
}

object Space {
    val s1 = 8.dp
    val s2 = 16.dp
    val s3 = 24.dp
    val s4 = 32.dp
    val s5 = 48.dp
    val s6 = 64.dp
    val s7 = 96.dp
}

object Radius {
    val sm = 8.dp
    val md = 16.dp
    val lg = 28.dp
}

object Dimens {
    val touchMin = 76.dp
    val controlH = 96.dp
    val iconMd = 40.dp
    val iconSm = 28.dp
    val callbarH = 112.dp
    val dialogW = 800.dp
    val dialogBorder = 2.dp
}
