package com.donnie1337.worldplus;

import org.bukkit.Difficulty;
import org.bukkit.World;

public record WorldSettings(
        String id,
        String name,
        World.Environment environment,
        long seed,
        double size,
        boolean structures,
        boolean removeStrongholds,
        boolean pvp,
        boolean keepInventory,
        Difficulty difficulty,
        boolean customSpawn,
        int spawnX,
        int spawnY,
        int spawnZ,
        float spawnYaw,
        float spawnPitch
) {
}
