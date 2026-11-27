package com.adhook.sdk;

import android.hardware.Sensor;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SensorAdBlockerTest {
    @Test public void itsOwnAndFrameworkFramesDoNotMakeOrdinaryMotionAnAd() {
        assertFalse(blocks(Sensor.TYPE_ACCELEROMETER,
                "java.lang.Thread", "com.adhook.sdk.SensorAdBlocker",
                "com.adhook.sdk.SensorAdBlocker$1", "com.adhook.runtime.HookBridge",
                "org.lsposed.lspd.SensorAdHook", "android.hardware.SystemSensorManager",
                "com.fitness.tracker.MotionTracker"));
    }

    @Test public void ordinaryShakeHelpersAndColdStartSensorsAreAllowed() {
        String[] callers = {
                "com.fitness.ShakeManager", "com.fitness.ShakeHelper", "com.fitness.ShakeSensor",
                "com.fitness.SensorAdapter", "com.fitness.ShakeAdapter",
                "com.fitness.launch.StartupSensor", "com.fitness.splash.ShakeManager"
        };
        for (String caller : callers) {
            assertFalse(caller, blocks(Sensor.TYPE_GYROSCOPE, "com.adhook.sdk.SensorAdBlocker", caller));
        }
    }

    @Test public void frameworkFramesDoNotHideARealSdkCaller() {
        for (int type : new int[]{Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR}) {
            assertTrue(blocks(type, "com.adhook.sdk.SensorAdBlocker$1",
                    "com.adhook.runtime.HookBridge", "com.bytedance.sdk.openadsdk.ShakeManager"));
            assertTrue(blocks(type, "com.qq.e.ads.splash.SensorController"));
        }
    }

    @Test public void explicitHostMotionAdNamesAreBlockedWithoutGenericSubstringMatches() {
        assertTrue(blocks(Sensor.TYPE_ACCELEROMETER, "com.host.SplashShakeAdController"));
        assertTrue(blocks(Sensor.TYPE_GYROSCOPE, "com.host.ads.TwistManager"));
        assertTrue(blocks(Sensor.TYPE_ACCELEROMETER, "com.host.sensorad"));
        assertFalse(blocks(Sensor.TYPE_ACCELEROMETER, "com.host.adventure.SensorAdapter"));
    }

    @Test public void nonMotionSensorsAndEmptyStacksAreAllowed() {
        assertFalse(blocks(Sensor.TYPE_STEP_COUNTER, "com.qq.e.ads.SensorAd"));
        assertFalse(blocks(Sensor.TYPE_LIGHT, "com.host.ShakeAd"));
        assertFalse(SensorAdBlocker.shouldBlockRegistration(Sensor.TYPE_ACCELEROMETER, null));
        assertFalse(SensorAdBlocker.shouldBlockRegistration(Sensor.TYPE_ACCELEROMETER, new StackTraceElement[0]));
        assertFalse(SensorAdBlocker.shouldBlockRegistration(Sensor.TYPE_ACCELEROMETER,
                new StackTraceElement[]{null}));
    }

    @Test public void classificationIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(blocks(Sensor.TYPE_GYROSCOPE, "com.host.ADS.TWIST"));
            assertFalse(blocks(Sensor.TYPE_GYROSCOPE, "com.host.SENSOR.ADAPTER"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static boolean blocks(int type, String... classNames) {
        StackTraceElement[] stack = new StackTraceElement[classNames.length];
        for (int i = 0; i < classNames.length; i++) {
            stack[i] = new StackTraceElement(classNames[i], "register", "Caller.java", 1);
        }
        return SensorAdBlocker.shouldBlockRegistration(type, stack);
    }
}
