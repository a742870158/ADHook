package com.adhook.ui.data

/** Pure presentation policy: absence is unknown, never a fabricated zero or proof of effectiveness. */
data class FeatureBadge(val text: String, val error: Boolean = false)

fun featureBadge(enabled: Boolean, count: Int?, registration: String?, implemented: Boolean = true,
                 legacy: Boolean = false): FeatureBadge = when {
    !implemented -> FeatureBadge("暂未适配")
    !enabled -> FeatureBadge("已关闭")
    legacy -> FeatureBadge(if (count != null) "旧版记录 ${count.coerceAtLeast(0)}" else "旧版未采集")
    registration == "FAILED" -> FeatureBadge(
        if (count != null && count > 0) "挂载异常 · 已拦截 $count" else "挂载异常", true)
    registration == "DISABLED" -> FeatureBadge("待重启应用")
    count != null -> FeatureBadge("已拦截 ${count.coerceAtLeast(0)}")
    registration == "OK" -> FeatureBadge("已注册 · 未采集")
    else -> FeatureBadge("未上报")
}
