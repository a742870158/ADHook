package com.adhook.runtime;

import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/** Business callback contract adapted to the native API101 interceptor chain. */
public abstract class HookCallback {
    public static final int PRIORITY_DEFAULT = XposedInterface.PRIORITY_DEFAULT;
    public static final int PRIORITY_LOWEST = XposedInterface.PRIORITY_LOWEST;
    public static final int PRIORITY_HIGHEST = XposedInterface.PRIORITY_HIGHEST;
    public final int priority;

    public HookCallback() { this(PRIORITY_DEFAULT); }
    public HookCallback(int priority) { this.priority = priority; }

    /** Preserve the originating callback definition through business-gate wrappers. */
    public Class<?> registrationType() { return getClass(); }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }
    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;
        private Map<String, Object> extras;

        public Object getResult() { return result; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setResult(Object result) { outcome(result, null, true); }
        public void setThrowable(Throwable throwable) { outcome(null, throwable, true); }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
        public void setObjectExtra(String key, Object value) {
            if (extras == null) extras = new HashMap<>();
            extras.put(key, value);
        }
        public Object getObjectExtra(String key) { return extras == null ? null : extras.get(key); }
        public boolean isReturnEarly() { return returnEarly; }
        State save() { return new State(this); }
        void restore(State state) {
            thisObject = state.receiver;
            args = state.args == null ? null : state.args.clone();
            outcome(state.result, state.throwable, state.early);
            extras = state.extras == null ? null : new HashMap<>(state.extras);
        }
        // Restore values and map structure, not arbitrary mutations inside host-owned objects.
        static final class State {
            final Object receiver;
            final Object[] args;
            final Object result;
            final Throwable throwable;
            final boolean early;
            final Map<String, Object> extras;
            State(MethodHookParam param) {
                receiver = param.thisObject;
                args = param.args == null ? null : param.args.clone();
                result = param.result;
                throwable = param.throwable;
                early = param.returnEarly;
                extras = param.extras == null ? null : new HashMap<>(param.extras);
            }
        }
        void outcome(Object value, Throwable failure, boolean early) {
            result = value;
            throwable = failure;
            returnEarly = early;
        }
    }

    /** A successful native registration; failed unhooks remain retryable. */
    public final class Unhook {
        private final Member member;
        private final XposedInterface.HookHandle nativeHandle;
        private final AtomicBoolean active = new AtomicBoolean(true);

        public Unhook(Member member, XposedInterface.HookHandle nativeHandle) {
            if (member == null || nativeHandle == null) throw new IllegalArgumentException("Member and native handle required");
            this.member = member;
            this.nativeHandle = nativeHandle;
        }
        public Member getHookedMethod() { return member; }
        public HookCallback getCallback() { return HookCallback.this; }
        public synchronized void unhook() {
            if (!active.get()) return;
            nativeHandle.unhook();
            active.set(false);
            HookBridge.forget(this);
        }
        public boolean isActive() { return active.get(); }
    }
}
