package com.adhook.inspector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.CustomRule;
import com.adhook.core.ViewCollapser;
import com.adhook.core.AdHookLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 宿主内可视化元素拾取器 (VisualInspector)。
 * 基于 Activity 顶层子窗口 (Sub-Window)，免系统悬浮窗权限、绝对置顶防穿透、所见即所得。
 */
public final class VisualInspector {

    private static final String TAG = "[VisualInspector]";
    private static final java.util.concurrent.ExecutorService SAVE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "adhook-inspector-save");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile boolean sInstalled = false;
    private static View sCurrentFab = null;
    private static InspectorOverlayView sCurrentOverlay = null;
    private static java.lang.ref.WeakReference<Activity> sCurrentActivityRef = null;
    /** scheduleAttachFab 短时间去重（onPostResume / onWindowFocusChanged 会连续触发），弱引用避免持有 Activity。 */
    private static java.lang.ref.WeakReference<Activity> sLastScheduledActivityRef = null;
    private static long sLastScheduleAt = 0L;

    private VisualInspector() {
    }

    /**
     * 在宿主进程中挂载拾取器生命周期监听。
     */
    public static void install(final ClassLoader classLoader, final String packageName) {
        if (sInstalled || classLoader == null || packageName == null) {
            return;
        }

        try {
            HookCallback resumeHook = new HookCallback() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (activity != null && !activity.isFinishing()) {
                        ConfigClient.init(activity.getApplicationContext());
                        scheduleAttachFab(activity, packageName, false);
                    }
                }
            };

            // 1. Hook Activity.onPostResume
            HookHelpers.findAndHookMethod(Activity.class, "onPostResume", resumeHook);

            // 2. Hook Activity.onWindowFocusChanged 彻底保证冷启动窗口获焦瞬间 Token 100% 就绪
            HookHelpers.findAndHookMethod(
                    Activity.class,
                    "onWindowFocusChanged",
                    boolean.class,
                    new HookCallback() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            boolean hasFocus = (boolean) param.args[0];
                            Activity activity = (Activity) param.thisObject;
                            if (hasFocus && activity != null && !activity.isFinishing()) {
                                ConfigClient.init(activity.getApplicationContext());
                                // 获焦是冷启动窗口就绪的权威时刻，强制检查挂载，不受 300ms 抑制拦截
                                scheduleAttachFab(activity, packageName, true);
                            }
                        }
                    }
            );

            // 3. Hook Activity.onAttachedToWindow 保障冷启动 DecorView 附着瞬间第一时间触发
            HookHelpers.findAndHookMethod(
                    Activity.class,
                    "onAttachedToWindow",
                    new HookCallback() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Activity activity = (Activity) param.thisObject;
                            if (activity != null && !activity.isFinishing()) {
                                ConfigClient.init(activity.getApplicationContext());
                                scheduleAttachFab(activity, packageName, true);
                            }
                        }
                    }
            );

            // 4. Hook Activity.onDestroy 进行清理，若发生页面切换则自动衔接至前台就绪 Activity
            HookHelpers.findAndHookMethod(
                    Activity.class,
                    "onDestroy",
                    new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Activity activity = (Activity) param.thisObject;
                            if (activity != null) {
                                if (sCurrentActivityRef != null && sCurrentActivityRef.get() == activity) {
                                    dismissAll(activity);
                                    // 若前一个启动页 (如 SplashActivity) 销毁，而下一个主界面已就绪，立即重连
                                    Activity nextActive = (sLastScheduledActivityRef != null) ? sLastScheduledActivityRef.get() : null;
                                    if (nextActive != null && nextActive != activity && !nextActive.isFinishing()) {
                                        nextActive.getWindow().getDecorView().post(() -> checkAndAttachFab(nextActive, packageName));
                                    }
                                }
                            }
                        }
                    }
            );

            sInstalled = true;
            AdHookLog.i(TAG + " Installed WindowManager hook for " + packageName);
        } catch (Throwable t) {
            AdHookLog.w(TAG + " install failed: " + t.getMessage());
        }
    }

    /**
     * 安全调度挂载悬浮球（解决冷启动 Window Token 尚未生成的异常）。
     */
    public static void scheduleAttachFab(final Activity activity, final String packageName) {
        scheduleAttachFab(activity, packageName, false);
    }

    public static void scheduleAttachFab(final Activity activity, final String packageName, boolean forceCheck) {
        if (activity == null || activity.isFinishing()) return;

        // 若当前该 Activity 已成功挂载悬浮球且非强制检查，才做 300ms 去重，防止同一页面重复读取与调度；
        // 若当前尚未附着悬浮球 (sCurrentFab == null) 或 Activity 发生流转，严禁拦截，立即执行调度！
        long now = System.currentTimeMillis();
        Activity currentAttached = (sCurrentActivityRef != null) ? sCurrentActivityRef.get() : null;
        boolean hasValidFab = (sCurrentFab != null && currentAttached == activity);

        if (!forceCheck && hasValidFab) {
            Activity lastScheduled = (sLastScheduledActivityRef != null) ? sLastScheduledActivityRef.get() : null;
            if (lastScheduled == activity && now - sLastScheduleAt < 300L) {
                return;
            }
        }
        sLastScheduledActivityRef = new java.lang.ref.WeakReference<>(activity);
        sLastScheduleAt = now;

        // 配置后台刷新；主线程挂载/操作仅查询内存总开关和拾取器开关。
        boolean enabled = isInspectorEnabledCached(packageName);
        AdHookLog.i(TAG + " scheduleAttachFab: " + activity.getClass().getSimpleName() + ", enabled=" + enabled);
        if (!enabled) {
            dismissAll(activity);
            return;
        }

        View decorView = (activity.getWindow() != null) ? activity.getWindow().peekDecorView() : null;
        if (decorView == null || !decorView.isAttachedToWindow() || decorView.getWindowToken() == null) {
            if (decorView != null) {
                decorView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        v.removeOnAttachStateChangeListener(this);
                        v.post(() -> checkAndAttachFab(activity, packageName));
                        v.postDelayed(() -> checkAndAttachFab(activity, packageName), 80);
                    }
                    @Override
                    public void onViewDetachedFromWindow(View v) {}
                });
                decorView.post(() -> checkAndAttachFab(activity, packageName));
                decorView.postDelayed(() -> checkAndAttachFab(activity, packageName), 60);
                decorView.postDelayed(() -> checkAndAttachFab(activity, packageName), 180);
                decorView.postDelayed(() -> checkAndAttachFab(activity, packageName), 450);
            } else {
                try {
                    View decor = activity.getWindow().getDecorView();
                    if (decor != null) {
                        decor.post(() -> checkAndAttachFab(activity, packageName));
                        decor.postDelayed(() -> checkAndAttachFab(activity, packageName), 100);
                        decor.postDelayed(() -> checkAndAttachFab(activity, packageName), 300);
                    }
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        checkAndAttachFab(activity, packageName);
    }

    /**
     * 检查配置，在 WindowManager 上贴附/移除悬浮球。
     */
    public static synchronized void checkAndAttachFab(Activity activity, String packageName) {
        if (activity == null || activity.isFinishing()) return;

        boolean enabled = isInspectorEnabledCached(packageName);
        if (!enabled) {
            dismissAll(activity);
            return;
        }

        // 若前台 Activity 发生流转（如 Splash -> Main，或 Main -> Detail）
        Activity currentAttached = (sCurrentActivityRef != null) ? sCurrentActivityRef.get() : null;
        if (currentAttached != activity) {
            if (currentAttached != null) {
                dismissAll(currentAttached);
            } else if (sCurrentFab != null) {
                dismissAll(null);
            }
        }

        if (sCurrentFab != null && sCurrentActivityRef != null && sCurrentActivityRef.get() == activity) {
            return;
        }

        View decorView = (activity.getWindow() != null) ? activity.getWindow().peekDecorView() : null;
        if (decorView == null || decorView.getWindowToken() == null) {
            if (decorView != null) {
                decorView.postDelayed(() -> checkAndAttachFab(activity, packageName), 100);
            }
            return;
        }

        try {
            WindowManager wm = activity.getWindowManager();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.x = dp2px(activity, 12);
            lp.y = 0;

            View fab = createFabView(activity, packageName, lp, wm);
            wm.addView(fab, lp);
            sCurrentFab = fab;
            sCurrentActivityRef = new java.lang.ref.WeakReference<>(activity);
            AdHookLog.i(TAG + " Attached WindowManager FAB for " + packageName + " on " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            AdHookLog.w(TAG + " attachFab fallback: " + t.getMessage());
            if (decorView != null) {
                decorView.postDelayed(() -> checkAndAttachFab(activity, packageName), 200);
            }
        }
    }

    private static View createFabView(final Activity activity, final String packageName,
                                      final WindowManager.LayoutParams lp, final WindowManager wm) {
        final InspectorFabView fab = new InspectorFabView(activity, packageName);

        // 稳健的上下拖拽与点击（防抖判定，阈值 16dp）
        fab.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private float lastRawY;
            private boolean isDrag = false;
            private final int touchSlop = dp2px(activity, 16);

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isInspectorEnabledCached(packageName)) {
                    dismissAll(activity);
                    return false;
                }
                AdHookLog.i(TAG + " fab onTouch action=" + event.getAction() + " (" + event.getRawX() + ", " + event.getRawY() + ")");
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downRawY = event.getRawY();
                        lastRawY = event.getRawY();
                        isDrag = false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float totalDy = event.getRawY() - downRawY;
                        if (Math.abs(totalDy) > touchSlop) {
                            isDrag = true;
                            lp.y += (int) (event.getRawY() - lastRawY);
                            wm.updateViewLayout(v, lp);
                        }
                        lastRawY = event.getRawY();
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            Activity target = (sCurrentActivityRef != null && sCurrentActivityRef.get() != null) ? sCurrentActivityRef.get() : activity;
                            AdHookLog.i(TAG + " FAB clicked -> toggleInspectMode on " + target.getClass().getSimpleName());
                            toggleInspectMode(target, packageName);
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        return fab;
    }

    /**
     * 高级极简圆形悬浮球 (InspectorFabView)。
     * 继承 TextView 保证最完美的 Window 测量与触控链路，Canvas 绘制科技瞄准准星。
     */
    private static class InspectorFabView extends TextView {
        private final Activity activity;
        private final String packageName;
        private final Paint bgPaint;
        private final Paint borderPaint;
        private final Paint reticlePaint;
        private final Paint pointPaint;
        private final Paint closePaint;
        private boolean isInspectMode = false;

        public InspectorFabView(Activity activity, String packageName) {
            super(activity);
            this.activity = activity;
            this.packageName = packageName;

            int size = dp2px(activity, 46);
            setWidth(size);
            setHeight(size);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(false);

            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(Color.parseColor("#E6181F2A")); // 半透明深空灰黑

            borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp2px(activity, 1.2f));
            borderPaint.setColor(Color.parseColor("#4DFFFFFF"));

            reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            reticlePaint.setStyle(Paint.Style.STROKE);
            reticlePaint.setStrokeWidth(dp2px(activity, 1.8f));
            reticlePaint.setColor(Color.parseColor("#00E5FF")); // 荧光青蓝

            pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            pointPaint.setStyle(Paint.Style.FILL);
            pointPaint.setColor(Color.parseColor("#00E5FF"));

            closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            closePaint.setStyle(Paint.Style.STROKE);
            closePaint.setStrokeCap(Paint.Cap.ROUND);
            closePaint.setStrokeWidth(dp2px(activity, 2.2f));
            closePaint.setColor(Color.WHITE);

            setElevation(dp2px(activity, 8));
        }

        @Override
        public boolean performClick() {
            super.performClick();
            Activity target = (sCurrentActivityRef != null && sCurrentActivityRef.get() != null) ? sCurrentActivityRef.get() : activity;
            AdHookLog.i(TAG + " performClick triggered on " + target.getClass().getSimpleName());
            toggleInspectMode(target, packageName);
            return true;
        }

        public void setInspectMode(boolean inspectMode) {
            this.isInspectMode = inspectMode;
            if (inspectMode) {
                bgPaint.setColor(Color.parseColor("#E6D32F2F")); // 拾取模式：半透明绯红
            } else {
                bgPaint.setColor(Color.parseColor("#E6181F2A")); // 正常模式：半透明深空灰黑
            }
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = dp2px(activity, 46);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = (getWidth() - dp2px(activity, 2.4f)) / 2f;

            // 1. 绘制圆形底色与边框
            canvas.drawCircle(cx, cy, r, bgPaint);
            canvas.drawCircle(cx, cy, r, borderPaint);

            if (isInspectMode) {
                // 绘制加粗关闭“✕”
                float halfCross = dp2px(activity, 6.5f);
                canvas.drawLine(cx - halfCross, cy - halfCross, cx + halfCross, cy + halfCross, closePaint);
                canvas.drawLine(cx + halfCross, cy - halfCross, cx - halfCross, cy + halfCross, closePaint);
            } else {
                // 绘制高科技瞄准镜准星 (Reticle)
                float reticleR = dp2px(activity, 8.5f);
                canvas.drawCircle(cx, cy, reticleR, reticlePaint);

                // 十字延伸刻度线
                float tickStart = reticleR - dp2px(activity, 1.5f);
                float tickEnd = reticleR + dp2px(activity, 4f);
                // 上
                canvas.drawLine(cx, cy - tickStart, cx, cy - tickEnd, reticlePaint);
                // 下
                canvas.drawLine(cx, cy + tickStart, cx, cy + tickEnd, reticlePaint);
                // 左
                canvas.drawLine(cx - tickStart, cy, cx - tickEnd, cy, reticlePaint);
                // 右
                canvas.drawLine(cx + tickStart, cy, cx + tickEnd, cy, reticlePaint);

                // 中心聚焦光点
                canvas.drawCircle(cx, cy, dp2px(activity, 2f), pointPaint);
            }
        }
    }

    public static synchronized void toggleInspectMode(Activity activity, String packageName) {
        if (sCurrentOverlay != null) {
            dismissOverlay(activity);
        } else {
            startInspectMode(activity, packageName);
        }
    }

    /** Recheck the observed foreground activity after background configuration publication. */
    public static void onHookConfigChanged(String packageName) {
        if (!sInstalled || packageName == null) return;
        android.os.Looper main = android.os.Looper.getMainLooper();
        if (main == null) return;
        new android.os.Handler(main).post(() -> {
            Activity activity = sLastScheduledActivityRef != null ? sLastScheduledActivityRef.get() : null;
            if (activity == null && sCurrentActivityRef != null) activity = sCurrentActivityRef.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                if (!isInspectorEnabledCached(packageName)) dismissAll(activity);
                else if (activity.hasWindowFocus()) scheduleAttachFab(activity, packageName, true);
            }
        });
    }

    private static boolean isInspectorEnabledCached(String packageName) {
        if (!ConfigClient.isHookEnabledCached(packageName)) return false;
        com.adhook.config.model.AppConfig config = ConfigClient.getAppConfigCached(packageName);
        return config != null && config.isInspectorEnabled();
    }

    public static synchronized void startInspectMode(Activity activity, String packageName) {
        if (activity == null || activity.isFinishing()) return;
        if (!isInspectorEnabledCached(packageName)) {
            dismissAll(activity);
            return;
        }
        if (sCurrentOverlay != null) return;

        try {
            WindowManager wm = activity.getWindowManager();
            InspectorOverlayView overlay = new InspectorOverlayView(activity, packageName);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.START;

            wm.addView(overlay, lp);
            sCurrentOverlay = overlay;

            if (sCurrentFab instanceof InspectorFabView fabView) {
                fabView.setInspectMode(true);
            }
            AdHookLog.i(TAG + " Inspect overlay attached");
        } catch (Throwable t) {
            AdHookLog.e(TAG + " startInspectMode failed", t);
        }
    }

    public static synchronized void dismissOverlay(Activity activity) {
        if (sCurrentOverlay != null) ViewCollapser.releaseOwner(sCurrentOverlay.previewOwner);
        Activity host = resolveHostActivity(activity, sCurrentOverlay);
        if (sCurrentOverlay != null && host != null) {
            if (sCurrentOverlay.modalLayer != null) {
                try {
                    sCurrentOverlay.removeView(sCurrentOverlay.modalLayer);
                } catch (Throwable ignored) {
                }
                sCurrentOverlay.modalLayer = null;
            }
            boolean removed = false;
            try {
                host.getWindowManager().removeView(sCurrentOverlay);
                removed = true;
            } catch (Throwable t) {
                AdHookLog.w(TAG + " dismissOverlay removeView failed: " + t.getMessage());
            }
            // 无论移除是否成功都要断开静态引用：该 View 已绑定宿主窗口 Token，
            // 继续持有会同时泄漏 Activity 与残留悬浮层。
            try {
                ViewParent parent = sCurrentOverlay.getParent();
                if (parent instanceof ViewGroup vg) {
                    vg.removeView(sCurrentOverlay);
                    removed = true;
                }
            } catch (Throwable ignored) {
            }
            if (!removed) {
                AdHookLog.w(TAG + " dismissOverlay could not detach overlay from window");
            }
            sCurrentOverlay = null;
        }
        if (sCurrentFab instanceof InspectorFabView fabView) {
            fabView.setInspectMode(false);
        }
    }

    /**
     * 解析当前应操作的 Activity：优先用入参，其次用已记录的悬浮球宿主 Activity，
     * 最后回退到悬浮球自身 Context，避免 dismissAll(null) 只清状态不摘窗。
     */
    private static Activity resolveHostActivity(Activity activity, View view) {
        if (activity != null) {
            return activity;
        }
        Activity recorded = (sCurrentActivityRef != null) ? sCurrentActivityRef.get() : null;
        if (recorded != null) {
            return recorded;
        }
        if (view != null) {
            android.content.Context ctx = view.getContext();
            while (ctx instanceof android.content.ContextWrapper) {
                if (ctx instanceof Activity act) {
                    return act;
                }
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
        }
        return null;
    }

    public static synchronized void dismissAll(Activity activity) {
        Activity host = resolveHostActivity(activity, sCurrentOverlay != null ? sCurrentOverlay : sCurrentFab);
        dismissOverlay(host);
        if (sCurrentFab != null && host != null) {
            try {
                host.getWindowManager().removeView(sCurrentFab);
            } catch (Throwable t) {
                AdHookLog.w(TAG + " dismissAll removeView(fab) failed: " + t.getMessage());
                try {
                    ViewParent parent = sCurrentFab.getParent();
                    if (parent instanceof ViewGroup vg) {
                        vg.removeView(sCurrentFab);
                    }
                } catch (Throwable ignored) {
                }
            }
            sCurrentFab = null;
        }
        if (sCurrentActivityRef != null && (host == null || sCurrentActivityRef.get() == host)) {
            sCurrentActivityRef = null;
        }
    }

    /**
     * 全屏透明触摸拦截与高亮画布（Leaf-First 深度优先叶子命中算法）。
     */
    private static class InspectorOverlayView extends FrameLayout {
        private final Activity activity;
        private final String packageName;
        private final Paint highlightPaint;
        private final Paint fillPaint;
        private final Rect highlightRect = new Rect();

        private final List<View> candidateList = new ArrayList<>();
        private int candidateIndex = 0;
        private View selectedView = null;
        private LinearLayout controlBar = null;
        private TextView tvViewInfo = null;
        private Button btnCollapse = null;
        private final Object previewOwner = new Object();
        private boolean isTestCollapsed = false;
        private float controlBarStartY = 0;
        private float controlBarStartTransY = 0;
        private boolean isDraggingBar = false;

        private final List<View> selectionHistory = new ArrayList<>();

        /** MOVE 事件命中测试限流参数（像素/毫秒），避免拖动时每帧全树 DFS。 */
        private static final long HIT_TEST_MIN_INTERVAL_MS = 60L;
        private static final float HIT_TEST_MIN_DISTANCE_PX = 18f;
        private float lastHitX = 0f;
        private float lastHitY = 0f;
        private long lastHitAt = 0L;
        private LinearLayout actionPanel = null;
        private LinearLayout nameInputPanel = null;
        private android.widget.EditText etRuleName = null;
        private String currentTargetValue = null;
        private String currentType = null;
        private String currentDefaultName = null;
        private FrameLayout modalLayer = null;

        public InspectorOverlayView(Activity activity, String packageName) {
            super(activity);
            this.activity = activity;
            this.packageName = packageName;
            setWillNotDraw(false);

            highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            highlightPaint.setColor(Color.RED);
            highlightPaint.setStyle(Paint.Style.STROKE);
            highlightPaint.setStrokeWidth(dp2px(activity, 2.5f));

            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setColor(Color.parseColor("#33FF0000")); // 半透明红底
            fillPaint.setStyle(Paint.Style.FILL);

            setupControlBar();
        }

        private void setupControlBar() {
            controlBar = new LinearLayout(getContext());
            controlBar.setOrientation(LinearLayout.VERTICAL);
            controlBar.setBackground(createBarBackground());
            controlBar.setPadding(dp2px(activity, 12), dp2px(activity, 8), dp2px(activity, 12), dp2px(activity, 10));
            controlBar.setElevation(dp2px(activity, 12));

            // 顶部拖动条与信息区域（按住可上下自由拖动，避开底部广告）
            LinearLayout dragHeader = new LinearLayout(getContext());
            dragHeader.setOrientation(LinearLayout.VERTICAL);
            dragHeader.setGravity(Gravity.CENTER_HORIZONTAL);
            dragHeader.setPadding(0, 0, 0, dp2px(activity, 4));

            // 1. 顶部手柄指示线
            View handleLine = new View(getContext());
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setColor(Color.parseColor("#80FFFFFF"));
            handleBg.setCornerRadius(dp2px(activity, 2));
            handleLine.setBackground(handleBg);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp2px(activity, 40), dp2px(activity, 4));
            handleLp.bottomMargin = dp2px(activity, 6);
            dragHeader.addView(handleLine, handleLp);

            // 2. 信息与拖拽提示行
            LinearLayout infoRow = new LinearLayout(getContext());
            infoRow.setOrientation(LinearLayout.HORIZONTAL);
            infoRow.setGravity(Gravity.CENTER_VERTICAL);

            tvViewInfo = new TextView(getContext());
            tvViewInfo.setTextColor(Color.WHITE);
            tvViewInfo.setTextSize(12);
            tvViewInfo.setText("请点击屏幕上的广告 View");
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            infoRow.addView(tvViewInfo, tvLp);

            TextView tvDragHint = new TextView(getContext());
            tvDragHint.setText("按住可拖动");
            tvDragHint.setTextSize(10);
            tvDragHint.setTextColor(Color.parseColor("#9CA3AF"));
            infoRow.addView(tvDragHint);

            dragHeader.addView(infoRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 绑定拖拽监听器：支持上下自由拖动浮窗
            dragHeader.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN -> {
                        controlBarStartY = event.getRawY();
                        controlBarStartTransY = controlBar.getTranslationY();
                        isDraggingBar = true;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        if (isDraggingBar) {
                            float dy = event.getRawY() - controlBarStartY;
                            float newTransY = controlBarStartTransY + dy;
                            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                            float maxUp = -(dm.heightPixels - controlBar.getHeight() - dp2px(activity, 60));
                            if (newTransY < maxUp) newTransY = maxUp;
                            if (newTransY > 0) newTransY = 0;
                            controlBar.setTranslationY(newTransY);
                            return true;
                        }
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDraggingBar = false;
                        return true;
                    }
                }
                return false;
            });

            controlBar.addView(dragHeader);

            // 操作主面板
            actionPanel = new LinearLayout(getContext());
            actionPanel.setOrientation(LinearLayout.VERTICAL);

            // 操作按钮行 1：层级漫游与测试
            LinearLayout row1 = new LinearLayout(getContext());
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setGravity(Gravity.CENTER_VERTICAL);
            row1.setPadding(0, dp2px(activity, 4), 0, 0);

            Button btnParent = createActionButton("选父级", "#455A64", 1.0f);
            btnParent.setOnClickListener(v -> selectParentView());

            Button btnChild = createActionButton("选子级", "#37474F", 1.0f);
            btnChild.setOnClickListener(v -> selectChildView());

            Button btnNext = createActionButton("切换", "#5C6BC0", 1.0f);
            btnNext.setOnClickListener(v -> cycleCandidates());

            btnCollapse = createActionButton("折叠测试", "#E65100", 1.0f);
            btnCollapse.setOnClickListener(v -> toggleCollapseTest());

            row1.addView(btnParent);
            row1.addView(btnChild);
            row1.addView(btnNext);
            row1.addView(btnCollapse);
            actionPanel.addView(row1);

            // 操作按钮行 2：核心操作
            LinearLayout row2 = new LinearLayout(getContext());
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setGravity(Gravity.CENTER_VERTICAL);
            row2.setPadding(0, dp2px(activity, 6), 0, 0);

            Button btnSave = createActionButton("屏蔽并保存", "#15803D", 1.3f);
            btnSave.setOnClickListener(v -> saveRuleAndApply());

            Button btnDump = createActionButton("Dump 日志", "#00838F", 1.0f);
            btnDump.setOnClickListener(v -> dumpInspectionData());

            Button btnExit = createActionButton("退出", "#424242", 0.8f);
            btnExit.setOnClickListener(v -> dismissOverlay(activity));

            row2.addView(btnSave);
            row2.addView(btnDump);
            row2.addView(btnExit);
            actionPanel.addView(row2);

            controlBar.addView(actionPanel);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.gravity = Gravity.BOTTOM;
            lp.setMargins(dp2px(activity, 12), 0, dp2px(activity, 12), dp2px(activity, 16));
            addView(controlBar, lp);
        }

        private Button createActionButton(String text, String colorHex) {
            return createActionButton(text, colorHex, 1.0f);
        }

        private Button createActionButton(String text, String colorHex, float weight) {
            Button btn = new Button(getContext());
            btn.setText(text);
            btn.setTextSize(11);
            btn.setTextColor(Color.WHITE);
            btn.setPadding(dp2px(activity, 4), dp2px(activity, 4), dp2px(activity, 4), dp2px(activity, 4));

            GradientDrawable gd = new GradientDrawable();
            gd.setColor(Color.parseColor(colorHex));
            gd.setCornerRadius(dp2px(activity, 6));
            btn.setBackground(gd);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp2px(activity, 34), weight);
            lp.rightMargin = dp2px(activity, 4);
            btn.setLayoutParams(lp);
            return btn;
        }

        private GradientDrawable createBarBackground() {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(Color.parseColor("#E6212121")); // 深黑高透
            gd.setCornerRadius(dp2px(activity, 10));
            gd.setStroke(dp2px(activity, 1), Color.parseColor("#66FFFFFF"));
            return gd;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (!isInspectorEnabledCached(packageName)) {
                dismissAll(activity);
                return false;
            }
            // 1. 若当前处于规则配置模态弹窗状态，触摸事件全量分发给模态弹窗及其子 View 处理，绝不执行拾取 Hit-Testing
            if (modalLayer != null && modalLayer.getVisibility() == View.VISIBLE) {
                return super.dispatchTouchEvent(ev);
            }

            // 2. 触摸点落在控制栏内或正在拖动控制栏，放行给按钮与手柄处理
            if (controlBar != null && controlBar.getVisibility() == View.VISIBLE) {
                int[] loc = new int[2];
                controlBar.getLocationOnScreen(loc);
                Rect barRect = new Rect(loc[0], loc[1], loc[0] + controlBar.getWidth(), loc[1] + controlBar.getHeight());
                if (barRect.contains((int) ev.getRawX(), (int) ev.getRawY()) || isDraggingBar) {
                    return super.dispatchTouchEvent(ev);
                }
            }

            // 3. 触摸屏幕其他区域进行 Hit-Testing 拾取
            int action = ev.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                hitTestView(ev.getRawX(), ev.getRawY());
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                // 拖动过程中对全树 DFS 限流：位移超过阈值且距上次命中测试超过 60ms 才重新计算，
                // 避免每个 MOVE 事件都遍历整棵视图树造成明显掉帧。
                long now = System.currentTimeMillis();
                float dx = ev.getRawX() - lastHitX;
                float dy = ev.getRawY() - lastHitY;
                if (now - lastHitAt >= HIT_TEST_MIN_INTERVAL_MS
                        && (dx * dx + dy * dy) >= HIT_TEST_MIN_DISTANCE_PX * HIT_TEST_MIN_DISTANCE_PX) {
                    hitTestView(ev.getRawX(), ev.getRawY());
                }
                return true;
            }

            return true;
        }

        private void hitTestView(float x, float y) {
            lastHitX = x;
            lastHitY = y;
            lastHitAt = System.currentTimeMillis();

            View decor = activity.getWindow().peekDecorView();
            if (decor == null) return;

            // 1. 全树深度穿透收集所有包含该点击点的可见节点
            List<View> hits = new ArrayList<>();
            collectHitViews(decor, (int) x, (int) y, hits);
            AdHookLog.i(TAG + " hitTest at (" + (int) x + ", " + (int) y + "), hits count=" + hits.size());
            if (hits.isEmpty()) {
                return;
            }

            // 2. 特征加权决策：优选面积小、包含文本（如“福利与活动”）、具象控件的目标，严厉惩罚全屏空白/蒙层 View
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int screenArea = dm.widthPixels * dm.heightPixels;

            View bestTarget = null;
            long bestScore = Long.MIN_VALUE;

            for (View v : hits) {
                long score = computeTargetScore(v, screenArea, dm);
                AdHookLog.i(TAG + " hit candidate: " + v.getClass().getSimpleName() + " @" + getResourceEntryName(v)
                        + " bounds=" + v.getWidth() + "x" + v.getHeight() + " score=" + score);
                if (score > bestScore) {
                    bestScore = score;
                    bestTarget = v;
                }
            }

            if (bestTarget == null) {
                bestTarget = hits.get(0);
            }
            AdHookLog.i(TAG + " bestTarget: " + bestTarget.getClass().getSimpleName() + " @" + getResourceEntryName(bestTarget)
                    + " bestScore=" + bestScore);

            // 3. 构建候选层级链：从最具体的节点向上回溯到内容根容器
            candidateList.clear();
            selectionHistory.clear();
            View cur = bestTarget;
            while (cur != null && cur != this && !isChildOf(cur, this)) {
                if (cur.getId() == android.R.id.content || "DecorView".equals(cur.getClass().getSimpleName())) {
                    break;
                }
                if (!candidateList.contains(cur)) {
                    candidateList.add(cur);
                }
                Object p = cur.getParent();
                if (p instanceof View pv) {
                    cur = pv;
                } else {
                    break;
                }
            }

            // 4. 将命中该坐标的其他独立分支也按面积从小到大追加到候选列表中，供「切换」按钮循环
            hits.sort((v1, v2) -> {
                long a1 = (long) v1.getWidth() * v1.getHeight();
                long a2 = (long) v2.getWidth() * v2.getHeight();
                return Long.compare(a1, a2);
            });
            for (View other : hits) {
                if (!candidateList.contains(other)) {
                    candidateList.add(other);
                }
            }

            candidateIndex = 0;
            setSelectedView(bestTarget);
        }

        private void collectHitViews(View root, int x, int y, List<View> outHits) {
            if (root == null || root == this || isChildOf(root, this)) {
                return;
            }
            if (root.getVisibility() != View.VISIBLE || root.getWidth() <= 0 || root.getHeight() <= 0) {
                return;
            }
            if (root.getAlpha() < 0.01f) {
                return;
            }

            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            Rect rect = new Rect(loc[0], loc[1], loc[0] + root.getWidth(), loc[1] + root.getHeight());
            if (!rect.contains(x, y)) {
                return;
            }

            // 深入遍历所有子节点
            if (root instanceof ViewGroup group) {
                int count = group.getChildCount();
                for (int i = count - 1; i >= 0; i--) {
                    collectHitViews(group.getChildAt(i), x, y, outHits);
                }
            }

            // 过滤顶层系统 DecorView 与 content 容器
            if (root.getId() != android.R.id.content && !"DecorView".equals(root.getClass().getSimpleName())) {
                outHits.add(root);
            }
        }

        private long computeTargetScore(View v, int screenArea, DisplayMetrics dm) {
            long area = (long) v.getWidth() * v.getHeight();
            // 基础分：面积越小越精准（基础分范围 0 ~ screenArea）
            long score = Math.max(0, screenArea - area);

            boolean isNearlyFullScreen = (v.getWidth() >= dm.widthPixels * 0.9f && v.getHeight() >= dm.heightPixels * 0.65f);
            String text = extractBestText(v);
            boolean hasText = (text != null && !text.isEmpty());
            String res = getResourceEntryName(v);
            boolean hasId = (res != null && !res.isEmpty());

            // 1. 严厉惩罚全屏且无具体特征的占位/蒙层层，彻底防止点击被顶层全屏 View 劫持
            if (isNearlyFullScreen) {
                if (!hasText && !hasId) {
                    return -100_000_000L;
                }
                score -= 5_000_000L;
            }

            // 2. 文本特征极大幅加分（如用户点击的“福利与活动”）
            if (hasText) {
                score += 2_000_000L;
                if (v instanceof TextView) {
                    score += 500_000L;
                }
            }

            // 3. 具有明确资源 ID 加分
            if (hasId) {
                score += 300_000L;
            }

            // 4. 具体可交互控件或业务 View 加分
            if (v instanceof android.widget.ImageView || v instanceof android.widget.Button) {
                score += 400_000L;
            }

            return score;
        }

        private void cycleCandidates() {
            if (candidateList.isEmpty()) return;
            candidateIndex = (candidateIndex + 1) % candidateList.size();
            setSelectedView(candidateList.get(candidateIndex));
        }

        private boolean isChildOf(View view, View parent) {
            if (view == null || parent == null) return false;
            Object cur = view;
            while (cur instanceof View v) {
                if (v == parent) return true;
                cur = v.getParent();
            }
            return false;
        }

        private void setSelectedView(View view) {
            this.selectedView = view;
            this.isTestCollapsed = false;
            if (btnCollapse != null) btnCollapse.setText("折叠测试");

            int[] loc = new int[2];
            view.getLocationOnScreen(loc);

            int[] overlayLoc = new int[2];
            getLocationOnScreen(overlayLoc);

            int left = loc[0] - overlayLoc[0];
            int top = loc[1] - overlayLoc[1];
            highlightRect.set(left, top, left + view.getWidth(), top + view.getHeight());

            updateViewInfoText();
            invalidate();
            AdHookLog.i(TAG + " Selected: " + view.getClass().getName() + " id=" + getResourceEntryName(view) + " rect=" + highlightRect);
        }

        private void selectParentView() {
            if (selectedView == null) return;
            Object parent = selectedView.getParent();
            if (parent instanceof View parentView && !(parentView instanceof FrameLayout && parentView.getId() == android.R.id.content)) {
                selectionHistory.add(selectedView);
                setSelectedView(parentView);
            }
        }

        private void selectChildView() {
            if (!selectionHistory.isEmpty()) {
                View prev = selectionHistory.remove(selectionHistory.size() - 1);
                setSelectedView(prev);
            }
        }

        private void toggleCollapseTest() {
            if (selectedView == null) return;
            if (!isInspectorEnabledCached(packageName) && !isTestCollapsed) return;
            if (!isTestCollapsed) {
                ViewCollapser.collapse(selectedView, previewOwner);
                isTestCollapsed = true;
                btnCollapse.setText("撤销折叠");
            } else {
                ViewCollapser.restore(selectedView, previewOwner);
                isTestCollapsed = false;
                btnCollapse.setText("折叠测试");
            }
            invalidate();
        }

        private String extractBestText(View root) {
            if (root == null) return null;
            if (root instanceof TextView tv) {
                CharSequence cs = tv.getText();
                if (cs != null && !cs.toString().trim().isEmpty()) {
                    return cs.toString().trim();
                }
            }
            CharSequence cd = root.getContentDescription();
            if (cd != null && !cd.toString().trim().isEmpty()) {
                return cd.toString().trim();
            }
            if (root instanceof ViewGroup group) {
                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    String t = extractBestText(group.getChildAt(i));
                    if (t != null && !t.isEmpty() && t.length() <= 30) {
                        return t;
                    }
                }
            }
            return null;
        }

        private View findTextSourceView(View root, String text) {
            if (root == null || text == null || text.trim().isEmpty()) return null;
            if (root instanceof TextView tv) {
                CharSequence cs = tv.getText();
                if (cs != null && cs.toString().trim().equals(text.trim())) {
                    return tv;
                }
            }
            CharSequence cd = root.getContentDescription();
            if (cd != null && cd.toString().trim().equals(text.trim())) {
                return root;
            }
            if (root instanceof ViewGroup group) {
                int count = group.getChildCount();
                for (int i = 0; i < count; i++) {
                    View found = findTextSourceView(group.getChildAt(i), text);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private int calculateDepthToParent(View child, View parent) {
            if (child == null || parent == null || child == parent) return 0;
            int depth = 0;
            View cur = child;
            while (cur != null && cur != parent) {
                Object p = cur.getParent();
                if (p instanceof View pv) {
                    depth++;
                    cur = pv;
                } else {
                    break;
                }
            }
            return (cur == parent) ? depth : 0;
        }

        private boolean isDangerousGenericClass(String className) {
            if (className == null) return false;
            return className.equals("android.view.View")
                    || className.equals("android.view.ViewGroup")
                    || className.equals("android.widget.FrameLayout")
                    || className.equals("android.widget.LinearLayout")
                    || className.equals("android.widget.RelativeLayout")
                    || className.equals("androidx.recyclerview.widget.RecyclerView")
                    || className.equals("android.widget.ListView")
                    || className.equals("android.widget.ScrollView")
                    || className.equals("androidx.core.widget.NestedScrollView")
                    || className.equals("androidx.constraintlayout.widget.ConstraintLayout")
                    || className.equals("miuix.flexible.view.HyperCellLayout");
        }

        private void saveRuleAndApply() {
            if (selectedView == null) {
                if (tvViewInfo != null) {
                    tvViewInfo.setText("⚠️ 请先点击选中屏幕上的 View");
                }
                return;
            }

            final String extractedText = extractBestText(selectedView);
            final String resName = getResourceEntryName(selectedView);
            final String fullClassName = selectedView.getClass().getName();

            final boolean isInsideListItem = ViewCollapser.isInsideListItem(selectedView);

            showDarkRuleNameDialog(extractedText, resName, fullClassName, isInsideListItem, selectedView);
        }

        /**
         * 纯深色模式智能规则保存弹窗（支持文本/ID/类名模式选择、整行条目折叠范围与高危防误杀拦截）。
         */
        private void showDarkRuleNameDialog(final String extractedText, final String resName,
                                            final String fullClassName, final boolean isInsideListItem,
                                            final View targetView) {
            try {
                final String[] currentType = new String[1];
                final String[] currentTarget = new String[1];
                final int[] currentDepth = new int[1];
                final boolean[] userEditedName = new boolean[]{false};

                // 智能优先级决策：优先推荐文案匹配；若无文案则检查是否有非通用ID；否则回退到类名
                boolean hasText = (extractedText != null && !extractedText.isEmpty());
                boolean hasResId = (resName != null && !resName.isEmpty());

                // 计算从提取出文案的叶子节点到当前用户选中的 targetView 的层级距离
                final View textSourceView = hasText ? findTextSourceView(targetView, extractedText) : null;
                final int depthToSelected = (textSourceView != null) ? calculateDepthToParent(textSourceView, targetView) : 0;
                final int targetItemDepth = (depthToSelected > 0) ? depthToSelected : CustomRule.PARENT_DEPTH_AUTO_ITEM;
                final boolean isParentCardSelected = (depthToSelected > 0);
                final boolean showScopeOptions = isInsideListItem || isParentCardSelected;

                if (hasText) {
                    currentType[0] = CustomRule.TYPE_TEXT;
                    currentTarget[0] = extractedText;
                } else if (hasResId) {
                    currentType[0] = CustomRule.TYPE_RES_ID;
                    currentTarget[0] = resName;
                } else {
                    currentType[0] = CustomRule.TYPE_CLASS_NAME;
                    currentTarget[0] = fullClassName;
                }

                if (showScopeOptions) {
                    currentDepth[0] = targetItemDepth;
                } else {
                    currentDepth[0] = CustomRule.PARENT_DEPTH_SELF;
                }

                // 自定义深色对话框卡片容器 (加宽展示，左右间距紧凑，杜绝文字折行)
                LinearLayout container = new LinearLayout(activity);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(dp2px(activity, 16), dp2px(activity, 16), dp2px(activity, 16), dp2px(activity, 16));

                GradientDrawable containerBg = new GradientDrawable();
                containerBg.setColor(Color.parseColor("#181F2A")); // 深空暗黑底色
                containerBg.setCornerRadius(dp2px(activity, 16));
                containerBg.setStroke(dp2px(activity, 1.2f), Color.parseColor("#374151"));
                container.setBackground(containerBg);

                TextView tvTitle = new TextView(activity);
                tvTitle.setText("保存自定义屏蔽规则");
                tvTitle.setTextSize(16);
                tvTitle.setTextColor(Color.WHITE);
                tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                container.addView(tvTitle);

                // 识别模式切换标签栏 (Tab Row)
                TextView lblMode = new TextView(activity);
                lblMode.setText("选择匹配特征模式：");
                lblMode.setTextSize(12);
                lblMode.setTextColor(Color.parseColor("#9CA3AF"));
                lblMode.setPadding(0, dp2px(activity, 10), 0, dp2px(activity, 6));
                container.addView(lblMode);

                LinearLayout modeRow = new LinearLayout(activity);
                modeRow.setOrientation(LinearLayout.HORIZONTAL);
                modeRow.setGravity(Gravity.CENTER_VERTICAL);

                final TextView btnModeText = createSegmentButton("文本匹配" + (hasText ? " (推荐)" : ""), 1.1f);
                final TextView btnModeResId = createSegmentButton("资源 ID", 0.9f);
                final TextView btnModeClass = createSegmentButton("控件类名", 0.9f);

                btnModeText.setEnabled(hasText);
                btnModeResId.setEnabled(hasResId);

                modeRow.addView(btnModeText);
                modeRow.addView(btnModeResId);
                modeRow.addView(btnModeClass);
                container.addView(modeRow);

                // 折叠目标范围选择行（当用户选中父级卡片或位于列表项内时显示）
                final LinearLayout scopeRow = new LinearLayout(activity);
                scopeRow.setOrientation(LinearLayout.HORIZONTAL);
                scopeRow.setGravity(Gravity.CENTER_VERTICAL);
                scopeRow.setPadding(0, dp2px(activity, 8), 0, 0);

                final String itemBtnTitle = isParentCardSelected
                        ? ("折叠选中的整行卡片" + (depthToSelected > 1 ? (" (回溯" + depthToSelected + "层)") : "") + " (推荐)")
                        : "折叠整行条目 (推荐)";
                final TextView btnScopeItem = createSegmentButton(itemBtnTitle, 1.3f);
                final TextView btnScopeSelf = createSegmentButton("仅隐藏局部控件", 0.9f);
                scopeRow.addView(btnScopeItem);
                scopeRow.addView(btnScopeSelf);

                final TextView tvScopeWarning = new TextView(activity);
                tvScopeWarning.setTextSize(11);
                tvScopeWarning.setTextColor(Color.parseColor("#FDE047")); // 黄色提示文字
                tvScopeWarning.setSingleLine(true);
                tvScopeWarning.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tvScopeWarning.setPadding(0, dp2px(activity, 6), 0, 0);
                tvScopeWarning.setText("提示：仅隐藏文字会残留图标与箭头，建议选【折叠整行卡片】彻底消除。");
                tvScopeWarning.setVisibility(View.GONE);

                if (showScopeOptions) {
                    TextView lblScope = new TextView(activity);
                    lblScope.setText("折叠目标生效范围：");
                    lblScope.setTextSize(12);
                    lblScope.setTextColor(Color.parseColor("#9CA3AF"));
                    lblScope.setPadding(0, dp2px(activity, 10), 0, dp2px(activity, 4));
                    container.addView(lblScope);
                    container.addView(scopeRow);
                    container.addView(tvScopeWarning);
                }

                // 目标值展示框
                final TextView tvTargetPreview = new TextView(activity);
                tvTargetPreview.setTextSize(12);
                tvTargetPreview.setTextColor(Color.parseColor("#67E8F9"));
                tvTargetPreview.setSingleLine(true);
                tvTargetPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tvTargetPreview.setPadding(dp2px(activity, 10), dp2px(activity, 8), dp2px(activity, 10), dp2px(activity, 8));

                GradientDrawable previewBg = new GradientDrawable();
                previewBg.setColor(Color.parseColor("#111827"));
                previewBg.setCornerRadius(dp2px(activity, 6));
                previewBg.setStroke(dp2px(activity, 1), Color.parseColor("#1F2937"));
                tvTargetPreview.setBackground(previewBg);

                LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                previewLp.topMargin = dp2px(activity, 10);
                previewLp.bottomMargin = dp2px(activity, 6);
                container.addView(tvTargetPreview, previewLp);

                // 高危警告提示卡片（默认隐藏）
                final TextView tvDangerWarning = new TextView(activity);
                tvDangerWarning.setTextSize(11);
                tvDangerWarning.setTextColor(Color.parseColor("#FCA5A5")); // 淡红报警文字
                tvDangerWarning.setSingleLine(true);
                tvDangerWarning.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tvDangerWarning.setPadding(dp2px(activity, 10), dp2px(activity, 8), dp2px(activity, 10), dp2px(activity, 8));
                GradientDrawable dangerBg = new GradientDrawable();
                dangerBg.setColor(Color.parseColor("#33B91C1C")); // 半透明暗红底色
                dangerBg.setCornerRadius(dp2px(activity, 6));
                dangerBg.setStroke(dp2px(activity, 1), Color.parseColor("#DC2626"));
                tvDangerWarning.setBackground(dangerBg);
                tvDangerWarning.setVisibility(View.GONE);
                container.addView(tvDangerWarning);

                // 规则名称输入框
                TextView lblName = new TextView(activity);
                lblName.setText("规则名称：");
                lblName.setTextSize(12);
                lblName.setTextColor(Color.parseColor("#9CA3AF"));
                lblName.setPadding(0, dp2px(activity, 8), 0, dp2px(activity, 4));
                container.addView(lblName);

                final android.widget.EditText input = new android.widget.EditText(activity);
                input.setHint("输入规则名称");
                input.setSingleLine(true);
                input.setTextSize(14);
                input.setTextColor(Color.WHITE);
                input.setHintTextColor(Color.parseColor("#6B7280"));
                input.setPadding(dp2px(activity, 12), dp2px(activity, 10), dp2px(activity, 12), dp2px(activity, 10));

                GradientDrawable inputBg = new GradientDrawable();
                inputBg.setColor(Color.parseColor("#263238"));
                inputBg.setCornerRadius(dp2px(activity, 8));
                inputBg.setStroke(dp2px(activity, 1), Color.parseColor("#455A64"));
                input.setBackground(inputBg);
                input.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (input.hasFocus()) userEditedName[0] = true;
                    }
                    @Override public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(input);

                // 底部操作按钮行
                LinearLayout btnRow = new LinearLayout(activity);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                btnRow.setPadding(0, dp2px(activity, 16), 0, 0);

                TextView btnCancel = new TextView(activity);
                btnCancel.setText("取消");
                btnCancel.setTextSize(14);
                btnCancel.setTextColor(Color.parseColor("#9CA3AF"));
                btnCancel.setPadding(dp2px(activity, 16), dp2px(activity, 8), dp2px(activity, 16), dp2px(activity, 8));

                final TextView btnSave = new TextView(activity);
                btnSave.setText("保存规则");
                btnSave.setTextSize(14);
                btnSave.setTextColor(Color.WHITE);
                btnSave.setTypeface(null, android.graphics.Typeface.BOLD);
                btnSave.setPadding(dp2px(activity, 18), dp2px(activity, 8), dp2px(activity, 18), dp2px(activity, 8));

                final GradientDrawable saveBg = new GradientDrawable();
                saveBg.setColor(Color.parseColor("#15803D")); // 翠绿可保存状态
                saveBg.setCornerRadius(dp2px(activity, 8));
                btnSave.setBackground(saveBg);

                btnRow.addView(btnCancel);
                btnRow.addView(btnSave);
                container.addView(btnRow);

                // 刷新全量状态联动的闭包函数
                final Runnable refreshDialogState = new Runnable() {
                    @Override
                    public void run() {
                        String type = currentType[0];
                        String target = currentTarget[0];
                        int depth = currentDepth[0];

                        // 更新模式按钮高亮
                        updateSegmentHighlight(btnModeText, CustomRule.TYPE_TEXT.equals(type));
                        updateSegmentHighlight(btnModeResId, CustomRule.TYPE_RES_ID.equals(type));
                        updateSegmentHighlight(btnModeClass, CustomRule.TYPE_CLASS_NAME.equals(type));

                        // 更新范围按钮高亮与提示
                        updateSegmentHighlight(btnScopeItem, depth == targetItemDepth);
                        updateSegmentHighlight(btnScopeSelf, depth == CustomRule.PARENT_DEPTH_SELF);
                        if (showScopeOptions && depth == CustomRule.PARENT_DEPTH_SELF && CustomRule.TYPE_TEXT.equals(type)) {
                            tvScopeWarning.setVisibility(View.VISIBLE);
                        } else {
                            tvScopeWarning.setVisibility(View.GONE);
                        }

                        // 预览文字
                        if (CustomRule.TYPE_TEXT.equals(type)) {
                            tvTargetPreview.setText("匹配文案: \"" + target + "\"");
                        } else if (CustomRule.TYPE_RES_ID.equals(type)) {
                            tvTargetPreview.setText("匹配 ID: @" + target);
                        } else {
                            tvTargetPreview.setText("匹配类名: " + target);
                        }

                        // 危险通用类名安全护栏
                        boolean isDangerous = CustomRule.TYPE_CLASS_NAME.equals(type) && isDangerousGenericClass(target);
                        if (isDangerous) {
                            tvDangerWarning.setVisibility(View.VISIBLE);
                            tvDangerWarning.setText("通用容器类名，按类名折叠将误杀同类条目，已禁止保存。");
                            btnSave.setEnabled(false);
                            saveBg.setColor(Color.parseColor("#4B5563")); // 禁用灰色
                        } else {
                            tvDangerWarning.setVisibility(View.GONE);
                            btnSave.setEnabled(true);
                            saveBg.setColor(Color.parseColor("#15803D")); // 翠绿可用
                        }

                        // 若用户未手动改过名称，根据特征智能生成优雅名称
                        if (!userEditedName[0]) {
                            String suggestedName;
                            if (CustomRule.TYPE_TEXT.equals(type)) {
                                if (depth > 0) {
                                    suggestedName = "折叠「" + target + "」整行卡片";
                                } else if (depth == CustomRule.PARENT_DEPTH_AUTO_ITEM) {
                                    suggestedName = "折叠「" + target + "」条目";
                                } else {
                                    suggestedName = "隐藏「" + target + "」";
                                }
                            } else if (CustomRule.TYPE_RES_ID.equals(type)) {
                                suggestedName = "屏蔽 @" + target;
                            } else {
                                suggestedName = "折叠 " + generateDefaultRuleName(target, type);
                            }
                            input.setText(suggestedName);
                            input.selectAll();
                        }
                    }
                };

                // 点击事件绑定
                btnModeText.setOnClickListener(v -> {
                    if (hasText) {
                        currentType[0] = CustomRule.TYPE_TEXT;
                        currentTarget[0] = extractedText;
                        refreshDialogState.run();
                    }
                });

                btnModeResId.setOnClickListener(v -> {
                    if (hasResId) {
                        currentType[0] = CustomRule.TYPE_RES_ID;
                        currentTarget[0] = resName;
                        refreshDialogState.run();
                    }
                });

                btnModeClass.setOnClickListener(v -> {
                    currentType[0] = CustomRule.TYPE_CLASS_NAME;
                    currentTarget[0] = fullClassName;
                    refreshDialogState.run();
                });

                btnScopeItem.setOnClickListener(v -> {
                    currentDepth[0] = targetItemDepth;
                    refreshDialogState.run();
                });

                btnScopeSelf.setOnClickListener(v -> {
                    currentDepth[0] = CustomRule.PARENT_DEPTH_SELF;
                    refreshDialogState.run();
                });

                refreshDialogState.run();

                if (this.modalLayer != null) {
                    removeView(this.modalLayer);
                    this.modalLayer = null;
                }

                // 构建全屏模态遮罩层 (深色半透明背景，拦截点击防穿透，完全内嵌在 Overlay 中免除系统 Window Token 依赖)
                final FrameLayout layer = new FrameLayout(activity);
                layer.setBackgroundColor(Color.parseColor("#B3000000"));
                layer.setClickable(true);
                layer.setFocusable(true);

                FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                        (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.94f),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                cardLp.gravity = Gravity.CENTER;
                layer.addView(container, cardLp);

                // 点击模态卡片外部遮罩取消
                layer.setOnClickListener(v -> {
                    if (this.modalLayer != null) {
                        removeView(this.modalLayer);
                        this.modalLayer = null;
                    }
                    controlBar.setVisibility(View.VISIBLE);
                    invalidate();
                });
                container.setOnClickListener(v -> {}); // 消费卡片内点击，防误触外层遮罩

                // 隐藏底部控制栏，将全屏模态层添加为当前 Overlay 的顶层子 View
                controlBar.setVisibility(View.GONE);
                this.modalLayer = layer;
                addView(this.modalLayer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                btnCancel.setOnClickListener(v -> {
                    if (this.modalLayer != null) {
                        removeView(this.modalLayer);
                        this.modalLayer = null;
                    }
                    controlBar.setVisibility(View.VISIBLE);
                    invalidate();
                });

                btnSave.setOnClickListener(v -> {
                    if (!isInspectorEnabledCached(packageName)) {
                        dismissAll(activity);
                        return;
                    }
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "自定义屏蔽规则";
                    }
                    CustomRule rule = new CustomRule(
                            name,
                            currentType[0],
                            currentTarget[0],
                            CustomRule.ACTION_COLLAPSE,
                            currentDepth[0]
                    );

                    btnSave.setEnabled(false);
                    SAVE_EXECUTOR.execute(() -> {
                        boolean saved = false;
                        try {
                            if (isInspectorEnabledCached(packageName)) {
                                saved = ConfigClient.saveCustomRule(packageName, rule);
                            }
                        } catch (Throwable failure) {
                            AdHookLog.w(TAG + " save rule failed: " + failure.getMessage());
                        }
                        final boolean success = saved;
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            btnSave.setEnabled(true);
                            if (!success) {
                                Toast.makeText(activity, "规则保存失败，请确认配置访问授权后重试", Toast.LENGTH_LONG).show();
                                return;
                            }
                            if (isInspectorEnabledCached(packageName)) {
                                com.adhook.engine.CustomRuleEngine.matchAndApply(packageName, targetView);
                            }
                            if (this.modalLayer != null) {
                                removeView(this.modalLayer);
                                this.modalLayer = null;
                            }
                            dismissOverlay(activity);
                            // Provider 已授权保存成功；回跳只展示，不通过外部 Intent 写配置。
                            switchToADHookDetail(rule);
                        });
                    });
                });

                // 自动拉起输入框焦点与软键盘
                input.postDelayed(() -> {
                    input.requestFocus();
                    try {
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    } catch (Throwable ignored) {
                    }
                }, 120);
            } catch (Throwable t) {
                AdHookLog.e(TAG + " showDarkRuleNameDialog failed", t);
                controlBar.setVisibility(View.VISIBLE);
                if (tvViewInfo != null) {
                    tvViewInfo.setText("⚠️ 启动规则配置失败: " + t.getMessage());
                }
            }
        }

        private TextView createSegmentButton(String text, float weight) {
            TextView btn = new TextView(getContext());
            btn.setText(text);
            btn.setTextSize(11);
            btn.setGravity(Gravity.CENTER);
            btn.setSingleLine(true);
            btn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            btn.setPadding(dp2px(activity, 4), dp2px(activity, 8), dp2px(activity, 4), dp2px(activity, 8));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
            lp.rightMargin = dp2px(activity, 4);
            btn.setLayoutParams(lp);
            return btn;
        }

        private void updateSegmentHighlight(TextView btn, boolean selected) {
            GradientDrawable gd = new GradientDrawable();
            if (selected) {
                gd.setColor(Color.parseColor("#15803D")); // 翠绿选中
                gd.setCornerRadius(dp2px(activity, 6));
                btn.setTextColor(Color.WHITE);
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                gd.setColor(Color.parseColor("#1F2937")); // 深灰未选
                gd.setCornerRadius(dp2px(activity, 6));
                gd.setStroke(dp2px(activity, 1), Color.parseColor("#374151"));
                btn.setTextColor(btn.isEnabled() ? Color.parseColor("#9CA3AF") : Color.parseColor("#4B5563"));
                btn.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
            btn.setBackground(gd);
        }

        private void switchToADHookDetail(CustomRule rule) {
            try {
                Intent backIntent = new Intent();
                backIntent.setComponent(new android.content.ComponentName("com.adhook", "com.adhook.ui.AppDetailActivity"));
                backIntent.putExtra("package_name", packageName);
                if (rule != null) {
                    backIntent.putExtra("pending_rule_json", rule.toJson().toString());
                }
                backIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(backIntent);
            } catch (Throwable t) {
                AdHookLog.w(TAG + " switchToADHookDetail failed: " + t.getMessage());
            }
        }

        private String generateDefaultRuleName(String targetValue, String type) {
            if (CustomRule.TYPE_RES_ID.equals(type)) {
                String entry = targetValue;
                int slash = entry.lastIndexOf('/');
                if (slash >= 0 && slash < entry.length() - 1) {
                    entry = entry.substring(slash + 1);
                }
                int colon = entry.indexOf(':');
                if (colon >= 0 && colon < entry.length() - 1) {
                    entry = entry.substring(colon + 1);
                }
                return entry;
            } else {
                int dot = targetValue.lastIndexOf('.');
                if (dot >= 0 && dot < targetValue.length() - 1) {
                    return targetValue.substring(dot + 1);
                }
                return targetValue;
            }
        }

        private void dumpInspectionData() {
            if (selectedView == null) {
                if (tvViewInfo != null) {
                    tvViewInfo.setText("⚠️ 请先点击选中要分析的 View");
                }
                return;
            }

            try {
                JSONObject dump = new JSONObject();
                dump.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
                dump.put("package", packageName);
                dump.put("activity", activity.getClass().getName());

                // View 基本属性
                JSONObject viewObj = new JSONObject();
                viewObj.put("class", selectedView.getClass().getName());
                viewObj.put("id_entry", getResourceEntryName(selectedView));
                viewObj.put("width", selectedView.getWidth());
                viewObj.put("height", selectedView.getHeight());
                viewObj.put("visibility", selectedView.getVisibility());
                dump.put("view", viewObj);

                // 溯源 RecyclerView / ViewHolder / Adapter
                JSONObject rvObj = extractRecyclerViewContext(selectedView);
                if (rvObj != null) {
                    dump.put("recycler_context", rvObj);
                }

                // 父级链
                JSONArray parentChain = new JSONArray();
                Object p = selectedView.getParent();
                while (p instanceof View pv) {
                    parentChain.put(pv.getClass().getName() + " (" + getResourceEntryName(pv) + ")");
                    p = pv.getParent();
                }
                dump.put("parent_chain", parentChain);

                // 保存到 /sdcard/ADHook/dump/<pkg>/
                File dumpDir = new File(Environment.getExternalStorageDirectory(), "ADHook/dump/" + packageName);
                if (!dumpDir.exists()) dumpDir.mkdirs();

                String fileName = "inspect_" + System.currentTimeMillis() + ".json";
                File outFile = new File(dumpDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(dump.toString(2).getBytes(StandardCharsets.UTF_8));
                }

                AdHookLog.i("[VisualInspector] DUMP: " + dump.toString());
                Toast.makeText(getContext(), "✅ 已导出: /sdcard/ADHook/dump/" + packageName + "/" + fileName, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                AdHookLog.e(TAG + " dump failed", t);
                Toast.makeText(getContext(), "导出失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        private JSONObject extractRecyclerViewContext(View view) {
            try {
                Object cur = view;
                while (cur instanceof View v) {
                    Object tag = v.getTag();
                    if (tag != null && tag.getClass().getName().contains("ViewHolder")) {
                        return buildHolderJson(tag);
                    }

                    if (v.getParent() != null && v.getParent().getClass().getName().contains("RecyclerView")) {
                        Object rv = v.getParent();
                        Method getHolder = rv.getClass().getMethod("getChildViewHolder", View.class);
                        Object holder = getHolder.invoke(rv, v);
                        if (holder != null) {
                            return buildHolderJson(holder);
                        }
                    }
                    cur = v.getParent();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private JSONObject buildHolderJson(Object holder) {
            try {
                JSONObject json = new JSONObject();
                json.put("view_holder_class", holder.getClass().getName());
                try {
                    Method getItemViewType = holder.getClass().getMethod("getItemViewType");
                    json.put("item_view_type", getItemViewType.invoke(holder));
                } catch (Throwable ignored) {}
                return json;
            } catch (Throwable t) {
                return null;
            }
        }

        private void updateViewInfoText() {
            if (selectedView == null || tvViewInfo == null) return;
            String res = getResourceEntryName(selectedView);
            String idStr = (res != null && !res.isEmpty()) ? ("@" + res) : "(无ID)";
            String cls = selectedView.getClass().getSimpleName();
            String bestText = extractBestText(selectedView);
            String textHint = (bestText != null && !bestText.isEmpty()) ? ("  \"" + (bestText.length() > 8 ? bestText.substring(0, 8) + "…" : bestText) + "\"") : "";
            String indexInfo = candidateList.size() > 1 ? (" [" + (candidateIndex + 1) + "/" + candidateList.size() + "]") : "";
            tvViewInfo.setText(cls + "  " + idStr + textHint + indexInfo + "\n尺寸: " + selectedView.getWidth() + "x" + selectedView.getHeight() + "px");
        }

        private String getResourceEntryName(View v) {
            int id = v.getId();
            if (id == View.NO_ID || id <= 0) return "";
            try {
                Resources res = v.getResources();
                if (res != null) {
                    return res.getResourceEntryName(id);
                }
            } catch (Throwable ignored) {
            }
            return "";
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // 弹窗展示期间不绘制红框与半透明红底，防止遮挡弹窗
            if (modalLayer != null && modalLayer.getVisibility() == View.VISIBLE) {
                return;
            }
            if (selectedView != null && !highlightRect.isEmpty()) {
                canvas.drawRect(highlightRect, fillPaint);
                canvas.drawRect(highlightRect, highlightPaint);
            }
        }
    }

    private static int dp2px(Context context, float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        );
    }
}
