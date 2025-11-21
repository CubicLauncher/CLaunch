/*
 * Copyright (C) 2025 Santiagolxx, Notstaff and CubicLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.cubiclauncher.claunch.models;

public class LaunchOptions {
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