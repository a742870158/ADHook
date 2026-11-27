package com.adhook.engine;

import com.adhook.config.model.CustomRule;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only index over detached rules; candidates preserve order and retain no View or Context. */
final class RuleCandidateIndex {
    static final int MAX_CANDIDATES = 512;
    static final int MAX_TEXT_LENGTH = 8192;
    private record Entry(int order, CustomRule rule) { }
    private final Map<String, List<Entry>> classes = new HashMap<>();
    private final Map<String, List<Entry>> resourceNames = new HashMap<>(), resourceEntries = new HashMap<>();
    private final Map<String, List<Entry>> textExact = new HashMap<>(), textTrimmed = new HashMap<>();
    private final Map<String, List<Entry>> descExact = new HashMap<>(), descTrimmed = new HashMap<>();
    private final Map<Character, List<Entry>> textContains = new HashMap<>(), descContains = new HashMap<>();
    final boolean needsResources, needsText, needsDescription;

    RuleCandidateIndex(List<CustomRule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            CustomRule rule = rules.get(i);
            String target = rule.getTargetValue();
            if (target == null || target.isEmpty()) continue;
            Entry entry = new Entry(i, rule);
            if (CustomRule.TYPE_CLASS_NAME.equals(rule.getType())) add(classes, target, entry);
            else if (CustomRule.TYPE_RES_ID.equals(rule.getType())) {
                add(resourceNames, target, entry);
                if (!rule.isExactResourceId()) add(resourceEntries, target.substring(target.lastIndexOf('/') + 1), entry);
            } else if (CustomRule.TYPE_TEXT.equals(rule.getType())) {
                if (!"desc".equals(rule.getTextProperty())) addText(entry, textExact, textTrimmed, textContains);
                if (!"text".equals(rule.getTextProperty())) addText(entry, descExact, descTrimmed, descContains);
            }
        }
        needsResources = !resourceNames.isEmpty();
        needsText = !textExact.isEmpty() || !textTrimmed.isEmpty() || !textContains.isEmpty();
        needsDescription = !descExact.isEmpty() || !descTrimmed.isEmpty() || !descContains.isEmpty();
    }

    private static <K> void add(Map<K, List<Entry>> map, K key, Entry entry) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
    }

    private static void addText(Entry entry, Map<String, List<Entry>> exact, Map<String, List<Entry>> trimmed,
                                Map<Character, List<Entry>> contains) {
        CustomRule rule = entry.rule();
        if ("equals".equals(rule.getTextMatchMode())) add(rule.isTrimText() ? trimmed : exact, rule.getTargetValue(), entry);
        else add(contains, rule.getTargetValue().charAt(0), entry);
    }

    /** Null refuses the WHOLE node on exhaustion; never commit a partial candidate set. */
    List<CustomRule> candidates(String className, String resourceName, String resourceEntry, String text, String desc) {
        if ((text != null && text.length() > MAX_TEXT_LENGTH) || (desc != null && desc.length() > MAX_TEXT_LENGTH)) return null;
        TreeMap<Integer, CustomRule> selected = new TreeMap<>();
        if (!classes.isEmpty()) {
            for (int start = 0; className != null && start < className.length();) {
                if (!collect(selected, classes.get(className.substring(start)))) return null;
                int dot = className.indexOf('.', start);
                if (dot < 0) break;
                start = dot + 1;
            }
        }
        if (!collect(selected, resourceNames.get(resourceName)) || !collect(selected, resourceEntries.get(resourceEntry))) return null;
        if (!collectText(selected, text, textExact, textTrimmed, textContains)
                || !collectText(selected, desc, descExact, descTrimmed, descContains)) return null;
        return new ArrayList<>(selected.values());
    }

    private static boolean collectText(TreeMap<Integer, CustomRule> selected, String value,
                                       Map<String, List<Entry>> exact, Map<String, List<Entry>> trimmed,
                                       Map<Character, List<Entry>> contains) {
        if (value == null) return true;
        if (!collect(selected, exact.get(value)) || !collect(selected, trimmed.get(value.trim()))) return false;
        if (contains.isEmpty()) return true;
        BitSet seen = new BitSet();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (seen.get(character)) continue;
            seen.set(character);
            if (!collect(selected, contains.get(character))) return false;
        }
        return true;
    }

    private static boolean collect(TreeMap<Integer, CustomRule> selected, List<Entry> entries) {
        if (entries == null) return true;
        for (Entry entry : entries) {
            if (!selected.containsKey(entry.order()) && selected.size() >= MAX_CANDIDATES) return false;
            selected.put(entry.order(), entry.rule());
        }
        return true;
    }
}
