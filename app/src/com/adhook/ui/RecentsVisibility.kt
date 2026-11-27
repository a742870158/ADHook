package com.adhook.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/** 动态控制应用任务在系统近期任务列表中的可见性，避免误划掉后台进程。 */
internal fun applyExcludeFromRecents(activity: Activity, exclude: Boolean) {
    runCatching {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        manager.appTasks?.forEach { task -> task.setExcludeFromRecents(exclude) }
    }
}
