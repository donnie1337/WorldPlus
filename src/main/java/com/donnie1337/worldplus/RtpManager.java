package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
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
    private final Map<ChunkKey, ChunkRequest> pendingChunks = new HashMap<>();
    private final Map<UUID, Integer> activeLoadsByWorld = new HashMap<>();
    private static final int MAX_CONCURRENT_CHUNK_LOADS = 1;

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

        long remaining = cooldownRemaining(player);
        if (remaining > 0) {
            message(player, "cooldown",
                    "&cAguarde &f{tempo} segundos &cpara usar o teleporte novamente.",
                    "tempo", Long.toString(remaining));
            return;
        }

        UUID uuid = player.getUniqueId();
        if (pendingWorlds.containsKey(uuid)) {
            message(player, "cooldown",
                    "&cAguarde &f{tempo} segundos &cpara usar o teleporte novamente.",
                    "tempo", Long.toString(Math.max(1L, cooldownSeconds(settings))));
            return;
        }

        long reservation = Math.max(1L, cooldownSeconds(settings));
        cooldowns.put(uuid, System.currentTimeMillis() + reservation * 1000L);
        pendingWorlds.put(uuid, settings.id());

        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().showRtpLoading(player, 10);
        }

        // Delay temporariamente desativado para teste de carga/massa.
        delays.remove(uuid);
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
                    removeTicket(location.getWorld(), location.getChunk().getX(), location.getChunk().getZ());
                    cooldowns.remove(player.getUniqueId());
                    message(player, "local-nao-encontrado",
                            "&cNão foi possível concluir o teleporte para o local preparado.", null, null);
                    clear(player);
                    return;
                }

                removeTicket(location.getWorld(), location.getChunk().getX(), location.getChunk().getZ());

                long cooldown = cooldownSeconds(settings);
                cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
                completeTeleport(player, settings, location);
            });
        });
    }

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
        double centerX = plugin.getConfig().getDouble(path + ".centro-x", world.getWorldBorder().getCenter().getX());
        double centerZ = plugin.getConfig().getDouble(path + ".centro-z", world.getWorldBorder().getCenter().getZ());

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double radius = Math.sqrt(random.nextDouble((double) minRadius * minRadius, (double) maxRadius * maxRadius));
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

        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        if (pendingChunks.containsKey(key)) {
            retry(player, world, settings, maxAttempts, attempt, callback);
            return;
        }

        ChunkRequest request = new ChunkRequest(player, world, settings, maxAttempts, attempt, callback, key);
        pendingChunks.put(key, request);
        processChunkQueue();
    }

    private void processChunkQueue() {
        if (pendingChunks.isEmpty()) return;

        ChunkRequest selected = null;
        for (ChunkRequest request : pendingChunks.values()) {
            if (!request.player().isOnline()) continue;
            if (!worldLoadActive(request.world())) {
                selected = request;
                break;
            }
        }

        if (selected != null) {
            startChunkPreparation(selected);
        }
    }

    private boolean worldLoadActive(World world) {
        return activeLoadsByWorld.getOrDefault(world.getUID(), 0) >= MAX_CONCURRENT_CHUNK_LOADS;
    }

    private void startChunkPreparation(ChunkRequest request) {
        pendingChunks.remove(request.key());

        World world = request.world();
        int chunkX = request.key().x();
        int chunkZ = request.key().z();

        if (world.isChunkLoaded(chunkX, chunkZ)) {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            inspectLoadedChunk(request, chunk);
            processChunkQueue();
            return;
        }

        if (!world.isChunkGenerated(chunkX, chunkZ)) {
            retry(request.player(), world, request.settings(), request.maxAttempts(),
                    request.attempt(), request.callback());
            processChunkQueue();
            return;
        }

        activeLoadsByWorld.merge(world.getUID(), 1, Integer::sum);
        pendingChunks.put(request.key(), request);

        // Caminho seguro para Spigot: não usamos NMS ServerChunkCache/
        // DistanceManager. A API oficial de ticket é responsável pelo
        // carregamento e mantém a chunk viva até o teleport.
        try {
            if (!world.addPluginChunkTicket(chunkX, chunkZ, plugin)) {
                scheduleChunkCheck(request);
                return;
            }

            scheduleChunkCheck(request);
        } catch (Throwable ignored) {
            finishChunkLoad(request);
            pendingChunks.remove(request.key());
            retry(request.player(), world, request.settings(), request.maxAttempts(),
                    request.attempt(), request.callback());
            processChunkQueue();
        }
    }

    private void finishChunkLoad(ChunkRequest request) {
        UUID worldId = request.world().getUID();
        activeLoadsByWorld.computeIfPresent(worldId, (id, count) -> count <= 1 ? null : count - 1);
    }

    private void scheduleChunkCheck(ChunkRequest request) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingChunks.get(request.key()) != request) return;

            if (!request.player().isOnline()) {
                removeTicket(request.world(), request.key().x(), request.key().z());
                finishChunkLoad(request);
                pendingChunks.remove(request.key());
                processChunkQueue();
                return;
            }

            if (!request.world().isChunkLoaded(request.key().x(), request.key().z())) {
                scheduleChunkCheck(request);
                return;
            }

            Chunk chunk = request.world().getChunkAt(request.key().x(), request.key().z());
            pendingChunks.remove(request.key());
            finishChunkLoad(request);
            inspectLoadedChunk(request, chunk);
            processChunkQueue();
        }, 1L);
    }

    private void inspectLoadedChunk(ChunkRequest request, Chunk chunk) {
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Location safe = findSafeColumn(request.world(), snapshot);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!request.player().isOnline()) {
                    removeTicket(request.world(), request.key().x(), request.key().z());
                    request.callback().accept(null);
                    return;
                }

                if (safe != null) {
                    request.callback().accept(safe);
                    return;
                }

                removeTicket(request.world(), request.key().x(), request.key().z());
                retry(request.player(), request.world(), request.settings(),
                        request.maxAttempts(), request.attempt(), request.callback());
            });
        });
    }

    private void removeTicket(World world, int chunkX, int chunkZ) {
        world.removePluginChunkTicket(chunkX, chunkZ, plugin);
    }

    private Location findSafeColumn(World world, ChunkSnapshot snapshot) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean nether = world.getEnvironment() == World.Environment.NETHER;

        int columnsToCheck = nether ? 48 : 64;
        int start = random.nextInt(256);

        for (int offset = 0; offset < columnsToCheck; offset++) {
            int index = (start + offset * 4) & 255;
            int localX = index & 15;
            int localZ = index >> 4;

            int y;
            if (nether) {
                int maxY = Math.min(world.getMaxHeight() - 3, 125);
                int minY = Math.max(world.getMinHeight(), 1);
                y = findNetherSafeY(snapshot, localX, localZ, maxY, minY);
            } else {
                y = snapshot.getHighestBlockYAt(localX, localZ);
            }

            if (y < world.getMinHeight() || y + 2 >= world.getMaxHeight()) continue;

            Material floor = snapshot.getBlockType(localX, y, localZ);
            Material feet = snapshot.getBlockType(localX, y + 1, localZ);
            Material head = snapshot.getBlockType(localX, y + 2, localZ);

            if (floor == Material.BEDROCK) continue;
            if (isLiquid(floor) || isLiquid(feet) || isLiquid(head)) continue;
            if (!floor.isSolid()) continue;
            if (!feet.isAir() || !head.isAir()) continue;

            int x = (snapshot.getX() << 4) + localX;
            int z = (snapshot.getZ() << 4) + localZ;
            return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
        }

        return null;
    }

    private int findNetherSafeY(ChunkSnapshot snapshot, int localX, int localZ,
                                int maxY, int minY) {
        for (int y = maxY; y >= minY; y--) {
            Material floor = snapshot.getBlockType(localX, y, localZ);
            if (floor == Material.BEDROCK || isLiquid(floor) || !floor.isSolid()) {
                continue;
            }

            Material feet = snapshot.getBlockType(localX, y + 1, localZ);
            Material head = snapshot.getBlockType(localX, y + 2, localZ);

            if (isLiquid(feet) || isLiquid(head)
                    || !feet.isAir() || !head.isAir()) {
                continue;
            }

            return y;
        }

        return Integer.MIN_VALUE;
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
        clear(player);

        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().endRtpTitle(player);
            plugin.getTitleManager().showBiomeAfterRtp(player, location);
        }

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

    public void shutdown() {
        pendingChunks.clear();
        activeLoadsByWorld.clear();
        for (World world : Bukkit.getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
    }

    private void message(Player player, String key, String fallback,
                          String placeholder, String value) {
        String message = plugin.getConfig().getString("mensagens." + key, fallback);
        if (placeholder != null) {
            message = message.replace("{" + placeholder + "}", value);
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private record ChunkKey(UUID worldId, int x, int z) {}

    private record ChunkRequest(Player player, World world, WorldSettings settings,
                                int maxAttempts, int attempt, Consumer<Location> callback,
                                ChunkKey key) {}
}
