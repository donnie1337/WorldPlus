package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

public final class WorldTimeManager {
    private static final long DAY_GAME_TICKS = 12_000L;
    private static final long DUSK_GAME_TICKS = 1_000L;
    private static final long NIGHT_GAME_TICKS = 10_000L;
    private static final long DAWN_GAME_TICKS = 1_000L;

    private static final long DAY_REAL_TICKS = 20L * 60L * 20L;
    private static final long NIGHT_REAL_TICKS = 14L * 60L * 20L;
    private static final long TRANSITION_REAL_TICKS = 3L * 60L * 20L;

    private final WorldPlus plugin;
    private final Map<String, Double> accumulators = new HashMap<>();
    private BukkitTask task;

    public WorldTimeManager(WorldPlus plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            update("overworld");
            update("mineracao");
        }, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (String id : new String[]{"overworld", "mineracao"}) {
            WorldPlus worldPlus = plugin;
            World world = worldPlus.getWorldById(id);
            if (world != null) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
            }
        }

        accumulators.clear();
    }

    public void apply(World world, String id) {
        if (world == null || !isManaged(id)) return;
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    }

    private void update(String id) {
        if (!isManaged(id)) return;

        World world = plugin.getWorldById(id);
        if (world == null) return;

        long time = Math.floorMod(world.getTime(), 24_000L);
        long phaseGameTicks;
        long phaseRealTicks;

        if (time < DAY_GAME_TICKS) {
            phaseGameTicks = DAY_GAME_TICKS;
            phaseRealTicks = DAY_REAL_TICKS;
        } else if (time < DAY_GAME_TICKS + DUSK_GAME_TICKS) {
            phaseGameTicks = DUSK_GAME_TICKS;
            phaseRealTicks = TRANSITION_REAL_TICKS;
        } else if (time < DAY_GAME_TICKS + DUSK_GAME_TICKS + NIGHT_GAME_TICKS) {
            phaseGameTicks = NIGHT_GAME_TICKS;
            phaseRealTicks = NIGHT_REAL_TICKS;
        } else {
            phaseGameTicks = DAWN_GAME_TICKS;
            phaseRealTicks = TRANSITION_REAL_TICKS;
        }

        double ticksPerServerTick = (double) phaseGameTicks / (double) phaseRealTicks;
        double accumulator = accumulators.getOrDefault(id, 0.0D) + ticksPerServerTick;
        long advance = (long) accumulator;

        if (advance > 0L) {
            world.setTime(world.getTime() + advance);
            accumulator -= advance;
        }

        accumulators.put(id, accumulator);
    }

    private boolean isManaged(String id) {
        return "overworld".equalsIgnoreCase(id) || "mineracao".equalsIgnoreCase(id);
    }
}
