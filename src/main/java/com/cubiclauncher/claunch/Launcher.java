package com.cubiclauncher.claunch;

import com.cubiclauncher.claunch.models.VersionInfo;
import com.cubiclauncher.claunch.models.LaunchOptions;
import com.cubiclauncher.claunch.resolvers.DependencyResolver;
import com.cubiclauncher.claunch.resolvers.CommandBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Universal Minecraft Launcher
 * Soporta Vanilla, Forge, NeoForge y Fabric con sistema de herencia de versiones
 */
public class Launcher {
    private static final Logger log = LoggerFactory.getLogger(Launcher.class);
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

        log.info("=== CubicLauncher CLaunch ===");

        VersionInfo info = new VersionInfo(versionJsonPath, gameDir);
        log.info("Version: {}", info.getVersionId());
        log.info("Demo mode: {}", options.demoMode);
        log.info("Quick Play: {}", options.quickPlayMode != null ?
                options.quickPlayMode + " -> " + options.quickPlayValue : "disabled");

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

    private static String buildClasspath(VersionInfo info) {
        log.info("Building classpath...");

        DependencyResolver resolver = new DependencyResolver(info.getLibDir(), info.getNativesDir());

        // Procesar parent primero (isChild = false)
        if (info.hasInheritance()) {
            resolver.processVersion(info.getBaseVersionData(), false);
        }

        // Procesar child después (isChild = true) - tiene prioridad en conflictos
        resolver.processVersion(info.getVersionData(), true);

        String classpath = resolver.buildClasspath(info);
        log.info("Classpath built with {} libraries", resolver.getLibraryCount());

        return classpath;
    }

    private static void executeGame(List<String> command, String gameDir, String javaPath)
            throws IOException, InterruptedException {
        log.info("\n=== Final Command ===");
        log.info(String.join(" ", command));
        log.info("\n=== Starting Game ===");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(gameDir));
        builder.inheritIO();

        Map<String, String> env = builder.environment();
        env.put("JAVA_HOME", new File(javaPath).getParent());

        Process process = builder.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            log.info("Game finished successfully");
        } else {
            log.error("ERROR: Exit code: {}", exitCode);
        }
    }
}