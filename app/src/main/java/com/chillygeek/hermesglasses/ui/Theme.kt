package com.chillygeek.hermesglasses.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App palette and theme.
 *
 * The colours follow the convention Meta's own samples use so the app reads as
 * part of the same family: a deep blue for the app's own accent, a green for
 * "live", a red for destructive states, and a dark surface with light text so
 * the app is legible in a dim room or at night.
 */
object AppColor {
  val DeepBlue = Color(0xFF0064E0)
  val Green = Color(0xFF61BC63)
  val Red = Color(0xFFFF3B30)
  val Yellow = Color(0xFFFFCC00)
  val DestructiveBackground = Color(0xFFFFD8DB)
  val DestructiveForeground = Color(0xFFAA071E)

  val Surface = Color(0xFF121417)
  val SurfaceElevated = Color(0xFF1C1F24)
  val OnSurface = Color(0xFFEDEFF2)
  val OnSurfaceMuted = Color(0xFF9AA3AE)
  val Outline = Color(0xFF2C3138)
}

private val HermesColors =
    darkColorScheme(
        primary = AppColor.DeepBlue,
        onPrimary = Color.White,
        secondary = AppColor.Green,
        onSecondary = Color.Black,
        error = AppColor.Red,
        onError = Color.White,
        background = AppColor.Surface,
        onBackground = AppColor.OnSurface,
        surface = AppColor.SurfaceElevated,
        onSurface = AppColor.OnSurface,
        surfaceVariant = AppColor.Outline,
        onSurfaceVariant = AppColor.OnSurfaceMuted,
        outline = AppColor.Outline,
    )

private val HermesTypography =
    Typography(
        headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )

@Composable
fun HermesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  // The app is always dark: it is a companion to a wearable you use at night.
  MaterialTheme(colorScheme = HermesColors, typography = HermesTypography, content = content)
}
