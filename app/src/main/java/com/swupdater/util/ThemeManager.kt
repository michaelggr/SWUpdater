package com.swupdater.util

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.swupdater.R

/**
 * 主题管理器
 * 支持4套主题风格：
 * - GAME: 魔灵风格（深蓝紫+金色，游戏元素边框）
 * - SIMPLE: 简洁风格（深灰+蓝色，扁平化无边框）
 * - NIGHT: 暗夜风格（纯黑+绿色，OLED友好）
 * - SAKURA: 樱花风格（深紫红+粉色，柔和温暖）
 */
object ThemeManager {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME_STYLE = "theme_style"

    const val THEME_GAME = "game"
    const val THEME_SIMPLE = "simple"
    const val THEME_NIGHT = "night"
    const val THEME_SAKURA = "sakura"

    /**
     * 获取当前主题风格
     */
    fun getThemeStyle(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME_STYLE, THEME_GAME) ?: THEME_GAME
    }

    /**
     * 设置主题风格
     */
    fun setThemeStyle(context: Context, style: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_STYLE, style).apply()
    }

    /**
     * 在Activity的onCreate中调用，在setContentView之前
     * 根据保存的主题设置应用对应样式
     */
    fun applyTheme(activity: AppCompatActivity) {
        val style = getThemeStyle(activity)
        val themeResId = when (style) {
            THEME_GAME -> R.style.Theme_SWUpdater_Game
            THEME_SIMPLE -> R.style.Theme_SWUpdater_Simple
            THEME_NIGHT -> R.style.Theme_SWUpdater_Night
            THEME_SAKURA -> R.style.Theme_SWUpdater_Sakura
            else -> R.style.Theme_SWUpdater_Game
        }
        activity.setTheme(themeResId)
    }

    /**
     * 获取主题显示名称
     */
    fun getThemeDisplayName(style: String): String {
        return when (style) {
            THEME_GAME -> "魔灵风格"
            THEME_SIMPLE -> "简洁风格"
            THEME_NIGHT -> "暗夜风格"
            THEME_SAKURA -> "樱花风格"
            else -> "魔灵风格"
        }
    }

    /**
     * 获取主题描述
     */
    fun getThemeDescription(style: String): String {
        return when (style) {
            THEME_GAME -> "深蓝紫+金色，游戏元素边框"
            THEME_SIMPLE -> "深灰+蓝色，扁平化无边框"
            THEME_NIGHT -> "纯黑+绿色，OLED友好省电"
            THEME_SAKURA -> "深紫红+粉色，柔和温暖"
            else -> "深蓝紫+金色，游戏元素边框"
        }
    }

    /**
     * 所有主题选项（显示名）
     */
    val THEME_OPTIONS = arrayOf("魔灵风格", "简洁风格", "暗夜风格", "樱花风格")

    /**
     * 所有主题值
     */
    val THEME_VALUES = arrayOf(THEME_GAME, THEME_SIMPLE, THEME_NIGHT, THEME_SAKURA)
}