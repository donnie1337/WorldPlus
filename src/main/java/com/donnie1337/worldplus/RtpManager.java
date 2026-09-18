package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class RtpManager implements Listener {
    private final WorldPlus plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> delays = new HashMap<>();
    private final Map<UUID, String> pendingWorlds = new HashMap<>();
    private final Map<String, Long> heatmap = new HashMap<>();

    public RtpManager(WorldPlus plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, String worldId) {
        WorldSettings settings = plugin.getSettings(worldId);
        if (settings == null) {
            message(player, "mundo-nao-encontrado", "&cMundo de RTP não encontrado: &f{id}&c.", "id", worldId);
            return;
        }

        String path = "rtp.mundos." + settings.id();
        if (!plugin.getConfig().getBoolean(path + ".habilitado", true)) {
            message(player, "mundo-desativado", "&cO RTP está desativado neste mundo.", null, null);
            return;
        }

        if (!player.hasPermission("worldplus.rtp.world." + settings.id())
                && !player.hasPermission("worldplus.rtp.world.*")) {
            message(player, "sem-permissao-rtp",
                    "&cVocê não tem permissão para usar o RTP neste mundo.", null, null);
            return;
        }

        if (!player.hasPermission("worldplus.rtp.bypass.cooldown")) {
            long remaining = cooldownRemaining(player);
            if (remaining > 0) {
                message(player, "cooldown",
                        "&cAguarde &f{tempo}s &cantes de usar o RTP novamente.",
                        "tempo", Long.toString(remaining));
                return;
            }
        }

        UUID uuid = player.getUniqueId();
        if (pendingWorlds.containsKey(uuid)) {
            message(player, "em-processamento", "&bSeu RTP já está sendo processado.", null, null);
            return;
        }

        int delay = Math.max(0, plugin.getConfig().getInt("rtp.geral.atraso-segundos", 3));
        pendingWorlds.put(uuid, settings.id());

        if (delay > 0 && !player.hasPermission("worldplus.rtp.bypass.delay")) {
            delays.put(uuid, System.currentTimeMillis() + delay * 1000L);

            if (plugin.getTitleManager() != null) {
                plugin.getTitleManager().showRtpLoading(player, delay * 20);
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || !pendingWorlds.containsKey(uuid)) return;
                delays.remove(uuid);
                findAndTeleport(player, settings);
            }, delay * 20L);
            return;
        }

        findAndTeleport(player, settings);
    }

    private void findAndTeleport(Player player, WorldSettings settings) {
        if (!player.isOnline()) {
            clear(player);
            return;
        }

        World world = Bukkit.getWorld(settings.name());
        if (world == null) {
            world = plugin.createOrLoadWorld(settings);
        }

        if (world == null) {
            message(player, "erro", "&cNão foi possível carregar o mundo de RTP.", null, null);
            clear(player);
            return;
        }

        final World targetWorld = world;
        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.geral.max-tentativas", 32));

        findChunk(player, targetWorld, settings, attempts, 0, location -> {
            if (location == null) {
                message(player, "local-nao-encontrado",
                        "&cNão foi possível encontrar um local seguro para o RTP.", null, null);
                clear(player);
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !pendingWorlds.containsKey(player.getUniqueId())) {
                    clear(player);
                    return;
                }

                boolean teleported = player.teleport(
                        location,
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN
                );

                if (!teleported) {
                    message(player, "local-nao-encontrado",
                            "&cNão foi possível concluir o teleporte para o local preparado.", null, null);
                    clear(player);
                    return;
                }

                completeTeleport(player, settings, location);
            });
        });
    }

    /**
     * Escolhe uma única chunk por tentativa e carrega somente essa chunk.
     * Depois que ela estiver pronta, o RTP procura uma coluna segura dentro dela.
     * Não existe fila global, pré-geração ou carregamento de várias chunks.
     */
    private void findChunk(Player player, World world, WorldSettings settings,
                           int maxAttempts, int attempt, Consumer<Location> callback) {
        if (!player.isOnline() || attempt >= maxAttempts) {
            callback.accept(null);
            return;
        }

        String path = "rtp.mundos." + settings.id();
        int minRadius = Math.max(0, plugin.getConfig().getInt(path + ".raio-minimo", 100));
        int maxRadius = Math.max(minRadius + 1,
                plugin.getConfig().getInt(path + ".raio-maximo",
                        (int) (world.getWorldBorder().getSize() / 2.0D)));

        double centerX = plugin.getConfig().getDouble(
                path + ".centro-x", world.getWorldBorder().getCenter().getX());
        double centerZ = plugin.getConfig().getDouble(
                path + ".centro-z", world.getWorldBorder().getCenter().getZ());

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double minSquared = (double) minRadius * minRadius;
        double maxSquared = (double) maxRadius * maxRadius;
        double radius = Math.sqrt(random.nextDouble(minSquared, maxSquared));
        double angle = random.nextDouble(0.0D, Math.PI * 2.0D);

        int x = (int) Math.floor(centerX + Math.cos(angle) * radius);
        int z = (int) Math.floor(centerZ + Math.sin(angle) * radius);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        Location probe = new Location(world, x + 0.5D, world.getMinHeight(), z + 0.5D);
        if (!world.getWorldBorder().isInside(probe)) {
            retry(player, world, settings, maxAttempts, attempt, callback);
            return;
        }

        if (world.isChunkLoaded(chunkX, chunkZ)) {
            inspectChunk(player, world, settings, maxAttempts, attempt, chunkX, chunkZ, callback);
            return;
        }

        world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk -> {
            if (!player.isOnline()) {
                callback.accept(null);
                return;
            }

            // O carregamento pode terminar fora da thread principal.
            // A leitura dos blocos e a preparação do teleporte ficam na thread do servidor.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    callback.accept(null);
                    return;
                }
                inspectLoadedChunk(player, world, settings, maxAttempts, attempt, chunk, callback);
            });
        });
    }

    private void inspectChunk(Player player, World world, WorldSettings settings,
                              int maxAttempts, int attempt, int chunkX, int chunkZ,
                              Consumer<Location> callback) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
        inspectLoadedChunk(player, world, settings, maxAttempts, attempt, chunk, callback);
    }

    private void inspectLoadedChunk(Player player, World world, WorldSettings settings,
                                    int maxAttempts, int attempt, Chunk chunk,
                                    Consumer<Location> callback) {
        Location safe = findSafeColumn(world, chunk);
        if (safe != null) {
            callback.accept(safe);
            return;
        }

        retry(player, world, settings, maxAttempts, attempt, callback);
    }

    private Location findSafeColumn(World world, Chunk chunk) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int start = random.nextInt(256);

        for (int offset = 0; offset < 256; offset++) {
            int index = (start + offset) & 255;
            int localX = index & 15;
            int localZ = index >> 4;

            int x = (chunk.getX() << 4) + localX;
            int z = (chunk.getZ() << 4) + localZ;
            int y = world.getHighestBlockYAt(x, z);

            if (y < world.getMinHeight() || y + 2 >= world.getMaxHeight()) continue;

            Material floor = world.getBlockAt(x, y, z).getType();
            Material feet = world.getBlockAt(x, y + 1, z).getType();
            Material head = world.getBlockAt(x, y + 2, z).getType();

            // O RTP nunca coloca o jogador sobre água ou lava.
            if (isLiquid(floor) || isLiquid(feet) || isLiquid(head)) continue;

            if (!floor.isSolid()) continue;
            if (!feet.isAir() || !head.isAir()) continue;

            return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
        }

        return null;
    }

    private boolean isLiquid(Material material) {
        return material == Material.WATER || material == Material.LAVA
                || material.name().endsWith("_WATER");
    }

    private void retry(Player player, World world, WorldSettings settings,
                       int maxAttempts, int attempt, Consumer<Location> callback) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> findChunk(player, world, settings, maxAttempts, attempt + 1, callback), 1L);
    }

    private void completeTeleport(Player player, WorldSettings settings, Location location) {
        UUID uuid = player.getUniqueId();
        clear(player);

        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().endRtpTitle(player);
            plugin.getTitleManager().showBiomeAfterRtp(player, location);
        }

        cooldowns.put(uuid, System.currentTimeMillis() + cooldownSeconds(settings) * 1000L);
        heatmap.merge(location.getWorld().getName(), 1L, Long::sum);
    }

    private void clear(Player player) {
        UUID uuid = player.getUniqueId();
        pendingWorlds.remove(uuid);
        delays.remove(uuid);
        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().endRtpTitle(player);
        }
    }

    private long cooldownSeconds(WorldSettings settings) {
        return Math.max(0L, plugin.getConfig().getLong(
                "rtp.mundos." + settings.id() + ".cooldown-segundos",
                plugin.getConfig().getLong("rtp.geral.cooldown-segundos", 15L)));
    }

    private long cooldownRemaining(Player player) {
        long until = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        return Math.max(0L, (until - System.currentTimeMillis() + 999L) / 1000L);
    }

    public long cooldownRemainingSeconds(Player player) {
        return cooldownRemaining(player);
    }

    public Map<String, Long> getHeatmap() {
        return Collections.unmodifiableMap(heatmap);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("rtp.geral.cancelar-ao-mover", false)) return;
        if (event.getTo() == null) return;

        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        UUID uuid = event.getPlayer().getUniqueId();
        if (pendingWorlds.remove(uuid) != null) {
            delays.remove(uuid);
            message(event.getPlayer(), "atraso-cancelado",
                    "&cRTP cancelado porque você se moveu.", null, null);
            if (plugin.getTitleManager() != null) {
                plugin.getTitleManager().endRtpTitle(event.getPlayer());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingWorlds.remove(uuid);
        delays.remove(uuid);
        cooldowns.remove(uuid);
    }

    private void message(Player player, String key, String fallback,
                          String placeholder, String value) {
        String message = plugin.getConfig().getString("mensagens." + key, fallback);
        if (placeholder != null) {
            message = message.replace("{" + placeholder + "}", value);
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
