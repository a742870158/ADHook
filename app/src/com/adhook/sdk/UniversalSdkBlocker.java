package com.adhook.sdk;

import com.adhook.core.AdHookLog;

/**
 * 通用 SDK 拦截器总入口。
 * 每次调用都重新探测：同一 ClassLoader 可能在 Application.onCreate 或解壳后追加 dex。
 * 幂等由每个子层的成功 Method/Constructor 注册表保证；“探测过”不能等同于“安装完成”。
 * 联盟 SDK 拦截不再受全局开关控制：应用详情页打开 Hook 开关即全量挂载。
 */
public final class UniversalSdkBlocker {
    private UniversalSdkBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        for (String layer : new String[]{"activity", "sensor", "webview", "pangle", "gdt", "kwad", "baidu",
                "google", "facebook", "sigmob", "topon", "mintegral", "mimo"}) {
            SdkRuntime.bind(layer);
        }
        installSafely("activity", UniversalActivityBlocker::install);
        installSafely("sensor", SensorAdBlocker::install);
        installSafely("webview", com.adhook.core.UniversalWebViewBlocker::install);

        installSafely("pangle", () -> PangleBlocker.install(classLoader));
        installSafely("gdt", () -> GdtBlocker.install(classLoader));
        installSafely("kwad", () -> KwadBlocker.install(classLoader));
        installSafely("baidu", () -> BaiduMobadsBlocker.install(classLoader));
        installSafely("google", () -> GoogleAdMobBlocker.install(classLoader));
        installSafely("facebook", () -> FacebookAdsBlocker.install(classLoader));
        installSafely("sigmob", () -> SigmobBlocker.install(classLoader));
        installSafely("topon", () -> TopOnBlocker.install(classLoader));
        installSafely("mintegral", () -> MintegralBlocker.install(classLoader));
        installSafely("mimo", () -> MimoBlocker.install(classLoader));
    }

    private static void installSafely(String name, Runnable installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            // 一个 SDK/框架 Hook 异常不阻断其他 SDK 的安装，也不封死下一次重试。
            AdHookLog.e("[UniversalSdkBlocker] " + name + " install failed", t);
        }
    }
}
