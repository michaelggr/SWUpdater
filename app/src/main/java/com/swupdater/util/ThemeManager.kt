package com.swupdater.util

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题管理器
 * 支持三种主题模式：
 * - GAME: 游戏元素风格（深蓝紫+金色主题）
 * - SIMPLE: 简洁风格（深灰+蓝色主题）
 * - SYSTEM: 跟随系统
 */
object ThemeManager {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    const val THEME_GAME = "game"
    const val THEME_SIMPLE = "simple"
    const val THEME_SYSTEM = "system"

    fun getThemeMode(@NonNull context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME_MODE, THEME_GAME) ?: THEME_GAME
    }

    fun setThemeMode(@NonNull context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: String = THEME_GAME) {
        when (mode) {
            THEME_GAME -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            THEME_SIMPLE -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            THEME_SYSTEM -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    fun getThemeDisplayName(mode: String): String {
        return when (mode) {
            THEME_GAME -> "游戏风格"
            THEME_SIMPLE -> "简洁风格"
            THEME_SYSTEM -> "跟随系统"
            else -> "游戏风格"
        }
    }

    val THEME_OPTIONS = arrayOf("游戏风格", "简洁风格", "跟随系统")
    val THEME_VALUES = arrayOf(THEME_GAME, THEME_SIMPLE, THEME_SYSTEM)
}