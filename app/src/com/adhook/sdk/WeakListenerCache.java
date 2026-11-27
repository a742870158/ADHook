package com.adhook.sdk;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 广告实例到 listener 的非拥有型索引。
 * 弱键还不够：listener 常经 Activity 反向持有广告实例，强 value 会让整个环永远存活。
 * listener 的正常生命周期由 SDK 实例/宿主维护；查询后用局部强引用完成本次失败通知。
 */
final class WeakListenerCache {
    private final Map<Object, WeakReference<Object>> listeners = new WeakHashMap<>();

    synchronized void put(Object adInstance, Object listener) {
        if (adInstance == null) {
            return;
        }
        if (listener == null) {
            listeners.remove(adInstance);
        } else {
            listeners.put(adInstance, new WeakReference<>(listener));
        }
    }

    synchronized Object get(Object adInstance) {
        if (adInstance == null) {
            return null;
        }
        WeakReference<Object> reference = listeners.get(adInstance);
        Object listener = reference != null ? reference.get() : null;
        if (reference != null && listener == null) {
            listeners.remove(adInstance);
        }
        return listener;
    }
}
