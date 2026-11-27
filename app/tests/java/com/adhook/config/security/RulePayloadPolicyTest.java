package com.adhook.config.security;

import com.adhook.config.model.CustomRule;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RulePayloadPolicyTest {
    private JSONObject raw() throws Exception {
        return new JSONObject().put("id", "local-rule").put("type", "text").put("targetValue", "Skip").put("action", "collapse");
    }

    @Test public void retainsAllExactMatchingConstraints() throws Exception {
        CustomRule rule = RulePayloadPolicy.parse(raw().put("textProperty", "desc").put("textMatchMode", "equals")
            .put("trimText", false).put("exactActivityFilter", true).put("exactResourceId", true)
            .put("activityFilter", "com.host.Splash").put("maxTextLength", 8).toString());
        assertNotNull(rule); assertEquals("desc", rule.getTextProperty()); assertEquals("equals", rule.getTextMatchMode());
        assertFalse(rule.isTrimText()); assertTrue(rule.isExactActivityFilter()); assertTrue(rule.isExactResourceId());
        assertEquals(8, rule.getMaxTextLength());
    }

    @Test public void rejectsRatherThanBroadeningMalformedConstraints() throws Exception {
        assertNull(RulePayloadPolicy.parse(raw().put("maxTextLength", -1).toString()));
        assertNull(RulePayloadPolicy.parse(raw().put("maxTextLength", "4").toString()));
        assertNull(RulePayloadPolicy.parse(raw().put("trimText", "false").toString()));
        assertNull(RulePayloadPolicy.parse(raw().put("textMatchMode", "regex").toString()));
        assertNull(RulePayloadPolicy.parse(raw().put("exactActivityFilter", 1).toString()));
        assertNull(RulePayloadPolicy.parse(raw().put("activityFilter", new JSONObject()).toString()));
    }
}
