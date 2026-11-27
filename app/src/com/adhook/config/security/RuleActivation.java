package com.adhook.config.security;

import com.adhook.config.model.CustomRule;

/**
 * 规则来源开关的唯一判定口径，供 Hook 引擎共用。
 *
 * 两个来源语义：
 * - picker（本地拾取，source 为空也视作本地）：受「本地规则」总开关控制；
 * - remote（ADHook 远程订阅投影）：启用状态由订阅自身持久化的 enabled 字段决定，不受本地总开关影响。
 *
 * 引擎手写过滤条件必然漂移，因此统一收敛到本类。
 */
public final class RuleActivation {

    private RuleActivation() {
    }

    public static boolean isActive(CustomRule rule, boolean localEnabled) {
        if (rule == null || !rule.isEnabled()) return false;
        if (CustomRule.SOURCE_REMOTE.equals(rule.getSource())) return true;
        return localEnabled;
    }

    /** Hook 引擎只执行 ACTION_COLLAPSE 折叠。 */
    public static boolean isHookActionable(CustomRule rule, boolean localEnabled) {
        return isActive(rule, localEnabled) && CustomRule.ACTION_COLLAPSE.equals(rule.getAction());
    }
}
