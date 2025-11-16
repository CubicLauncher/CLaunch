package com.cubiclauncher.claunch;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.cubiclauncher.claunch.Launcher.launch;

public class Main {
    static void main(String[] args) throws IOException, InterruptedException {
        Path gamePath = Paths.get("C:\\Users\\santi\\Desktop\\test");
        launch(gamePath.resolve("version", "xd", "xd.json").toString(),
                gamePath.toString(), "Santiagolxx", "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe", "512M", "2G", 600, 750, true);
    }
}
