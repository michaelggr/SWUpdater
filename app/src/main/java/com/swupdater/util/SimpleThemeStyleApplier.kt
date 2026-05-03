package com.swupdater.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.swupdater.R

/**
 * 简洁主题样式应用器
 * 在运行时将游戏风格的 View 样式覆盖为简洁风格
 */
object SimpleThemeStyleApplier {

    private const val THEME_PREFS = "theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    fun applySimpleTheme(view: View) {
        val context = view.context
        if (ThemeManager.getThemeMode(context) != ThemeManager.THEME_SIMPLE) {
            return
        }

        when (view) {
            is MaterialCardView -> applySimpleCardStyle(view)
            is MaterialButton -> applySimpleButtonStyle(view)
            is ProgressBar -> applySimpleProgressStyle(view)
            is Button -> applySimpleButtonStyle(view)
        }
    }

    fun applySimpleCardStyle(card: MaterialCardView) {
        card.setCardBackgroundColor(ContextCompat.getColor(card.context, R.color.simple_surface))
        card.cardElevation = dpToPx(card.context, 2f)
        card.radius = dpToPx(card.context, 12f)
        card.strokeWidth = 0
    }

    fun applySimpleButtonStyle(button: MaterialButton) {
        val context = button.context
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.simple_secondary))
        button.setTextColor(Color.WHITE)
        button.cornerRadius = dpToPx(context, 8f)
    }

    fun applySimpleProgressStyle(progressBar: ProgressBar) {
        val context = progressBar.context
        progressBar.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.simple_secondary))
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.simple_divider))
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    fun getSimpleColors(context: Context): SimpleColors {
        return SimpleColors(
            primary = ContextCompat.getColor(context, R.color.simple_primary),
            secondary = ContextCompat.getColor(context, R.color.simple_secondary),
            background = ContextCompat.getColor(context, R.color.simple_background),
            surface = ContextCompat.getColor(context, R.color.simple_surface),
            onSurface = ContextCompat.getColor(context, R.color.simple_on_surface),
            divider = ContextCompat.getColor(context, R.color.simple_divider),
            cardBackground = ContextCompat.getColor(context, R.color.simple_card_background),
            progressBg = ContextCompat.getColor(context, R.color.simple_progress_background),
            progressFg = ContextCompat.getColor(context, R.color.simple_progress_foreground),
            success = ContextCompat.getColor(context, R.color.simple_success),
            warning = ContextCompat.getColor(context, R.color.simple_warning),
            error = ContextCompat.getColor(context, R.color.simple_error),
            info = ContextCompat.getColor(context, R.color.simple_info)
        )
    }

    data class SimpleColors(
        val primary: Int,
        val secondary: Int,
        val background: Int,
        val surface: Int,
        val onSurface: Int,
        val divider: Int,
        val cardBackground: Int,
        val progressBg: Int,
        val progressFg: Int,
        val success: Int,
        val warning: Int,
        val error: Int,
        val info: Int
    )
}