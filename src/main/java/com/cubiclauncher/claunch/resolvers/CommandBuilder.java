package com.cubiclauncher.claunch.resolvers;

import com.cubiclauncher.claunch.models.VersionInfo;
import com.cubiclauncher.claunch.models.LaunchOptions;
import com.cubiclauncher.claunch.utils.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Constructor de comandos de lanzamiento
 */
public class CommandBuilder {
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
        JsonArray childArgs = JsonUtils.getArgsFromVersion(info.getVersionData(), "jvm");
        if (childArgs != null) processJvmArray(childArgs);

        if (info.hasInheritance()) {
            JsonArray parentArgs = JsonUtils.getArgsFromVersion(info.getBaseVersionData(), "jvm");
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
        if (argObj.has("rules") && !JsonUtils.evaluateRules(argObj.getAsJsonArray("rules"))) {
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
        JsonArray childArgs = JsonUtils.getArgsFromVersion(info.getVersionData(), "game");
        if (childArgs != null) {
            addGameArgsArray(childArgs);
        } else if (info.hasInheritance()) {
            JsonArray parentArgs = JsonUtils.getArgsFromVersion(info.getBaseVersionData(), "game");
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

        String name = JsonUtils.safeGetString(os, "name");
        String arch = JsonUtils.safeGetString(os, "arch");

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

    private String getJavaBin(String javaPath) {
        Path path = Paths.get(javaPath);
        if (!path.toFile().exists() || !path.toFile().isFile()) {
            throw new IllegalArgumentException("Invalid Java path: " + javaPath);
        }
        return javaPath;
    }

    public List<String> build() {
        return command;
    }
}