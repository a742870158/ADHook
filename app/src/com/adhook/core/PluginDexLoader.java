package com.adhook.core;

/**
 * Host-process loader for an installed plugin dex.
 *
 * <p>Production uses an in-memory dex class loader whose parent is the module class loader, so
 * plugin classes resolve the base ABI classes shipped in this APK. Tests replace it without
 * touching the native framework.</p>
 */
public interface PluginDexLoader {

    ClassLoader load(byte[] dex, ClassLoader parent) throws Throwable;

    /** In-memory loader: no plugin bytecode is copied to host-owned storage. */
    final class InMemory implements PluginDexLoader {
        @Override
        public ClassLoader load(byte[] dex, ClassLoader parent) throws Throwable {
            if (dex == null || dex.length == 0) throw new IllegalArgumentException("Plugin dex is empty");
            // android.os.Build.VERSION_CODES.O(26) API; the module supports API 31+.
            return new dalvik.system.InMemoryDexClassLoader(java.nio.ByteBuffer.wrap(dex), parent);
        }
    }
}
