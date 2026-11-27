package com.adhook.sdk;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookBridge;

/**
 * 每个 SDK 独立持有的成功 Hook 注册表。
 *
 * 不缓存 ClassLoader 的“已探测”状态：同一 loader 后续可能追加 dex，失败的方法也必须可重试。
 * 以声明类（弱引用）+ 完整签名去重，值中不保留 Method / Class / callback，避免反向保活 loader。
 */
public final class SdkHookRegistry {
    private final Map<Class<?>, Map<String, Boolean>> hooks = new WeakHashMap<>();

    @FunctionalInterface
    interface Installer {
        /** 只有底层确认安装成功才返回 true。 */
        boolean install() throws Throwable;
    }

    /** Host business callbacks inherit their registration package/feature action gate. */
    public boolean hookMethod(Member member, HookCallback callback) throws Throwable {
        return install(member, () -> com.adhook.core.HookRegistration.hookMethod(member, callback) != null);
    }

    /** Observers or SDK callbacks with their own multi-owner SdkRuntime decision remain raw. */
    public boolean hookObserverMethod(Member member, HookCallback callback) throws Throwable {
        return install(member, () -> HookBridge.hookMethod(member, callback) != null);
    }

    /**
     * 安装和成功登记处于同一临界区，防止并发 install 重复挂载。
     * false 仅为当前调用期间的占位（防同线程重入），失败/异常必在 finally 中撤销。
     */
    synchronized boolean install(Member member, Installer installer) throws Throwable {
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            return false;
        }
        if (Modifier.isAbstract(member.getModifiers())) {
            return false;
        }
        Class<?> owner = member.getDeclaringClass();
        String signature = signatureOf(member);
        Map<String, Boolean> methods = hooks.get(owner);
        if (methods == null) {
            methods = new HashMap<>();
            hooks.put(owner, methods);
        }
        if (methods.containsKey(signature)) {
            return false;
        }
        methods.put(signature, Boolean.FALSE);
        try {
            if (!installer.install()) {
                return false;
            }
            methods.put(signature, Boolean.TRUE);
            return true;
        } finally {
            if (!Boolean.TRUE.equals(methods.get(signature))) {
                methods.remove(signature);
                if (methods.isEmpty()) {
                    hooks.remove(owner);
                }
            }
        }
    }

    private static String signatureOf(Member member) {
        StringBuilder signature = new StringBuilder(member.getName()).append('(');
        Class<?>[] parameters = member instanceof Method
                ? ((Method) member).getParameterTypes()
                : ((Constructor<?>) member).getParameterTypes();
        for (Class<?> parameter : parameters) {
            signature.append(parameter.getName()).append(';');
        }
        signature.append(')');
        if (member instanceof Method) {
            // 桥接方法可与原方法同名同参、返回类型不同，必须分别登记。
            signature.append(((Method) member).getReturnType().getName());
        }
        return signature.toString();
    }
}
