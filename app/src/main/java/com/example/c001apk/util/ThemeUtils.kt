package com.example.c001apk.util

import android.content.Context
import androidx.annotation.StyleRes
import com.example.c001apk.R
import com.google.android.material.color.DynamicColors
import rikka.core.util.ResourceUtils

object ThemeUtils {

    @StyleRes
    fun getNightThemeStyleRes(context: Context): Int {
        return if (PrefManager.blackDarkTheme && ResourceUtils.isNightMode(context.resources.configuration))
            R.style.ThemeOverlay_Black else R.style.ThemeOverlay
    }

    val isSystemAccent
        get() = DynamicColors.isDynamicColorAvailable() && PrefManager.followSystemAccent

    private val colorThemeMap = mapOf(
        "MATERIAL_DEFAULT" to R.style.ThemeOverlay_MaterialDefault,
        "MATERIAL_RED" to R.style.ThemeOverlay_MaterialRed,
        "MATERIAL_PINK" to R.style.ThemeOverlay_MaterialPink,
        "MATERIAL_PURPLE" to R.style.ThemeOverlay_MaterialPurple,
        "MATERIAL_BLUE" to R.style.ThemeOverlay_MaterialBlue,
        "MATERIAL_GREEN" to R.style.ThemeOverlay_MaterialGreen,
    )

    val colorTheme get() = if (isSystemAccent) "SYSTEM" else PrefManager.themeColor
    val colorThemeStyleRes: Int
        @StyleRes get() = colorThemeMap[colorTheme] ?: R.style.ThemeOverlay_MaterialDefault
}