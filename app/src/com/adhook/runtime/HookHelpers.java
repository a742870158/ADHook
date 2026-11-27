package com.adhook.runtime;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reflection helpers for the actual production call sites. No framework stub is bundled. */
public final class HookHelpers {
    private HookHelpers() { }
    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "byte", byte.class, "short", short.class,
            "char", char.class, "int", int.class, "long", long.class,
            "float", float.class, "double", double.class, "void", void.class);
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class,
            char.class, Character.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class, void.class, Void.class);

    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(String name, Throwable cause) { super(name, cause); }
    }
    public static final class InvocationTargetError extends Error {
        public InvocationTargetError(Throwable cause) { super(cause); }
    }

    public static Class<?> findClass(String name, ClassLoader loader) {
        if (name == null) throw new IllegalArgumentException("Class name required");
        Class<?> primitive = PRIMITIVES.get(name);
        if (primitive != null) return primitive;
        if (name.endsWith("[]")) return Array.newInstance(findClass(name.substring(0, name.length() - 2), loader), 0).getClass();
        ClassLoader effective = loader == null ? HookHelpers.class.getClassLoader() : loader;
        try { return Class.forName(name, false, effective); }
        catch (ClassNotFoundException missing) {
            String nested = name;
            for (int dot = nested.lastIndexOf('.'); dot >= 0; dot = nested.lastIndexOf('.')) {
                nested = nested.substring(0, dot) + '$' + nested.substring(dot + 1);
                try { return Class.forName(nested, false, effective); }
                catch (ClassNotFoundException ignored) { }
            }
            throw new ClassNotFoundError(name, missing);
        }
    }
    public static Class<?> findClassIfExists(String name, ClassLoader loader) {
        try { return findClass(name, loader); } catch (ClassNotFoundError missing) { return null; }
    }

    public static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldError(type.getName() + '#' + name);
    }
    public static Field findFieldIfExists(Class<?> type, String name) {
        try { return findField(type, name); } catch (NoSuchFieldError missing) { return null; }
    }

    private static Class<?>[] parameterClasses(ClassLoader loader, Object... tokens) {
        Class<?>[] result = new Class<?>[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i] instanceof Class<?>) result[i] = (Class<?>) tokens[i];
            else if (tokens[i] instanceof String) result[i] = findClass((String) tokens[i], loader);
            else throw new IllegalArgumentException("Parameter type must be a Class or class-name string");
        }
        return result;
    }

    public static Method findMethodExact(Class<?> type, String name, Object... types) {
        Class<?>[] parameters = parameterClasses(type.getClassLoader(), types);
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) { }
        }
        // Default/interface methods are inherited too.
        try { Method method = type.getMethod(name, parameters); method.setAccessible(true); return method; }
        catch (NoSuchMethodException missing) { throw new NoSuchMethodError(type.getName() + '#' + name + Arrays.toString(parameters)); }
    }
    public static Method findMethodExact(String type, ClassLoader loader, String name, Object... types) {
        return findMethodExact(findClass(type, loader), name, types);
    }
    public static Method findMethodExactIfExists(Class<?> type, String name, Object... types) {
        try { return findMethodExact(type, name, types); }
        catch (NoSuchMethodError | ClassNotFoundError missing) { return null; }
    }
    public static Method findMethodExactIfExists(String type, ClassLoader loader, String name, Object... types) {
        try { return findMethodExact(type, loader, name, types); }
        catch (NoSuchMethodError | ClassNotFoundError missing) { return null; }
    }
    public static Constructor<?> findConstructorExact(Class<?> type, Object... types) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameterClasses(type.getClassLoader(), types));
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException missing) { throw new NoSuchMethodError(type.getName() + " constructor"); }
    }

    public static HookCallback.Unhook findAndHookMethod(Class<?> type, String name, Object... typesAndCallback) {
        HookCallback callback = callback(typesAndCallback);
        try {
            return HookBridge.hookMethod(findMethodExact(type, name,
                    Arrays.copyOf(typesAndCallback, typesAndCallback.length - 1)), callback);
        } catch (NoSuchMethodError | ClassNotFoundError failure) { throw failure; }
    }
    public static HookCallback.Unhook findAndHookMethod(String type, ClassLoader loader, String name, Object... parameters) {
        return findAndHookMethod(findClass(type, loader), name, parameters);
    }
    public static HookCallback.Unhook findAndHookConstructor(Class<?> type, Object... parameters) {
        HookCallback callback = callback(parameters);
        try {
            return HookBridge.hookMethod(findConstructorExact(type, Arrays.copyOf(parameters, parameters.length - 1)), callback);
        } catch (NoSuchMethodError | ClassNotFoundError failure) { throw failure; }
    }
    public static HookCallback.Unhook findAndHookConstructor(String type, ClassLoader loader, Object... parameters) {
        return findAndHookConstructor(findClass(type, loader), parameters);
    }
    private static HookCallback callback(Object[] parameters) {
        if (parameters.length == 0 || !(parameters[parameters.length - 1] instanceof HookCallback)) {
            throw new IllegalArgumentException("Last parameter must be a HookCallback");
        }
        return (HookCallback) parameters[parameters.length - 1];
    }

    public static Object getObjectField(Object target, String name) {
        try { return findField(target.getClass(), name).get(target); }
        catch (IllegalAccessException failure) { throw new IllegalAccessError(failure.getMessage()); }
    }
    public static void setObjectField(Object target, String name, Object value) {
        try { findField(target.getClass(), name).set(target, value); }
        catch (IllegalAccessException failure) { throw new IllegalAccessError(failure.getMessage()); }
    }
    public static boolean getBooleanField(Object target, String name) { return (Boolean) getObjectField(target, name); }
    public static int getIntField(Object target, String name) { return ((Number) getObjectField(target, name)).intValue(); }
    public static long getLongField(Object target, String name) { return ((Number) getObjectField(target, name)).longValue(); }
    public static void setBooleanField(Object target, String name, boolean value) { setObjectField(target, name, value); }
    public static void setIntField(Object target, String name, int value) { setObjectField(target, name, value); }
    public static void setLongField(Object target, String name, long value) { setObjectField(target, name, value); }
    public static Object getStaticObjectField(Class<?> type, String name) {
        try { return findField(type, name).get(null); }
        catch (IllegalAccessException failure) { throw new IllegalAccessError(failure.getMessage()); }
    }
    public static void setStaticObjectField(Class<?> type, String name, Object value) {
        try { findField(type, name).set(null, value); }
        catch (IllegalAccessException failure) { throw new IllegalAccessError(failure.getMessage()); }
    }

    public static Object callMethod(Object target, String name, Object... args) {
        return invoke(bestMethod(target.getClass(), name, actualTypes(args), false), target, args);
    }
    public static Object callMethod(Object target, String name, Class<?>[] types, Object... args) {
        return invoke(bestMethod(target.getClass(), name, effectiveTypes(types, args), false), target, args);
    }
    public static Object callStaticMethod(Class<?> type, String name, Object... args) {
        return invoke(bestMethod(type, name, actualTypes(args), true), null, args);
    }
    public static Object callStaticMethod(Class<?> type, String name, Class<?>[] types, Object... args) {
        return invoke(bestMethod(type, name, effectiveTypes(types, args), true), null, args);
    }
    private static Object invoke(Method method, Object receiver, Object[] args) {
        try { return method.invoke(receiver, invocationArgs(method, args)); }
        catch (InvocationTargetException failure) { throw new InvocationTargetError(failure.getCause()); }
        catch (IllegalAccessException failure) { throw new IllegalAccessError(failure.getMessage()); }
    }
    public static Object newInstance(Class<?> type, Object... args) {
        return newInstance(type, actualTypes(args), args);
    }
    public static Object newInstance(Class<?> type, Class<?>[] types, Object... args) {
        List<Constructor<?>> candidates = Arrays.asList(type.getDeclaredConstructors());
        Constructor<?> constructor = best(candidates, effectiveTypes(types, args));
        constructor.setAccessible(true);
        try { return constructor.newInstance(invocationArgs(constructor, args)); }
        catch (InvocationTargetException failure) { throw new InvocationTargetError(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Constructor invocation failed", failure); }
    }
    public static Method findMethodBestMatch(Class<?> type, String name, Class<?>... parameters) {
        return bestMethod(type, name, parameters, false);
    }

    private static Class<?>[] actualTypes(Object[] args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i] == null ? null : args[i].getClass();
        return types;
    }
    private static Class<?>[] effectiveTypes(Class<?>[] provided, Object[] args) {
        if (provided.length != args.length) throw new IllegalArgumentException("Argument/type lengths differ");
        Class<?>[] types = provided.clone();
        for (int i = 0; i < types.length; i++) if (types[i] == null && args[i] != null) types[i] = args[i].getClass();
        return types;
    }
    private static Method bestMethod(Class<?> type, String name, Class<?>[] args, boolean staticOnly) {
        List<Method> candidates = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) addCandidate(method, name, staticOnly, signatures, candidates);
        }
        for (Method method : type.getMethods()) addCandidate(method, name, staticOnly, signatures, candidates);
        Method method = best(candidates, args);
        method.setAccessible(true);
        return method;
    }
    private static void addCandidate(Method method, String name, boolean staticOnly,
                                     Set<String> signatures, List<Method> candidates) {
        if (!method.getName().equals(name) || method.isBridge() || method.isSynthetic()
                || (staticOnly && !Modifier.isStatic(method.getModifiers()))) return;
        if (signatures.add(Arrays.toString(method.getParameterTypes()))) candidates.add(method);
    }
    private static <T extends Executable> T best(List<T> candidates, Class<?>[] args) {
        List<T> matches = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        for (T candidate : candidates) {
            int score = score(candidate, args);
            if (score < lowest) { matches.clear(); lowest = score; }
            if (score == lowest && score < Integer.MAX_VALUE) matches.add(candidate);
        }
        if (matches.isEmpty()) throw new NoSuchMethodError("No compatible executable for " + Arrays.toString(args));
        T winner = null;
        for (T candidate : matches) {
            boolean dominates = true;
            for (T other : matches) {
                if (candidate != other && !moreSpecific(candidate, other, args.length)) { dominates = false; break; }
            }
            if (dominates) { winner = candidate; break; }
        }
        if (winner == null) throw new IllegalArgumentException("Ambiguous executable for " + Arrays.toString(args));
        return winner;
    }
    private static boolean moreSpecific(Executable left, Executable right, int count) {
        Class<?>[] a = expandedTypes(left, count), b = expandedTypes(right, count);
        boolean stricter = false;
        for (int i = 0; i < count; i++) {
            Class<?> aa = box(a[i]), bb = box(b[i]);
            if (aa == bb) continue;
            if (!bb.isAssignableFrom(aa)) return false;
            stricter = true;
        }
        return stricter;
    }
    private static int score(Executable executable, Class<?>[] args) {
        Class<?>[] parameters = executable.getParameterTypes();
        if (parameters.length == args.length) {
            int direct = scoreTypes(parameters, args);
            if (direct < Integer.MAX_VALUE) return direct;
        }
        if (!executable.isVarArgs() || args.length < parameters.length - 1) return Integer.MAX_VALUE;
        int expanded = scoreTypes(expandedTypes(executable, args.length), args);
        return expanded == Integer.MAX_VALUE ? expanded : expanded + 100;
    }
    private static Class<?>[] expandedTypes(Executable executable, int count) {
        Class<?>[] parameters = executable.getParameterTypes();
        if (!executable.isVarArgs()) return parameters;
        Class<?>[] expanded = new Class<?>[count];
        int fixed = parameters.length - 1;
        System.arraycopy(parameters, 0, expanded, 0, Math.min(fixed, count));
        for (int i = fixed; i < count; i++) expanded[i] = parameters[fixed].getComponentType();
        return expanded;
    }
    private static int scoreTypes(Class<?>[] parameters, Class<?>[] args) {
        int total = 0;
        for (int i = 0; i < parameters.length; i++) {
            int cost = conversion(args[i], parameters[i]);
            if (cost == Integer.MAX_VALUE) return cost;
            total += cost;
        }
        return total;
    }
    private static int conversion(Class<?> from, Class<?> to) {
        if (from == null) return to.isPrimitive() ? Integer.MAX_VALUE : 0;
        if (from == to) return 0;
        if (to.isPrimitive()) {
            Class<?> unboxed = unbox(from);
            if (unboxed == to) return 1;
            int widening = primitiveDistance(unboxed, to);
            return widening < 0 ? Integer.MAX_VALUE : 2 + widening;
        }
        Class<?> boxed = box(from);
        if (boxed == to) return 1;
        if (!to.isAssignableFrom(boxed)) return Integer.MAX_VALUE;
        return 4 + referenceDistance(boxed, to);
    }
    private static Class<?> box(Class<?> type) { return BOXES.getOrDefault(type, type); }
    private static Class<?> unbox(Class<?> type) {
        if (type.isPrimitive()) return type;
        for (Map.Entry<Class<?>, Class<?>> pair : BOXES.entrySet()) if (pair.getValue() == type) return pair.getKey();
        return type;
    }
    private static int primitiveDistance(Class<?> from, Class<?> to) {
        List<Class<?>> path;
        if (from == byte.class) path = List.of(short.class, int.class, long.class, float.class, double.class);
        else if (from == short.class || from == char.class) path = List.of(int.class, long.class, float.class, double.class);
        else if (from == int.class) path = List.of(long.class, float.class, double.class);
        else if (from == long.class) path = List.of(float.class, double.class);
        else if (from == float.class) path = List.of(double.class);
        else return -1;
        return path.indexOf(to);
    }
    private static int referenceDistance(Class<?> from, Class<?> to) {
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        Map<Class<?>, Integer> distances = new HashMap<>();
        queue.add(from); distances.put(from, 0);
        while (!queue.isEmpty()) {
            Class<?> current = queue.remove();
            int distance = distances.get(current);
            if (current == to) return distance;
            List<Class<?>> parents = new ArrayList<>(Arrays.asList(current.getInterfaces()));
            if (current.getSuperclass() != null) parents.add(current.getSuperclass());
            for (Class<?> parent : parents) if (distances.putIfAbsent(parent, distance + 1) == null) queue.add(parent);
        }
        return 32; // Covariant arrays have no reflected superclass/interface path to another array.
    }
    private static Object[] invocationArgs(Executable executable, Object[] args) {
        Class<?>[] parameters = executable.getParameterTypes();
        if (!executable.isVarArgs() || (args.length == parameters.length
                && (args[args.length - 1] == null || parameters[parameters.length - 1].isInstance(args[args.length - 1])))) return args;
        int fixed = parameters.length - 1;
        Object[] packed = new Object[parameters.length];
        System.arraycopy(args, 0, packed, 0, fixed);
        Object tail = Array.newInstance(parameters[fixed].getComponentType(), args.length - fixed);
        for (int i = fixed; i < args.length; i++) Array.set(tail, i - fixed, args[i]);
        packed[fixed] = tail;
        return packed;
    }
}
