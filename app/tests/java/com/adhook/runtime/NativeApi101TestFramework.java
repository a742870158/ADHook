package com.adhook.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/** A recording framework implementing the actual API101 interface, not a callback/bridge shadow. */
public final class NativeApi101TestFramework {
    private final java.util.Set<Executable> failOnce = new java.util.HashSet<>();
    public void initialize() { HookBridge.initialize(api); }
    public void reset() { HookBridge.resetForTests(); }
    public void failOnce(Executable executable) { failOnce.add(executable); }
    public int installCount(Executable executable) {
        int count = 0;
        for (Installed hook : hooks) if (hook.executable.equals(executable) && hook.active) count++;
        return count;
    }
    final List<Installed> hooks = new ArrayList<>();
    final List<Throwable> errors = new ArrayList<>();
    XposedInterface.Invoker.Type invokedType;
    boolean failNextInstall;
    boolean failNextUnhook;
    int nativeUnhooks;
    int originalCalls;
    private long sequence;
    public final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(
            XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getApiVersion": return 101;
                    case "hook": return new Builder((Executable) args[0]);
                    case "getInvoker": return invoker((Executable) args[0]);
                    case "log": if (args.length == 4) errors.add((Throwable) args[3]); return null;
                    case "getFrameworkName": return "recording-api101";
                    case "getFrameworkVersion": return "1";
                    case "getFrameworkVersionCode": return 1L;
                    case "getFrameworkProperties": return 0L;
                    case "toString": return "recording-api101";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });

    final class Builder implements XposedInterface.HookBuilder {
        final Executable executable;
        int priority;
        XposedInterface.ExceptionMode mode;
        Builder(Executable executable) { this.executable = executable; }
        @Override public XposedInterface.HookBuilder setPriority(int priority) { this.priority = priority; return this; }
        @Override public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode mode) { this.mode = mode; return this; }
        @Override public XposedInterface.HookHandle intercept(XposedInterface.Hooker hooker) {
            if (failOnce.remove(executable) || failNextInstall) { failNextInstall = false; throw new IllegalStateException("native install rejected"); }
            Installed installed = new Installed(executable, hooker, priority, mode, sequence++);
            hooks.add(installed);
            return installed;
        }
    }
    final class Installed implements XposedInterface.HookHandle {
        final Executable executable;
        final XposedInterface.Hooker hooker;
        final int priority;
        final XposedInterface.ExceptionMode mode;
        final long sequence;
        boolean active = true;
        Installed(Executable executable, XposedInterface.Hooker hooker, int priority,
                  XposedInterface.ExceptionMode mode, long sequence) {
            this.executable = executable; this.hooker = hooker; this.priority = priority;
            this.mode = mode; this.sequence = sequence;
        }
        @Override public Executable getExecutable() { return executable; }
        @Override public void unhook() {
            if (failNextUnhook) { failNextUnhook = false; throw new IllegalStateException("native unhook rejected"); }
            active = false; nativeUnhooks++;
        }
    }
    public Object call(Executable executable, Object receiver, Object... args) throws Throwable {
        List<Installed> active = new ArrayList<>();
        for (Installed installed : hooks) if (installed.active && installed.executable.equals(executable)) active.add(installed);
        active.sort(Comparator.comparingInt((Installed hook) -> hook.priority).reversed().thenComparingLong(hook -> hook.sequence));
        return next(active, 0, executable, receiver, args);
    }
    private Object next(List<Installed> active, int index, Executable executable, Object receiver, Object[] args) throws Throwable {
        if (index == active.size()) return original(executable, receiver, args);
        return active.get(index).hooker.intercept(new XposedInterface.Chain() {
            @Override public Executable getExecutable() { return executable; }
            @Override public Object getThisObject() { return receiver; }
            @Override public List<Object> getArgs() { return Arrays.asList(args.clone()); }
            @Override public Object getArg(int position) { return args[position]; }
            @Override public Object proceed() throws Throwable { return next(active, index + 1, executable, receiver, args); }
            @Override public Object proceed(Object[] changed) throws Throwable { return next(active, index + 1, executable, receiver, changed); }
            @Override public Object proceedWith(Object changedReceiver) throws Throwable { return next(active, index + 1, executable, changedReceiver, args); }
            @Override public Object proceedWith(Object changedReceiver, Object[] changed) throws Throwable {
                return next(active, index + 1, executable, changedReceiver, changed);
            }
        });
    }
    private Object original(Executable executable, Object receiver, Object[] args) throws Throwable {
        originalCalls++;
        executable.setAccessible(true);
        try {
            if (executable instanceof Method) return ((Method) executable).invoke(receiver, args);
            return ((Constructor<?>) executable).newInstance(args);
        } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private Object invoker(Executable executable) {
        Class<?> contract = executable instanceof Method ? XposedInterface.Invoker.class : XposedInterface.CtorInvoker.class;
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
            if (method.getName().equals("setType")) { invokedType = (XposedInterface.Invoker.Type) args[0]; return proxy; }
            if (method.getName().equals("invoke") || method.getName().equals("invokeSpecial")) {
                try { return original(executable, args[0], (Object[]) args[1]); }
                catch (Throwable failure) { throw new InvocationTargetException(failure); }
            }
            if (method.getName().equals("newInstance")) return ((Constructor<?>) executable).newInstance((Object[]) args[0]);
            throw new UnsupportedOperationException(method.getName());
        });
    }
}
