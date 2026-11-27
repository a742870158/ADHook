package com.adhook.sdk;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 谷歌广告 (Google AdMob / Google Mobile Ads SDK) 通用拦截器。
 * 覆盖开屏/应用开启广告 (AppOpenAd)、横幅 (AdView)、原生广告 (AdLoader)、
 * 插屏 (InterstitialAd) 以及激励视频 (RewardedAd)。
 *
 * 回调识别说明（重要）：宿主几乎都用匿名内部类 / Lambda 实现
 * AppOpenAdLoadCallback、InterstitialAdLoadCallback、RewardedAdLoadCallback，
 * 其运行时类名形如 MainActivity$1，绝不包含 Callback/Listener；
 * 因此这里统一交给 AdCallbackHelper 按「声明参数类型 + 运行时接口/父类 + 方法名多签名」识别，
 * 保证静态 load 被拦截后宿主的 onAdFailedToLoad 一定能收到，避免开屏/插屏流程死等。
 */
public final class GoogleAdMobBlocker {

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private GoogleAdMobBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("google");

        try {
            Class<?> mobileAds = HookHelpers.findClassIfExists("com.google.android.gms.ads.MobileAds", classLoader);
            Class<?> baseAdView = HookHelpers.findClassIfExists("com.google.android.gms.ads.BaseAdView", classLoader);
            Class<?> appOpenAd = HookHelpers.findClassIfExists("com.google.android.gms.ads.appopen.AppOpenAd", classLoader);

            if (mobileAds == null && baseAdView == null && appOpenAd == null) {
                return;
            }

            AdHookLog.i("[GoogleAdMobBlocker] Detected Google AdMob SDK in classLoader=" + classLoader);
            hookAppOpenAd(classLoader);
            hookAdViews(classLoader);
            hookAdLoader(classLoader);
            hookInterstitialAndRewarded(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[GoogleAdMobBlocker] install failed", t);
        }
    }

    /**
     * 拦截开屏与应用打开广告 (AppOpenAd.load)。
     * load 为静态方法，没有 thisObject 可回退查字段，只能依赖入参回调。
     */
    private static void hookAppOpenAd(final ClassLoader classLoader) {
        Class<?> appOpenAd = HookHelpers.findClassIfExists("com.google.android.gms.ads.appopen.AppOpenAd", classLoader);
        if (appOpenAd == null) {
            return;
        }

        for (final Method method : appOpenAd.getDeclaredMethods()) {
            if (!"load".equals(method.getName())) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(method, new HookCallback() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SdkRuntime.Decision accounting = SdkRuntime.decision("google");
                        if (accounting == null) return;
                        AdHookLog.d("[GoogleAdMobBlocker] Intercepted AppOpenAd.load");
                        AdCallbackHelper.setNullLikeResult(param);
                        accounting.hit();
                        AdCallbackHelper.notifyParamCallbacks(param, 3, classLoader);
                    }
                });
                AdHookLog.i("[GoogleAdMobBlocker] Hooked AppOpenAd.load");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 拦截 Banner 横幅与自适应广告 (AdView / BaseAdView.loadAd)。
     * 此类调用的 listener 在字段里，按字段类型 / 运行时接口识别。
     */
    private static void hookAdViews(final ClassLoader classLoader) {
        String[] viewClasses = new String[]{
                "com.google.android.gms.ads.BaseAdView",
                "com.google.android.gms.ads.AdView"
        };

        for (String clsName : viewClasses) {
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
                            SdkRuntime.Decision accounting = SdkRuntime.decision("google");
                            if (accounting == null) return;
                            AdHookLog.d("[GoogleAdMobBlocker] Intercepted " + clazz.getSimpleName() + ".loadAd");
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();
                            if (!AdCallbackHelper.notifyParamCallbacks(param, 3, classLoader)) {
                                AdCallbackHelper.notifyFieldCallbacks(param.thisObject, 3, classLoader);
                            }
                        }
                    });
                    AdHookLog.i("[GoogleAdMobBlocker] Hooked " + clazz.getSimpleName() + ".loadAd");
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 拦截原生广告加载 (AdLoader.loadAd / loadAds)。
     */
    private static void hookAdLoader(final ClassLoader classLoader) {
        Class<?> adLoader = HookHelpers.findClassIfExists("com.google.android.gms.ads.AdLoader", classLoader);
        if (adLoader == null) {
            return;
        }

        for (final Method method : adLoader.getDeclaredMethods()) {
            final String name = method.getName();
            if (!"loadAd".equals(name) && !"loadAds".equals(name)) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(method, new HookCallback() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SdkRuntime.Decision accounting = SdkRuntime.decision("google");
                        if (accounting == null) return;
                        AdHookLog.d("[GoogleAdMobBlocker] Intercepted AdLoader." + name);
                        AdCallbackHelper.setNullLikeResult(param);
                        accounting.hit();
                        if (!AdCallbackHelper.notifyParamCallbacks(param, 3, classLoader)) {
                            AdCallbackHelper.notifyFieldCallbacks(param.thisObject, 3, classLoader);
                        }
                    }
                });
                AdHookLog.i("[GoogleAdMobBlocker] Hooked AdLoader." + name);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 拦截插屏与激励广告 (InterstitialAd.load, RewardedAd.load)。
     * 同为静态方法，回调必须从入参识别。
     */
    private static void hookInterstitialAndRewarded(final ClassLoader classLoader) {
        String[] adClasses = new String[]{
                "com.google.android.gms.ads.interstitial.InterstitialAd",
                "com.google.android.gms.ads.rewarded.RewardedAd",
                "com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd"
        };

        for (String clsName : adClasses) {
            final Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            for (final Method method : clazz.getDeclaredMethods()) {
                if (!"load".equals(method.getName())) {
                    continue;
                }
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("google");
                            if (accounting == null) return;
                            AdHookLog.d("[GoogleAdMobBlocker] Intercepted " + clazz.getSimpleName() + ".load");
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();
                            AdCallbackHelper.notifyParamCallbacks(param, 3, classLoader);
                        }
                    });
                    AdHookLog.i("[GoogleAdMobBlocker] Hooked " + clazz.getSimpleName() + ".load");
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
