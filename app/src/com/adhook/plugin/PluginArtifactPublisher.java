package com.adhook.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;

import com.adhook.core.AdHookLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * App-side publisher of the plugin routing snapshot to framework RemotePreferences and remote
 * dex files.
 *
 * <p>All Binder and disk work happens on the single IO thread; {@link #initialize(Context)} and
 * {@link #requestPublish()} only record intent and enqueue. The published snapshot is built from
 * a fresh {@link PluginStore} read, every dex is written and size-verified before the remote
 * preferences commit, and old {@code p_*.dex} files are deleted only after the new generation is
 * committed. Failures keep the previous publication and retry with 1s/3s/10s backoff, four
 * attempts maximum.</p>
 */
public final class PluginArtifactPublisher {

    private static final String THREAD_NAME = "adhook-plugin-publish";
    private static final int MAX_SERVICES = 8;
    private static final int MAX_ATTEMPTS = 4;

    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });
    private static final Object LOCK = new Object();
    /** Only touched on the IO thread. */
    private static final Map<XposedService, RemoteService> BINDINGS = new IdentityHashMap<>();
    private static final Map<RemoteService, ServiceState> SERVICES = new IdentityHashMap<>();
    private static volatile State state = new State(0L, 0L, null, 0, false);
    private static volatile Context appContext;
    private static volatile boolean initializeRequested;
    private static boolean publishScheduled;

    /** Immutable publication status exposed to the UI. */
    public static final class State {
        public final long desiredGeneration;
        public final long publishedGeneration;
        public final String error;
        public final int attempts;
        public final boolean serviceAvailable;

        public State(long desiredGeneration, long publishedGeneration, String error, int attempts,
                     boolean serviceAvailable) {
            this.desiredGeneration = desiredGeneration;
            this.publishedGeneration = publishedGeneration;
            this.error = error;
            this.attempts = attempts;
            this.serviceAvailable = serviceAvailable;
        }

        public boolean isConfirmed() {
            return desiredGeneration > 0L && desiredGeneration == publishedGeneration && error == null;
        }
    }

    /** Prepared table plus the exact dex bytes to upload, keyed by remote file name. */
    static final class PublishPayload {
        final String table;
        final Map<String, byte[]> dexFiles;
        final String error;

        PublishPayload(String table, Map<String, byte[]> dexFiles, String error) {
            this.table = table;
            this.dexFiles = dexFiles != null ? dexFiles : Collections.emptyMap();
            this.error = error;
        }
    }

    /** Reads a plugin's installed dex; injectable so payload planning is pure-testable. */
    interface DexSource {
        byte[] read(InstalledPlugin plugin) throws IOException;
    }

    /** Service surface used by the publisher; the Xposed binding stays behind this interface. */
    interface RemoteService {
        int apiVersion();
        long frameworkProperties();
        String[] listRemoteFiles();
        boolean deleteRemoteFile(String name);
        boolean writeRemoteFile(String name, byte[] bytes);
        boolean commitPreferences(String table, long generation);
    }

    private static final class ServiceState {
        int attempts;
        String error = "PENDING";
        long publishedGeneration;
        boolean supported;
        ScheduledFuture<?> retry;

        void cancelRetry() {
            if (retry != null) retry.cancel(false);
            retry = null;
        }
    }

    private PluginArtifactPublisher() {}

    public static State getState() {
        return state;
    }

    /** Registers the framework service listener exactly once and requests an initial publication. */
    public static void initialize(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
        if (!initializeRequested) {
            initializeRequested = true;
            try {
                XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                    @Override public void onServiceBind(XposedService service) {
                        IO.execute(() -> bind(service));
                    }

                    @Override public void onServiceDied(XposedService service) {
                        IO.execute(() -> unbind(service));
                    }
                });
            } catch (RuntimeException failure) {
                AdHookLog.w("[PluginArtifactPublisher] registerListener failed: " + failure.getClass().getSimpleName());
            }
        }
        requestPublish();
    }

    /** Records a new desired generation and enqueues a publication; safe from the main thread. */
    public static void requestPublish() {
        if (appContext == null) return;
        boolean enqueue;
        synchronized (LOCK) {
            long desired = nextGeneration(Math.max(state.desiredGeneration, state.publishedGeneration));
            state = new State(desired, state.publishedGeneration, "PENDING", 0, state.serviceAvailable);
            enqueue = !publishScheduled;
            if (enqueue) publishScheduled = true;
        }
        if (enqueue) IO.execute(PluginArtifactPublisher::runPublish);
    }

    /** Monotonic generation: strictly greater than the previous value and wall-clock time. */
    static long nextGeneration(long previous) {
        long now = System.currentTimeMillis();
        if (previous >= Long.MAX_VALUE - 1L) return Long.MAX_VALUE;
        long candidate = Math.max(previous + 1L, now);
        if (candidate <= previous) candidate = previous + 1L;
        return candidate;
    }

    /** 1s/3s/10s after the first three completed failures; -1 when the budget is exhausted. */
    static long retryDelayAfterAttempt(int attempts) {
        if (attempts == 1) return 1_000L;
        if (attempts == 2) return 3_000L;
        if (attempts == 3) return 10_000L;
        return -1L;
    }

    /** Remote {@code p_*.dex} names no longer referenced by the current snapshot. */
    static Set<String> obsoleteDexNames(Set<String> currentNames, String[] remoteFiles) {
        Set<String> obsolete = new TreeSet<>();
        if (remoteFiles == null) return obsolete;
        Set<String> current = currentNames != null ? currentNames : Collections.emptySet();
        for (String name : remoteFiles) {
            if (name != null && PluginConstants.isValidDexFileName(name) && !current.contains(name)) {
                obsolete.add(name);
            }
        }
        return obsolete;
    }

    /** Immutable application of one publication attempt, kept pure for regression tests. */
    static State applyResult(State previous, long generation, String error, int attempts,
                             boolean serviceAvailable) {
        if (previous == null || generation != previous.desiredGeneration) return previous;
        return new State(previous.desiredGeneration,
                error == null ? generation : previous.publishedGeneration,
                error, attempts, serviceAvailable);
    }

    /**
     * Plans the snapshot and dex payloads from a plugin list; no Binder or store access.
     * Returns an error payload when any enabled plugin has missing/changed dex bytes.
     */
    static PublishPayload preparePayloadWith(List<InstalledPlugin> plugins, long generation,
                                             DexSource dexSource) {
        if (plugins == null || dexSource == null) {
            return new PublishPayload(null, Collections.emptyMap(), "INVALID_PLUGIN_STATE");
        }
        Map<String, String> dexHashes = new LinkedHashMap<>();
        Map<String, byte[]> dexFiles = new LinkedHashMap<>();
        for (InstalledPlugin plugin : plugins) {
            if (plugin == null) continue;
            byte[] dex;
            try {
                dex = dexSource.read(plugin);
            } catch (IOException failure) {
                return new PublishPayload(null, Collections.emptyMap(), "MISSING_DEX");
            }
            if (dex == null || dex.length == 0) {
                return new PublishPayload(null, Collections.emptyMap(), "MISSING_DEX");
            }
            String sha = PluginFiles.sha256Hex(dex);
            if (!plugin.getDexSha256().isEmpty() && !plugin.getDexSha256().equalsIgnoreCase(sha)) {
                return new PublishPayload(null, Collections.emptyMap(), "DEX_HASH_MISMATCH");
            }
            String name = PluginRuntimeTable.dexFileName(plugin, sha);
            if (name.isEmpty() || dexFiles.containsKey(name)) {
                return new PublishPayload(null, Collections.emptyMap(), "INVALID_DEX_NAME");
            }
            dexHashes.put(plugin.getId(), sha);
            dexFiles.put(name, dex);
        }
        String table = PluginRuntimeTable.build(plugins, generation, dexHashes);
        if (table == null) return new PublishPayload(null, Collections.emptyMap(), "TABLE_TOO_LARGE");
        return new PublishPayload(table, dexFiles, null);
    }

    /** Uploads one snapshot to one service; returns null on success or a retryable error code. */
    static String publishToService(RemoteService service, Map<String, byte[]> dexFiles,
                                   String table, long generation) {
        if (service == null) return "SERVICE_UNAVAILABLE";
        try {
            if (service.apiVersion() < 101) return "REMOTE_UNSUPPORTED";
            // Some LSPosed builds do not populate PROP_CAP_REMOTE even though remote prefs/files
            // work. Capability flags are advisory here: the actual remote operations decide.
            for (Map.Entry<String, byte[]> dex : dexFiles.entrySet()) {
                if (!service.writeRemoteFile(dex.getKey(), dex.getValue())) {
                    return "REMOTE_WRITE_FAILED";
                }
            }
            String[] files = service.listRemoteFiles();
            if (files == null) return "REMOTE_LIST_FAILED";
            Set<String> listed = new HashSet<>(Arrays.asList(files));
            for (String name : dexFiles.keySet()) {
                if (!listed.contains(name)) return "REMOTE_WRITE_UNVERIFIED";
            }
            if (!service.commitPreferences(table, generation)) return "COMMIT_FAILED";
            // Only after the new snapshot is committed may obsolete files be removed.
            Set<String> obsolete = obsoleteDexNames(dexFiles.keySet(), service.listRemoteFiles());
            for (String name : obsolete) service.deleteRemoteFile(name);
            return null;
        } catch (UnsupportedOperationException unsupported) {
            return "REMOTE_UNSUPPORTED";
        } catch (RuntimeException failure) {
            return "SERVICE_ERROR:" + failure.getClass().getSimpleName();
        }
    }

    private static void bind(XposedService service) {
        if (service == null) return;
        if (BINDINGS.containsKey(service)) return;
        if (BINDINGS.size() >= MAX_SERVICES) {
            AdHookLog.w("[PluginArtifactPublisher] service budget exceeded");
            return;
        }
        RemoteService remote = new XposedRemoteService(service);
        BINDINGS.put(service, remote);
        SERVICES.put(remote, new ServiceState());
        AdHookLog.i("[PluginArtifactPublisher] service bound");
        requestPublish();
    }

    private static void unbind(XposedService service) {
        RemoteService remote = BINDINGS.remove(service);
        if (remote == null) return;
        ServiceState removed = SERVICES.remove(remote);
        if (removed != null) removed.cancelRetry();
        synchronized (LOCK) {
            state = new State(state.desiredGeneration, state.publishedGeneration, "SERVICE_DIED", 0,
                    !SERVICES.isEmpty() && state.serviceAvailable);
        }
    }

    private static void runPublish() {
        long generation;
        synchronized (LOCK) {
            generation = state.desiredGeneration;
            publishScheduled = false;
        }
        Context context = appContext;
        if (context == null || generation <= 0L) return;
        List<RemoteService> services = snapshotServices();
        if (services.isEmpty()) {
            synchronized (LOCK) {
                state = new State(state.desiredGeneration, state.publishedGeneration,
                        "SERVICE_UNAVAILABLE", 0, false);
            }
            return;
        }
        PublishPayload payload = preparePayload(context, generation);
        if (payload.error != null) {
            synchronized (LOCK) {
                state = new State(state.desiredGeneration, state.publishedGeneration, payload.error, 0,
                        state.serviceAvailable);
            }
            return;
        }
        for (RemoteService service : services) {
            ServiceState serviceState = serviceState(service);
            if (serviceState == null) continue;
            attempt(service, serviceState, payload, generation);
        }
    }

    private static void attempt(RemoteService service, ServiceState serviceState,
                                PublishPayload payload, long generation) {
        int attempts;
        synchronized (LOCK) {
            if (state.desiredGeneration != generation || serviceState(service) != serviceState) return;
            serviceState.attempts++;
            attempts = serviceState.attempts;
        }
        String error = publishToService(service, payload.dexFiles, payload.table, generation);
        boolean supported = !"REMOTE_UNSUPPORTED".equals(error);
        boolean scheduleRetry = false;
        if (error == null) {
            AdHookLog.i("[PluginArtifactPublisher] published generation " + generation);
        } else {
            AdHookLog.w("[PluginArtifactPublisher] publish failed: " + error + " attempts=" + attempts);
        }
        synchronized (LOCK) {
            if (state.desiredGeneration != generation || serviceState(service) != serviceState) return;
            serviceState.error = error;
            serviceState.supported = serviceState.supported || supported;
            if (error == null) {
                serviceState.publishedGeneration = generation;
                serviceState.attempts = 0;
            }
            state = aggregateLocked(generation, attempts);
            if (error == null) {
                AdHookLog.i("[PluginArtifactPublisher] state confirmed available=" + state.serviceAvailable
                        + " published=" + state.publishedGeneration);
            }
            if (error != null && serviceState.attempts < MAX_ATTEMPTS && supported) {
                long delay = retryDelayAfterAttempt(serviceState.attempts);
                if (delay >= 0L) scheduleRetry = true;
            }
            if (scheduleRetry) {
                final int retryAttempt = serviceState.attempts;
                serviceState.retry = IO.schedule(
                        () -> attempt(service, serviceState, payload, generation),
                        delayFor(retryAttempt), TimeUnit.MILLISECONDS);
            }
            publishScheduled = false;
        }
        maybeRunNextGeneration(generation);
    }

    private static State aggregateLocked(long generation, int attempts) {
        if (state.desiredGeneration != generation) return state;
        boolean confirmed = true;
        boolean available = false;
        String error = null;
        int maxAttempts = Math.max(attempts, 0);
        for (ServiceState serviceState : SERVICES.values()) {
            maxAttempts = Math.max(maxAttempts, serviceState.attempts);
            available = available || serviceState.supported;
            if (serviceState.publishedGeneration != generation || serviceState.error != null) {
                confirmed = false;
                if (error == null) {
                    error = serviceState.error != null ? serviceState.error : "PENDING";
                }
            }
        }
        if (SERVICES.isEmpty()) {
            confirmed = false;
            error = "SERVICE_UNAVAILABLE";
        }
        return new State(state.desiredGeneration,
                confirmed ? generation : state.publishedGeneration,
                confirmed ? null : error, maxAttempts, available);
    }

    private static void maybeRunNextGeneration(long completedGeneration) {
        boolean enqueue = false;
        synchronized (LOCK) {
            if (!publishScheduled && state.desiredGeneration != completedGeneration
                    && state.desiredGeneration > 0L) {
                publishScheduled = true;
                enqueue = true;
            }
        }
        if (enqueue) IO.execute(PluginArtifactPublisher::runPublish);
    }

    private static PublishPayload preparePayload(Context context, long generation) {
        File pluginsDirectory = new File(context.getFilesDir(), "plugins");
        List<InstalledPlugin> plugins = PluginStore.get(context).list();
        DexSource source = plugin -> {
            File dex = new File(new File(new File(pluginsDirectory, plugin.getId()),
                    Integer.toString(plugin.getVersionCode())), PluginZip.DEX_ENTRY);
            if (dex.length() > PluginConstants.MAX_DEX_BYTES) throw new IOException("dex too large");
            return PluginFiles.readAll(dex, PluginConstants.MAX_DEX_BYTES);
        };
        return preparePayloadWith(plugins, generation, source);
    }

    private static List<RemoteService> snapshotServices() {
        synchronized (LOCK) {
            return new ArrayList<>(SERVICES.keySet());
        }
    }

    private static ServiceState serviceState(RemoteService service) {
        synchronized (LOCK) {
            return SERVICES.get(service);
        }
    }

    private static long delayFor(int attempt) {
        long delay = retryDelayAfterAttempt(attempt);
        return delay >= 0L ? delay : -1L;
    }

    /** App-side Xposed adapter for the exact service 101 remote file/preference contract. */
    private static final class XposedRemoteService implements RemoteService {
        private final XposedService service;

        XposedRemoteService(XposedService service) {
            this.service = service;
        }

        @Override public int apiVersion() {
            try {
                return service.getApiVersion();
            } catch (RuntimeException failure) {
                return 0;
            }
        }

        @Override public long frameworkProperties() {
            try {
                return service.getFrameworkProperties();
            } catch (RuntimeException failure) {
                return 0L;
            }
        }

        @Override public String[] listRemoteFiles() {
            try {
                return service.listRemoteFiles();
            } catch (RuntimeException failure) {
                return null;
            }
        }

        @Override public boolean deleteRemoteFile(String name) {
            try {
                return service.deleteRemoteFile(name);
            } catch (RuntimeException failure) {
                return false;
            }
        }

        @Override public boolean writeRemoteFile(String name, byte[] bytes) {
            if (name == null || bytes == null) return false;
            try {
                service.deleteRemoteFile(name);
            } catch (RuntimeException ignored) {
                // Missing files are expected; a failed delete is retried by the open below.
            }
            try (ParcelFileDescriptor descriptor = service.openRemoteFile(name)) {
                if (descriptor == null) return false;
                try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                    output.write(bytes);
                    output.flush();
                    output.getFD().sync();
                }
                return descriptor.getStatSize() == bytes.length;
            } catch (Exception failure) {
                return false;
            }
        }

        @Override public boolean commitPreferences(String table, long generation) {
            try {
                SharedPreferences preferences = service.getRemotePreferences(
                        PluginConstants.REMOTE_PREFS_GROUP);
                if (preferences == null) return false;
                return preferences.edit()
                        .putString(PluginConstants.REMOTE_TABLE_KEY, table)
                        .putLong(PluginConstants.REMOTE_GENERATION_KEY, generation)
                        .commit();
            } catch (RuntimeException failure) {
                return false;
            }
        }
    }
}
