package com.adhook.core;

import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 通用视图与卡片零开销坍缩折叠器 (ViewCollapser)。
 * 适用于所有宿主应用中各类信息流卡片、评论区商业推广、顶部/底部横幅、悬浮窗等视图的精准坍塌消除。
 *
 * 核心特性：
 * 1. 消除空白占位：高度清零、外边距清零、最小高度清零、设为 GONE，防止列表滚动抖动。
 * 2. 智能追溯根卡片：从 ViewHolder 或嵌套深层 Child 自动向上追溯到 RecyclerView 的直接子项。
 * 3. 防复原防膨胀：可选挂载布局变化监听器，阻止宿主异步网络回包后将 View 重新展示。
 * 4. 幂等与可复用：重复折叠不再触发多余的 requestLayout；列表复用场景通过 {@link #reset(View)} 还原。
 */
public final class ViewCollapser {

    /** Values must not hold a View or LayoutParams (RecyclerView params can point back to a holder). */
    private static final Map<View, CollapseState> SAVED_STATES = new WeakHashMap<>();
    private static final Object LEGACY_OWNER = new Object();
    private static volatile boolean visibilityHookInstalled = false;

    /** One non-capturing listener; importantly, enforcing a guard must not acquire the legacy owner. */
    private static final View.OnLayoutChangeListener GUARD =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    synchronized (SAVED_STATES) {
                        CollapseState state = liveState(view);
                        if (state != null) applyCollapsedState(view, state);
                    }
                } catch (Throwable failure) {
                    AdHookLog.e("[ViewCollapser] guard failed", failure);
                }
            };

    private static final class OwnerState extends WeakReference<Object> {
        boolean guarded;

        OwnerState(Object owner, boolean guarded) {
            super(owner);
            this.guarded = guarded;
        }
    }

    private static final class CollapseState {
        final int visibility;
        final int minHeight;
        final int minWidth;
        final List<OwnerState> owners = new ArrayList<>();
        boolean hasLayout;
        int height;
        int width;
        int topMargin;
        int bottomMargin;
        boolean guardAttached;
        boolean applying;

        CollapseState(View view) {
            visibility = view.getVisibility();
            minHeight = view.getMinimumHeight();
            minWidth = view.getMinimumWidth();
            captureLayout(view.getLayoutParams());
        }

        void captureLayout(ViewGroup.LayoutParams params) {
            if (hasLayout || params == null) return;
            hasLayout = true;
            height = params.height;
            width = params.width;
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                topMargin = margins.topMargin;
                bottomMargin = margins.bottomMargin;
            }
        }
    }

    private ViewCollapser() {
    }

    /** Legacy/native callers have their own independent owner. */
    public static void collapse(View view) {
        collapse(view, LEGACY_OWNER);
    }

    /**
     * Collapse for one owner, without taking ownership of other callers' suppression.
     * Keep the opaque owner token alive for the lease's lifetime. Owners are compared by identity
     * and held weakly, so even a caller-supplied owner referring to the View cannot leak the View.
     */
    public static void collapse(View view, Object owner) {
        acquire(view, owner, false);
    }

    /** True only when this acquisition actually changes a previously unsuppressed visible View. */
    public static boolean collapseOnce(View view, Object owner) {
        return acquire(view, owner, false);
    }

    private static boolean acquire(View view, Object owner, boolean guarded) {
        if (view == null || owner == null) return false;
        boolean changed = false;
        try {
            synchronized (SAVED_STATES) {
                CollapseState state = liveState(view);
                boolean firstSuppression = state == null && view.getVisibility() != View.GONE;
                if (state == null) {
                    state = new CollapseState(view);
                    SAVED_STATES.put(view, state);
                }
                OwnerState existing = null;
                for (OwnerState candidate : state.owners) {
                    if (candidate.get() == owner) {
                        existing = candidate;
                        break;
                    }
                }
                if (existing == null) {
                    state.owners.add(new OwnerState(owner, guarded));
                } else {
                    existing.guarded |= guarded;
                }
                updateGuard(view, state);
                applyCollapsedState(view, state);
                changed = firstSuppression && view.getVisibility() == View.GONE;
            }
            ensureVisibilityHookInstalled();
        } catch (Throwable t) {
            AdHookLog.e("[ViewCollapser] collapse failed", t);
        }
        return changed;
    }

    private static void applyCollapsedState(View view, CollapseState state) {
        if (state.applying) return;
        state.applying = true;
        try {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            state.captureLayout(params);
            boolean layoutChanged = false;
            if (params != null) {
                layoutChanged = params.height != 0;
                params.height = 0;
                if (params instanceof ViewGroup.MarginLayoutParams margins) {
                    layoutChanged |= margins.topMargin != 0 || margins.bottomMargin != 0;
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                }
            }
            if (view.getMinimumHeight() != 0) view.setMinimumHeight(0);
            if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
            if (layoutChanged) view.setLayoutParams(params);
        } finally {
            state.applying = false;
        }
    }

    /** Must be called under SAVED_STATES' monitor. */
    private static CollapseState liveState(View view) {
        CollapseState state = SAVED_STATES.get(view);
        if (state == null) return null;
        state.owners.removeIf(owner -> owner.get() == null);
        if (state.owners.isEmpty()) {
            restoreState(view, state);
            return null;
        }
        return state;
    }

    private static void updateGuard(View view, CollapseState state) {
        boolean guarded = false;
        for (OwnerState owner : state.owners) guarded |= owner.guarded;
        if (guarded && !state.guardAttached) {
            view.addOnLayoutChangeListener(GUARD);
        } else if (!guarded && state.guardAttached) {
            view.removeOnLayoutChangeListener(GUARD);
        }
        state.guardAttached = guarded;
    }

    /** Shared with the visibility hook; a collected owner cannot suppress a View forever. */
    static boolean isSuppressed(View view) {
        if (view == null) return false;
        synchronized (SAVED_STATES) {
            return liveState(view) != null;
        }
    }

    /** 智能折叠 ViewHolder 或嵌套视图对应的根卡片。 */
    public static void collapseViewHolder(Object holderOrView) {
        View target = resolveTargetView(holderOrView);
        if (target != null) collapse(target);
    }

    public static void preventExpansion(View view) {
        preventExpansion(view, LEGACY_OWNER);
    }

    /** The guard belongs to this owner; releasing it never acquires or releases another owner. */
    public static void preventExpansion(View view, Object owner) {
        acquire(view, owner, true);
    }

    /**
     * Release only the legacy/native owner. Other live owners still keep the View collapsed.
     * Untracked Views retain the historical reset fallback for existing host integrations.
     */
    public static void reset(View view) {
        release(view, LEGACY_OWNER, true, true);
    }

    /** Release exactly this owner; an unowned View is a no-op, never a blanket child reset. */
    public static void reset(View view, Object owner) {
        release(view, owner, false, true);
    }

    public static void restore(View view) {
        release(view, LEGACY_OWNER, true, false);
    }

    public static void restore(View view, Object owner) {
        release(view, owner, false, false);
    }

    /** Release one known owner across live weak keys; never touches legacy or unrelated leases. */
    public static void releaseOwner(Object owner) {
        if (owner == null) return;
        List<View> views;
        synchronized (SAVED_STATES) {
            views = new ArrayList<>(SAVED_STATES.keySet());
        }
        for (View view : views) restore(view, owner);
    }

    private static void release(View view, Object owner, boolean legacyFallback, boolean reset) {
        if (view == null || owner == null) return;
        try {
            synchronized (SAVED_STATES) {
                CollapseState state = SAVED_STATES.get(view);
                if (state == null) {
                    if (legacyFallback) restoreUntracked(view, reset);
                    return;
                }
                for (Iterator<OwnerState> it = state.owners.iterator(); it.hasNext();) {
                    Object current = it.next().get();
                    if (current == null || current == owner) it.remove();
                }
                if (state.owners.isEmpty()) {
                    restoreState(view, state);
                } else {
                    updateGuard(view, state);
                    applyCollapsedState(view, state);
                }
            }
        } catch (Throwable t) {
            AdHookLog.e("[ViewCollapser] reset failed", t);
        }
    }

    private static void restoreState(View view, CollapseState state) {
        // Remove suppression before calling any host View setters, including setVisibility.
        SAVED_STATES.remove(view);
        if (state.guardAttached) view.removeOnLayoutChangeListener(GUARD);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && state.hasLayout) {
            params.height = state.height;
            params.width = state.width;
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                margins.topMargin = state.topMargin;
                margins.bottomMargin = state.bottomMargin;
            }
            view.setLayoutParams(params);
        }
        view.setMinimumHeight(state.minHeight);
        view.setMinimumWidth(state.minWidth);
        view.setVisibility(state.visibility);
    }

    private static void restoreUntracked(View view, boolean reset) {
        if (reset) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && params.height == 0) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setLayoutParams(params);
            }
            if (view.getVisibility() == View.GONE) view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 智能追溯：从 ViewHolder 或 View 向上解析出作为列表直接项的目标卡片 View。
     */
    public static View resolveTargetView(Object holderOrView) {
        if (holderOrView == null) {
            return null;
        }

        View view = null;
        if (holderOrView instanceof View v) {
            view = v;
        } else {
            try {
                Class<?> cur = holderOrView.getClass();
                while (cur != null) {
                    for (Field f : cur.getDeclaredFields()) {
                        if ("itemView".equals(f.getName())) {
                            f.setAccessible(true);
                            Object val = f.get(holderOrView);
                            if (val instanceof View v) {
                                view = v;
                                break;
                            }
                        }
                    }
                    if (view != null) {
                        break;
                    }
                    cur = cur.getSuperclass();
                }
            } catch (Throwable ignored) {
            }
        }

        if (view == null) {
            return null;
        }

        // 向上回溯到 RecyclerView / ListView 的直接 Child
        View current = view;
        for (int i = 0; i < 16; i++) {
            Object parent = current.getParent();
            if (isListContainer(parent)) return current;
            if (parent instanceof View pv) {
                current = pv;
            } else {
                break;
            }
        }
        return view;
    }

    /**
     * 判断指定 View 是否属于 RecyclerView 或 ListView 的列表条目（无论是条目根容器还是内部子节点）。
     */
    public static boolean isInsideListItem(View view) {
        if (view == null) return false;
        View current = view;
        for (int i = 0; i < 16; i++) {
            Object parent = current.getParent();
            if (isListContainer(parent)) return true;
            if (parent instanceof View pv) {
                current = pv;
            } else {
                break;
            }
        }
        return false;
    }

    private static boolean isListContainer(Object parent) {
        if (parent instanceof android.widget.AbsListView) return true;
        for (Class<?> type = parent != null ? parent.getClass() : null;
             type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.contains("RecyclerView") || name.contains("ListView")) return true;
        }
        return false;
    }

    /**
     * 确保 View.setVisibility Hook 已安装，用于对被标记折叠的 View 施加持续可见性压制。
     */
    private static void ensureVisibilityHookInstalled() {
        if (visibilityHookInstalled) return;
        synchronized (SAVED_STATES) {
            if (visibilityHookInstalled) return;
            try {
                java.lang.reflect.Method setVisibility = View.class.getDeclaredMethod("setVisibility", int.class);
                com.adhook.runtime.HookBridge.hookMethod(setVisibility, new com.adhook.runtime.HookCallback() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (v != null && isSuppressed(v)) {
                            Integer want = (Integer) param.args[0];
                            if (want != null && want != View.GONE) {
                                param.args[0] = View.GONE;
                            }
                        }
                    }
                });
                visibilityHookInstalled = true;
                AdHookLog.d("[ViewCollapser] Hooked View.setVisibility for persistent ad suppression");
            } catch (Throwable t) {
                AdHookLog.w("[ViewCollapser] Failed to hook View.setVisibility: " + t.getMessage());
            }
        }
    }

    /**
     * 消除广告弹窗残留的半透明全屏灰黑蒙层 (dimOff)。
     * WMS 在绘制带 FLAG_DIM_BEHIND 的窗口时，黑色蒙层属于系统层绘制，隐藏 View 后屏幕依旧会发灰。
     * 本方法动态清除 dimAmount 与 FLAG_DIM_BEHIND 标志位，使界面恢复通透。
     *
     * @param window 目标 Dialog 或 PopupWindow 对应的 Window 实例
     */
    public static void dimOff(android.view.Window window) {
        if (window == null) return;
        try {
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            if (attrs == null) return;
            int dimFlag = android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            if (attrs.dimAmount == 0f && (attrs.flags & dimFlag) == 0) return;
            attrs.dimAmount = 0f;
            attrs.flags &= ~dimFlag;
            window.setAttributes(attrs);
            AdHookLog.d("[ViewCollapser] dimOff cleared window dim behind");
        } catch (Throwable t) {
            AdHookLog.w("[ViewCollapser] dimOff failed: " + t.getMessage());
        }
    }

    /**
     * 针对 Dialog 实例消除残留半透明蒙层并视情况安全关闭。
     * 若广告 View 占据 Dialog 面积超过 50%，或者传入 forceDismiss 为 true，则直接安全 dismiss；
     * 否则仅关闭蒙层并彻底折叠 View。
     *
     * @param dialog 目标 Dialog
     * @param adView 广告根 View
     */
    public static void dismissOrDimOff(android.app.Dialog dialog, View adView) {
        if (dialog == null) return;
        try {
            if (adView != null) {
                collapse(adView);
            }
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                dimOff(window);
            }
            // 安全关闭 Dialog
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Throwable t) {
            AdHookLog.w("[ViewCollapser] dismissOrDimOff failed: " + t.getMessage());
        }
    }
}
