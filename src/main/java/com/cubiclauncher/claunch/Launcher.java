package com.cubiclauncher.claunch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class Launcher {

    /**
     * Lanza Minecraft usando un archivo de versión JSON específico
     *
     * @param versionJsonPath Ruta completa al archivo JSON de la versión (ej: "gameDir/shared/versions/1.20.1/1.20.1.json")
     * @param gameDir Directorio base del juego
     * @param username Nombre de usuario
     * @param javaPath Ruta al ejecutable de Java
     * @param minRam Memoria RAM mínima (ej: "512M")
     * @param maxRam Memoria RAM máxima (ej: "2G")
     * @param width Ancho de ventana
     * @param height Alto de ventana
     * @param cracked Modo offline/cracked
     */
    public static void launch(String versionJsonPath, String gameDir, String username, String javaPath,
                              String minRam, String maxRam, int width, int height, boolean cracked)
            throws IOException, InterruptedException {

        System.out.println("=== Iniciando Minecraft Launcher Universal ===");
        System.out.println("Version JSON: " + versionJsonPath);

        // Normalizar directorios
        gameDir = new File(gameDir).getAbsolutePath();

        // Cargar el JSON de la versión principal
        JsonObject versionData = loadJsonFromFile(versionJsonPath);
        if (versionData == null) {
            System.err.println("ERROR: No se pudo cargar el archivo de versión: " + versionJsonPath);
            return;
        }

        String versionId = versionData.get("id").getAsString();
        System.out.println("Version ID: " + versionId);

        // Cargar versión base si existe herencia
        JsonObject baseVersionData = null;
        String baseVersionId = null;

        if (versionData.has("inheritsFrom")) {
            baseVersionId = versionData.get("inheritsFrom").getAsString();
            System.out.println("Hereda de: " + baseVersionId);

            String baseVersionJsonPath = Paths.get(gameDir, "shared", "versions", baseVersionId, baseVersionId + ".json").toString();
            baseVersionData = loadJsonFromFile(baseVersionJsonPath);

            if (baseVersionData == null) {
                System.err.println("ERROR: No se pudo cargar la versión base: " + baseVersionJsonPath);
                return;
            }
        }

        // Preparar directorios
        Path assetsDir = Paths.get(gameDir, "shared", "assets").toAbsolutePath();
        String assetsIndexName = getFromVersionOrBase(versionData, baseVersionData, "assets", "legacy");

        Path assetsVirtualDir = Paths.get(gameDir, "shared", "assets", "virtual", assetsIndexName);
        Files.createDirectories(assetsVirtualDir);

        Path nativesDir = Paths.get(gameDir, "shared", "natives", versionId).toAbsolutePath();
        Files.createDirectories(nativesDir);

        // Crear directorios adicionales
        Files.createDirectories(Paths.get(gameDir, "mods"));
        Files.createDirectories(Paths.get(gameDir, "config"));

        // Obtener clase principal
        String mainClass = getFromVersionOrBase(versionData, baseVersionData, "mainClass", null);
        if (mainClass == null || mainClass.isEmpty()) {
            System.err.println("ERROR: No se encontró la clase principal");
            return;
        }
        System.out.println("Clase principal: " + mainClass);

        // Construir classpath
        String classpath = buildClasspath(versionData, baseVersionData, gameDir, versionId, baseVersionId);
        if (classpath.isEmpty()) {
            System.err.println("ERROR: El classpath está vacío");
            return;
        }

        // Configurar variables
        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", versionId);
        vars.put("game_directory", gameDir);
        vars.put("assets_root", assetsDir.toString());
        vars.put("assets_index_name", assetsIndexName);
        vars.put("auth_uuid", UUID.randomUUID().toString().replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("user_type", "mojang");
        vars.put("user_properties", "{}");
        vars.put("version_type", getFromVersionOrBase(versionData, baseVersionData, "type", "release"));

        // Detectar tipo de loader automáticamente
        detectAndSetLoaderVariables(versionData, vars, gameDir, versionId);

        // Preparar comando
        List<String> command = new ArrayList<>();
        command.add(getJavaBin(javaPath));

        // Argumentos JVM básicos
        command.add("-Djava.library.path=" + nativesDir);
        command.add("-Dminecraft.launcher.brand=CubicLauncher");
        command.add("-Dminecraft.launcher.version=1.0");

        // Argumentos para modo cracked
        if (cracked) {
            System.out.println("Modo offline activado");
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
        processJvmArguments(versionData, baseVersionData, command, gameDir, versionId);

        // Classpath y clase principal
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);

        // Argumentos del juego
        processGameArguments(versionData, baseVersionData, command, vars);

        // Argumentos adicionales si no están
        if (!command.contains("--width")) {
            command.add("--width");
            command.add(String.valueOf(width));
        }
        if (!command.contains("--height")) {
            command.add("--height");
            command.add(String.valueOf(height));
        }

        // Asegurar assets para Fabric y otros loaders
        if (!command.contains("--assetIndex")) {
            command.add("--assetIndex");
            command.add(assetsIndexName);
        }
        if (!command.contains("--assetsDir")) {
            command.add("--assetsDir");
            command.add(assetsDir.toString());
        }

        // Ejecutar
        System.out.println("\n=== Iniciando juego ===");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(gameDir));
        builder.inheritIO();

        Map<String, String> env = builder.environment();
        env.put("JAVA_HOME", new File(javaPath).getParent());

        Process process = builder.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            System.out.println("Juego finalizado correctamente");
        } else {
            System.err.println("ERROR: Código de salida: " + exitCode);
        }
    }

    /**
     * Detecta automáticamente el tipo de loader basándose en el JSON
     */
    private static void detectAndSetLoaderVariables(JsonObject versionData, Map<String, String> vars,
                                                    String gameDir, String versionId) {
        String versionIdLower = versionId.toLowerCase();

        // Detectar Forge
        if (versionIdLower.contains("forge")) {
            System.out.println("Loader detectado: Forge");
            vars.put("loader_type", "forge");

            // Intentar extraer versión de forge del ID
            if (versionIdLower.matches(".*forge[.-]\\d+.*")) {
                String forgeVersion = extractLoaderVersion(versionId, "forge");
                if (forgeVersion != null) {
                    vars.put("forge_version", forgeVersion);
                }
            }

            // Argumentos específicos de Forge
            System.setProperty("forge.logging.console.level", "info");
            System.setProperty("fml.ignoreInvalidMinecraftCertificates", "true");
            System.setProperty("fml.ignorePatchDiscrepancies", "true");
        }
        // Detectar Fabric
        else if (versionIdLower.contains("fabric")) {
            System.out.println("Loader detectado: Fabric");
            vars.put("loader_type", "fabric");

            // Intentar extraer versión de fabric del ID
            if (versionIdLower.contains("fabric-loader")) {
                String fabricVersion = extractLoaderVersion(versionId, "fabric-loader");
                if (fabricVersion != null) {
                    vars.put("fabric_version", fabricVersion);
                }
            }

            // Argumentos específicos de Fabric
            System.setProperty("fabric.development", "false");
            System.setProperty("fabric.debug.disableClassPathIsolation", "false");
        }
        // Detectar otros loaders potenciales
        else if (versionIdLower.contains("quilt")) {
            System.out.println("Loader detectado: Quilt");
            vars.put("loader_type", "quilt");
        }
        else if (versionIdLower.contains("neoforge")) {
            System.out.println("Loader detectado: NeoForge");
            vars.put("loader_type", "neoforge");
        }
        else {
            System.out.println("Loader: Vanilla");
            vars.put("loader_type", "vanilla");
        }
    }

    /**
     * Extrae la versión del loader del ID de versión
     */
    private static String extractLoaderVersion(String versionId, String loaderName) {
        try {
            String[] parts = versionId.split("-");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].toLowerCase().contains(loaderName.toLowerCase()) && i + 1 < parts.length) {
                    return parts[i + 1];
                }
            }
        } catch (Exception e) {
            System.err.println("No se pudo extraer versión del loader: " + e.getMessage());
        }
        return null;
    }

    /**
     * Obtiene un valor del JSON principal o del base
     */
    private static String getFromVersionOrBase(JsonObject versionData, JsonObject baseVersionData,
                                               String key, String defaultValue) {
        if (versionData.has(key)) {
            return versionData.get(key).getAsString();
        }
        if (baseVersionData != null && baseVersionData.has(key)) {
            return baseVersionData.get(key).getAsString();
        }
        return defaultValue;
    }

    private static void processJvmArguments(JsonObject versionData, JsonObject baseVersionData,
                                            List<String> command, String gameDir, String version) {
        JsonArray jvmArgs = null;

        // Primero intentar del JSON principal
        if (versionData.has("arguments") && versionData.getAsJsonObject("arguments").has("jvm")) {
            jvmArgs = versionData.getAsJsonObject("arguments").getAsJsonArray("jvm");
        }
        // Si no, intentar del base
        else if (baseVersionData != null && baseVersionData.has("arguments") &&
                baseVersionData.getAsJsonObject("arguments").has("jvm")) {
            jvmArgs = baseVersionData.getAsJsonObject("arguments").getAsJsonArray("jvm");
        }

        if (jvmArgs != null) {
            for (JsonElement arg : jvmArgs) {
                if (arg.isJsonPrimitive()) {
                    String argStr = arg.getAsString();

                    // Omitir classpath (lo manejamos nosotros)
                    if (argStr.equals("-cp") || argStr.equals("-classpath") || argStr.contains("${classpath}")) {
                        continue;
                    }

                    // Reemplazar variables
                    argStr = argStr.replace("${natives_directory}",
                            Paths.get(gameDir, "shared", "natives", version).toString());
                    argStr = argStr.replace("${launcher_name}", "CubicLauncher");
                    argStr = argStr.replace("${launcher_version}", "1.0");

                    command.add(argStr);
                }
            }
        }
    }

    private static void processGameArguments(JsonObject versionData, JsonObject baseVersionData,
                                             List<String> command, Map<String, String> vars) {
        // Intentar formato moderno (arguments.game)
        if (versionData.has("arguments") && versionData.getAsJsonObject("arguments").has("game")) {
            JsonArray gameArgs = versionData.getAsJsonObject("arguments").getAsJsonArray("game");
            processGameArgumentsArray(gameArgs, command, vars);
        }
        // Intentar formato legacy (minecraftArguments)
        else if (versionData.has("minecraftArguments")) {
            processMinecraftArguments(versionData.get("minecraftArguments").getAsString(), command, vars);
        }
        // Intentar versión base
        else if (baseVersionData != null) {
            if (baseVersionData.has("arguments") && baseVersionData.getAsJsonObject("arguments").has("game")) {
                JsonArray baseGameArgs = baseVersionData.getAsJsonObject("arguments").getAsJsonArray("game");
                processGameArgumentsArray(baseGameArgs, command, vars);
            } else if (baseVersionData.has("minecraftArguments")) {
                processMinecraftArguments(baseVersionData.get("minecraftArguments").getAsString(), command, vars);
            }
        }
    }

    private static void processGameArgumentsArray(JsonArray gameArgs, List<String> command, Map<String, String> vars) {
        for (JsonElement arg : gameArgs) {
            if (arg.isJsonPrimitive()) {
                String argStr = arg.getAsString();
                if (argStr.startsWith("${") && argStr.endsWith("}")) {
                    String key = argStr.substring(2, argStr.length() - 1);
                    command.add(vars.getOrDefault(key, argStr));
                } else {
                    command.add(argStr);
                }
            }
        }
    }

    private static void processMinecraftArguments(String minecraftArgs, List<String> command, Map<String, String> vars) {
        for (String arg : minecraftArgs.split(" ")) {
            if (arg.startsWith("${") && arg.endsWith("}")) {
                String key = arg.substring(2, arg.length() - 1);
                command.add(vars.getOrDefault(key, arg));
            } else {
                command.add(arg);
            }
        }
    }

    private static JsonObject loadJsonFromFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("Archivo no existe: " + filePath);
            return null;
        }

        String content = Files.readString(path);
        try {
            return new com.google.gson.JsonParser().parse(content).getAsJsonObject();
        } catch (Exception e) {
            return com.google.gson.JsonParser.parseString(content).getAsJsonObject();
        }
    }

    private static String getJavaBin(String javaPath) {
        Path path = Paths.get(javaPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Ruta de Java inválida: " + javaPath);
        }
        return javaPath;
    }

    private static String buildClasspath(JsonObject versionData, JsonObject baseVersionData,
                                         String gameDir, String versionId, String baseVersionId) {
        System.out.println("Construyendo classpath...");

        Set<String> paths = new LinkedHashSet<>();
        Path libDir = Paths.get(gameDir, "shared", "libraries").toAbsolutePath();
        Path nativesDir = Paths.get(gameDir, "shared", "natives", versionId).toAbsolutePath();

        // Procesar librerías del JSON principal
        processLibrariesForClasspath(versionData, paths, libDir, nativesDir);

        // Procesar librerías de la versión base
        if (baseVersionData != null) {
            processLibrariesForClasspath(baseVersionData, paths, libDir, nativesDir);
        }

        // Agregar JAR del cliente
        String clientVersionId = (baseVersionId != null) ? baseVersionId : versionId;
        Path clientJar = Paths.get(gameDir, "shared", "versions", clientVersionId, clientVersionId + ".jar");

        if (Files.exists(clientJar)) {
            paths.add(clientJar.toString());
            System.out.println("Cliente JAR: " + clientJar);
        } else {
            System.err.println("ADVERTENCIA: Cliente JAR no encontrado: " + clientJar);
        }

        System.out.println("Total de librerías en classpath: " + paths.size());
        return String.join(File.pathSeparator, paths);
    }

    private static void processLibrariesForClasspath(JsonObject versionData, Set<String> paths,
                                                     Path libDir, Path nativesDir) {
        if (!versionData.has("libraries")) {
            return;
        }

        JsonArray libraries = versionData.getAsJsonArray("libraries");

        for (JsonElement libElement : libraries) {
            JsonObject lib = libElement.getAsJsonObject();

            // Verificar reglas de OS
            if (lib.has("rules") && !shouldIncludeLibrary(lib.getAsJsonArray("rules"))) {
                continue;
            }

            // Procesar artifact principal
            if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                if (artifact.has("path")) {
                    Path jarPath = libDir.resolve(artifact.get("path").getAsString());
                    if (Files.exists(jarPath)) {
                        paths.add(jarPath.toString());
                    }
                }
            }
            // Formato alternativo (name)
            else if (lib.has("name")) {
                Path jarPath = resolveLibraryPath(lib.get("name").getAsString(), libDir);
                if (jarPath != null && Files.exists(jarPath)) {
                    paths.add(jarPath.toString());
                }
            }

            // Procesar nativos
            processNatives(lib, libDir, nativesDir);
        }
    }

    private static Path resolveLibraryPath(String name, Path libDir) {
        String[] parts = name.split(":");
        if (parts.length < 3) return null;

        String groupId = parts[0].replace(".", "/");
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = (parts.length > 3) ? "-" + parts[3] : "";

        return libDir.resolve(Paths.get(groupId, artifactId, version,
                artifactId + "-" + version + classifier + ".jar"));
    }

    private static void processNatives(JsonObject lib, Path libDir, Path nativesDir) {
        if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("classifiers")) {
            return;
        }

        JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
        String nativeClassifier = getNativeClassifier();

        if (nativeClassifier != null && classifiers.has(nativeClassifier)) {
            JsonObject nativeArtifact = classifiers.getAsJsonObject(nativeClassifier);
            if (nativeArtifact.has("path")) {
                Path nativeJar = libDir.resolve(nativeArtifact.get("path").getAsString());
                if (Files.exists(nativeJar)) {
                    try {
                        extractNatives(nativeJar, nativesDir);
                    } catch (IOException e) {
                        System.err.println("Error al extraer nativos: " + e.getMessage());
                    }
                }
            }
        }
    }

    private static String getNativeClassifier() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) return "natives-windows";
        if (osName.contains("linux")) return "natives-linux";
        if (osName.contains("mac")) return "natives-macos";
        return null;
    }

    private static boolean shouldIncludeLibrary(JsonArray rules) {
        boolean allow = false;
        String currentOs = System.getProperty("os.name").toLowerCase();

        for (JsonElement ruleElement : rules) {
            JsonObject rule = ruleElement.getAsJsonObject();
            String action = rule.get("action").getAsString();

            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                String name = os.get("name").getAsString();

                boolean osMatch = (name.equals("windows") && currentOs.contains("win")) ||
                        (name.equals("linux") && currentOs.contains("linux")) ||
                        (name.equals("osx") && (currentOs.contains("mac") || currentOs.contains("darwin")));

                if (osMatch) {
                    allow = action.equals("allow");
                }
            } else {
                allow = action.equals("allow");
            }
        }

        return allow;
    }

    private static void extractNatives(Path nativeJar, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(nativeJar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();

                boolean isValidNative = name.endsWith(".dll") || name.endsWith(".so") ||
                        name.endsWith(".dylib") || name.endsWith(".jnilib");

                if (isValidNative && !name.startsWith("META-INF/")) {
                    Path targetFile = nativesDir.resolve(name);
                    Files.createDirectories(targetFile.getParent());

                    if (!Files.exists(targetFile) ||
                            entry.getLastModifiedTime().toMillis() > Files.getLastModifiedTime(targetFile).toMillis()) {
                        try (java.io.InputStream in = jar.getInputStream(entry)) {
                            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
    }
}