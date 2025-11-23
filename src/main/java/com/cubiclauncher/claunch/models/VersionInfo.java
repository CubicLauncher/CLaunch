package com.cubiclauncher.claunch.models;

import com.google.gson.JsonObject;
import com.cubiclauncher.claunch.utils.JsonUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Encapsula información completa de versión con soporte de herencia
 */
public class VersionInfo {
    private final JsonObject versionData;
    private final JsonObject baseVersionData;
    private final String versionId;
    private final String baseVersionId;
    private final String resolvedVersionId;
    private final Path gameDir;
    private final Path libDir;
    private final Path assetsDir;
    private final Path nativesDir;
    private final Path versionDir;
    private String MinimumJREVersion;

    public VersionInfo(String versionJsonPath, String gameDir) throws IOException {
        this.gameDir = Paths.get(gameDir).toAbsolutePath();
        this.versionData = JsonUtils.loadJson(versionJsonPath);

        if (this.versionData == null) {
            throw new IOException("Failed to load version file: " + versionJsonPath);
        }

        this.versionId = versionData.get("id").getAsString();
        this.versionDir = Paths.get(versionJsonPath).getParent();

        // ============================
        //   CARGA DEL PADRE (HERENCIA)
        // ============================
        if (versionData.has("inheritsFrom")) {
            this.baseVersionId = versionData.get("inheritsFrom").getAsString();

            String baseJsonPath = this.gameDir.resolve("shared/versions")
                    .resolve(baseVersionId)
                    .resolve(baseVersionId + ".json")
                    .toString();

            this.baseVersionData = JsonUtils.loadJson(baseJsonPath);

            if (this.baseVersionData == null) {
                throw new IOException("Failed to load base version: " + baseJsonPath);
            }

            this.resolvedVersionId = baseVersionId;
        } else {
            this.baseVersionId = null;
            this.baseVersionData = null;
            this.resolvedVersionId = versionId;
        }

        // ==================================
        //   RESOLVER javaVersion / Fallback
        // ==================================
        JsonObject javaVer = null;

        // 1) Primero la versión hija
        if (versionData.has("javaVersion")) {
            javaVer = versionData.getAsJsonObject("javaVersion");
        }

        // 2) Si no tiene, usar la del padre
        else if (baseVersionData != null && baseVersionData.has("javaVersion")) {
            javaVer = baseVersionData.getAsJsonObject("javaVersion");
        }

        // 3) Si ninguna tiene → devolver "0"
        if (javaVer != null && javaVer.has("majorVersion")) {
            this.MinimumJREVersion = javaVer.get("majorVersion").getAsString();
        } else {
            this.MinimumJREVersion = "0";
        }

        Path sharedDir = this.gameDir.resolve("shared");
        this.libDir = sharedDir.resolve("libraries").toAbsolutePath();
        this.assetsDir = sharedDir.resolve("assets").toAbsolutePath();
        this.nativesDir = sharedDir.resolve("natives").resolve(resolvedVersionId).toAbsolutePath();
    }

    public String getProperty(String key, String defaultValue) {
        if (versionData.has(key)) return versionData.get(key).getAsString();
        if (baseVersionData != null && baseVersionData.has(key)) {
            return baseVersionData.get(key).getAsString();
        }
        return defaultValue;
    }

    public Path getClientJar() {
        return gameDir.resolve("shared/versions")
                .resolve(resolvedVersionId)
                .resolve(resolvedVersionId + ".jar");
    }

    public Path getVersionJar() {
        return gameDir.resolve("shared/versions")
                .resolve(versionId)
                .resolve(versionId + ".jar");
    }

    public String getAssetsIndexName() {
        return getProperty("assets", "legacy");
    }

    public Path getAssetsVirtualDir() {
        return assetsDir.resolve("virtual").resolve(getAssetsIndexName());
    }

    // Getters
    public String getVersionId() { return versionId; }
    public String getBaseVersionId() { return baseVersionId; }
    public Path getGameDir() { return gameDir; }
    public Path getLibDir() { return libDir; }
    public String getMinimumJREVersion() { return MinimumJREVersion; }
    public Path getAssetsDir() { return assetsDir; }
    public Path getNativesDir() { return nativesDir; }
    public JsonObject getVersionData() { return versionData; }
    public JsonObject getBaseVersionData() { return baseVersionData; }
    public boolean hasInheritance() { return baseVersionId != null; }
}
