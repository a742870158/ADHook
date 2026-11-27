package com.adhook.hook.support;

import com.adhook.core.ClassFingerprintProbe;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Exact semantic/serialized names only; never guesses a member from its primitive type or position. */
public final class SemanticMembers {
    private SemanticMembers() {}

    public static Field findField(Class<?> owner, Class<?> type, String... names) {
        if (type == null || names == null || names.length == 0) return null;
        return ClassFingerprintProbe.findUniqueField(owner, "semantic-field:" + Arrays.toString(names), field ->
                !Modifier.isStatic(field.getModifiers()) && field.getType() == type && hasName(field, names));
    }

    public static Method findGetter(Class<?> owner, Class<?> type, String... names) {
        if (type == null || names == null || names.length == 0) return null;
        return ClassFingerprintProbe.findUniqueMethod(owner, "semantic-getter:" + Arrays.toString(names), method ->
                !Modifier.isStatic(method.getModifiers()) && method.getParameterTypes().length == 0
                        && method.getReturnType() == type && contains(names, method.getName()));
    }

    public static boolean hasName(Field field, String... names) {
        if (contains(names, field.getName())) return true;
        try {
            for (Annotation annotation : field.getDeclaredAnnotations()) {
                String name = annotation.annotationType().getName();
                String attribute;
                if ("com.google.gson.annotations.SerializedName".equals(name)) attribute = "value";
                else if ("com.alibaba.fastjson.annotation.JSONField".equals(name)
                        || "com.alibaba.fastjson2.annotation.JSONField".equals(name)
                        || "com.squareup.moshi.Json".equals(name)) attribute = "name";
                else continue;
                Object value = annotation.annotationType().getMethod(attribute).invoke(annotation);
                if (value instanceof String text && contains(names, text)) return true;
            }
        } catch (Throwable unavailable) {
            return false;
        }
        return false;
    }

    private static boolean contains(String[] names, String name) {
        for (String candidate : names) if (candidate.equals(name)) return true;
        return false;
    }
}
