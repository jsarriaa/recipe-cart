package com.lechugones.cocina.util

import android.content.Context
import android.content.Intent

/** Restarts the app process, e.g. after a restore replaced the database files (SPEC § 4.7). */
object ProcessRestarter {
    fun restart(context: Context) {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return
        context.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }
}
