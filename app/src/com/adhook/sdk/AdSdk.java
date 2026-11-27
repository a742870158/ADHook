package com.adhook.sdk;

/**
 * 广告 SDK 类名前缀表与识别工具。
 * 综合穿山甲、优量汇、快手、AnyThink/TopOn、百度、Sigmob、倍孜等国内外主流及聚合广告平台。
 */
public final class AdSdk {

    private AdSdk() {
    }

    public static final String[] AD_CLASS_PREFIXES = {
            // ── 聚合平台 ──────────────────────────────────────────
            "com.anythink.",
            "com.topon.",
            "com.tradplus.ads.",
            "com.applovin.",
            "com.ironsource.",
            // ── 主流广告联盟 ──────────────────────────────────────
            "com.bytedance.sdk.openadsdk.",
            "com.bytedance.msdk.",
            "com.byazt.",
            "com.qq.e.",
            "com.kwad.sdk.",
            "com.kwad.components.",
            "com.baidu.mobads.",
            "com.alimm.tanx.",
            "com.noah.",
            // ── 海外主流广告联盟 ──────────────────────────────────
            "com.google.android.gms.ads.",
            "com.facebook.ads.",
            "com.unity3d.ads.",
            "com.vungle.ads.",
            "com.mbridge.msdk.",
            // ── 常见中长尾广告联盟与手机厂商广告 ──────────────────
            "com.sigmob.sdk.",
            "com.sigmob.windad.",
            "com.miui.zeus.mimo.",
            "com.beizi.ad.",
            "com.beizi.fusion.",
            "com.octopus.ad.",
            "com.meishu.sdk.",
            "com.mcto.sspsdk.",
            "com.wangmai.",
            "com.smartdigimkt.",
            "com.qy.sdk.",
            "com.ptg.ptgapi.",
            "com.adgain.sdk.",
            "com.huanxiao.sdk.",
            "cj.mobile."
    };

    /**
     * 判断类名是否属于广告 SDK。
     */
    public static boolean isAdClass(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        for (String prefix : AD_CLASS_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
