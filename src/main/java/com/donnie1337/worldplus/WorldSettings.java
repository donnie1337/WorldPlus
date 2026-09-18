package com.donnie1337.worldplus;

import org.bukkit.World;

public record WorldSettings(
        String id,
        String name,
        World.Environment environment,
        long seed,
        double size,
        boolean structures,
        boolean removeStrongholds
) {
}
