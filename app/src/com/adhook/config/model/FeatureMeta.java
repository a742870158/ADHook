package com.adhook.config.model;

/**
 * 原生 Hook 功能特性元数据声明。
 */
public class FeatureMeta {

    private final String id;
    private final String name;
    private final String description;
    private final boolean defaultEnabled;
    private final boolean implemented;

    public FeatureMeta(String id, String name, String description, boolean defaultEnabled) {
        this(id, name, description, defaultEnabled, true);
    }

    public FeatureMeta(String id, String name, String description, boolean defaultEnabled, boolean implemented) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.defaultEnabled = defaultEnabled;
        this.implemented = implemented;
    }

    public FeatureMeta(String id, String name, String description) {
        this(id, name, description, true);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public boolean isImplemented() {
        return implemented;
    }
}
