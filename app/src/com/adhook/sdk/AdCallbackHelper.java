package com.adhook.sdk;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.adhook.runtime.HookCallback;

/**
 * 广告 SDK Hook 组内共享工具（包内可见，不修改 core）。
 *
 * 集中解决两类共性问题：
 * 1. 失败回调安全下发：宿主普遍用匿名内部类 / Lambda 实现 SDK 回调（运行时类名形如
 *    MainActivity$1，不含 Callback/Listener），因此回调识别必须基于
 *    「方法声明参数类型 + 运行时实现接口 / 父类可赋值性 + 失败方法名多签名兜底」三层判定；
 *    任何情况下都尽力让宿主收到失败回调，避免宿主一直等待（开屏黑屏 / 卡加载）。
 * 2. 返回值类型安全替换：向 Xposed 设置 null 结果前必须校验目标方法返回类型，
 *    基本类型返回值不能用 null（框架回传时拆箱会崩溃），统一填零值。
 *
 * 约束：绝不把 null 的错误对象（AdError / LoadAdError 等）传给宿主，构造失败就跳过该重载。
 */
final class AdCallbackHelper {

    /** 统一空广告原因文案。 */
    static final String REASON = "No fill (Blocked by ADHook)";

    /** 判定“错误对象”的类名关键字（用于反射构造 LoadAdError / AdError）。 */
    private static final String[] ERROR_TYPE_KEYWORDS = {"loadaderror", "aderror", "error"};

    /** 回调类型判定关键字（类名 / 接口名小写匹配）。 */
    private static final String[] CALLBACK_TYPE_KEYWORDS = {"callback", "listener", "interaction"};

    /** 失败回调方法名（按优先级排列，多签名兜底）。 */
    private static final String[] FAILURE_METHOD_NAMES = {
            "onAdFailedToLoad", "onAdFailed", "onNoAD", "onNoAd",
            "onNativeFail", "onSplashLoadFail", "onSplashAdLoadFail",
            "onSplashRenderFail", "onFail", "onError", "onRequestResult"
    };

    /** 明确属于“数据参数”而非回调的类型，避免把 Context / 请求对象误判为回调。 */
    private static final String[] NON_CALLBACK_TYPE_PREFIXES = {
            "android.content.Context", "android.app.Activity", "android.view.View",
            "java.lang.String", "java.lang.CharSequence", "java.lang.Number",
            "java.lang.Boolean", "java.lang.Character", "java.lang.Enum",
            "com.google.android.gms.ads.AdRequest",
            "com.google.android.gms.ads.admanager.AdManagerAdRequest",
            "com.google.android.gms.ads.RequestConfiguration",
            "android.os.Bundle", "java.util.Map", "java.util.List"
    };

    private AdCallbackHelper() {
    }

    /**
     * 按目标方法真实返回类型安全置空：引用类型 / void 置 null；基本类型填零值。
     * 对应审查项：setResult(null) / returnConstant(null) 目标必须做返回类型校验。
     */
    static void setNullLikeResult(HookCallback.MethodHookParam param) {
        if (param == null) {
            return;
        }
        Member member = param.method;
        if (!(member instanceof Method)) {
            param.setResult(null);
            return;
        }
        param.setResult(defaultValue(((Method) member).getReturnType()));
    }

    /** 返回类型对应的安全默认值：引用类型为 null，基本类型为零值。 */
    static Object defaultValue(Class<?> type) {
        if (type == null || type == void.class || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0f);
        }
        if (type == double.class) {
            return Double.valueOf(0d);
        }
        return null;
    }

    /**
     * 从被 hook 方法的入参中提取“疑似广告回调”对象。
     * 静态 load 方法没有 thisObject，依然可依赖声明参数类型与运行时接口命中。
     */
    static List<Object> collectParamCallbacks(HookCallback.MethodHookParam param) {
        List<Object> callbacks = new ArrayList<>();
        if (param == null || param.args == null) {
            return callbacks;
        }
        Class<?>[] declaredTypes = null;
        if (param.method instanceof Method) {
            declaredTypes = ((Method) param.method).getParameterTypes();
        } else if (param.method instanceof Constructor) {
            // 构造器阶段也需要按声明参数类型识别 Listener（GDT / Baidu 的 listener 在构造函数里）
            declaredTypes = ((Constructor<?>) param.method).getParameterTypes();
        }
        for (int i = 0; i < param.args.length; i++) {
            Object arg = param.args[i];
            if (arg == null) {
                continue;
            }
            Class<?> declared = (declaredTypes != null && i < declaredTypes.length) ? declaredTypes[i] : null;
            if (declared != null && declared.isPrimitive()) {
                continue;
            }
            boolean candidate = (declared != null && isCallbackType(declared)) || isCallbackObject(arg);
            if (!candidate) {
                if (declared != null && isNonCallbackType(declared)) {
                    continue;
                }
                // 兜底：不认识的引用类型，只要自身暴露失败回调方法也视为候选（多签名兜底）
                candidate = hasFailureMethod(arg);
            }
            if (candidate && !containsIdentity(callbacks, arg)) {
                callbacks.add(arg);
            }
        }
        return callbacks;
    }

    /** 从实例字段中寻找回调（GDT / Baidu / AdMob 的 listener 常保存在字段里）。 */
    static List<Object> collectFieldCallbacks(Object target) {
        List<Object> callbacks = new ArrayList<>();
        if (target == null) {
            return callbacks;
        }
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    try {
                        Class<?> fieldType = field.getType();
                        if (fieldType.isPrimitive() || fieldType.isArray()) {
                            continue;
                        }
                        boolean typeHint = isCallbackType(fieldType);
                        if (!typeHint) {
                            continue;
                        }
                        field.setAccessible(true);
                        Object value = field.get(target);
                        if (value != null && (typeHint || isCallbackObject(value)) && !containsIdentity(callbacks, value)) {
                            callbacks.add(value);
                        }
                    } catch (Throwable ignored) {
                        // 单个字段反射失败不影响其它字段
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return callbacks;
    }

    /** 对入参中识别到的回调统一下发失败，返回是否至少通知成功一个。 */
    static boolean notifyParamCallbacks(HookCallback.MethodHookParam param, int errorCode, ClassLoader classLoader) {
        boolean notified = false;
        for (Object callback : collectParamCallbacks(param)) {
            if (notifyFailure(callback, errorCode, classLoader)) {
                notified = true;
            }
        }
        return notified;
    }

    /** 对实例字段中保存的回调统一下发失败，返回是否至少通知成功一个。 */
    static boolean notifyFieldCallbacks(Object target, int errorCode, ClassLoader classLoader) {
        boolean notified = false;
        for (Object callback : collectFieldCallbacks(target)) {
            if (notifyFailure(callback, errorCode, classLoader)) {
                notified = true;
            }
        }
        return notified;
    }

    /**
     * 合并弱缓存与 SDK 正常持有的参数/字段回调。局部列表在通知期间强持有 listener，
     * 避免弱值在读取后消失；同一个 listener 即使同时出现在三个来源中也只通知一次。
     */
    static boolean notifyCachedCallbacks(HookCallback.MethodHookParam param, Object cachedListener,
                                         int errorCode, ClassLoader classLoader) {
        List<Object> callbacks = collectParamCallbacks(param);
        if (cachedListener != null && !containsIdentity(callbacks, cachedListener)) {
            callbacks.add(cachedListener);
        }
        Object target = param != null ? param.thisObject : null;
        for (Object callback : collectFieldCallbacks(target)) {
            if (!containsIdentity(callbacks, callback)) {
                callbacks.add(callback);
            }
        }
        boolean notified = false;
        for (Object callback : callbacks) {
            if (notifyFailure(callback, errorCode, classLoader)) {
                notified = true;
            }
        }
        return notified;
    }

    /** 对象若是疑似回调则下发失败（代理 / 任意参数兜底用）。 */
    static boolean notifyFailureIfCallback(Object candidate, int errorCode, ClassLoader classLoader) {
        if (candidate == null) {
            return false;
        }
        if (!isCallbackObject(candidate) && !hasFailureMethod(candidate)) {
            return false;
        }
        return notifyFailure(candidate, errorCode, classLoader);
    }

    /** 下发失败回调（无广告实例上下文的重载）。 */
    static boolean notifyFailure(Object callback, int errorCode, ClassLoader classLoader) {
        return notifyFailure(callback, errorCode, classLoader, null);
    }

    /**
     * 下发失败回调：遍历 onAdFailedToLoad / onNoAD / onNativeFail / onAdFailed / onError 等
     * 多签名重载，优先简单签名；错误对象构造失败的重载直接跳过（绝不传 null 错误对象）。
     *
     * @param adInstance 部分回调（如 Facebook onError(Ad, AdError)）需要广告实例参数，可为 null
     */
    static boolean notifyFailure(Object callback, int errorCode, ClassLoader classLoader, Object adInstance) {
        if (callback == null) {
            return false;
        }
        for (Method method : collectFailureMethods(callback.getClass())) {
            Object[] args = buildFailureArgs(method, errorCode, classLoader, adInstance);
            if (args == null) {
                // 该重载含无法安全构造的引用参数（典型为错误对象），跳过换下一个重载
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(callback, args);
                AdHookLog.d("[AdCallbackHelper] Notified " + method.getDeclaringClass().getSimpleName()
                        + "#" + method.getName() + " code=" + errorCode);
                return true;
            } catch (Throwable t) {
                AdHookLog.w("[AdCallbackHelper] invoke " + method.getName() + " failed: " + t);
            }
        }
        AdHookLog.w("[AdCallbackHelper] no safe failure callback found on " + callback.getClass().getName());
        return false;
    }

    /** 判断类型是否可能是广告回调（按声明类型识别，不依赖运行时类名）。 */
    static boolean isCallbackType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray() || isNonCallbackType(type)) {
            return false;
        }
        String lower = type.getName().toLowerCase();
        for (String keyword : CALLBACK_TYPE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 判断对象运行时是否实现了回调接口 / 继承了回调基类（匿名内部类靠接口可赋值性识别）。 */
    static boolean isCallbackObject(Object candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            for (Class<?> c = candidate.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (!c.isAnonymousClass() && !c.isSynthetic() && isCallbackType(c)) {
                    return true;
                }
                for (Class<?> itf : safeGetInterfaces(c)) {
                    if (isCallbackType(itf)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 对象是否暴露任一失败回调方法（方法名多签名兜底）。 */
    static boolean hasFailureMethod(Object candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            return !collectFailureMethods(candidate.getClass()).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNonCallbackType(Class<?> type) {
        String name = type.getName();
        for (String prefix : NON_CALLBACK_TYPE_PREFIXES) {
            if (name.equals(prefix) || name.startsWith(prefix + "$")) {
                return true;
            }
        }
        return false;
    }

    private static Class<?>[] safeGetInterfaces(Class<?> c) {
        try {
            return c.getInterfaces();
        } catch (Throwable t) {
            return new Class<?>[0];
        }
    }

    private static boolean containsIdentity(List<Object> list, Object value) {
        for (Object item : list) {
            if (item == value) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> collectFailureMethods(Class<?> callbackClass) {
        List<Method> result = new ArrayList<>();
        List<Class<?>> visited = new ArrayList<>();
        for (Class<?> c = callbackClass; c != null && c != Object.class; c = c.getSuperclass()) {
            appendFailureMethods(c, result, visited);
            for (Class<?> itf : safeGetInterfaces(c)) {
                appendFailureMethods(itf, result, visited);
            }
        }
        result.sort((a, b) -> {
            int pa = failurePriority(a.getName());
            int pb = failurePriority(b.getName());
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            int sa = simpleSignatureScore(a);
            int sb = simpleSignatureScore(b);
            if (sa != sb) {
                return Integer.compare(sa, sb);
            }
            return Integer.compare(a.getParameterTypes().length, b.getParameterTypes().length);
        });
        return result;
    }

    private static void appendFailureMethods(Class<?> type, List<Method> result, List<Class<?>> visited) {
        if (type == null || type == Object.class || visited.contains(type)) {
            return;
        }
        visited.add(type);
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || !isFailureName(method.getName())) {
                    continue;
                }
                if (!containsSameMethod(result, method)) {
                    result.add(method);
                }
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> itf : safeGetInterfaces(type)) {
            appendFailureMethods(itf, result, visited);
        }
    }

    private static boolean containsSameMethod(List<Method> list, Method method) {
        for (Method existing : list) {
            if (!existing.getName().equals(method.getName())) {
                continue;
            }
            if (Arrays.equals(existing.getParameterTypes(), method.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFailureName(String name) {
        if (name == null) {
            return false;
        }
        for (String failureName : FAILURE_METHOD_NAMES) {
            if (failureName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int failurePriority(String name) {
        for (int i = 0; i < FAILURE_METHOD_NAMES.length; i++) {
            if (FAILURE_METHOD_NAMES[i].equals(name)) {
                return i;
            }
        }
        return FAILURE_METHOD_NAMES.length;
    }

    /** 0 表示全部参数都是基本类型 / String（可无条件安全构造），1 表示含引用参数。 */
    private static int simpleSignatureScore(Method method) {
        for (Class<?> pType : method.getParameterTypes()) {
            if (pType.isPrimitive() || pType == String.class || pType == CharSequence.class) {
                continue;
            }
            return 1;
        }
        return 0;
    }

    private static Object[] buildFailureArgs(Method method, int errorCode, ClassLoader classLoader, Object adInstance) {
        Class<?>[] pTypes = method.getParameterTypes();
        Object[] args = new Object[pTypes.length];
        int stringIndex = 0;
        for (int i = 0; i < pTypes.length; i++) {
            Class<?> type = pTypes[i];
            if (type == int.class || type == Integer.class) {
                args[i] = Integer.valueOf(errorCode);
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = Boolean.FALSE;
            } else if (type == long.class || type == Long.class) {
                args[i] = Long.valueOf(0L);
            } else if (type == float.class || type == Float.class) {
                args[i] = Float.valueOf(0f);
            } else if (type == double.class || type == Double.class) {
                args[i] = Double.valueOf(0d);
            } else if (type == short.class || type == Short.class) {
                args[i] = Short.valueOf((short) 0);
            } else if (type == byte.class || type == Byte.class) {
                args[i] = Byte.valueOf((byte) 0);
            } else if (type == char.class || type == Character.class) {
                args[i] = Character.valueOf('\0');
            } else if (type == String.class || type == CharSequence.class) {
                args[i] = stringIndex == 0 ? REASON : "com.adhook";
                stringIndex++;
            } else if (adInstance != null && type.isInstance(adInstance)) {
                args[i] = adInstance;
            } else if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                if (constants == null || constants.length == 0) {
                    return null;
                }
                args[i] = constants[0];
            } else if (isErrorType(type)) {
                Object error = buildErrorObject(type, errorCode, classLoader, 0);
                if (error == null) {
                    // 构造不出错误对象：绝不向宿主传 null，直接跳过该重载
                    AdHookLog.w("[AdCallbackHelper] cannot construct error object " + type.getName()
                            + ", skip callback " + method.getName());
                    return null;
                }
                args[i] = error;
            } else {
                // 其它未知引用参数无法安全构造，跳过该重载
                return null;
            }
        }
        return args;
    }

    private static boolean isErrorType(Class<?> type) {
        String lower = type.getName().toLowerCase();
        for (String keyword : ERROR_TYPE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 反射构造 AdError / LoadAdError 等错误对象；构造失败返回 null（调用方跳过回调）。 */
    private static Object buildErrorObject(Class<?> targetType, int errorCode, ClassLoader classLoader, int depth) {
        if (targetType == null || depth > 2 || targetType.isPrimitive() || targetType.isInterface()
                || Modifier.isAbstract(targetType.getModifiers())) {
            return null;
        }
        Constructor<?>[] constructors;
        try {
            constructors = targetType.getDeclaredConstructors();
        } catch (Throwable t) {
            return null;
        }
        for (Constructor<?> constructor : constructors) {
            try {
                Class<?>[] pTypes = constructor.getParameterTypes();
                Object[] args = new Object[pTypes.length];
                int stringIndex = 0;
                for (int i = 0; i < pTypes.length; i++) {
                    Class<?> type = pTypes[i];
                    if (type == int.class || type == Integer.class) {
                        args[i] = Integer.valueOf(errorCode);
                    } else if (type == boolean.class || type == Boolean.class) {
                        args[i] = Boolean.FALSE;
                    } else if (type == long.class || type == Long.class) {
                        args[i] = Long.valueOf(0L);
                    } else if (type == String.class || type == CharSequence.class) {
                        args[i] = stringIndex == 0 ? REASON : "com.adhook";
                        stringIndex++;
                    } else if (type.isEnum()) {
                        Object[] constants = type.getEnumConstants();
                        if (constants == null || constants.length == 0) {
                            args[i] = null;
                        } else {
                            args[i] = constants[0];
                        }
                    } else if (isErrorType(type)) {
                        // 内部 cause 允许为 null（顶层错误对象本身非 null）
                        args[i] = buildErrorObject(type, errorCode, classLoader, depth + 1);
                    } else {
                        // 如 ResponseInfo 等辅助参数允许为 null
                        args[i] = null;
                    }
                }
                constructor.setAccessible(true);
                Object instance = constructor.newInstance(args);
                if (instance != null) {
                    return instance;
                }
            } catch (Throwable ignored) {
                // 尝试下一个构造器
            }
        }
        return null;
    }
}
