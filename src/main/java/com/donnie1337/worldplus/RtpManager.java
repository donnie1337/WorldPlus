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
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                perform(player, request.worldId());
            } finally {
                processing.remove(player.getUniqueId());
                processNext();
            }
        });
    }

    private void perform(Player player, String worldId) {
        WorldSettings settings = plugin.getSettings(worldId);
        if (settings == null) return;

        World world = Bukkit.getWorld(settings.name());
        if (world == null) world = plugin.createOrLoadWorld(settings);
        if (world == null) {
            message(player, "erro", "&cNão foi possível carregar o mundo de RTP.", null, null);
            return;
        }

        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.geral.max-tentativas", 32));
        Location safe = findSafeLocation(world, settings, attempts);
        if (safe == null) {
            message(player, "local-nao-encontrado", "&cNão foi possível encontrar um local seguro para o RTP.", null, null);
            return;
        }

        player.teleport(safe);
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds(settings) * 1000L);
        heatmap.merge(world.getName(), 1L, Long::sum);
        message(player, "teleportado", "&aTeleportado aleatoriamente para &f{id}&a.", "id", settings.id());
    }

    private Location findSafeLocation(World world, WorldSettings settings, int attempts) {
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

        for (int attempt = 0; attempt < attempts; attempt++) {
            double x;
            double z;
            if ("square".equalsIgnoreCase(shape)) {
                x = centerX + randomBetween(-maxRadius, maxRadius);
                z = centerZ + randomBetween(-maxRadius, maxRadius);
                if (Math.abs(x - centerX) < minRadius && Math.abs(z - centerZ) < minRadius) continue;
            } else {
                double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
                double radius = Math.sqrt(ThreadLocalRandom.current().nextDouble(
                        minRadius * minRadius, Math.max(minRadius * minRadius + 1, maxRadius * maxRadius)));
                x = centerX + Math.cos(angle) * radius;
                z = centerZ + Math.sin(angle) * radius;
            }

            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            if (useBorder && !world.getWorldBorder().isInside(new Location(world, blockX, 64, blockZ))) continue;

            int y = findSafeY(world, blockX, blockZ, minY, maxY);
            if (y < minY) continue;

            Location location = new Location(world, blockX + 0.5D, y, blockZ + 0.5D);
            if (isSafe(location)) return location;
        }
        return null;
    }

    private int findSafeY(World world, int x, int z, int minY, int maxY) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = Math.min(maxY, world.getMaxHeight() - 2); y >= Math.max(minY, world.getMinHeight() + 1); y--) {
                if (isSolid(world.getBlockAt(x, y, z)) && isAirLike(world.getBlockAt(x, y + 1, z)) && isAirLike(world.getBlockAt(x, y + 2, z)))
                    return y + 1;
            }
            return Integer.MIN_VALUE;
        }

        int highest = world.getHighestBlockYAt(x, z);
        if (highest < minY || highest > maxY) return Integer.MIN_VALUE;
        return highest + 1;
    }

    private boolean isSafe(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        World world = location.getWorld();
        if (world == null || y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;

        Material floor = world.getBlockAt(x, y - 1, z).getType();
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();

        List<String> blacklist = plugin.getConfig().getStringList("rtp.geral.blocos-bloqueados");
        if (blacklist.contains(floor.name().toLowerCase(Locale.ROOT))
                || blacklist.contains(feet.name().toLowerCase(Locale.ROOT))
                || blacklist.contains(head.name().toLowerCase(Locale.ROOT))) return false;

        return isSolid(world.getBlockAt(x, y - 1, z)) && isAirLike(world.getBlockAt(x, y, z)) && isAirLike(world.getBlockAt(x, y + 1, z));
    }

    private boolean isSolid(org.bukkit.block.Block block) {
        return block.getType().isSolid();
    }

    private boolean isAirLike(org.bukkit.block.Block block) {
        Material type = block.getType();
        return type.isAir() || type == Material.WATER;
    }

    private double randomBetween(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
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
