package com.cubiclauncher.claunch.resolvers;

import com.cubiclauncher.claunch.models.VersionInfo;
import com.cubiclauncher.claunch.models.Library;
import com.cubiclauncher.claunch.utils.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Resuelve dependencias con soporte de herencia y conflictos
 * Prioriza librerías del child sobre el parent
 */
public class DependencyResolver {
    private final Set<String> addedPaths = new LinkedHashSet<>();
    private final Map<String, String> libraryKeys = new HashMap<>(); // group:artifact -> path
    private final Map<String, String> nativeLibraries = new HashMap<>(); // Para natives separados
    private final Path libDir;

    public DependencyResolver(Path libDir, Path nativesDir) {
        this.libDir = libDir;
    }

    public void processVersion(JsonObject versionData, boolean isChild) {
        if (!versionData.has("libraries")) return;

        JsonArray libsArray = versionData.getAsJsonArray("libraries");
        for (JsonElement element : libsArray) {
            JsonObject libObj = element.getAsJsonObject();
            Library lib = new Library(libObj);

            if (!lib.shouldInclude()) continue;

            // Procesar artifact principal
            addArtifact(libObj, lib, isChild);

            // Procesar natives
            addNatives(libObj, lib, isChild);
        }
    }

    private void addArtifact(JsonObject libObj, Library lib, boolean isChild) {
        JsonObject downloads = JsonUtils.safeGetObject(libObj, "downloads");
        if (downloads != null && downloads.has("artifact")) {
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            String path = JsonUtils.safeGetString(artifact, "path");
            if (path != null) {
                Path fullPath = libDir.resolve(path);
                // Solo procesar como artifact si NO es un native
                if (isNativeLibrary(path)) {
                    addPath(fullPath, lib.getName() + " (artifact)", lib.getKey(), isChild, false);
                }
            }
        } else {
            // Construir path desde name si no hay downloads
            Path path = lib.resolvePath(libDir);
            if (path != null && isNativeLibrary(path.toString())) {
                addPath(path, lib.getName(), lib.getKey(), isChild, false);
            }
        }
    }

    private void addNatives(JsonObject libObj, Library lib, boolean isChild) {
        JsonObject downloads = JsonUtils.safeGetObject(libObj, "downloads");
        JsonObject natives = JsonUtils.safeGetObject(libObj, "natives");

        if (downloads == null || natives == null) return;

        // Determinar el classifier de natives para el OS actual
        String osName = System.getProperty("os.name").toLowerCase();
        String nativeKey = null;

        if (osName.contains("win")) {
            nativeKey = JsonUtils.safeGetString(natives, "windows");
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            nativeKey = JsonUtils.safeGetString(natives, "osx");
        } else if (osName.contains("linux")) {
            nativeKey = JsonUtils.safeGetString(natives, "linux");
        }

        if (nativeKey == null) return;

        // Reemplazar ${arch} si existe
        String arch = System.getProperty("os.arch").contains("64") ? "64" : "32";
        nativeKey = nativeKey.replace("${arch}", arch);

        // Buscar en classifiers
        if (downloads.has("classifiers")) {
            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
            if (classifiers.has(nativeKey)) {
                JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                String path = JsonUtils.safeGetString(nativeArtifact, "path");
                if (path != null) {
                    Path fullPath = libDir.resolve(path);
                    // Los natives se agregan sin clave y sin reemplazar artifacts
                    addNativePath(fullPath, lib.getName() + " (native: " + nativeKey + ")");
                }
            }
        }
    }

    private boolean isNativeLibrary(String path) {
        return path == null || !path.contains("-natives-");
    }

    private void addPath(Path path, String description, String libraryKey, boolean isChild, boolean isNative) {
        if (path == null || !Files.exists(path)) {
            if (path != null) {
                System.err.println("Library not found: " + description + " -> " + path);
            }
            return;
        }

        String pathStr = path.toString();

        // Si tiene clave (group:artifact), verificar conflictos SOLO para no-natives
        if (libraryKey != null && !isNative) {
            String existingPath = libraryKeys.get(libraryKey);

            if (existingPath != null) {
                // Conflicto detectado: misma librería, diferentes versiones
                if (isChild) {
                    // El child tiene prioridad: reemplazar la del parent
                    System.out.println("Library conflict resolved - Child priority: " + libraryKey);
                    System.out.println("  Replacing: " + existingPath);
                    System.out.println("  With: " + pathStr);

                    addedPaths.remove(existingPath);
                    addedPaths.add(pathStr);
                    libraryKeys.put(libraryKey, pathStr);
                } else {
                    // El parent se procesa primero, mantener la existente si el child no la reemplazó
                    if (!addedPaths.contains(pathStr)) {
                        System.out.println("Library conflict - Keeping parent version: " + libraryKey);
                    }
                }
            } else {
                // Primera vez que vemos esta librería
                libraryKeys.put(libraryKey, pathStr);
                if (addedPaths.add(pathStr)) {
                    System.out.println("Library: " + description + " -> " + pathStr);
                }
            }
        } else {
            // Librería sin clave o nativa, agregar por path
            if (addedPaths.add(pathStr)) {
                System.out.println("Library: " + description + " -> " + pathStr);
            }
        }
    }

    private void addNativePath(Path path, String description) {
        if (path == null || !Files.exists(path)) {
            if (path != null) {
                System.err.println("Native library not found: " + description + " -> " + path);
            }
            return;
        }

        String pathStr = path.toString();
        // Los natives se agregan directamente sin verificar conflictos
        if (addedPaths.add(pathStr)) {
            System.out.println("Native: " + description + " -> " + pathStr);
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

        System.out.println("Loader: " + loaderType);

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
                addIfExists(classpath, versionJar);
        }
    }

    private void addIfExists(List<String> classpath, Path jar) {
        if (Files.exists(jar)) {
            String jarPath = jar.toString();
            if (!classpath.contains(jarPath)) {
                classpath.add(jarPath);
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

    public int getLibraryCount() { return addedPaths.size(); }
}