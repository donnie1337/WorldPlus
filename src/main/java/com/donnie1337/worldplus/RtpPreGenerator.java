package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

public final class RtpPreGenerator {
    private final WorldPlus plugin;
    private final List<WorldSettings> worlds = new ArrayList<>();
    private int worldIndex;
    private int ring;
    private int ringSide;
    private long totalProcessed;
    private long totalSkipped;

    public RtpPreGenerator(WorldPlus plugin) {
        this.plugin = plugin;
        this.worlds.addAll(plugin.getWorlds().values());
    }

    public void start() {
        if (worlds.isEmpty()) {
            plugin.getLogger().info("WorldPlus: pré-geração não encontrou mundos configurados.");
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
        if (world == null) world = plugin.createOrLoadWorld(settings);

        if (world == null) {
            plugin.getLogger().warning("WorldPlus: não foi possível pré-gerar " + settings.name() + ".");
            worldIndex++;
            prepareWorld();
            return;
        }

        ring = 0;
        ringSide = 0;
        totalProcessed = 0;
        totalSkipped = 0;

        plugin.getLogger().info("WorldPlus: iniciando pré-geração de " + settings.id()
                + " (" + settings.size() + "x" + settings.size()
                + "), começando pelo centro para disponibilizar o RTP rapidamente.");
    }

    private void tick() {
        if (!plugin.isEnabled() || worldIndex >= worlds.size()) return;

        WorldSettings settings = worlds.get(worldIndex);
        World world = Bukkit.getWorld(settings.name());
        if (world == null) {
            worldIndex++;
            prepareWorld();
            return;
        }

        int halfSize = Math.max(1, (int) Math.ceil(settings.size() / 2.0D));
        int maxChunk = (int) Math.ceil(halfSize / 16.0D) - 1;
        int minChunk = (int) Math.floor(-halfSize / 16.0D);

        int[] coordinate = nextChunk(maxChunk, minChunk);
        if (coordinate == null) {
            plugin.getLogger().info("WorldPlus: pré-geração concluída para " + settings.id()
                    + " — " + totalProcessed + " chunks processadas, "
                    + totalSkipped + " já existentes.");
            worldIndex++;
            prepareWorld();
            return;
        }

        int x = coordinate[0];
        int z = coordinate[1];
        totalProcessed++;

        try {
            if (world.isChunkGenerated(x, z)) {
                totalSkipped++;
            } else {
                world.getChunkAt(x, z, true);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("WorldPlus: falha ao pré-gerar chunk "
                    + x + "," + z + " em " + world.getName() + ": "
                    + throwable.getMessage());
        }

        if (totalProcessed % 1000 == 0) {
            plugin.getLogger().info("WorldPlus: pré-geração " + settings.id()
                    + " — " + totalProcessed + " chunks processadas, "
                    + totalSkipped + " já existentes.");
        }
    }

    private int[] nextChunk(int maxChunk, int minChunk) {
        if (ring == 0) {
            ring = 1;
            return new int[]{0, 0};
        }

        int x;
        int z;
        int r = ring;

        switch (ringSide) {
            case 0 -> {
                x = -r + ringSideOffset;
                z = -r;
            }
            case 1 -> {
                x = r;
                z = -r + ringSideOffset;
            }
            case 2 -> {
                x = r - ringSideOffset;
                z = r;
            }
            default -> {
                x = -r;
                z = r - ringSideOffset;
            }
        }

        ringSideOffset++;

        if (ringSideOffset > r * 2) {
            ringSideOffset = 0;
            ringSide++;
            if (ringSide > 3) {
                ringSide = 0;
                ring++;
            }
        }

        if (x < minChunk || x > maxChunk || z < minChunk || z > maxChunk) {
            return nextChunk(maxChunk, minChunk);
        }

        return new int[]{x, z};
    }

    private int ringSideOffset;
}
