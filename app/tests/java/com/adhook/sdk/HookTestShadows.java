package com.adhook.sdk;

import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookCallback;
import io.github.libxposed.api.XposedInterface;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Registration seam for SDK accounting tests. Callback/parameter implementations stay real.
 * Native API101 chain semantics are tested separately in the runtime integration tests. */
public final class HookTestShadows {
    private HookTestShadows() { }

    @Implements(value = HookBridge.class, isInAndroidSdk = false)
    public static class Bridge {
        static final Map<Member, Integer> attempts = new HashMap<>();
        static final Map<Member, Integer> successes = new HashMap<>();
        static final Set<Member> failOnce = new HashSet<>();
        static final Map<Member, HookCallback> callbacks = new HashMap<>();

        static void reset() {
            attempts.clear();
            successes.clear();
            failOnce.clear();
            callbacks.clear();
        }

        @Implementation protected static HookCallback.Unhook hookMethod(Member member, HookCallback callback) {
            attempts.put(member, attempts.getOrDefault(member, 0) + 1);
            if (failOnce.remove(member)) {
                throw new IllegalStateException("simulated transient Hook failure");
            }
            XposedInterface.HookHandle nativeHandle = new XposedInterface.HookHandle() {
                @Override public Executable getExecutable() { return (Executable) member; }
                @Override public void unhook() { callbacks.remove(member, callback); }
            };
            HookCallback.Unhook handle = callback.new Unhook(member, nativeHandle);
            successes.put(member, successes.getOrDefault(member, 0) + 1);
            callbacks.put(member, callback);
            return handle;
        }

        @Implementation protected static void log(String text) { }
        @Implementation protected static void log(Throwable error) { }
    }
}
