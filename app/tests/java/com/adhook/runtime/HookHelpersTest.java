package com.adhook.runtime;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class HookHelpersTest {
    static class Parent {
        private int count = 7;
        private boolean enabled;
        private Object value;
        private String inherited(String input) { return "parent:" + input; }
    }
    static class Child extends Parent {
        private String boxed(Integer input) { return "boxed"; }
        private String boxed(int input) { return "primitive"; }
        private String widen(long input) { return "long:" + input; }
        private String choose(Object input) { return "object"; }
        private String choose(String input) { return "string"; }
        private String ambiguous(String input) { return "string"; }
        private String ambiguous(Integer input) { return "integer"; }
        private String join(String... input) { return String.join("-", input); }
        private static String staticOnly(int input) { return "static:" + input; }
        private String staticOnly(String input) { return "instance"; }
        private String throwsHost() { throw FAILURE; }
    }
    static class Built {
        final String selected;
        private Built(int value) { selected = "int:" + value; }
        private Built(String value) { selected = "string:" + value; }
    }
    static class ExactNamed { private void target(int value, String[] array) { } }
    static final IllegalStateException FAILURE = new IllegalStateException("host failure");

    @Test public void primitiveArrayDescriptorAndNestedClassResolution() {
        ClassLoader loader = getClass().getClassLoader();
        assertSame(int.class, HookHelpers.findClass("int", loader));
        assertSame(int[][].class, HookHelpers.findClass("int[][]", loader));
        assertSame(String[].class, HookHelpers.findClass("[Ljava.lang.String;", loader));
        assertSame(Child.class, HookHelpers.findClass(Child.class.getName().replace('$', '.'), loader));
        assertNull(HookHelpers.findClassIfExists("missing.runtime.Class", loader));
    }
    @Test public void exactLookupSupportsClassNameParameterTokensAndInheritance() {
        Method method = HookHelpers.findMethodExact(ExactNamed.class, "target", "int", "java.lang.String[]");
        assertArrayEquals(new Class<?>[]{int.class, String[].class}, method.getParameterTypes());
        assertEquals(Parent.class, HookHelpers.findMethodExact(Child.class, "inherited", String.class).getDeclaringClass());
        assertEquals("parent:x", HookHelpers.callMethod(new Child(), "inherited", "x"));
        assertNull(HookHelpers.findMethodExactIfExists(Child.class, "missing", String.class));
    }
    @Test public void privateInheritedFieldsReadWriteWithoutFirstFieldGuessing() {
        Child child = new Child();
        assertEquals(7, HookHelpers.getIntField(child, "count"));
        HookHelpers.setIntField(child, "count", 11);
        HookHelpers.setBooleanField(child, "enabled", true);
        HookHelpers.setObjectField(child, "value", "changed");
        assertEquals(11, HookHelpers.getIntField(child, "count"));
        assertTrue(HookHelpers.getBooleanField(child, "enabled"));
        assertEquals("changed", HookHelpers.getObjectField(child, "value"));
        assertNull(HookHelpers.findFieldIfExists(Child.class, "absent"));
    }
    @Test public void boxingExactExplicitPrimitiveAndNumericWidening() {
        Child child = new Child();
        assertEquals("boxed", HookHelpers.callMethod(child, "boxed", Integer.valueOf(3)));
        assertEquals("primitive", HookHelpers.callMethod(child, "boxed", new Class<?>[]{int.class}, 3));
        assertEquals("long:3", HookHelpers.callMethod(child, "widen", 3));
    }
    @Test public void nullSelectsUniqueMoreSpecificOverloadButRejectsUnrelatedAmbiguity() {
        Child child = new Child();
        assertEquals("string", HookHelpers.callMethod(child, "choose", (Object) null));
        assertThrows(IllegalArgumentException.class, () -> HookHelpers.callMethod(child, "ambiguous", (Object) null));
        assertThrows(NoSuchMethodError.class, () -> HookHelpers.callMethod(child, "widen", (Object) null));
    }
    @Test public void varargsExpandedAndArrayArgumentsRemainDistinct() {
        Child child = new Child();
        assertEquals("a-b", HookHelpers.callMethod(child, "join", "a", "b"));
        assertEquals("a-b", HookHelpers.callMethod(child, "join", (Object) new String[]{"a", "b"}));
        assertEquals("", HookHelpers.callMethod(child, "join"));
    }
    @Test public void staticLookupRejectsInstanceOverloadAndUnwrapsHostExceptions() {
        assertEquals("static:4", HookHelpers.callStaticMethod(Child.class, "staticOnly", 4));
        assertThrows(NoSuchMethodError.class, () -> HookHelpers.callStaticMethod(Child.class, "staticOnly", "x"));
        HookHelpers.InvocationTargetError failure = assertThrows(HookHelpers.InvocationTargetError.class,
                () -> HookHelpers.callMethod(new Child(), "throwsHost"));
        assertSame(FAILURE, failure.getCause());
    }
    @Test public void constructorsResolveBoxingAndNullWithoutUnsafe() {
        assertEquals("int:4", ((Built) HookHelpers.newInstance(Built.class, 4)).selected);
        assertEquals("string:null", ((Built) HookHelpers.newInstance(Built.class, (Object) null)).selected);
        HostContext host = new HostContext("test.host", null, getClass().getClassLoader(), null, true);
        assertEquals("test.host", host.packageName);
        assertEquals("test.host", host.processName);
        assertTrue(host.isFirstApplication);
    }
}
