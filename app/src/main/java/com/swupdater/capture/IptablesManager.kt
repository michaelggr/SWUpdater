package com.swupdater.capture

import android.util.Log
import com.swupdater.util.AppLog

object IptablesManager {

    private const val TAG = "IptablesMgr"
    private const val CHAIN_NAME = "SWUPDATER_CAPTURE"

    @Volatile
    private var rulesApplied = false

    fun setupRedirect(proxyPort: Int = 8080, gameUid: Int? = null): Boolean {
        if (rulesApplied) {
            AppLog.d(TAG, "iptables 规则已存在，跳过")
            return true
        }

        val commands = mutableListOf<String>()

        commands.add("iptables -t nat -N $CHAIN_NAME 2>/dev/null || true")

        if (gameUid != null) {
            commands.add(
                "iptables -t nat -A $CHAIN_NAME -m owner --uid-owner $gameUid -p tcp --dport 443 -j REDIRECT --to-port $proxyPort"
            )
            AppLog.i(TAG, "设置 iptables 规则（仅游戏 UID=$gameUid）→ 端口 $proxyPort")
        } else {
            commands.add(
                "iptables -t nat -A $CHAIN_NAME -p tcp --dport 443 -j REDIRECT --to-port $proxyPort"
            )
            AppLog.i(TAG, "设置 iptables 规则（全部 HTTPS 流量）→ 端口 $proxyPort")
        }

        commands.add("iptables -t nat -A OUTPUT -p tcp -j $CHAIN_NAME")

        val (success, output) = executeRootCommands(commands)
        if (success) {
            rulesApplied = true
            AppLog.i(TAG, "iptables 规则设置成功")
        } else {
            AppLog.e(TAG, "iptables 规则设置失败: $output")
        }
        return success
    }

    fun cleanupRedirect(): Boolean {
        if (!rulesApplied) return true

        val proxyPort = 8080

        val commands = listOf(
            "iptables -t nat -D OUTPUT -p tcp -j $CHAIN_NAME 2>/dev/null || true",
            "iptables -t nat -F $CHAIN_NAME 2>/dev/null || true",
            "iptables -t nat -X $CHAIN_NAME 2>/dev/null || true"
        )

        val (success, output) = executeRootCommands(commands)
        if (success) {
            rulesApplied = false
            AppLog.i(TAG, "iptables 规则清理成功")
        } else {
            AppLog.e(TAG, "iptables 规则清理失败: $output")
        }
        return success
    }

    fun getGameUid(): Int? {
        val gamePackages = listOf(
            "com.com2us.smon.normal.freefull.google.kr.android.common",
            "com.com2us.smon.normal.freefull.google.kr.android.official",
            "com.com2us.smon.normal.freefull.google.kr.android"
        )

        for (pkg in gamePackages) {
            val (success, output) = executeRootCommand("dumpsys package $pkg | grep userId=")
            if (success && output.contains("userId=")) {
                val uid = output.substringAfter("userId=").trim().substringBefore(" ").toIntOrNull()
                if (uid != null) {
                    AppLog.i(TAG, "游戏 UID: $uid (包名: $pkg)")
                    return uid
                }
            }
        }

        AppLog.w(TAG, "未找到游戏 UID，将拦截全部 HTTPS 流量")
        return null
    }

    fun isRulesApplied(): Boolean = rulesApplied

    private fun executeRootCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            val result = if (exitCode == 0) output else error
            (exitCode == 0) to result
        } catch (e: Exception) {
            false to (e.message ?: "执行失败")
        }
    }

    private fun executeRootCommands(commands: List<String>): Pair<Boolean, String> {
        val combinedCommand = commands.joinToString(" ; ")
        return executeRootCommand(combinedCommand)
    }
}
