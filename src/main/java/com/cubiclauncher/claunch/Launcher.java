package com.cubiclauncher.claunch;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Universal Minecraft Launcher
 * Soporta Vanilla, Forge, NeoForge y Fabric con sistema de herencia de versiones
 */
public class Launcher {

    // ==================== CLASES INTERNAS ====================

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

            // Cargar versión base si existe herencia
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

            // Inicializar rutas compartidas
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
        private final JsonObject natives;

        public Library(JsonObject libObject) {
            this.name = safeGetString(libObject, "name");
            this.downloads = safeGetObject(libObject, "downloads");
            this.rules = safeGetArray(libObject, "rules");
            this.natives = safeGetObject(libObject, "natives");
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

            // Construir path desde name
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
     */
    private static class DependencyResolver {
        private final Map<String, Library> libraries = new LinkedHashMap<>();
        private final Map<String, List<String>> paths = new LinkedHashMap<>();
        private final Path libDir;
        private final Path nativesDir;

        public DependencyResolver(Path libDir, Path nativesDir) {
            this.libDir = libDir;
            this.nativesDir = nativesDir;
        }

        public void processVersion(JsonObject versionData, boolean isChild) {
            if (!versionData.has("libraries")) return;

            JsonArray libsArray = versionData.getAsJsonArray("libraries");
            for (JsonElement element : libsArray) {
                Library lib = new Library(element.getAsJsonObject());

                if (!lib.shouldInclude()) continue;

                String key = lib.getKey();
                if (key == null) continue;

                // Child version siempre sobrescribe parent
                if (isChild || !libraries.containsKey(key)) {
                    libraries.put(key, lib);
                    resolveLibraryPath(lib, key);
                }
            }
        }

        private void resolveLibraryPath(Library lib, String key) {
            Path path = lib.resolvePath(libDir);
            if (path != null && Files.exists(path)) {
                paths.computeIfAbsent(key, k -> new ArrayList<>()).add(path.toString());
                System.out.println("Library: " + key + " -> " + path);
            } else {
                System.err.println("Library not found: " + key);
            }
        }

        public String buildClasspath(VersionInfo info) {
            List<String> classpath = paths.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
            addVersionJars(classpath, info);
            return String.join(File.pathSeparator, classpath);
        }

        private void addVersionJars(List<String> classpath, VersionInfo info) {
            String loaderType = detectLoader(info.getVersionId());
            Path clientJar = info.getClientJar();
            Path versionJar = info.getVersionJar();

            System.out.println("Loader: " + loaderType);
            System.out.println("Client JAR: " + clientJar + " (exists: " + Files.exists(clientJar) + ")");
            System.out.println("Version JAR: " + versionJar + " (exists: " + Files.exists(versionJar) + ")");

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
                    System.out.println("Added JAR: " + jar);
                }
            }
        }

        private void addForgeUniversalJar(List<String> classpath, VersionInfo info) {
            Path forgeJar = findForgeUniversalJar(info);
            if (forgeJar != null) {
                addIfExists(classpath, forgeJar);
            }
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

            // Probar con classifier "universal"
            Path universal = libDir.resolve(groupPath)
                    .resolve(artifact)
                    .resolve(version)
                    .resolve(artifact + "-" + version + "-universal.jar");

            if (Files.exists(universal)) return universal;

            // Probar sin classifier
            return libDir.resolve(groupPath)
                    .resolve(artifact)
                    .resolve(version)
                    .resolve(artifact + "-" + version + ".jar");
        }

        public int getLibraryCount() {
            return libraries.size();
        }
    }

    /**
     * Constructor de comandos de lanzamiento
     */
    private static class CommandBuilder {
        private final List<String> command = new ArrayList<>();
        private final VersionInfo info;
        private final Map<String, String> vars;

        public CommandBuilder(VersionInfo info, Map<String, String> vars) {
            this.info = info;
            this.vars = vars;
        }

        public CommandBuilder addJava(String javaPath) {
            command.add(getJavaBin(javaPath));
            return this;
        }

        public CommandBuilder addJvmArgs(String minRam, String maxRam, boolean cracked) {
            // System properties
            command.add("-Djava.library.path=" + info.getNativesDir());
            command.add("-Dminecraft.launcher.brand=CubicLauncher");
            command.add("-Dminecraft.launcher.version=1.0");

            // Modo offline
            if (cracked) {
                System.out.println("Offline mode enabled");
                command.add("-Dminecraft.api.env=custom");
                command.add("-Dminecraft.api.auth.host=https://invalid.invalid");
                command.add("-Dminecraft.api.account.host=https://invalid.invalid");
                command.add("-Dminecraft.api.session.host=https://invalid.invalid");
                command.add("-Dminecraft.api.services.host=https://invalid.invalid");
            }

            // Memoria
            command.add("-Xms" + minRam);
            command.add("-Xmx" + maxRam);

            // Argumentos JVM del JSON
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
            return this;
        }

        private void processJvmArguments() {
            // Procesar child primero
            JsonArray childArgs = getArgsFromVersion(info.getVersionData(), "jvm");
            if (childArgs != null) {
                System.out.println("Processing child JVM args");
                processJvmArray(childArgs);
            }

            // Luego parent
            if (info.hasInheritance()) {
                JsonArray parentArgs = getArgsFromVersion(info.getBaseVersionData(), "jvm");
                if (parentArgs != null) {
                    System.out.println("Processing parent JVM args");
                    processJvmArray(parentArgs);
                }
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
            // Ignorar classpath (se maneja por separado)
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.contains("${classpath}")) {
                return;
            }

            String replaced = replaceVars(arg);

            // Flags pueden repetirse con valores diferentes
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
            if (values.size() == 0) return;

            String first = values.get(0).getAsString();

            // Flags con múltiples valores (--add-opens, etc)
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
                // Valores normales
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

                if (!skip && !toAdd.isEmpty() && !command.contains(toAdd.get(0))) {
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
            // Formato moderno (arguments.game)
            JsonArray childArgs = getArgsFromVersion(info.getVersionData(), "game");
            if (childArgs != null) {
                addGameArgsArray(childArgs);
            } else if (info.hasInheritance()) {
                JsonArray parentArgs = getArgsFromVersion(info.getBaseVersionData(), "game");
                if (parentArgs != null) {
                    addGameArgsArray(parentArgs);
                }
            }
            // Formato legacy (minecraftArguments)
            else {
                addLegacyArgs();
            }
        }

        private void addGameArgsArray(JsonArray args) {
            for (JsonElement element : args) {
                if (element.isJsonPrimitive()) {
                    command.add(replaceVars(element.getAsString()));
                } else if (element.isJsonObject()) {
                    JsonObject argObj = element.getAsJsonObject();
                    if (argObj.has("rules") && !evaluateRules(argObj.getAsJsonArray("rules"))) {
                        continue;
                    }
                    if (argObj.has("value")) {
                        addGameValue(argObj.get("value"));
                    }
                }
            }
        }

        private void addGameValue(JsonElement value) {
            if (value.isJsonPrimitive()) {
                command.add(replaceVars(value.getAsString()));
            } else if (value.isJsonArray()) {
                for (JsonElement elem : value.getAsJsonArray()) {
                    command.add(replaceVars(elem.getAsString()));
                }
            }
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
            defaults.put("--gameDir", info.getGameDir().toString());

            defaults.forEach((key, value) -> {
                if (!command.contains(key)) {
                    command.add(key);
                    command.add(value);
                }
            });
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

    // ==================== MÉTODO PRINCIPAL ====================

    public static void launch(String versionJsonPath, String gameDir, Path instanceDir,
                              String username, String javaPath, String minRam, String maxRam,
                              int width, int height, boolean cracked)
            throws IOException, InterruptedException {

        System.out.println("=== CubicLauncher - Universal Minecraft Launcher ===");

        // Cargar versión
        VersionInfo info = new VersionInfo(versionJsonPath, gameDir);
        System.out.println("Version: " + info.getVersionId());
        System.out.println("Natives: " + info.getNativesDir());

        // Preparar directorios
        prepareDirectories(info);

        // Obtener clase principal
        String mainClass = info.getProperty("mainClass", null);
        if (mainClass == null) {
            throw new IllegalStateException("Main class not found");
        }
        System.out.println("Main class: " + mainClass);

        // Construir classpath
        String classpath = buildClasspath(info);
        if (classpath.isEmpty()) {
            throw new IllegalStateException("Classpath is empty");
        }

        // Preparar variables
        Map<String, String> vars = buildVariables(info, username, instanceDir.toString());

        // Construir comando
        List<String> command = new CommandBuilder(info, vars)
                .addJava(javaPath)
                .addJvmArgs(minRam, maxRam, cracked)
                .addClasspath(classpath)
                .addMainClass(mainClass)
                .addGameArgs(width, height)
                .build();

        // Ejecutar
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

    private static String buildClasspath(VersionInfo info) {
        System.out.println("Building classpath...");

        DependencyResolver resolver = new DependencyResolver(info.getLibDir(), info.getNativesDir());

        // Procesar parent primero, luego child
        if (info.hasInheritance()) {
            resolver.processVersion(info.getBaseVersionData(), false);
        }
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

    // Helpers para acceso seguro a JSON
    private static String safeGetString(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : null;
    }

    private static JsonObject safeGetObject(JsonObject obj, String key) {
        return obj.has(key) ? obj.getAsJsonObject(key) : null;
    }

    private static JsonArray safeGetArray(JsonObject obj, String key) {
        return obj.has(key) ? obj.getAsJsonArray(key) : null;
    }
}