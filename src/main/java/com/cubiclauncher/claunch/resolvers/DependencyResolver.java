package com.cubiclauncher.claunch.resolvers;

import com.cubiclauncher.claunch.models.VersionInfo;
import com.cubiclauncher.claunch.models.Library;
import com.cubiclauncher.claunch.utils.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Resuelve dependencias con soporte de herencia y conflictos
 * Prioriza librerías del child sobre el parent
 * NO incluye natives en el classpath (solo se extraen)
 */
public class DependencyResolver {
    private final Set<String> addedPaths = new LinkedHashSet<>();
    private final Map<String, String> libraryKeys = new HashMap<>();
    private final Path libDir;
    private final Path nativesDir;
    private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);

    public DependencyResolver(Path libDir, Path nativesDir) {
        this.libDir = libDir;
        this.nativesDir = nativesDir;
    }

    public void processVersion(JsonObject versionData, boolean isChild) {
        if (!versionData.has("libraries")) return;

        JsonArray libsArray = versionData.getAsJsonArray("libraries");
        for (JsonElement element : libsArray) {
            JsonObject libObj = element.getAsJsonObject();
            Library lib = new Library(libObj);

            if (!lib.shouldInclude()) continue;

            // Procesar artifact principal (NO natives)
            addArtifact(libObj, lib, isChild);
        }
    }

    private void addArtifact(JsonObject libObj, Library lib, boolean isChild) {
        JsonObject downloads = JsonUtils.safeGetObject(libObj, "downloads");

        if (downloads != null && downloads.has("artifact")) {
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            String path = JsonUtils.safeGetString(artifact, "path");

            if (path != null) {
                // IMPORTANTE: NO agregar JARs de natives al classpath
                if (isNativeJar(path)) {
                    log.debug("Skipping native JAR from classpath: {}", path);
                    return;
                }

                Path fullPath = libDir.resolve(path);
                addPath(fullPath, lib.getName(), lib.getKey(), isChild);
            }
        } else {
            // Construir path desde name si no hay downloads
            Path path = lib.resolvePath(libDir);
            if (path != null) {
                String pathStr = path.toString();
                if (isNativeJar(pathStr)) {
                    log.debug("Skipping native JAR from classpath: {}", pathStr);
                    return;
                }
                addPath(path, lib.getName(), lib.getKey(), isChild);
            }
        }
    }

    /**
     * Detecta si un JAR es de natives basándose en su path
     */
    private boolean isNativeJar(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();

        // Patrones comunes de JARs de natives
        return lower.contains("-natives-") ||
                lower.contains("/natives/") ||
                lower.endsWith("-natives.jar") ||
                // Patrones específicos de LWJGL
                (lower.contains("lwjgl") && (
                        lower.contains("-linux") ||
                                lower.contains("-windows") ||
                                lower.contains("-macos") ||
                                lower.contains("-freebsd")
                ));
    }

    private void addPath(Path path, String description, String libraryKey, boolean isChild) {
        if (path == null || !Files.exists(path)) {
            if (path != null) {
                log.warn("Library not found: {} -> {}", description, path);
            }
            return;
        }

        String pathStr = path.toString();

        if (libraryKey != null) {
            String existingPath = libraryKeys.get(libraryKey);

            if (existingPath != null) {
                if (isChild) {
                    log.info("Library conflict resolved - Child priority: {}", libraryKey);
                    log.info("  Replacing: {}", existingPath);
                    log.info("  With: {}", pathStr);

                    addedPaths.remove(existingPath);
                    addedPaths.add(pathStr);
                    libraryKeys.put(libraryKey, pathStr);
                }
                // Si es parent y ya existe, no hacer nada
            } else {
                libraryKeys.put(libraryKey, pathStr);
                addedPaths.add(pathStr);
                log.debug("Added library: {} -> {}", description, pathStr);
            }
        } else {
            if (addedPaths.add(pathStr)) {
                log.debug("Added library: {} -> {}", description, pathStr);
            }
        }
    }

    public String buildClasspath(VersionInfo info) {
        List<String> classpath = new ArrayList<>(addedPaths);
        addVersionJars(classpath, info);
        return String.join(File.pathSeparator, classpath);
    }

    private void addVersionJars(List<String> classpath, VersionInfo info) {
        String loaderType = detectLoader(info.getVersionId());
        Path clientJar = info.getClientJar();
        Path versionJar = info.getVersionJar();

        log.info("Loader: {}", loaderType);

        switch (loaderType) {
            case "forge":
                addIfExists(classpath, clientJar);
                addIfExists(classpath, versionJar);
                addForgeUniversalJar(classpath, info);
                break;
            case "neoforge":
                addIfExists(classpath, versionJar);
                break;
            default:
                addIfExists(classpath, clientJar);
                if (!clientJar.equals(versionJar)) {
                    addIfExists(classpath, versionJar);
                }
        }
    }

    private void addIfExists(List<String> classpath, Path jar) {
        if (jar != null && Files.exists(jar)) {
            String jarPath = jar.toString();
            if (!classpath.contains(jarPath)) {
                classpath.add(jarPath);
                log.debug("Added JAR to classpath: {}", jarPath);
            }
        }
    }

    private void addForgeUniversalJar(List<String> classpath, VersionInfo info) {
        Path forgeJar = findForgeUniversalJar(info);
        if (forgeJar != null) addIfExists(classpath, forgeJar);
    }

    private Path findForgeUniversalJar(VersionInfo info) {
        Path jar = searchForgeInLibraries(info.getVersionData());
        if (jar != null) return jar;
        if (info.hasInheritance()) {
            jar = searchForgeInLibraries(info.getBaseVersionData());
        }
        return jar;
    }

    private Path searchForgeInLibraries(JsonObject versionData) {
        if (!versionData.has("libraries")) return null;

        for (JsonElement element : versionData.getAsJsonArray("libraries")) {
            JsonObject lib = element.getAsJsonObject();
            String name = JsonUtils.safeGetString(lib, "name");

            if (name != null && (name.contains("net.minecraftforge:forge:") ||
                    name.contains("net.minecraftforge:minecraftforge:"))) {
                return buildForgePath(name);
            }
        }
        return null;
    }

    private Path buildForgePath(String name) {
        String[] parts = name.split(":");
        if (parts.length < 3) return null;

        String groupPath = parts[0].replace(".", "/");
        String artifact = parts[1];
        String version = parts[2];

        Path universal = libDir.resolve(groupPath)
                .resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + "-universal.jar");

        if (Files.exists(universal)) return universal;

        return libDir.resolve(groupPath)
                .resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + ".jar");
    }

    private String detectLoader(String versionId) {
        String lower = versionId.toLowerCase();
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("forge")) return "forge";
        if (lower.contains("fabric")) return "fabric";
        return "vanilla";
    }

    public int getLibraryCount() {
        return addedPaths.size();
    }
}