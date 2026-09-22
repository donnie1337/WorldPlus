package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Reseta periodicamente mundos descartáveis, mantendo o mundo principal intacto. */
public final class WorldResetManager {
    private static final List<String> DEFAULT_WORLDS = List.of("mineracao", "nether", "end");

    private final WorldPlus plugin;
    private int taskId = -1;

    public WorldResetManager(WorldPlus plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("reset-mundos.habilitado", true)) return;
        checkDueResets();
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::checkDueResets,
                20L * 60L, 20L * 60L * 60L);
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    public void checkDueResets() {
        if (!plugin.getConfig().getBoolean("reset-mundos.habilitado", true)) return;
        Instant now = Instant.now();
        for (String id : configuredWorlds()) {
            WorldSettings settings = plugin.getSettings(id);
            if (settings == null) {
                plugin.getLogger().warning("Reset de mundo ignorado: '" + id + "' não está configurado.");
                continue;
            }
            if (isOverworld(settings)) {
                plugin.getLogger().warning("O Overworld nunca pode ser resetado; ignorando '" + id + "'.");
                continue;
            }

            String path = resetPath(id);
            long dueAt = plugin.getConfig().getLong(path, 0L);
            if (dueAt == 0L) {
                scheduleNext(id, now);
            } else if (now.toEpochMilli() >= dueAt) {
                if (reset(settings)) scheduleNext(id, now);
            }
        }
    }

    public boolean reset(String id) {
        WorldSettings settings = plugin.getSettings(id);
        if (settings == null || isOverworld(settings)) return false;
        boolean success = reset(settings);
        if (success) scheduleNext(settings.id(), Instant.now());
        return success;
    }

    private boolean reset(WorldSettings settings) {
        World destination = plugin.getDimensionWorld("overworld", World.Environment.NORMAL);
        if (destination == null) {
            plugin.getLogger().severe("Reset de '" + settings.id() + "' cancelado: o Overworld não está disponível.");
            return false;
        }

        World world = Bukkit.getWorld(settings.name());
        if (world != null) {
            for (Player player : List.copyOf(world.getPlayers())) {
                player.teleport(destination.getSpawnLocation());
                player.sendMessage(plugin.color("&eO mundo &f" + settings.id() + " &efoi resetado. Você foi levado ao Overworld."));
            }
            world.save();
            if (!Bukkit.unloadWorld(world, true)) {
                plugin.getLogger().warning("Não foi possível descarregar '" + settings.name() + "' para o reset.");
                return false;
            }
        }

        Path folder = Bukkit.getWorldContainer().toPath().resolve(settings.name()).normalize();
        try {
            if (Files.exists(folder)) {
                try (var paths = Files.walk(folder)) {
                    paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new WorldPlus.WorldDeletionException(exception);
                        }
                    });
                }
            }
            World recreated = plugin.createOrLoadWorld(settings);
            if (recreated == null) {
                plugin.getLogger().severe("O mundo '" + settings.id() + "' foi excluído, mas não pôde ser recriado.");
                return false;
            }
            plugin.getLogger().info("Mundo resetado: " + settings.id() + ".");
            return true;
        } catch (WorldPlus.WorldDeletionException exception) {
            plugin.getLogger().warning("Falha ao resetar '" + settings.id() + "': " + exception.getCause().getMessage());
            return false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Falha ao acessar os arquivos de '" + settings.id() + "': " + exception.getMessage());
            return false;
        }
    }

    private List<String> configuredWorlds() {
        List<String> worlds = plugin.getConfig().getStringList("reset-mundos.mundos");
        return worlds.isEmpty() ? DEFAULT_WORLDS : worlds;
    }

    private void scheduleNext(String id, Instant from) {
        long days = Math.max(1L, plugin.getConfig().getLong("reset-mundos.intervalo-dias", 90L));
        plugin.getConfig().set(resetPath(id), from.plus(Duration.ofDays(days)).toEpochMilli());
        plugin.saveConfig();
    }

    private String resetPath(String id) {
        return "reset-mundos.proximos-resets." + id.toLowerCase();
    }

    private boolean isOverworld(WorldSettings settings) {
        return settings.id().equalsIgnoreCase("overworld") || settings.name().equalsIgnoreCase("world");
    }
}
