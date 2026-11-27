package com.adhook.sdk;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import com.adhook.core.AdHookLog;

import java.util.Locale;
import java.util.regex.Pattern;

import com.adhook.runtime.HookCallback;

/**
 * 通用防摇一摇 / 扭一扭传感器广告拦截器。
 * 仅拦截运动传感器且调用栈含已知广告 SDK 或明确的“运动 + 广告”语义。
 * 模块/Hook 框架栈不是调用方证据；冷启动、ShakeManager、SensorAdapter 等普通运动用途放行。
 */
public final class SensorAdBlocker {
    private static final String TAG = "SensorAdBlocker";
    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();
    private static final Pattern CLASS_TOKENS = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[.$_]");

    private SensorAdBlocker() {
    }

    public static void install() {
        SdkRuntime.bind("sensor");
        try {
            installRegistrationHooks();
        } catch (Throwable t) {
            AdHookLog.e("[" + TAG + "] Failed to install sensor hooks", t);
        }
    }

    private static void installRegistrationHooks() {
        HookCallback hook = new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                SdkRuntime.Decision accounting = SdkRuntime.decision("sensor");
                if (accounting != null && shouldBlock(param)) {
                    param.setResult(true);
                    accounting.hit();
                    AdHookLog.d("[" + TAG + "] Blocked shake sensor registration from ad SDK");
                }
            }
        };
        Class<?>[][] signatures = {
                {SensorEventListener.class, Sensor.class, int.class},
                {SensorEventListener.class, Sensor.class, int.class, Handler.class},
                {SensorEventListener.class, Sensor.class, int.class, int.class},
                {SensorEventListener.class, Sensor.class, int.class, int.class, Handler.class}
        };
        int installed = 0;
        for (Class<?>[] signature : signatures) {
            try {
                if (HOOKS.hookObserverMethod(SensorManager.class.getDeclaredMethod("registerListener", signature), hook)) {
                    installed++;
                }
            } catch (Throwable t) {
                // 某个系统版本缺少重载或暂时安装失败，不影响其它重载，也不永久封死重试。
                AdHookLog.d("[" + TAG + "] registerListener overload unavailable: " + t);
            }
        }
        if (installed > 0) {
            AdHookLog.i("[" + TAG + "] Hooked " + installed + " sensor registration overload(s)");
        }
    }

    private static boolean shouldBlock(HookCallback.MethodHookParam param) {
        if (param.args == null || param.args.length < 2 || !(param.args[1] instanceof Sensor)) {
            return false;
        }
        Sensor sensor = (Sensor) param.args[1];
        return shouldBlockRegistration(sensor.getType(), Thread.currentThread().getStackTrace());
    }

    /** 与 Android/Xposed 安装路径分离的实际分类逻辑，便于回归验证真实栈形态。 */
    static boolean shouldBlockRegistration(int sensorType, StackTraceElement[] stack) {
        if (sensorType != Sensor.TYPE_ACCELEROMETER
                && sensorType != Sensor.TYPE_GYROSCOPE
                && sensorType != Sensor.TYPE_LINEAR_ACCELERATION
                && sensorType != Sensor.TYPE_ROTATION_VECTOR) {
            return false;
        }
        if (stack == null) {
            return false;
        }
        for (StackTraceElement element : stack) {
            if (element == null) {
                continue;
            }
            String className = element.getClassName();
            if (isInfrastructureFrame(className)) {
                continue;
            }
            if (AdSdk.isAdClass(className) || isExplicitMotionAdName(className)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInfrastructureFrame(String name) {
        return name == null || name.startsWith("com.adhook.")
                || name.startsWith("de.robv.android.xposed.")
                || name.startsWith("org.lsposed.")
                || name.startsWith("io.github.libxposed.")
                || name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("com.android.") || name.startsWith("dalvik.");
    }

    private static boolean isExplicitMotionAdName(String name) {
        boolean motion = false;
        boolean advertisement = false;
        for (String token : CLASS_TOKENS.split(name)) {
            String lower = token.toLowerCase(Locale.ROOT);
            if ("shakead".equals(lower) || "twistad".equals(lower) || "sensorad".equals(lower)) {
                return true;
            }
            motion |= "shake".equals(lower) || "twist".equals(lower) || "sensor".equals(lower);
            advertisement |= "ad".equals(lower) || "ads".equals(lower)
                    || "advert".equals(lower) || "advertising".equals(lower) || "advertisement".equals(lower);
        }
        return motion && advertisement;
    }
}
