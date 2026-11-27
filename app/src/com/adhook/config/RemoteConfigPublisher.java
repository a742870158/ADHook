package com.adhook.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.adhook.core.AdHookLog;

import org.json.JSONObject;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** App-side service 101 only. Framework-owned preferences are not CE/DE SharedPreferences. */
public final class RemoteConfigPublisher {
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "adhook-remote-publish");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_SERVICES = 8;
    // All service and payload state belongs to IO; callbacks never perform synchronous service IPC.
    private static final Map<XposedService, ServiceState> SERVICES = new IdentityHashMap<>();
    private static volatile RemotePublicationState publication = new RemotePublicationState(0L, 0L, null, 0);
    private static String latestConfig;
    private static String latestLaunch;
    private static long latestVersion;
    private static boolean initialized;
    private static volatile Context appContext;

    private static final class ServiceState {
        long acknowledgedVersion;
        int attempts;
        String error = "PENDING";
        ScheduledFuture<?> retry;

        void reset() {
            if (retry != null) retry.cancel(false);
            retry = null;
            attempts = 0;
            error = "PENDING";
        }
    }

    private RemoteConfigPublisher() {}

    static RemotePublicationState getState() { return publication; }

    static synchronized void expectVersion(long version) {
        RemotePublicationState previous = publication;
        publication = publication.desired(version);
        notifyStateChanged(previous);
    }

    private static synchronized void result(long version, boolean confirmed, String error, int attempts) {
        RemotePublicationState previous = publication;
        publication = publication.result(version, confirmed, error, attempts);
        notifyStateChanged(previous);
    }

    private static void notifyStateChanged(RemotePublicationState previous) {
        if (publication == previous || appContext == null) return;
        IO.execute(() -> {
            Context context = appContext;
            if (context == null) return;
            try {
                context.getContentResolver().notifyChange(ConfigProvider.CONTENT_URI.buildUpon()
                        .appendPath("remote-publication").build(), null);
            } catch (RuntimeException ignored) {
                // Observation failure does not change remote commit acknowledgement or saved intent.
            }
        });
    }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        initialized = true;
        Context application = context.getApplicationContext();
        Context app = application != null ? application : context;
        appContext = app;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) {
                IO.execute(() -> {
                    if (!SERVICES.containsKey(service) && SERVICES.size() >= MAX_SERVICES) {
                        result(publication.desiredVersion, false, "SERVICE_BUDGET_EXCEEDED", 0);
                        return;
                    }
                    ServiceState state = SERVICES.computeIfAbsent(service, ignored -> new ServiceState());
                    state.reset();
                    result(publication.desiredVersion, false, "PENDING", 0);
                    if (latestConfig != null) publishTo(service, state, latestVersion);
                    else ConfigManager.getInstance(app).publishExistingRemoteConfig();
                });
            }
            @Override public void onServiceDied(XposedService service) {
                IO.execute(() -> {
                    ServiceState removed = SERVICES.remove(service);
                    if (removed != null) removed.reset();
                    result(publication.desiredVersion, false, "SERVICE_DIED", 0);
                });
            }
        });
        IO.execute(() -> ConfigManager.getInstance(app).publishExistingRemoteConfig());
    }

    static void publish(String config, String launch) {
        IO.execute(() -> {
            long version = 0L;
            try {
                JSONObject full = new JSONObject(config);
                HookRuntimeSnapshot startup = HookRuntimeSnapshot.parseLaunch(launch);
                version = full.optLong("updated_at", 0L);
                if (startup == null || HookRuntimeSnapshot.parseFull(full) == null
                        || version != startup.updatedAt) {
                    result(version, false, "INVALID_SNAPSHOT", 0);
                    return;
                }
                if (version < latestVersion || version < publication.desiredVersion) return;
                expectVersion(version);
                if (version == latestVersion && latestConfig != null) {
                    updateAcknowledgement(version);
                    return; // Duplicate startup publication cannot restart an exhausted retry budget.
                }
                latestConfig = config;
                latestLaunch = launch;
                latestVersion = version;
                if (SERVICES.isEmpty()) {
                    result(version, false, "SERVICE_UNAVAILABLE", 0);
                    return; // Service bind, not an unbounded timer, restarts delivery.
                }
                for (Map.Entry<XposedService, ServiceState> entry : SERVICES.entrySet()) {
                    entry.getValue().reset();
                    publishTo(entry.getKey(), entry.getValue(), version);
                }
            } catch (Exception error) {
                result(version, false, "INVALID_SNAPSHOT", 0);
                AdHookLog.w("[RemoteConfigPublisher] Invalid publication: " + error.getMessage());
            }
        });
    }

    static boolean commitPreferences(SharedPreferences preferences, String config, String launch) {
        return preferences.edit().putString("config_json", config)
                .putString(HookLaunchSnapshot.KEY, launch).commit();
    }

    /** Four attempts maximum: first call, then 1s, 3s and 10s after completed failures. */
    static long retryDelayAfterAttempt(int attempts) {
        return switch (attempts) {
            case 1 -> 1000L;
            case 2 -> 3000L;
            case 3 -> 10000L;
            default -> -1L;
        };
    }

    private static void publishTo(XposedService service, ServiceState state, long version) {
        if (version != latestVersion || version != publication.desiredVersion
                || SERVICES.get(service) != state || state.attempts >= 4) return;
        state.attempts++;
        boolean retryable = true;
        try {
            if (service.getApiVersion() < 101
                    || (service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0) {
                state.error = "REMOTE_UNSUPPORTED";
                retryable = false;
            } else if (commitPreferences(service.getRemotePreferences("adhook_config"), latestConfig, latestLaunch)) {
                state.acknowledgedVersion = version;
                state.error = null;
                retryable = false;
            } else {
                state.error = "COMMIT_FAILED";
            }
        } catch (RuntimeException error) {
            state.error = "SERVICE_ERROR:" + error.getClass().getSimpleName();
        }
        updateAcknowledgement(version);
        long delay = retryDelayAfterAttempt(state.attempts);
        if (retryable && delay >= 0L) {
            state.retry = IO.schedule(() -> publishTo(service, state, version), delay, TimeUnit.MILLISECONDS);
        }
    }

    private static void updateAcknowledgement(long version) {
        boolean confirmed = !SERVICES.isEmpty();
        String error = null;
        int attempts = 0;
        for (ServiceState state : SERVICES.values()) {
            attempts = Math.max(attempts, state.attempts);
            if (state.acknowledgedVersion != version || state.error != null) {
                confirmed = false;
                if (error == null) error = state.error == null ? "PENDING" : state.error;
            }
        }
        result(version, confirmed, error == null && !confirmed ? "SERVICE_UNAVAILABLE" : error, attempts);
    }
}
