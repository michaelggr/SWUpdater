package com.swupdater.util

object RootCommandUtil {

    private const val TAG = "RootCmd"

    fun executeRootCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            val result = if (exitCode == 0) output else error
            (exitCode == 0) to result
        } catch (e: Exception) {
            AppLog.e(TAG, "Root命令执行异常: $command", e)
            false to (e.message ?: "执行失败")
        }
    }

    fun executeRootCommands(
        commands: List<String>,
        tolerateErrorPatterns: List<String> = listOf("|| true", "2>/dev/null")
    ): Pair<Boolean, String> {
        val sb = StringBuilder()
        var allSuccess = true
        for (command in commands) {
            val (success, output) = executeRootCommand(command)
            val shouldTolerate = tolerateErrorPatterns.any { pattern -> command.contains(pattern) }

            if (!success && !shouldTolerate) {
                allSuccess = false
                sb.append("FAIL: $command -> $output\n")
            }
        }
        return allSuccess to sb.toString()
    }
}
