package com.cubiclauncher.claunch;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.cubiclauncher.claunch.Launcher.launch;

public class Main {
    public enum Loader_Paths {
        FABRIC("/tmp/minecraft/shared/versions/fabric-loader-0.17.3-1.21.8/fabric-loader-0.17.3-1.21.8.json"),
        FORGE("/tmp/minecraft/shared/versions/1.21.8-forge-58.1.0/1.21.8-forge-58.1.0.json"),
        NEOFORGE("/tmp/minecraft/shared/versions/neoforge-21.8.51/neoforge-21.8.51.json");
        private final String path;

        Loader_Paths(String path) {
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }

    static void main(String[] args) throws Exception {
        Path gamePath = Paths.get("/tmp/minecraft/");
        launch(Loader_Paths.FABRIC.getPath(),
                gamePath.toString(), Paths.get("instances", "fabric"),"Santiagolxx", "/usr/lib/jvm/java-21-graalvm/bin/java", "512M", "2G", 900, 600, true);
    }
}
