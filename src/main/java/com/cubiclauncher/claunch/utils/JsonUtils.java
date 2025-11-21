package com.cubiclauncher.claunch.utils;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utilidades para manejo de JSON
 */
public class JsonUtils {

    public static JsonObject loadJson(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.err.println("File not found: " + filePath);
            return null;
        }
        String content = Files.readString(path);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    public static String safeGetString(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : null;
    }

    public static JsonObject safeGetObject(JsonObject obj, String key) {
        return obj.has(key) ? obj.getAsJsonObject(key) : null;
    }

    public static JsonArray safeGetArray(JsonObject obj, String key) {
        return obj.has(key) ? obj.getAsJsonArray(key) : null;
    }

    public static JsonArray getArgsFromVersion(JsonObject versionData, String type) {
        if (versionData.has("arguments")) {
            JsonObject args = versionData.getAsJsonObject("arguments");
            if (args.has(type)) {
                return args.getAsJsonArray(type);
            }
        }
        return null;
    }

    public static boolean evaluateRules(JsonArray rules) {
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
}