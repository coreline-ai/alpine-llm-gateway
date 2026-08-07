package dev.alpine.chat.feature.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val AlpineTypography = Typography().copy(
    headlineLarge = Typography().headlineLarge.copy(
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.sp,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Typography().titleSmall.copy(fontWeight = FontWeight.Bold),
    bodyLarge = Typography().bodyLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = Typography().bodyMedium.copy(fontWeight = FontWeight.Medium),
    bodySmall = Typography().bodySmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = Typography().labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Typography().labelSmall.copy(fontWeight = FontWeight.SemiBold),
)
