package com.cubiclauncher.claunch;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Universal Minecraft Launcher
 * Soporta Vanilla, Forge, NeoForge y Fabric con sistema de herencia de versiones
 */
public class Launcher {

    // ==================== CLASES INTERNAS ====================

    /**
     * Opciones de lanzamiento opcionales
     */
    public static class LaunchOptions {
        public boolean demoMode = false;
        public String quickPlayMode = null; // "singleplayer", "multiplayer", "realms"
        public String quickPlayValue = null; // world name, server address, or realm id

        public static LaunchOptions defaults() {
            return new LaunchOptions();
        }

        public LaunchOptions withDemo(boolean demo) {
            this.demoMode = demo;
            return this;
        }

        public LaunchOptions withQuickPlaySingleplayer(String worldName) {
            this.quickPlayMode = "singleplayer";
            this.quickPlayValue = worldName;
            return this;
        }

        public LaunchOptions withQuickPlayMultiplayer(String serverAddress) {
            this.quickPlayMode = "multiplayer";
            this.quickPlayValue = serverAddress;
            return this;
        }

        public LaunchOptions withQuickPlayRealms(String realmId) {
            this.quickPlayMode = "realms";
            this.quickPlayValue = realmId;
            return this;
        }
    }

    /**
     * Encapsula información completa de versión con soporte de herencia
     */
    private static class VersionInfo {
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

        public VersionInfo(String versionJsonPath, String gameDir) throws IOException {
            this.gameDir = Paths.get(gameDir).toAbsolutePath();
            this.versionData = loadJson(versionJsonPath);

            if (this.versionData == null) {
                throw new IOException("Failed to load version file: " + versionJsonPath);
            }

            this.versionId = versionData.get("id").getAsString();
            this.versionDir = Paths.get(versionJsonPath).getParent();

            if (versionData.has("inheritsFrom")) {
                this.baseVersionId = versionData.get("inheritsFrom").getAsString();
                String baseJsonPath = this.gameDir.resolve("shared/versions")
                        .resolve(baseVersionId)
                        .resolve(baseVersionId + ".json")
                        .toString();

                this.baseVersionData = loadJson(baseJsonPath);
                if (this.baseVersionData == null) {
                    throw new IOException("Failed to load base version: " + baseJsonPath);
                }
                this.resolvedVersionId = baseVersionId;
            } else {
                this.baseVersionId = null;
                this.baseVersionData = null;
                this.resolvedVersionId = versionId;
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

        public String getVersionId() { return versionId; }
        public String getBaseVersionId() { return baseVersionId; }
        public String getResolvedVersionId() { return resolvedVersionId; }
        public Path getGameDir() { return gameDir; }
        public Path getLibDir() { return libDir; }
        public Path getAssetsDir() { return assetsDir; }
        public Path getNativesDir() { return nativesDir; }
        public Path getVersionDir() { return versionDir; }
        public JsonObject getVersionData() { return versionData; }
        public JsonObject getBaseVersionData() { return baseVersionData; }
        public boolean hasInheritance() { return baseVersionId != null; }
    }

    /**
     * Representa una librería con toda su información
     */
    private static class Library {
        private final String name;
        private final JsonObject downloads;
        private final JsonArray rules;

        public Library(JsonObject libObject) {
            this.name = safeGetString(libObject, "name");
            this.downloads = safeGetObject(libObject, "downloads");
            this.rules = safeGetArray(libObject);
            JsonObject natives = safeGetObject(libObject, "natives");
        }

        public boolean shouldInclude() {
            return rules == null || evaluateRules(rules);
        }

        public String getArtifactPath() {
            if (downloads != null && downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                return safeGetString(artifact, "path");
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
    }

    /**
     * Resuelve dependencias con soporte de herencia y conflictos
     * Prioriza librerías del child sobre el parent
     * Maneja correctamente natives cuando hay conflictos de versiones
     */
    private static class DependencyResolver {
        private final Set<String> addedPaths = new LinkedHashSet<>();
        private final Map<String, String> libraryKeys = new HashMap<>(); // group:artifact -> path
        private final Map<String, NativeInfo> nativeLibraries = new HashMap<>(); // group:artifact -> native info
        private final Path libDir;
        private final Path nativesDir;

        // Almacena info de natives pendientes de extracción
        private static class NativeInfo {
            final String description;
            final Path jarPath;
            final String classifier;

            NativeInfo(String description, Path jarPath, String classifier) {
                this.description = description;
                this.jarPath = jarPath;
                this.classifier = classifier;
            }
        }

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

                // Procesar artifact principal
                addArtifact(libObj, lib, isChild);

                // Procesar natives (con soporte de conflictos)
                addNatives(libObj, lib, isChild);
            }
        }

        private void addArtifact(JsonObject libObj, Library lib, boolean isChild) {
            JsonObject downloads = safeGetObject(libObj, "downloads");
            if (downloads != null && downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                String path = safeGetString(artifact, "path");
                if (path != null) {
                    Path fullPath = libDir.resolve(path);
                    // Solo procesar como artifact si NO es un native
                    if (isNativeLibrary(path)) {
                        addPath(fullPath, lib.name + " (artifact)", lib.getKey(), isChild, false);
                    }
                }
            } else {
                // Construir path desde name si no hay downloads
                Path path = lib.resolvePath(libDir);
                if (path != null && isNativeLibrary(path.toString())) {
                    addPath(path, lib.name, lib.getKey(), isChild, false);
                }
            }
        }

        private void addNatives(JsonObject libObj, Library lib, boolean isChild) {
            JsonObject downloads = safeGetObject(libObj, "downloads");
            JsonObject natives = safeGetObject(libObj, "natives");

            if (natives == null) return;

            // Determinar el classifier de natives para el OS actual
            String osName = System.getProperty("os.name").toLowerCase();
            String nativeKey = null;

            if (osName.contains("win")) {
                nativeKey = safeGetString(natives, "windows");
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                nativeKey = safeGetString(natives, "osx");
            } else if (osName.contains("linux")) {
                nativeKey = safeGetString(natives, "linux");
            }

            if (nativeKey == null) return;

            // Reemplazar ${arch} si existe
            String arch = System.getProperty("os.arch").contains("64") ? "64" : "32";
            nativeKey = nativeKey.replace("${arch}", arch);

            Path nativeJarPath = null;

            // Buscar en classifiers primero
            if (downloads != null && downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifiers.has(nativeKey)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeKey);
                    String path = safeGetString(nativeArtifact, "path");
                    if (path != null) {
                        nativeJarPath = libDir.resolve(path);
                    }
                }
            }

            // Si no hay classifiers, construir path manualmente
            if (nativeJarPath == null) {
                nativeJarPath = buildNativePath(lib.name, nativeKey);
            }

            if (nativeJarPath == null || !Files.exists(nativeJarPath)) {
                System.err.println("Native library not found: " + lib.name +
                        " (native: " + nativeKey + ") -> " + nativeJarPath);
                return;
            }

            String libraryKey = lib.getKey();
            NativeInfo newNative = new NativeInfo(
                    lib.name + " (native: " + nativeKey + ")",
                    nativeJarPath,
                    nativeKey
            );

            // Manejar conflictos de natives igual que artifacts
            NativeInfo existingNative = nativeLibraries.get(libraryKey);

            if (existingNative != null) {
                if (isChild) {
                    // Child tiene prioridad: reemplazar native del parent
                    System.out.println("Native conflict resolved - Child priority: " + libraryKey);
                    System.out.println("  Replacing native: " + existingNative.jarPath);
                    System.out.println("  With: " + nativeJarPath);
                    nativeLibraries.put(libraryKey, newNative);
                } else {
                    System.out.println("Native conflict - Keeping existing: " + libraryKey);
                }
            } else {
                nativeLibraries.put(libraryKey, newNative);
                System.out.println("Native: " + newNative.description + " -> " + nativeJarPath);
            }
        }

        private Path buildNativePath(String name, String classifier) {
            if (name == null) return null;

            String[] parts = name.split(":");
            if (parts.length < 3) return null;

            String groupPath = parts[0].replace(".", "/");
            String artifact = parts[1];
            String version = parts[2];

            return libDir.resolve(groupPath)
                    .resolve(artifact)
                    .resolve(version)
                    .resolve(artifact + "-" + version + "-" + classifier + ".jar");
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

            if (libraryKey != null && !isNative) {
                String existingPath = libraryKeys.get(libraryKey);

                if (existingPath != null) {
                    if (isChild) {
                        System.out.println("Library conflict resolved - Child priority: " + libraryKey);
                        System.out.println("  Replacing: " + existingPath);
                        System.out.println("  With: " + pathStr);

                        addedPaths.remove(existingPath);
                        addedPaths.add(pathStr);
                        libraryKeys.put(libraryKey, pathStr);
                    }
                } else {
                    libraryKeys.put(libraryKey, pathStr);
                    if (addedPaths.add(pathStr)) {
                        System.out.println("Library: " + description + " -> " + pathStr);
                    }
                }
            } else {
                if (addedPaths.add(pathStr)) {
                    System.out.println("Library: " + description + " -> " + pathStr);
                }
            }
        }

        /**
         * Extrae todos los natives resueltos al directorio de natives
         * Debe llamarse DESPUÉS de procesar todas las versiones
         */
        public void extractNatives() throws IOException {
            System.out.println("\n=== Extracting Natives ===");
            Files.createDirectories(nativesDir);

            // Limpiar natives anteriores para evitar conflictos
            // (opcional, descomentar si quieres limpiar siempre)
            // cleanNativesDirectory();

            for (Map.Entry<String, NativeInfo> entry : nativeLibraries.entrySet()) {
                NativeInfo info = entry.getValue();
                extractNativeJar(info.jarPath, info.description);
            }

            System.out.println("Natives extracted to: " + nativesDir);
        }

        private void extractNativeJar(Path jarPath, String description) throws IOException {
            System.out.println("Extracting: " + description);

            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();

                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // Solo extraer archivos nativos (.so, .dll, .dylib, .jnilib)
                    if (isNativeFile(name) && !entry.isDirectory()) {
                        // Extraer solo el nombre del archivo, sin subdirectorios
                        String fileName = Paths.get(name).getFileName().toString();
                        Path targetPath = nativesDir.resolve(fileName);

                        // Sobrescribir si existe (importante para resolver conflictos)
                        try (InputStream is = zip.getInputStream(entry)) {
                            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("  -> " + fileName);
                        }
                    }
                }
            }
        }

        private boolean isNativeFile(String name) {
            String lower = name.toLowerCase();
            return (lower.endsWith(".so") ||
                    lower.endsWith(".dll") ||
                    lower.endsWith(".dylib") ||
                    lower.endsWith(".jnilib")) &&
                    !lower.contains("meta-inf");
        }

        private void cleanNativesDirectory() throws IOException {
            if (Files.exists(nativesDir)) {
                try (var stream = Files.list(nativesDir)) {
                    stream.filter(p -> isNativeFile(p.toString()))
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException e) {
                                    System.err.println("Failed to delete: " + p);
                                }
                            });
                }
            }
        }

        private static String buildClasspath(VersionInfo info) throws IOException {
            System.out.println("Building classpath...");

            DependencyResolver resolver = new DependencyResolver(info.getLibDir(), info.getNativesDir());

            // Procesar parent primero (isChild = false)
            if (info.hasInheritance()) {
                resolver.processVersion(info.getBaseVersionData(), false);
            }

            // Procesar child después (isChild = true) - tiene prioridad en conflictos
            resolver.processVersion(info.getVersionData(), true);

            // IMPORTANTE: Extraer natives DESPUÉS de resolver todos los conflictos
            // Esto asegura que se usen los natives correctos (del child si hay conflicto)
            resolver.extractNatives();

            String classpath = resolver.buildClasspath(info);
            System.out.println("Classpath built with " + resolver.getLibraryCount() + " libraries");

            return classpath;
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
                String name = safeGetString(lib, "name");

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

        public int getLibraryCount() { return addedPaths.size(); }
    }

    /**
     * Constructor de comandos de lanzamiento
     */
    private static class CommandBuilder {
        private final List<String> command = new ArrayList<>();
        private final VersionInfo info;
        private final Map<String, String> vars;
        private final LaunchOptions options;

        // Argumentos que se deben ignorar si no están habilitados
        private static final Set<String> DEMO_ARGS = Set.of("--demo");
        private static final Set<String> QUICKPLAY_ARGS = Set.of(
                "--quickPlaySingleplayer", "--quickPlayMultiplayer",
                "--quickPlayRealms", "--quickPlayPath"
        );

        public CommandBuilder(VersionInfo info, Map<String, String> vars, LaunchOptions options) {
            this.info = info;
            this.vars = vars;
            this.options = options != null ? options : LaunchOptions.defaults();
        }

        public CommandBuilder addJava(String javaPath) {
            command.add(getJavaBin(javaPath));
            return this;
        }

        public CommandBuilder addJvmArgs(String minRam, String maxRam, boolean cracked) {
            command.add("-Djava.library.path=" + info.getNativesDir());
            command.add("-Dminecraft.launcher.brand=CubicLauncher");
            command.add("-Dminecraft.launcher.version=1.0");

            if (cracked) {
                System.out.println("Offline mode enabled");
                command.add("-Dminecraft.api.env=custom");
                command.add("-Dminecraft.api.auth.host=https://invalid.invalid");
                command.add("-Dminecraft.api.account.host=https://invalid.invalid");
                command.add("-Dminecraft.api.session.host=https://invalid.invalid");
                command.add("-Dminecraft.api.services.host=https://invalid.invalid");
            }

            command.add("-Xms" + minRam);
            command.add("-Xmx" + maxRam);

            processJvmArguments();
            return this;
        }

        public CommandBuilder addClasspath(String classpath) {
            command.add("-cp");
            command.add(classpath);
            return this;
        }

        public CommandBuilder addMainClass(String mainClass) {
            command.add(mainClass);
            return this;
        }

        public CommandBuilder addGameArgs(int width, int height) {
            processGameArguments();
            addDefaultGameArgs(width, height);
            addOptionalArgs();
            cleanupUnresolvedVars();
            return this;
        }

        private void processJvmArguments() {
            JsonArray childArgs = getArgsFromVersion(info.getVersionData(), "jvm");
            if (childArgs != null) processJvmArray(childArgs);

            if (info.hasInheritance()) {
                JsonArray parentArgs = getArgsFromVersion(info.getBaseVersionData(), "jvm");
                if (parentArgs != null) processJvmArray(parentArgs);
            }
        }

        private void processJvmArray(JsonArray args) {
            for (JsonElement element : args) {
                if (element.isJsonPrimitive()) {
                    addJvmArg(element.getAsString());
                } else if (element.isJsonObject()) {
                    processConditionalArg(element.getAsJsonObject());
                }
            }
        }

        private void addJvmArg(String arg) {
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.contains("${classpath}")) {
                return;
            }

            String replaced = replaceVars(arg);

            if (replaced.startsWith("--") || replaced.startsWith("-D") || replaced.startsWith("-X")) {
                command.add(replaced);
            } else if (!command.contains(replaced)) {
                command.add(replaced);
            }
        }

        private void processConditionalArg(JsonObject argObj) {
            if (argObj.has("rules") && !evaluateRules(argObj.getAsJsonArray("rules"))) {
                return;
            }

            if (!argObj.has("value")) return;

            JsonElement value = argObj.get("value");
            if (value.isJsonPrimitive()) {
                addJvmArg(value.getAsString());
            } else if (value.isJsonArray()) {
                processValueArray(value.getAsJsonArray());
            }
        }

        private void processValueArray(JsonArray values) {
            if (values.isEmpty()) return;

            String first = values.get(0).getAsString();

            if (first.startsWith("--") && values.size() > 2) {
                String flag = replaceVars(first);
                for (int i = 1; i < values.size(); i++) {
                    String val = replaceVars(values.get(i).getAsString());
                    if (!hasFlagValue(flag, val)) {
                        command.add(flag);
                        command.add(val);
                    }
                }
            } else {
                List<String> toAdd = new ArrayList<>();
                boolean skip = false;

                for (JsonElement elem : values) {
                    String arg = elem.getAsString();
                    if (arg.contains("${classpath}") || arg.equals("-cp")) {
                        skip = true;
                        break;
                    }
                    toAdd.add(replaceVars(arg));
                }

                if (!skip && !toAdd.isEmpty() && !command.contains(toAdd.getFirst())) {
                    command.addAll(toAdd);
                }
            }
        }

        private boolean hasFlagValue(String flag, String value) {
            for (int i = 0; i < command.size() - 1; i++) {
                if (command.get(i).equals(flag) && command.get(i + 1).equals(value)) {
                    return true;
                }
            }
            return false;
        }

        private void processGameArguments() {
            JsonArray childArgs = getArgsFromVersion(info.getVersionData(), "game");
            if (childArgs != null) {
                addGameArgsArray(childArgs);
            } else if (info.hasInheritance()) {
                JsonArray parentArgs = getArgsFromVersion(info.getBaseVersionData(), "game");
                if (parentArgs != null) {
                    addGameArgsArray(parentArgs);
                }
            } else {
                addLegacyArgs();
            }
        }

        private void addGameArgsArray(JsonArray args) {
            for (int i = 0; i < args.size(); i++) {
                JsonElement element = args.get(i);

                if (element.isJsonPrimitive()) {
                    String arg = element.getAsString();

                    // Filtrar argumentos de demo si no está habilitado
                    if (DEMO_ARGS.contains(arg) && !options.demoMode) {
                        continue;
                    }

                    // Filtrar argumentos de quickplay si no está habilitado
                    if (QUICKPLAY_ARGS.contains(arg) && options.quickPlayMode == null) {
                        // Saltar también el siguiente argumento (el valor)
                        if (i + 1 < args.size() && args.get(i + 1).isJsonPrimitive()) {
                            i++;
                        }
                        continue;
                    }

                    command.add(replaceVars(arg));
                } else if (element.isJsonObject()) {
                    JsonObject argObj = element.getAsJsonObject();

                    // Verificar si es un argumento condicional de demo/quickplay
                    if (argObj.has("rules")) {
                        JsonArray rules = argObj.getAsJsonArray("rules");
                        if (isFeatureRule(rules, "is_demo_user") && !options.demoMode) {
                            continue;
                        }
                        if (isFeatureRule(rules, "is_quick_play_singleplayer") &&
                                !"singleplayer".equals(options.quickPlayMode)) {
                            continue;
                        }
                        if (isFeatureRule(rules, "is_quick_play_multiplayer") &&
                                !"multiplayer".equals(options.quickPlayMode)) {
                            continue;
                        }
                        if (isFeatureRule(rules, "is_quick_play_realms") &&
                                !"realms".equals(options.quickPlayMode)) {
                            continue;
                        }
                        if (isFeatureRule(rules, "has_quick_plays_support") &&
                                options.quickPlayMode == null) {
                            continue;
                        }

                        if (!evaluateRulesWithOptions(rules)) {
                            continue;
                        }
                    }

                    if (argObj.has("value")) {
                        addGameValue(argObj.get("value"));
                    }
                }
            }
        }

        private boolean isFeatureRule(JsonArray rules, String featureName) {
            for (JsonElement ruleElem : rules) {
                JsonObject rule = ruleElem.getAsJsonObject();
                if (rule.has("features")) {
                    JsonObject features = rule.getAsJsonObject("features");
                    if (features.has(featureName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean evaluateRulesWithOptions(JsonArray rules) {
            boolean allow = false;

            for (JsonElement element : rules) {
                JsonObject rule = element.getAsJsonObject();
                String action = rule.get("action").getAsString();

                // Evaluar features
                if (rule.has("features")) {
                    JsonObject features = rule.getAsJsonObject("features");
                    boolean featureMatch = true;

                    if (features.has("is_demo_user")) {
                        featureMatch = features.get("is_demo_user").getAsBoolean() == options.demoMode;
                    }
                    if (features.has("has_custom_resolution")) {
                        featureMatch = true; // Siempre soportamos resolución custom
                    }
                    if (features.has("is_quick_play_singleplayer")) {
                        featureMatch = "singleplayer".equals(options.quickPlayMode);
                    }
                    if (features.has("is_quick_play_multiplayer")) {
                        featureMatch = "multiplayer".equals(options.quickPlayMode);
                    }
                    if (features.has("is_quick_play_realms")) {
                        featureMatch = "realms".equals(options.quickPlayMode);
                    }
                    if (features.has("has_quick_plays_support")) {
                        featureMatch = options.quickPlayMode != null;
                    }

                    if (featureMatch) {
                        allow = action.equals("allow");
                    }
                } else if (rule.has("os")) {
                    // Evaluar OS rules normalmente
                    if (evaluateOsRule(rule.getAsJsonObject("os"))) {
                        allow = action.equals("allow");
                    }
                } else {
                    allow = action.equals("allow");
                }
            }
            return allow;
        }

        private boolean evaluateOsRule(JsonObject os) {
            String currentOs = System.getProperty("os.name").toLowerCase();
            String currentArch = System.getProperty("os.arch").toLowerCase();

            String name = safeGetString(os, "name");
            String arch = safeGetString(os, "arch");

            boolean osMatch = name == null ||
                    (name.equals("windows") && currentOs.contains("win")) ||
                    (name.equals("linux") && currentOs.contains("linux")) ||
                    (name.equals("osx") && (currentOs.contains("mac") || currentOs.contains("darwin")));

            boolean archMatch = arch == null || arch.isEmpty() ||
                    (arch.equals("x86") && currentArch.contains("86")) ||
                    (arch.equals("x64") && currentArch.contains("64"));

            return osMatch && archMatch;
        }

        private void addGameValue(JsonElement value) {
            if (value.isJsonPrimitive()) {
                String arg = value.getAsString();
                if (shouldFilterArg(arg)) {
                    command.add(replaceVars(arg));
                }
            } else if (value.isJsonArray()) {
                for (JsonElement elem : value.getAsJsonArray()) {
                    String arg = elem.getAsString();
                    if (shouldFilterArg(arg)) {
                        command.add(replaceVars(arg));
                    }
                }
            }
        }

        private boolean shouldFilterArg(String arg) {
            if (DEMO_ARGS.contains(arg) && !options.demoMode) return false;
            return !QUICKPLAY_ARGS.contains(arg) || options.quickPlayMode != null;
        }

        private void addLegacyArgs() {
            String legacyArgs = null;
            if (info.getVersionData().has("minecraftArguments")) {
                legacyArgs = info.getVersionData().get("minecraftArguments").getAsString();
            } else if (info.hasInheritance() && info.getBaseVersionData().has("minecraftArguments")) {
                legacyArgs = info.getBaseVersionData().get("minecraftArguments").getAsString();
            }

            if (legacyArgs != null) {
                for (String arg : legacyArgs.split(" ")) {
                    command.add(replaceVars(arg));
                }
            }
        }

        private void addDefaultGameArgs(int width, int height) {
            Map<String, String> defaults = new LinkedHashMap<>();
            defaults.put("--width", String.valueOf(width));
            defaults.put("--height", String.valueOf(height));
            defaults.put("--assetIndex", info.getAssetsIndexName());
            defaults.put("--assetsDir", info.getAssetsDir().toString());
            defaults.put("--username", vars.get("auth_player_name"));
            defaults.put("--uuid", vars.get("auth_uuid"));
            defaults.put("--accessToken", vars.get("auth_access_token"));
            defaults.put("--version", info.hasInheritance() ?
                    info.getBaseVersionId() : info.getVersionId());
            defaults.put("--gameDir", vars.get("game_directory"));

            defaults.forEach((key, value) -> {
                if (!command.contains(key) && value != null && !value.isEmpty()) {
                    command.add(key);
                    command.add(value);
                }
            });
        }

        private void addOptionalArgs() {
            // Agregar demo si está habilitado
            if (options.demoMode && !command.contains("--demo")) {
                command.add("--demo");
            }

            // Agregar quickplay si está habilitado
            if (options.quickPlayMode != null && options.quickPlayValue != null) {
                String quickPlayArg = switch (options.quickPlayMode) {
                    case "singleplayer" -> "--quickPlaySingleplayer";
                    case "multiplayer" -> "--quickPlayMultiplayer";
                    case "realms" -> "--quickPlayRealms";
                    default -> null;
                };

                if (quickPlayArg != null && !command.contains(quickPlayArg)) {
                    command.add(quickPlayArg);
                    command.add(options.quickPlayValue);
                }
            }
        }

        private void cleanupUnresolvedVars() {
            // Eliminar argumentos con variables sin resolver
            List<Integer> toRemove = new ArrayList<>();
            for (int i = 0; i < command.size(); i++) {
                String arg = command.get(i);
                if (arg.contains("${")) {
                    toRemove.add(i);
                    // Si es un flag (--algo), también remover el siguiente valor
                    if (i > 0 && command.get(i-1).startsWith("--") && !command.get(i-1).contains("${")) {
                        toRemove.add(i-1);
                    }
                }
            }
            // Remover en orden inverso para no afectar índices
            toRemove.sort(Collections.reverseOrder());
            for (int idx : toRemove) {
                if (idx < command.size()) {
                    System.out.println("Removing unresolved arg: " + command.get(idx));
                    command.remove(idx);
                }
            }
        }

        private String replaceVars(String str) {
            String result = str;
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                result = result.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            return result
                    .replace("${launcher_name}", "CubicLauncher")
                    .replace("${launcher_version}", "1.0")
                    .replace("${classpath_separator}", File.pathSeparator);
        }

        public List<String> build() {
            return command;
        }
    }

    // ==================== MÉTODOS PRINCIPALES ====================

    /**
     * Lanzamiento simple sin opciones adicionales (Quick Play y Demo deshabilitados)
     */
    public static void launch(String versionJsonPath, String gameDir, Path instanceDir,
                              String username, String javaPath, String minRam, String maxRam,
                              int width, int height, boolean cracked)
            throws IOException, InterruptedException {
        launch(versionJsonPath, gameDir, instanceDir, username, javaPath,
                minRam, maxRam, width, height, cracked, LaunchOptions.defaults());
    }

    /**
     * Lanzamiento con opciones personalizadas
     */
    public static void launch(String versionJsonPath, String gameDir, Path instanceDir,
                              String username, String javaPath, String minRam, String maxRam,
                              int width, int height, boolean cracked, LaunchOptions options)
            throws IOException, InterruptedException {

        System.out.println("=== CubicLauncher - Universal Minecraft Launcher ===");

        VersionInfo info = new VersionInfo(versionJsonPath, gameDir);
        System.out.println("Version: " + info.getVersionId());
        System.out.println("Demo mode: " + options.demoMode);
        System.out.println("Quick Play: " + (options.quickPlayMode != null ?
                options.quickPlayMode + " -> " + options.quickPlayValue : "disabled"));

        prepareDirectories(info);

        String mainClass = info.getProperty("mainClass", null);
        if (mainClass == null) {
            throw new IllegalStateException("Main class not found");
        }

        String classpath = buildClasspath(info);
        if (classpath.isEmpty()) {
            throw new IllegalStateException("Classpath is empty");
        }

        Map<String, String> vars = buildVariables(info, username, instanceDir.toString());

        List<String> command = new CommandBuilder(info, vars, options)
                .addJava(javaPath)
                .addJvmArgs(minRam, maxRam, cracked)
                .addClasspath(classpath)
                .addMainClass(mainClass)
                .addGameArgs(width, height)
                .build();

        executeGame(command, info.getGameDir().toString(), javaPath);
    }

    // ==================== MÉTODOS AUXILIARES ====================

    private static void prepareDirectories(VersionInfo info) throws IOException {
        Files.createDirectories(info.getAssetsVirtualDir());
        Files.createDirectories(info.getGameDir().resolve("mods"));
        Files.createDirectories(info.getGameDir().resolve("config"));
    }

    private static Map<String, String> buildVariables(VersionInfo info, String username, String instanceDir) {
        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", info.getVersionId());
        vars.put("game_directory", instanceDir);
        vars.put("assets_root", info.getAssetsDir().toString());
        vars.put("assets_index_name", info.getAssetsIndexName());
        vars.put("auth_uuid", UUID.randomUUID().toString().replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("user_type", "mojang");
        vars.put("user_properties", "{}");
        vars.put("version_type", info.getProperty("type", "release"));
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", info.getLibDir().toString());
        vars.put("natives_directory", info.getNativesDir().toString());
        return vars;
    }

    private static String buildClasspath(VersionInfo info) throws IOException {
        System.out.println("Building classpath...");

        DependencyResolver resolver = new DependencyResolver(info.getLibDir(), info.getNativesDir());

        // Procesar parent primero (isChild = false)
        if (info.hasInheritance()) {
            resolver.processVersion(info.getBaseVersionData(), false);
        }

        // Procesar child después (isChild = true) - tiene prioridad en conflictos
        resolver.processVersion(info.getVersionData(), true);

        String classpath = resolver.buildClasspath(info);
        System.out.println("Classpath built with " + resolver.getLibraryCount() + " libraries");

        return classpath;
    }

    private static void executeGame(List<String> command, String gameDir, String javaPath)
            throws IOException, InterruptedException {
        System.out.println("\n=== Final Command ===");
        System.out.println(String.join(" ", command));
        System.out.println("\n=== Starting Game ===");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(gameDir));
        builder.inheritIO();

        Map<String, String> env = builder.environment();
        env.put("JAVA_HOME", new File(javaPath).getParent());

        Process process = builder.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            System.out.println("Game finished successfully");
        } else {
            System.err.println("ERROR: Exit code: " + exitCode);
        }
    }

    private static String detectLoader(String versionId) {
        String lower = versionId.toLowerCase();
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("forge")) return "forge";
        if (lower.contains("fabric")) return "fabric";
        return "vanilla";
    }

    private static JsonArray getArgsFromVersion(JsonObject versionData, String type) {
        if (versionData.has("arguments")) {
            JsonObject args = versionData.getAsJsonObject("arguments");
            if (args.has(type)) {
                return args.getAsJsonArray(type);
            }
        }
        return null;
    }

    private static boolean evaluateRules(JsonArray rules) {
        boolean allow = false;
        String currentOs = System.getProperty("os.name").toLowerCase();
        String currentArch = System.getProperty("os.arch").toLowerCase();

        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            String action = rule.get("action").getAsString();

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                String name = safeGetString(os, "name");
                String arch = safeGetString(os, "arch");

                boolean osMatch = (name != null &&
                        ((name.equals("windows") && currentOs.contains("win")) ||
                                (name.equals("linux") && currentOs.contains("linux")) ||
                                (name.equals("osx") && (currentOs.contains("mac") || currentOs.contains("darwin")))));

                boolean archMatch = arch == null || arch.isEmpty() ||
                        (arch.equals("x86") && currentArch.contains("86")) ||
                        (arch.equals("x64") && currentArch.contains("64"));

                if (osMatch && archMatch) {
                    allow = action.equals("allow");
                }
            } else {
                allow = action.equals("allow");
            }
        }
        return allow;
    }

    private static JsonObject loadJson(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("File not found: " + filePath);
            return null;
        }
        String content = Files.readString(path);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private static String getJavaBin(String javaPath) {
        Path path = Paths.get(javaPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Invalid Java path: " + javaPath);
        }
        return javaPath;
    }

    private static String safeGetString(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : null;
    }

    private static JsonObject safeGetObject(JsonObject obj, String key) {
        return obj.has(key) ? obj.getAsJsonObject(key) : null;
    }

    private static JsonArray safeGetArray(JsonObject obj) {
        return obj.has("rules") ? obj.getAsJsonArray("rules") : null;
    }
}