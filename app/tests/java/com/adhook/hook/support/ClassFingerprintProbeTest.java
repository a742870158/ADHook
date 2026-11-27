package com.adhook.hook.support;

import com.adhook.core.ClassFingerprintProbe;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class ClassFingerprintProbeTest {
    @Test public void uniqueMethodsFailOpenWhenTwoMembersMatch() {
        assertNull(ClassFingerprintProbe.findUniqueMethod(Ambiguous.class, "fixture.bool.v1",
                method -> method.getReturnType() == boolean.class && method.getParameterCount() == 0));
        assertNotNull(ClassFingerprintProbe.findUniqueMethod(Loaded.class, "fixture.string.v1",
                method -> method.getReturnType() == String.class && method.getParameterCount() == 0));
    }

    @Test public void fingerprintIdDoesNotAliasCapturedPredicateArguments() {
        Method first = ClassFingerprintProbe.findUniqueMethod(Ambiguous.class, "fixture.same-id", named("first"));
        Method second = ClassFingerprintProbe.findUniqueMethod(Ambiguous.class, "fixture.same-id", named("second"));
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
    }

    @Test public void fieldAndMethodCachesUseActualLoaderAndParameterTypeIdentity() throws Exception {
        byte[] bytes;
        String name = Loaded.class.getName();
        try (InputStream input = Loaded.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        Class<?> first = new IsolatedLoader(name, bytes).loadClass(name);
        Class<?> second = new IsolatedLoader(name, bytes).loadClass(name);
        assertNotSame(first, second);
        assertSame(first, ClassFingerprintProbe.findFieldByType(first, String.class).getDeclaringClass());
        assertSame(second, ClassFingerprintProbe.findFieldByType(second, String.class).getDeclaringClass());
        assertSame(first, ClassFingerprintProbe.findMethodBySignature(first, String.class).getDeclaringClass());
        assertSame(second, ClassFingerprintProbe.findMethodBySignature(second, String.class).getDeclaringClass());
        assertNotNull(ClassFingerprintProbe.findMethodBySignature(first, first, first));
        assertNull(ClassFingerprintProbe.findMethodBySignature(first, second, second));
    }

    @Test public void nullWildcardAndVoidReturnTypesNeverShareCacheEntry() {
        assertNotNull(ClassFingerprintProbe.findMethodBySignature(OnlyString.class, null));
        assertNull(ClassFingerprintProbe.findMethodBySignature(OnlyString.class, void.class));
    }

    @Test public void invalidBudgetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ClassFingerprintProbe.ScanBudget(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClassFingerprintProbe.ScanBudget(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClassFingerprintProbe.ScanBudget(1, 1, 30_001));
    }

    @Test public void semanticFieldsAreNotChosenByBooleanPosition() {
        assertNull(SemanticMembers.findField(UnlabeledFlags.class, boolean.class, "disableWaterMark"));
        assertNotNull(SemanticMembers.findField(LabeledFlags.class, boolean.class, "disableWaterMark"));
        assertNull(SemanticMembers.findField(ShadowedFlags.class, boolean.class, "disableWaterMark"));
    }

    private static ClassFingerprintProbe.MethodPredicate named(String name) {
        return method -> method.getName().equals(name);
    }
    static class Ambiguous { boolean first() { return false; } boolean second() { return true; } }
    public static class Loaded {
        public String label;
        public String readLabel() { return label; }
        public Loaded echo(Loaded input) { return input; }
    }
    static class OnlyString { String text() { return ""; } }
    static class UnlabeledFlags { boolean first; boolean second; }
    static class LabeledFlags { boolean disableWaterMark; }
    static class ShadowedFlags extends LabeledFlags { boolean disableWaterMark; }

    private static final class IsolatedLoader extends ClassLoader {
        private final String target;
        private final byte[] bytes;
        IsolatedLoader(String target, byte[] bytes) {
            super(ClassFingerprintProbeTest.class.getClassLoader());
            this.target = target;
            this.bytes = bytes;
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!target.equals(name)) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) loaded = defineClass(name, bytes, 0, bytes.length);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
