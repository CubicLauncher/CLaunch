package com.cubiclauncher.claunch;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.cubiclauncher.claunch.Launcher.launch;

public class Main {
    public enum Loader_Paths {
        FABRIC("/home/santiagolxx/.config/CubicLauncher/shared/versions/1.20.4/1.20.4.json");
        private final String path;

        Loader_Paths(String path) {
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }

    static void main(String[] args) throws Exception {
        Path gamePath = Paths.get("/home/santiagolxx/.config/CubicLauncher/");
        launch(Loader_Paths.FABRIC.getPath(),
                gamePath.toString(), Paths.get("instances", "fabric"),"Santiagolxx", "/usr/lib/jvm/java-21-graalvm/bin/java", "512M", "2G", 900, 600, true);
    }
}
