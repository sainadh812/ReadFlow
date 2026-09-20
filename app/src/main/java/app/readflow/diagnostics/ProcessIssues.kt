package app.readflow.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

suspend fun recoverProcessIssues(context: Context, issues: IssueLogs) {
    try {
        val seen = context.getSharedPreferences("issue_exit_history", Context.MODE_PRIVATE)
        val floor = maxOf(seen.getLong("through", 0), context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime)
        val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 10)
        for (exit in exits.filter { it.timestamp > floor }.sortedBy { it.timestamp }) {
            if (exit.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)) {
                val id = issues.record("ANDROID_PROCESS_EXIT", IllegalStateException("Android process exit reason ${exit.reason}"),
                    input = IssueInput(details = mapOf("exitTimestampMs" to exit.timestamp.toString(), "androidReason" to exit.reason.toString(),
                        "status" to exit.status.toString(), "processName" to exit.processName, "pssKiB" to exit.pss.toString(), "rssKiB" to exit.rss.toString(),
                        "inputAvailability" to "Input lost with process death; successful work is not logged to disk")),
                    dedupeKey = "exit-${exit.timestamp}-${exit.reason}")
                if (id == null) break
            }
            seen.edit().putLong("through", exit.timestamp).apply()
        }
    } catch (_: Exception) { /* OS history is optional and must not prevent startup. */ }
}
