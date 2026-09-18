package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.ArrayList;
import java.util.List;

public final class RtpPreGenerator {
    private final WorldPlus plugin;
    private final List<WorldSettings> worlds = new ArrayList<>();
    private int worldIndex;
    private int chunkX;
    private int chunkZ;
    private long totalProcessed;
    private long totalSkipped;

    public RtpPreGenerator(WorldPlus plugin) {
        this.plugin = plugin;
        this.worlds.addAll(plugin.getWorlds().values());
    }

    public void start() {
        if (worlds.isEmpty()) {
            plugin.getLogger().info("WorldPlus: pré-geração do RTP não encontrou mundos configurados.");
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            prepareWorld();
            Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }, 1L);
    }

    public boolean isReady(String worldId) {
        if (worldIndex >= worlds.size()) return true;
        WorldSettings settings = plugin.getSettings(worldId);
        if (settings == null) return true;

        for (int i = 0; i < worldIndex; i++) {
            if (worlds.get(i).id().equalsIgnoreCase(settings.id())) return true;
        }
        return false;
    }

    private void prepareWorld() {
        if (worldIndex >= worlds.size()) return;

        WorldSettings settings = worlds.get(worldIndex);
        World world = Bukkit.getWorld(settings.name());
        if (world == null) {
            world = plugin.createOrLoadWorld(settings);
        }

        if (world == null) {
            plugin.getLogger().warning("WorldPlus: não foi possível iniciar a pré-geração de RTP em " + settings.name() + ".");
            worldIndex++;
            prepareWorld();
            return;
        }

        int halfSize = Math.max(1, (int) Math.ceil(settings.size() / 2.0D));
        chunkX = (int) Math.floor((-halfSize) / 16.0D);
        chunkZ = (int) Math.floor((-halfSize) / 16.0D);

        plugin.getLogger().info("WorldPlus: iniciando pré-geração completa de " + settings.id()
                + " (" + settings.size() + "x" + settings.size() + " blocos).");
    }

    private void tick() {
        if (!plugin.isEnabled()) return;

        if (worldIndex >= worlds.size()) {
            return;
        }

        WorldSettings settings = worlds.get(worldIndex);
        World world = Bukkit.getWorld(settings.name());
        if (world == null) {
            worldIndex++;
            prepareWorld();
            return;
        }

        int maxChunk = (int) Math.ceil(getMaxRadius(settings) / 16.0D);

        while (chunkX <= maxChunk) {
            if (chunkZ > maxChunk) {
                chunkX++;
                chunkZ = -maxChunk;
                continue;
            }

            int currentX = chunkX;
            int currentZ = chunkZ;
            chunkZ++;

            double centerX = getCenterX(settings);
            double centerZ = getCenterZ(settings);
            double blockX = currentX * 16.0D + 8.0D;
            double blockZ = currentZ * 16.0D + 8.0D;
            double distanceSquared = square(blockX - centerX) + square(blockZ - centerZ);
            double minRadius = getMinRadius(settings);
            double maxRadius = getMaxRadius(settings);

            if (distanceSquared < minRadius * minRadius
                    || distanceSquared > maxRadius * maxRadius) {
                continue;
            }

            totalProcessed++;

            try {
                if (world.isChunkGenerated(currentX, currentZ)) {
                    totalSkipped++;
                    world.unloadChunkRequest(currentX, currentZ);
                } else {
                    world.getChunkAt(currentX, currentZ, true);
                    world.unloadChunkRequest(currentX, currentZ);
                }
            } catch (Throwable throwable) {
                plugin.getLogger().warning("WorldPlus: falha ao pré-gerar chunk "
                        + currentX + "," + currentZ + " em " + world.getName()
                        + ": " + throwable.getMessage());
            }

            if (totalProcessed % 1000 == 0) {
                plugin.getLogger().info("WorldPlus: pré-geração RTP " + settings.id()
                        + " — " + totalProcessed + " chunks processadas, "
                        + totalSkipped + " já existentes.");
            }
            return;
        }

        plugin.getLogger().info("WorldPlus: pré-geração RTP concluída para " + settings.id() + ".");
        worldIndex++;
        prepareWorld();
    }

}
