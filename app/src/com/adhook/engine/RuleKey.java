package com.adhook.engine;

import com.adhook.config.model.CustomRule;

/** Immutable matching/action identity, including edits made under the same rule id. */
record RuleKey(String packageName, String id, String source, String type, String target,
               String action, int depth, String activityFilter, int maxTextLength,
               String textProperty, String textMatchMode, boolean trimText,
               boolean exactActivityFilter, boolean exactResourceId) {
    static RuleKey of(String packageName, CustomRule rule) {
        return new RuleKey(packageName, rule.getId(), rule.getSource(), rule.getType(),
                rule.getTargetValue(), rule.getAction(), rule.getParentDepth(),
                rule.getActivityFilter(), rule.getMaxTextLength(), rule.getTextProperty(),
                rule.getTextMatchMode(), rule.isTrimText(), rule.isExactActivityFilter(),
                rule.isExactResourceId());
    }
}
