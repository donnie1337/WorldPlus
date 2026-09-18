package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class RtpManager implements Listener {
    private final WorldPlus plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> delays = new HashMap<>();
    private final Map<UUID, String> pendingWorlds = new HashMap<>();
    private final Map<String, Long> heatmap = new HashMap<>();
    private final Set<UUID> processing = new HashSet<>();
    private final Queue<RtpRequest> queue = new ArrayDeque<>();

    public RtpManager(WorldPlus plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, String worldId) {
        WorldSettings settings = plugin.getSettings(worldId);
        if (settings == null) {
            message(player, "mundo-nao-encontrado", "&cMundo de RTP não encontrado: &f{id}&c.", "id", worldId);
            return;
        }

        if (!plugin.getConfig().getBoolean("rtp.mundos." + settings.id() + ".habilitado", true)) {
            message(player, "mundo-desativado", "&cO RTP está desativado neste mundo.", null, null);
            return;
        }

        if (!player.hasPermission("worldplus.rtp.world." + settings.id()) && !player.hasPermission("worldplus.rtp.world.*")) {
            message(player, "sem-permissao-rtp", "&cVocê não tem permissão para usar o RTP neste mundo.", null, null);
            return;
        }

        if (!player.hasPermission("worldplus.rtp.bypass.cooldown")) {
            long remaining = cooldownRemaining(player);
            if (remaining > 0) {
                message(player, "cooldown", "&cAguarde &f{tempo}s &cantes de usar o RTP novamente.", "tempo", Long.toString(remaining));
                return;
            }
        }

        if (processing.contains(player.getUniqueId()) || pendingWorlds.containsKey(player.getUniqueId())) {
            message(player, "em-processamento", "&bSeu RTP já está sendo processado.", null, null);
            return;
        }

        int delay = plugin.getConfig().getInt("rtp.geral.atraso-segundos", 5);
        boolean cancelOnMove = plugin.getConfig().getBoolean("rtp.geral.cancelar-ao-mover", true);

        if (delay > 0 && !player.hasPermission("worldplus.rtp.bypass.delay")) {
            pendingWorlds.put(player.getUniqueId(), settings.id());
            delays.put(player.getUniqueId(), System.currentTimeMillis() + delay * 1000L);
            message(player, "atraso", "&bRTP em &f" + delay + "s&b. " + (cancelOnMove ? "Não se mova." : ""), null, null);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                String pending = pendingWorlds.remove(player.getUniqueId());
                delays.remove(player.getUniqueId());
                if (pending == null || !player.isOnline()) return;
                enqueue(player, pending);
            }, delay * 20L);
            return;
        }

        enqueue(player, settings.id());
    }

    private void enqueue(Player player, String worldId) {
        queue.offer(new RtpRequest(player.getUniqueId(), worldId));
        processNext();
    }

    private void processNext() {
        if (!processing.isEmpty()) return;
        RtpRequest request = queue.poll();
        if (request == null) return;
        Player player = Bukkit.getPlayer(request.playerId());
        if (player == null || !player.isOnline()) {
            processNext();
            return;
        }
        processing.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> perform(player, request.worldId()));
    }

    private void perform(Player player, String worldId) {
        WorldSettings settings = plugin.getSettings(worldId);
        if (settings == null) {
            finish(player);
            return;
        }

        World world = Bukkit.getWorld(settings.name());
        if (world == null) world = plugin.createOrLoadWorld(settings);
        if (world == null) {
            message(player, "erro", "&cNão foi possível carregar o mundo de RTP.", null, null);
            finish(player);
            return;
        }

        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.geral.max-tentativas", 32));
        findSafeLocationAsync(player, world, settings, attempts, safe -> {
            if (safe == null) {
                message(player, "local-nao-encontrado", "&cNão foi possível encontrar um local seguro para o RTP.", null, null);
                finish(player);
                return;
            }

            player.teleport(safe);
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds(settings) * 1000L);
            heatmap.merge(world.getName(), 1L, Long::sum);
            message(player, "teleportado", "&aTeleportado aleatoriamente para &f{id}&a.", "id", settings.id());
            finish(player);
        });
    }

    private void finish(Player player) {
        processing.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, this::processNext);
    }

    private void findSafeLocationAsync(Player player, World world, WorldSettings settings, int attempts,
                                       java.util.function.Consumer<Location> callback) {
        double borderRadius = world.getWorldBorder().getSize() / 2.0D - 16.0D;
        double maxRadius = plugin.getConfig().getDouble("rtp.mundos." + settings.id() + ".raio-maximo", borderRadius);
        double minRadius = plugin.getConfig().getDouble("rtp.mundos." + settings.id() + ".raio-minimo", 100.0D);
        boolean useBorder = plugin.getConfig().getBoolean("rtp.mundos." + settings.id() + ".usar-borda", true);
        double centerX = plugin.getConfig().getDouble("rtp.mundos." + settings.id() + ".centro-x", 0.0D);
        double centerZ = plugin.getConfig().getDouble("rtp.mundos." + settings.id() + ".centro-z", 0.0D);
        String shape = plugin.getConfig().getString("rtp.mundos." + settings.id() + ".formato", "circle");

        if (useBorder) maxRadius = Math.min(maxRadius, borderRadius);
        if (maxRadius <= minRadius) minRadius = 0;

        int minY = plugin.getConfig().getInt("rtp.mundos." + settings.id() + ".y-minimo", 0);
        int maxY = plugin.getConfig().getInt("rtp.mundos." + settings.id() + ".y-maximo", 320);

        findCandidate(player, world, settings, attempts, 0, minRadius, maxRadius, useBorder,
                centerX, centerZ, shape, minY, maxY, callback);
    }

    private void findCandidate(Player player, World world, WorldSettings settings, int attempts, int attempt,
                               double minRadius, double maxRadius, boolean useBorder,
                               double centerX, double centerZ, String shape, int minY, int maxY,
                               java.util.function.Consumer<Location> callback) {
        if (!player.isOnline() || attempt >= attempts) {
            callback.accept(null);
            return;
        }

        double x;
        double z;
        if ("square".equalsIgnoreCase(shape)) {
            x = centerX + randomBetween(-maxRadius, maxRadius);
            z = centerZ + randomBetween(-maxRadius, maxRadius);
            if (Math.abs(x - centerX) < minRadius && Math.abs(z - centerZ) < minRadius) {
                findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                        useBorder, centerX, centerZ, shape, minY, maxY, callback);
                return;
            }
        } else {
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double radius = Math.sqrt(ThreadLocalRandom.current().nextDouble(
                    minRadius * minRadius,
                    Math.max(minRadius * minRadius + 1, maxRadius * maxRadius)));
            x = centerX + Math.cos(angle) * radius;
            z = centerZ + Math.sin(angle) * radius;
        }

        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (useBorder && !world.getWorldBorder().isInside(new Location(world, blockX, 64, blockZ))) {
            findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                    useBorder, centerX, centerZ, shape, minY, maxY, callback);
            return;
        }

        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        // A geração é solicitada pelo pipeline assíncrono do servidor. O callback
        // volta para a thread principal, evitando getHighestBlockYAt/getBlockAt
        // em um chunk que ainda não foi gerado.
        world.getChunkAtAsync(chunkX, chunkZ, true, chunk -> {
            try {
                org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
                int localX = blockX & 15;
                int localZ = blockZ & 15;
                int highest = snapshot.getHighestBlockYAt(localX, localZ);

                if (highest < minY || highest > maxY) {
                    findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                            useBorder, centerX, centerZ, shape, minY, maxY, callback);
                    return;
                }

                int y = highest + 1;
                if (world.getEnvironment() == World.Environment.NETHER) {
                    y = findSafeNetherY(snapshot, localX, localZ, minY, maxY);
                    if (y == Integer.MIN_VALUE) {
                        findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                                useBorder, centerX, centerZ, shape, minY, maxY, callback);
                        return;
                    }
                }

                Material floor = snapshot.getBlockType(localX, y - 1, localZ);
                Material feet = snapshot.getBlockType(localX, y, localZ);
                Material head = snapshot.getBlockType(localX, y + 1, localZ);

                if (!isSafeMaterials(floor, feet, head)) {
                    findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                            useBorder, centerX, centerZ, shape, minY, maxY, callback);
                    return;
                }

                callback.accept(new Location(world, blockX + 0.5D, y, blockZ + 0.5D));
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Falha ao analisar chunk do RTP em " + chunkX + "," + chunkZ
                        + " no mundo " + world.getName() + ": " + throwable.getMessage());
                findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                        useBorder, centerX, centerZ, shape, minY, maxY, callback);
            }
        });
    }

    private int findSafeNetherY(org.bukkit.ChunkSnapshot snapshot, int localX, int localZ, int minY, int maxY) {
        int top = Math.min(maxY, snapshot.getHighestBlockYAt(localX, localZ));
        for (int y = top; y >= minY + 1; y--) {
            Material floor = snapshot.getBlockType(localX, y - 1, localZ);
            Material feet = snapshot.getBlockType(localX, y, localZ);
            Material head = snapshot.getBlockType(localX, y + 1, localZ);
            if (isSolid(floor) && isAirLike(feet) && isAirLike(head) && isSafeMaterials(floor, feet, head)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean isSafeMaterials(Material floor, Material feet, Material head) {
        List<String> blacklist = plugin.getConfig().getStringList("rtp.geral.blocos-bloqueados");
        if (blacklist.contains(floor.name().toLowerCase(Locale.ROOT))
                || blacklist.contains(feet.name().toLowerCase(Locale.ROOT))
                || blacklist.contains(head.name().toLowerCase(Locale.ROOT))) return false;

        return isSolid(floor) && isAirLike(feet) && isAirLike(head);
    }

    private boolean isSolid(Material material) {
        return material.isSolid();
    }

    private boolean isAirLike(Material material) {
        return material.isAir() || material == Material.WATER;
    }

    private long cooldownSeconds(WorldSettings settings) {
        return Math.max(0L, plugin.getConfig().getLong("rtp.mundos." + settings.id() + ".cooldown-segundos",
                plugin.getConfig().getLong("rtp.geral.cooldown-segundos", 60L)));
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
        if (!plugin.getConfig().getBoolean("rtp.geral.cancelar-ao-mover", true)) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (pendingWorlds.remove(uuid) != null) {
            delays.remove(uuid);
            message(event.getPlayer(), "atraso-cancelado", "&cRTP cancelado porque você se moveu.", null, null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingWorlds.remove(uuid);
        delays.remove(uuid);
        processing.remove(uuid);
    }

    private void message(Player player, String key, String fallback, String placeholder, String value) {
        String message = plugin.getConfig().getString("mensagens." + key, fallback);
        if (placeholder != null) message = message.replace("{" + placeholder + "}", value);
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
    }

    private record RtpRequest(UUID playerId, String worldId) {}
}
