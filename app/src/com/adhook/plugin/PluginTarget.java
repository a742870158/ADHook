package com.adhook.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One host application a plugin wants to run in.
 *
 * <p>{@code primary} is the effective runtime identity: {@link #matches(String)} compares the
 * requested package/process name against {@code primary}, falling back to {@code packageName}
 * when no primary is declared. {@code processes} lists optional additional process names.</p>
 */
public final class PluginTarget {

    private final String packageName;
    private final String label;
    private final String primary;
    private final List<String> processes;

    public PluginTarget(String packageName, String label, String primary, List<String> processes) {
        this.packageName = packageName != null ? packageName : "";
        this.label = label != null ? label : "";
        this.primary = primary != null ? primary : "";
        this.processes = Collections.unmodifiableList(
                processes != null ? new ArrayList<>(processes) : new ArrayList<>());
    }

    /** Returns null when any field is invalid; the manifest parser treats that as a rejection. */
    public static PluginTarget fromJson(JSONObject json) {
        if (json == null) return null;
        try {
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"packageName".equals(key) && !"label".equals(key) && !"primary".equals(key)
                        && !"processes".equals(key)) return null;
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        Object rawPackage = json.opt("packageName");
        if (!(rawPackage instanceof String)) return null;
        String packageName = (String) rawPackage;
        if (!PluginConstants.isValidPackageName(packageName)) return null;
        if (PluginConstants.isForbiddenTargetPackage(packageName)) return null;
        Object rawLabel = json.opt("label");
        if (rawLabel != null && !(rawLabel instanceof String)) return null;
        String label = rawLabel instanceof String ? (String) rawLabel : "";
        if (label.length() > PluginConstants.MAX_TARGET_LABEL_LENGTH
                || PluginConstants.hasControlCharacters(label)) return null;
        Object rawPrimary = json.opt("primary");
        if (rawPrimary != null && !(rawPrimary instanceof String)) return null;
        String primary = rawPrimary instanceof String ? (String) rawPrimary : "";
        if (!primary.isEmpty()) {
            if (!PluginConstants.isValidPackageName(primary)) return null;
            if (PluginConstants.isForbiddenTargetPackage(primary)) return null;
        }
        List<String> processes = new ArrayList<>();
        Object rawProcesses = json.opt("processes");
        if (rawProcesses != null) {
            if (!(rawProcesses instanceof JSONArray)) return null;
            JSONArray array = (JSONArray) rawProcesses;
            if (array.length() > PluginConstants.MAX_PROCESSES) return null;
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (!(value instanceof String)) return null;
                String process = (String) value;
                if (!PluginConstants.isValidProcessName(process)) return null;
                if (!unique.add(process)) return null;
            }
            processes.addAll(unique);
        }
        return new PluginTarget(packageName, label, primary, processes);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("packageName", packageName);
            json.put("label", label);
            json.put("primary", primary);
            JSONArray array = new JSONArray();
            for (String process : processes) array.put(process);
            json.put("processes", array);
        } catch (org.json.JSONException ignored) {
            // Values are non-null host identifiers; nothing here can be rejected by org.json.
        }
        return json;
    }

    /** The declared runtime identity ({@code primary} when present, otherwise packageName). */
    public boolean matches(String value) {
        if (value == null) return false;
        String effective = primary.isEmpty() ? packageName : primary;
        return effective.equals(value);
    }

    /** Every package key this target may be registered under by the runtime table. */
    public Set<String> coveredPackages() {
        Set<String> covered = new LinkedHashSet<>();
        if (!packageName.isEmpty()) covered.add(packageName);
        if (!primary.isEmpty()) covered.add(primary);
        return covered;
    }

    public String getPackageName() { return packageName; }
    public String getLabel() { return label; }
    public String getPrimary() { return primary; }
    public List<String> getProcesses() { return processes; }

    public PluginTarget copy() {
        return new PluginTarget(packageName, label, primary, new ArrayList<>(processes));
    }
}
