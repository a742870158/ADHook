package com.adhook.ui

import com.adhook.ui.data.*
import org.junit.Assert.*
import org.junit.Test

class FeatureBadgeTest {
    @Test fun absentCountersAreNotFabricatedZerosOrProofOfEffectiveness() {
        assertEquals("未上报", featureBadge(true, null, null).text)
        assertEquals("已注册 · 未采集", featureBadge(true, null, "OK").text)
        assertEquals("已拦截 0", featureBadge(true, 0, "OK").text)
        assertEquals("已拦截 12", featureBadge(true, 12, "OK").text)
    }

    @Test fun failuresAreVisibleEvenWhenAnEarlierActionHasHits() {
        val badge = featureBadge(true, 12, "FAILED")
        assertTrue(badge.error)
        assertEquals("挂载异常 · 已拦截 12", badge.text)
        assertEquals("挂载异常", featureBadge(true, null, "FAILED").text)
    }

    @Test fun savedPreferenceAndLastRuntimeStateAreNotConflated() {
        assertEquals("已关闭", featureBadge(false, 12, "OK").text)
        assertEquals("待重启应用", featureBadge(true, 12, "DISABLED").text)
    }

    @Test fun legacyCountsAreNotPresentedAsFreshVerifiedActions() {
        assertEquals("旧版记录 9", featureBadge(true, 9, "OK", legacy = true).text)
        assertEquals("旧版未采集", featureBadge(true, null, "OK", legacy = true).text)
    }

    @Test fun anUnimplementedPlaceholderIsNotAnActionableSwitchOrAnOldFalsePositive() {
        assertEquals("暂未适配", featureBadge(true, 100, "OK", false).text)
        assertEquals("暂未适配", featureBadge(false, null, null, false).text)
    }
}
