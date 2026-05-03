package com.swupdater.ui

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.swupdater.R

object SnackbarHelper {

    fun success(view: View, message: String, duration: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return make(view, message, duration, SnackbarType.SUCCESS)
    }

    fun error(view: View, message: String, duration: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return make(view, message, duration, SnackbarType.ERROR)
    }

    fun warning(view: View, message: String, duration: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return make(view, message, duration, SnackbarType.WARNING)
    }

    fun info(view: View, message: String, duration: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return make(view, message, duration, SnackbarType.INFO)
    }

    fun show(view: View, message: String, duration: Int = Snackbar.LENGTH_SHORT): Snackbar {
        return make(view, message, duration, SnackbarType.DEFAULT)
    }

    private fun make(view: View, message: String, duration: Int, type: SnackbarType): Snackbar {
        val snackbar = Snackbar.make(view, message, duration)
        styleSnackbar(snackbar, type)
        return snackbar
    }

    private fun styleSnackbar(snackbar: Snackbar, type: SnackbarType) {
        val snackbarView = snackbar.view
        val context = snackbarView.context

        when (type) {
            SnackbarType.SUCCESS -> {
                snackbarView.setBackgroundColor(ContextCompat.getColor(context, R.color.success))
                styleTextAndAction(snackbarView, android.R.color.white)
            }
            SnackbarType.ERROR -> {
                snackbarView.setBackgroundColor(ContextCompat.getColor(context, R.color.error))
                styleTextAndAction(snackbarView, android.R.color.white)
            }
            SnackbarType.WARNING -> {
                snackbarView.setBackgroundColor(ContextCompat.getColor(context, R.color.warning))
                styleTextAndAction(snackbarView, android.R.color.white)
            }
            SnackbarType.INFO -> {
                snackbarView.setBackgroundColor(ContextCompat.getColor(context, R.color.info))
                styleTextAndAction(snackbarView, android.R.color.white)
            }
            SnackbarType.DEFAULT -> {
                styleTextAndAction(snackbarView, R.color.text_primary)
            }
        }
    }

    private fun styleTextAndAction(snackbarView: View, textColorRes: Int) {
        val textColor = ContextCompat.getColor(snackbarView.context, textColorRes)
        val actionColor = textColor

        snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
            setTextColor(textColor)
        }
        snackbarView.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)?.apply {
            setTextColor(actionColor)
        }
    }

    private enum class SnackbarType {
        DEFAULT, SUCCESS, ERROR, WARNING, INFO
    }
}
