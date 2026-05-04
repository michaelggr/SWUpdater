package com.swupdater.capture

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

        // IPv4 规则
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

        // IPv6 规则（双栈网络环境下防止 IPv6 流量绕过代理）
        val ip6ChainName = "${CHAIN_NAME}_6"
        commands.add("ip6tables -t nat -N $ip6ChainName 2>/dev/null || true")

        if (gameUid != null) {
            commands.add(
                "ip6tables -t nat -A $ip6ChainName -m owner --uid-owner $gameUid -p tcp --dport 443 -j REDIRECT --to-port $proxyPort"
            )
        } else {
            commands.add(
                "ip6tables -t nat -A $ip6ChainName -p tcp --dport 443 -j REDIRECT --to-port $proxyPort"
            )
        }
        commands.add("ip6tables -t nat -A OUTPUT -p tcp -j $ip6ChainName")

        val (success, output) = executeRootCommands(commands)
        if (success) {
            rulesApplied = true
            AppLog.i(TAG, "iptables/ip6tables 规则设置成功")
        } else {
            AppLog.e(TAG, "iptables 规则设置失败: $output")
        }
        return success
    }

    fun cleanupRedirect(): Boolean {
        if (!rulesApplied) return true

        val ip6ChainName = "${CHAIN_NAME}_6"

        val commands = listOf(
            // 清理 IPv4 规则
            "iptables -t nat -D OUTPUT -p tcp -j $CHAIN_NAME 2>/dev/null || true",
            "iptables -t nat -F $CHAIN_NAME 2>/dev/null || true",
            "iptables -t nat -X $CHAIN_NAME 2>/dev/null || true",
            // 清理 IPv6 规则
            "ip6tables -t nat -D OUTPUT -p tcp -j $ip6ChainName 2>/dev/null || true",
            "ip6tables -t nat -F $ip6ChainName 2>/dev/null || true",
            "ip6tables -t nat -X $ip6ChainName 2>/dev/null || true"
        )

        val (success, output) = executeRootCommands(commands)
        if (success) {
            rulesApplied = false
            AppLog.i(TAG, "iptables/ip6tables 规则清理成功")
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
        // 逐条执行，每条命令独立判断成功与否
        // 不用 && 连接，因为部分命令含 || true 会导致优先级混乱
        val sb = StringBuilder()
        var allSuccess = true
        for (command in commands) {
            val (success, output) = executeRootCommand(command)
            if (!success && !command.contains("|| true")) {
                allSuccess = false
                sb.append("FAIL: $command → $output\n")
            }
        }
        return allSuccess to sb.toString()
    }
}
