package com.adhook.engine;

import android.view.View;

import com.adhook.core.ViewCollapser;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Leases are attributed to (matching source View, immutable rule, resolved target).
 * A miss on an unrelated child can never restore another child's target, or a native collapse.
 * WeakHashMap values deliberately contain neither the source nor a strong target reference.
 */
final class RuleOwnership {
    private final Map<View, Map<RuleKey, Lease>> sources = new WeakHashMap<>();
    private final View.OnAttachStateChangeListener detachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) {
            releaseSource(view);
        }
    };

    private static final class Lease {
        final Object owner = new Object();
        final WeakReference<View> target;

        Lease(View target) {
            this.target = new WeakReference<>(target);
        }

        void release() {
            View view = target.get();
            if (view != null) ViewCollapser.reset(view, owner);
        }
    }

    /** A short-lived scan snapshot only; never stored in a static map or listener. */
    record Source(View view, String packageName) { }

    synchronized Set<RuleKey> reconcile(View source, Map<RuleKey, View> desired) {
        if (desired.isEmpty()) {
            releaseSource(source);
            return Collections.emptySet();
        }
        Map<RuleKey, Lease> previous = sources.get(source);
        if (previous == null) previous = Collections.emptyMap();
        Map<RuleKey, Lease> next = new LinkedHashMap<>();
        Set<RuleKey> acquired = new LinkedHashSet<>();
        // Acquire replacements first: shared targets keep their original, uncollapsed snapshot.
        for (Map.Entry<RuleKey, View> entry : desired.entrySet()) {
            View target = entry.getValue();
            if (target == null) continue;
            Lease lease = previous.get(entry.getKey());
            if (lease == null || lease.target.get() != target) {
                lease = new Lease(target);
                acquired.add(entry.getKey());
            }
            next.put(entry.getKey(), lease);
            ViewCollapser.preventExpansion(target, lease.owner);
        }
        for (Map.Entry<RuleKey, Lease> entry : previous.entrySet()) {
            if (next.get(entry.getKey()) != entry.getValue()) entry.getValue().release();
        }
        if (next.isEmpty()) {
            sources.remove(source);
            source.removeOnAttachStateChangeListener(detachListener);
        } else {
            boolean wasTracked = sources.containsKey(source);
            sources.put(source, next);
            if (!wasTracked) source.addOnAttachStateChangeListener(detachListener);
        }
        return acquired;
    }

    synchronized void releaseSource(View source) {
        if (source == null) return;
        Map<RuleKey, Lease> leases = sources.remove(source);
        if (leases == null) return;
        source.removeOnAttachStateChangeListener(detachListener);
        for (Lease lease : leases.values()) lease.release();
    }

    /** Rebinding/recycling restores only leases sourced from or targeting this reusable item. */
    synchronized void releaseTree(View root) {
        if (root == null) return;
        for (View source : new ArrayList<>(sources.keySet())) {
            if (source == null) continue;
            if (isInTree(source, root)) {
                releaseSource(source);
                continue;
            }
            Map<RuleKey, Lease> leases = sources.get(source);
            if (leases == null) continue;
            for (var iterator = leases.entrySet().iterator(); iterator.hasNext();) {
                Lease lease = iterator.next().getValue();
                View target = lease.target.get();
                if (target == null || isInTree(target, root)) {
                    iterator.remove();
                    lease.release();
                }
            }
            removeIfEmpty(source, leases);
        }
    }

    /** Switch changes, deletions and same-id edits invalidate only that host's affected rules. */
    synchronized void retainRules(String packageName, Set<RuleKey> active) {
        for (View source : new ArrayList<>(sources.keySet())) {
            if (source == null) continue;
            Map<RuleKey, Lease> leases = sources.get(source);
            if (leases == null) continue;
            for (var iterator = leases.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<RuleKey, Lease> entry = iterator.next();
                if (packageName.equals(entry.getKey().packageName()) && !active.contains(entry.getKey())) {
                    iterator.remove();
                    entry.getValue().release();
                }
            }
            removeIfEmpty(source, leases);
        }
    }

    synchronized List<Source> snapshotSources() {
        List<Source> result = new ArrayList<>();
        for (Map.Entry<View, Map<RuleKey, Lease>> entry : sources.entrySet()) {
            View view = entry.getKey();
            if (view == null) continue;
            Set<String> packages = new LinkedHashSet<>();
            for (RuleKey key : entry.getValue().keySet()) packages.add(key.packageName());
            for (String packageName : packages) result.add(new Source(view, packageName));
        }
        return result;
    }

    private void removeIfEmpty(View source, Map<RuleKey, Lease> leases) {
        if (!leases.isEmpty()) return;
        sources.remove(source);
        source.removeOnAttachStateChangeListener(detachListener);
    }

    private static boolean isInTree(View view, View root) {
        for (int depth = 0; view != null && depth < 128; depth++) {
            if (view == root) return true;
            view = view.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }
}
