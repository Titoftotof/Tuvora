package com.nuvio.tv.core.network

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Private helper in a separate process: the old process must die before opening the new space. */
class WorkspaceRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldPid = intent.getIntExtra("old_pid", -1)
        val deadline = intent.getLongExtra("restart_deadline", 0)
        if (oldPid <= 0 || oldPid == Process.myPid() ||
            android.os.SystemClock.elapsedRealtime() >= deadline) { finish(); return }
        Process.killProcess(oldPid)
        Handler(Looper.getMainLooper()).postDelayed({
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            try {
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } finally {
                finish()
                Process.killProcess(Process.myPid())
            }
        }, 300)
    }

    companion object {
        fun isRestartProcess(context: Context): Boolean {
            val name = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
                else (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            return name?.endsWith(":workspace_restart") == true
        }

        suspend fun launch(context: Context): Nothing = awaitWorkspaceProcessExit {
            context.startActivity(
                Intent(context, WorkspaceRestartActivity::class.java)
                    .putExtra("old_pid", Process.myPid())
                    .putExtra("restart_deadline", android.os.SystemClock.elapsedRealtime() + 4000)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }
}
