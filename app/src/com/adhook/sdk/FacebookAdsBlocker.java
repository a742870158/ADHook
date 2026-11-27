package com.adhook.sdk;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * Meta·Facebook 广告 (Meta Audience Network / Facebook Ads SDK) 通用拦截器。
 * 覆盖横幅 (AdView)、插屏 (InterstitialAd)、激励视频 (RewardedVideoAd) 与原生广告 (NativeAd)。
 *
 * 失败回调说明：onError(Ad, AdError) 这类回调需要「广告实例 + 非 null 的 AdError」，
 * AdError 由 AdCallbackHelper 反射构造；构造失败时跳过该重载并记录告警，
 * 绝不把 null 当作错误对象传给宿主（否则宿主回调里 error.getCode() 直接 NPE）。
 */
public final class FacebookAdsBlocker {

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    /** Facebook SDK 的 NO_FILL 错误码。 */
    private static final int ERROR_CODE_NO_FILL = 1001;

    private FacebookAdsBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("facebook");

        try {
            Class<?> audienceNetworkAds = HookHelpers.findClassIfExists("com.facebook.ads.AudienceNetworkAds", classLoader);
            Class<?> adView = HookHelpers.findClassIfExists("com.facebook.ads.AdView", classLoader);
            Class<?> interstitialAd = HookHelpers.findClassIfExists("com.facebook.ads.InterstitialAd", classLoader);

            if (audienceNetworkAds == null && adView == null && interstitialAd == null) {
                return;
            }

            AdHookLog.i("[FacebookAdsBlocker] Detected Facebook Audience Network in classLoader=" + classLoader);
            hookAds(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[FacebookAdsBlocker] install failed", t);
        }
    }

    private static void hookAds(final ClassLoader classLoader) {
        String[] adClasses = new String[]{
                "com.facebook.ads.AdView",
                "com.facebook.ads.InterstitialAd",
                "com.facebook.ads.RewardedVideoAd",
                "com.facebook.ads.NativeAdBase",
                "com.facebook.ads.NativeAd",
                "com.facebook.ads.NativeBannerAd"
        };

        for (String clsName : adClasses) {
            final Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            for (final Method method : clazz.getDeclaredMethods()) {
                if (!"loadAd".equals(method.getName())) {
                    continue;
                }
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("facebook");
                            if (accounting == null) return;
                            AdHookLog.d("[FacebookAdsBlocker] Intercepted " + clazz.getSimpleName() + ".loadAd");
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();

                            // 优先入参识别（按声明类型 / 运行时接口，而非匿名类名），
                            // 再把当前广告实例作为 onError(Ad, AdError) 的 Ad 参数
                            boolean notified = false;
                            for (Object callback : AdCallbackHelper.collectParamCallbacks(param)) {
                                if (AdCallbackHelper.notifyFailure(
                                        callback, ERROR_CODE_NO_FILL, classLoader, param.thisObject)) {
                                    notified = true;
                                }
                            }
                            if (!notified) {
                                // listener 通常通过 setAdListener 保存在字段里
                                for (Object callback : AdCallbackHelper.collectFieldCallbacks(param.thisObject)) {
                                    if (AdCallbackHelper.notifyFailure(
                                            callback, ERROR_CODE_NO_FILL, classLoader, param.thisObject)) {
                                        notified = true;
                                    }
                                }
                            }
                            if (!notified) {
                                AdHookLog.w("[FacebookAdsBlocker] No callback could be notified for "
                                        + clazz.getSimpleName() + ".loadAd");
                            }
                        }
                    });
                    AdHookLog.i("[FacebookAdsBlocker] Hooked " + clazz.getSimpleName() + ".loadAd");
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
