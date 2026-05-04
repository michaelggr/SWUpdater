package com.swupdater.ui

import android.content.Context
import android.text.Spanned
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * 统一的 Material Design 风格对话框和提示工具类
 */
object DialogHelper {

    // ==================== 确认对话框 ====================
    fun showConfirmDialog(
        context: Context,
        @StringRes title: Int,
        @StringRes message: Int,
        @StringRes positiveText: Int = android.R.string.ok,
        @StringRes negativeText: Int = android.R.string.cancel,
        positiveListener: (() -> Unit)? = null,
        negativeListener: (() -> Unit)? = null
    ) {
        showConfirmDialog(
            context,
            context.getString(title),
            context.getString(message),
            context.getString(positiveText),
            context.getString(negativeText),
            positiveListener,
            negativeListener
        )
    }

    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        positiveText: String = "确定",
        negativeText: String = "取消",
        positiveListener: (() -> Unit)? = null,
        negativeListener: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ ->
                positiveListener?.invoke()
            }
            .setNegativeButton(negativeText) { _, _ ->
                negativeListener?.invoke()
            }
            .show()
    }

    // ==================== 信息对话框 ====================
    fun showInfoDialog(
        context: Context,
        @StringRes title: Int,
        @StringRes message: Int,
        @StringRes buttonText: Int = android.R.string.ok,
        listener: (() -> Unit)? = null
    ) {
        showInfoDialog(
            context,
            context.getString(title),
            context.getString(message),
            context.getString(buttonText),
            listener
        )
    }

    fun showInfoDialog(
        context: Context,
        title: String,
        message: String,
        buttonText: String = "确定",
        listener: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(buttonText) { _, _ ->
                listener?.invoke()
            }
            .show()
    }

    fun showInfoDialogWithHtml(
        context: Context,
        @StringRes title: Int,
        message: Spanned,
        @StringRes buttonText: Int = android.R.string.ok,
        listener: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(buttonText) { _, _ ->
                listener?.invoke()
            }
            .show()
    }

    // ==================== 带选项的对话框 ====================
    fun showOptionsDialog(
        context: Context,
        @StringRes title: Int,
        options: Array<String>,
        onOptionSelected: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(options) { _, which ->
                onOptionSelected(which)
            }
            .show()
    }

    // ==================== Snackbar ====================
    fun showSnackbar(
        view: View,
        @StringRes message: Int,
        duration: Int = Snackbar.LENGTH_SHORT
    ) {
        Snackbar.make(view, message, duration).show()
    }

    fun showSnackbar(
        view: View,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT
    ) {
        Snackbar.make(view, message, duration).show()
    }

    fun showSnackbarWithAction(
        view: View,
        @StringRes message: Int,
        @StringRes actionText: Int,
        duration: Int = Snackbar.LENGTH_LONG,
        actionListener: () -> Unit
    ) {
        Snackbar.make(view, message, duration)
            .setAction(actionText) { actionListener() }
            .show()
    }

    fun showSnackbarWithAction(
        view: View,
        message: String,
        actionText: String,
        duration: Int = Snackbar.LENGTH_LONG,
        actionListener: () -> Unit
    ) {
        Snackbar.make(view, message, duration)
            .setAction(actionText) { actionListener() }
            .show()
    }
}
