package com.adhook.core;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.adhook.sdk.SdkHookRegistry;
import com.adhook.sdk.SdkRuntime;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.adhook.runtime.HookCallback;

/**
 * 通用 WebView 广告拦截与内嵌页面净化器。
 * 吸收 Zhiliao 与 PureNGA 的 Web 净化设计：
 * 1. 在 {@link WebViewClient#shouldInterceptRequest} 拦截已知广告脚本、Tracking 追踪与横幅物料；
 * 2. 在 {@link WebViewClient#onPageFinished} 向页面自动注入轻量 CSS 样式表，
 *    强力折叠常见 Web 广告卡片、悬浮吸顶/吸底诱导下载条与商单推荐位。
 */
public final class UniversalWebViewBlocker {

    private static final String TAG = "UniversalWebViewBlocker";
    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    /** 常见广告、追踪与推广域名关键字。 */
    private static final String[] AD_HOST_KEYWORDS = {
            "pos.baidu.com",
            "cpro.baidu.com",
            "pgdt.gtimg.cn",
            "adnet.qq.com",
            "pangolin-sdk",
            "toblog.ctobsnssdk.com",
            "ad.toutiao.com",
            "adservice.google.",
            "pagead2.googlesyndication.com",
            "atanx.alicdn.com",
            "tanx.com",
            "beacon.sina.com.cn",
            "sax.sina.com.cn"
    };

    /** 通用注入 CSS：彻底消除常见推广占位与悬浮引流。 */
    private static final String PURIFY_CSS =
            "javascript:(function() {" +
            "  var style = document.getElementById('adhook_purify_style');" +
            "  if (!style) {" +
            "    style = document.createElement('style');" +
            "    style.id = 'adhook_purify_style';" +
            "    style.innerHTML = '" +
            "      .ad-banner, .ad-box, .ad-wrap, .ad-card, [class*=\"advert\"], " +
            "      [class*=\"ad-slot\"], [id*=\"ad_container\"], .footer-banner, " +
            "      .app-download-bar, .open-in-app, .guide-download-box { " +
            "        display: none !important; height: 0 !important; visibility: hidden !important; " +
            "      }';" +
            "    document.head.appendChild(style);" +
            "  }" +
            "})()";

    private UniversalWebViewBlocker() {
    }

    public static void install() {
        SdkRuntime.bind("webview");
        try {
            // 成功方法才去重；setWebViewClient 失败不妨碍基础 client 兜底或下次重试。
            if (HOOKS.hookObserverMethod(
                    WebView.class.getDeclaredMethod("setWebViewClient", WebViewClient.class),
                    new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args[0] != null) {
                                hookClientClass(param.args[0].getClass());
                            }
                        }
                    })) {
                AdHookLog.i("[" + TAG + "] Hooked WebView.setWebViewClient");
            }
        } catch (Throwable t) {
            AdHookLog.e("[" + TAG + "] Failed to hook WebView.setWebViewClient", t);
        }
        hookClientClass(WebViewClient.class);
    }

    private static void hookClientClass(Class<?> clientClass) {
        if (clientClass == null) {
            return;
        }

        try {
            // WebResourceRequest is available throughout the supported Android 12+ range.
            try {
                HOOKS.hookObserverMethod(
                        clientClass.getDeclaredMethod("shouldInterceptRequest", WebView.class, WebResourceRequest.class),
                        new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length < 2) return;
                                WebResourceRequest req = (WebResourceRequest) param.args[1];
                                if (req != null && req.getUrl() != null) {
                                    String url = req.getUrl().toString();
                                    SdkRuntime.Decision accounting = SdkRuntime.decision("webview");
                                    if (accounting != null && isAdUrl(url)) {
                                        param.setResult(createEmptyResponse());
                                        accounting.hit();
                                        AdHookLog.d("[" + TAG + "] Intercepted Web ad request: " + url);
                                    }
                                }
                            }
                        }
                );
            } catch (Throwable ignored) {
            }

            // Hook onPageFinished(WebView, String) 注入纯净 CSS
            try {
                HOOKS.hookObserverMethod(
                        clientClass.getDeclaredMethod("onPageFinished", WebView.class, String.class),
                        new HookCallback() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length < 1) return;
                                WebView view = (WebView) param.args[0];
                                if (view != null && SdkRuntime.decision("webview") != null) {
                                    view.evaluateJavascript(PURIFY_CSS, null);
                                    // Injecting CSS is not evidence that any ad element was present or hidden.
                                }
                            }
                        }
                );
            } catch (Throwable ignored) {
            }

        } catch (Throwable t) {
            AdHookLog.w("[" + TAG + "] Hook client class failed: " + clientClass.getName() + " -> " + t.getMessage());
        }
    }

    private static boolean isAdUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        for (String keyword : AD_HOST_KEYWORDS) {
            if (url.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static WebResourceResponse createEmptyResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                new ByteArrayInputStream(new byte[0])
        );
    }
}
