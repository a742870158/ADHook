package com.adhook.runtime;

/** Replacement callbacks still travel through the same native API101 adapter. */
public abstract class HookReplacement extends HookCallback {
    public static final HookReplacement DO_NOTHING = returnConstant(null);
    public HookReplacement() { }
    public HookReplacement(int priority) { super(priority); }
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
    @Override protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        // Replacement failure deliberately returns to the adapter's safe original-method fallback.
        param.setResult(replaceHookedMethod(param));
    }
    public static HookReplacement returnConstant(Object value) { return returnConstant(PRIORITY_DEFAULT, value); }
    public static HookReplacement returnConstant(int priority, Object value) {
        return new HookReplacement(priority) {
            @Override protected Object replaceHookedMethod(MethodHookParam param) { return value; }
        };
    }
}
