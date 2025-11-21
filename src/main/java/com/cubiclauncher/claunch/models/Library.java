package com.cubiclauncher.claunch.models;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.cubiclauncher.claunch.utils.JsonUtils;

import java.nio.file.Path;

/**
 * Representa una librería con toda su información
 */
public class Library {
    private final String name;
    private final JsonObject downloads;
    private final JsonArray rules;

    public Library(JsonObject libObject) {
        this.name = JsonUtils.safeGetString(libObject, "name");
        this.downloads = JsonUtils.safeGetObject(libObject, "downloads");
        this.rules = JsonUtils.safeGetArray(libObject, "rules");
    }

    public boolean shouldInclude() {
        return rules == null || JsonUtils.evaluateRules(rules);
    }

    public String getArtifactPath() {
        if (downloads != null && downloads.has("artifact")) {
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            return JsonUtils.safeGetString(artifact, "path");
        }
        return null;
    }

    public Path resolvePath(Path libDir) {
        String path = getArtifactPath();
        if (path != null) return libDir.resolve(path);

        if (name != null) {
            String[] parts = name.split(":");
            if (parts.length >= 3) {
                String groupPath = parts[0].replace(".", "/");
                String artifact = parts[1];
                String version = parts[2];
                String classifier = parts.length > 3 ? "-" + parts[3] : "";

                return libDir.resolve(groupPath)
                        .resolve(artifact)
                        .resolve(version)
                        .resolve(artifact + "-" + version + classifier + ".jar");
            }
        }
        return null;
    }

    public String getGroupId() {
        return name != null && name.contains(":") ? name.split(":")[0] : null;
    }

    public String getArtifactId() {
        String[] parts = name != null ? name.split(":") : new String[0];
        return parts.length > 1 ? parts[1] : null;
    }

    public String getKey() {
        String group = getGroupId();
        String artifact = getArtifactId();
        return (group != null && artifact != null) ? group + ":" + artifact : name;
    }

    // Getters
    public String getName() { return name; }
    public JsonObject getDownloads() { return downloads; }
    public JsonArray getRules() { return rules; }
}