package com.adhook.engine;

import com.adhook.config.model.CustomRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuleCandidateIndexTest {
    private static CustomRule rule(String type, String target) {
        return new CustomRule(target, type, target, CustomRule.ACTION_COLLAPSE);
    }

    @Test public void resourceAndClassIndexesExcludeUnrelatedRulesAndPreserveLegacySuffixes() {
        List<CustomRule> rules = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rules.add(rule(CustomRule.TYPE_RES_ID, "unrelated_" + i));
        CustomRule legacy = rule(CustomRule.TYPE_RES_ID, "other.pkg:id/banner");
        CustomRule exact = rule(CustomRule.TYPE_RES_ID, "other.pkg:id/banner"); exact.setExactResourceId(true);
        CustomRule classRule = rule(CustomRule.TYPE_CLASS_NAME, "HostBanner");
        rules.add(legacy); rules.add(exact); rules.add(classRule);
        RuleCandidateIndex index = new RuleCandidateIndex(rules);
        assertEquals(Arrays.asList(legacy, classRule), index.candidates("com.host.HostBanner", "actual.pkg:id/banner", "banner", null, null));
        assertEquals(1, index.candidates("android.view.View", "actual.pkg:id/banner", "banner", null, null).size());
    }

    @Test public void textPropertyTrimmingAndOriginalRuleOrderRemainIndependent() {
        CustomRule exactText = rule(CustomRule.TYPE_TEXT, "Skip");
        exactText.setTextMatchMode("equals"); exactText.setTextProperty("text");
        CustomRule rawText = exactText.copy(); rawText.setTrimText(false);
        CustomRule description = rule(CustomRule.TYPE_TEXT, "Close"); description.setTextProperty("desc");
        CustomRule unrelated = rule(CustomRule.TYPE_TEXT, "广告");
        RuleCandidateIndex index = new RuleCandidateIndex(Arrays.asList(description, rawText, unrelated, exactText));
        assertEquals(Arrays.asList(description, exactText), index.candidates("android.widget.TextView", null, null, " Skip ", "Close ad"));
        assertEquals(Arrays.asList(rawText, exactText), index.candidates("android.widget.TextView", null, null, "Skip", null));
    }

    @Test public void candidateAndAttributeBudgetsNeverReturnAPartialMatchSet() {
        List<CustomRule> rules = new ArrayList<>();
        for (int i = 0; i <= RuleCandidateIndex.MAX_CANDIDATES; i++) rules.add(rule(CustomRule.TYPE_TEXT, "广告" + i));
        RuleCandidateIndex index = new RuleCandidateIndex(rules);
        assertNull(index.candidates("android.widget.TextView", null, null, "广告", null));
        assertNull(index.candidates("android.widget.TextView", null, null, "a".repeat(RuleCandidateIndex.MAX_TEXT_LENGTH + 1), null));
        assertTrue(index.candidates("android.widget.TextView", null, null, "normal", null).isEmpty());
    }
}
