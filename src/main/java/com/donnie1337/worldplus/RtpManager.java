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

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class RtpManager implements Listener {
    private final WorldPlus plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> delays = new HashMap<>();
    private final Map<UUID, String> pendingWorlds = new HashMap<>();
    private final Map<UUID, Location> pendingLocations = new HashMap<>();
    private final Map<UUID, Boolean> delayExpired = new HashMap<>();
    private final Set<UUID> pendingTeleport = new HashSet<>();
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

        // O atraso é o único período de espera do RTP. Durante esses segundos,
        // a coordenada já é sorteada e a única chunk do candidato é preparada.
        // Quando o contador termina, o jogador não espera mais nada: se a chunk
        // estiver pronta e a posição for segura, ele é teleportado imediatamente.
        if (delay > 0 && !player.hasPermission("worldplus.rtp.bypass.delay")) {
            UUID uuid = player.getUniqueId();
            pendingWorlds.put(uuid, settings.id());
            pendingLocations.remove(uuid);
            delayExpired.put(uuid, false);
            delays.put(uuid, System.currentTimeMillis() + delay * 1000L);

            if (plugin.getTitleManager() != null) {
                plugin.getTitleManager().showRtpLoading(player, delay * 20);
            }

            // A preparação começa imediatamente. O contador e a preparação
            // acontecem em paralelo.
            enqueue(player, settings.id());

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;

                delays.remove(uuid);
                delayExpired.put(uuid, true);

                // Se a chunk/local já estiver pronto, teleporta agora.
                // Se ainda estiver sendo preparado, o callback da preparação
                // fará o teleporte assim que ficar pronto.
                attemptPendingTeleport(player, settings);
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

        // A referência usada pelo callback precisa ser final/efetivamente final.
        // A variável world acima pode ser reatribuída durante o carregamento.
        final World rtpWorld = world;

        // O RTP escolhe uma única coordenada aleatória e trabalha somente com a
        // chunk que contém essa coordenada. Assim que a chunk estiver pronta e
        // o bloco de superfície for validado, o jogador é teleportado imediatamente.
        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.geral.max-tentativas", 32));
        findSafeLocationAsync(player, rtpWorld, settings, attempts, safe -> {
            if (safe == null) {
                UUID uuid = player.getUniqueId();
                pendingWorlds.remove(uuid);
                pendingLocations.remove(uuid);
                delayExpired.remove(uuid);
                delays.remove(uuid);
                pendingTeleport.remove(uuid);
                message(player, "local-nao-encontrado", "&cNão foi possível encontrar um local seguro para o RTP.", null, null);
                finish(player);
                return;
            }

            UUID uuid = player.getUniqueId();
            if (pendingWorlds.containsKey(uuid)) {
                pendingLocations.put(uuid, safe);

                // Se os 3 segundos já terminaram enquanto a chunk era preparada,
                // não existe uma segunda espera: teleporta imediatamente.
                if (Boolean.TRUE.equals(delayExpired.get(uuid))) {
                    attemptPendingTeleport(player, settings);
                }
                return;
            }

            teleportReady(player, settings, safe, rtpWorld);
        });
    }

    private void attemptPendingTeleport(Player player, WorldSettings settings) {
        UUID uuid = player.getUniqueId();

        if (!Boolean.TRUE.equals(delayExpired.get(uuid))) return;
        if (!pendingWorlds.containsKey(uuid)) return;
        if (pendingTeleport.contains(uuid)) return;

        Location ready = pendingLocations.get(uuid);
        if (ready == null) return;

        World world = ready.getWorld();
        if (world == null || !player.isOnline()) {
            pendingLocations.remove(uuid);
            pendingWorlds.remove(uuid);
            delayExpired.remove(uuid);
            finish(player);
            return;
        }

        pendingTeleport.add(uuid);
        teleportReady(player, settings, ready, world);
    }

    private void teleportReady(Player player, WorldSettings settings, Location location, World world) {
        UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                pendingTeleport.remove(uuid);
                pendingWorlds.remove(uuid);
                pendingLocations.remove(uuid);
                delayExpired.remove(uuid);
                finish(player);
                return;
            }

            // Garante que a chunk usada para a posição continua carregada antes
            // de entregar o jogador ao destino.
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            try {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    world.loadChunk(chunkX, chunkZ, true);
                }

                boolean success = player.teleport(location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (!success) {
                    // Uma tentativa adicional no tick seguinte cobre cancelamentos
                    // transitórios do ciclo de teleporte sem criar outro atraso de RTP.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            pendingTeleport.remove(uuid);
                            pendingWorlds.remove(uuid);
                            pendingLocations.remove(uuid);
                            delayExpired.remove(uuid);
                            finish(player);
                            return;
                        }

                        boolean retrySuccess = player.teleport(location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                        if (retrySuccess) {
                            completeTeleport(player, settings, location, world);
                        } else {
                            message(player, "local-nao-encontrado", "&cNão foi possível concluir o teleporte para o local preparado.", null, null);
                            pendingTeleport.remove(uuid);
                            pendingWorlds.remove(uuid);
                            pendingLocations.remove(uuid);
                            delayExpired.remove(uuid);
                            finish(player);
                        }
                    });
                    return;
                }

                completeTeleport(player, settings, location, world);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Falha ao teleportar jogador para o RTP: " + throwable.getMessage());
                message(player, "local-nao-encontrado", "&cNão foi possível concluir o teleporte para o local preparado.", null, null);
                pendingTeleport.remove(uuid);
                pendingWorlds.remove(uuid);
                pendingLocations.remove(uuid);
                delayExpired.remove(uuid);
                finish(player);
            }
        });
    }

    private void completeTeleport(Player player, WorldSettings settings, Location location, World world) {
        UUID uuid = player.getUniqueId();

        pendingTeleport.remove(uuid);
        pendingWorlds.remove(uuid);
        pendingLocations.remove(uuid);
        delayExpired.remove(uuid);
        delays.remove(uuid);

        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().showBiomeAfterRtp(player, location);
        }

        cooldowns.put(uuid, System.currentTimeMillis() + cooldownSeconds(settings) * 1000L);
        heatmap.merge(world.getName(), 1L, Long::sum);

        // Depois do teleporte, a própria presença/renderização do jogador
        // mantém/carrega a chunk de destino.
        finish(player);
    }

    private void finish(Player player) {
        processing.remove(player.getUniqueId());
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, this::processNext);
        }
    }

    private void findSafeLocationAsync(Player player, World world, WorldSettings settings, int attempts,
                                       java.util.function.Consumer<Location> callback) {
        org.bukkit.WorldBorder border = world.getWorldBorder();
        Location borderCenter = border.getCenter();

        // O sorteio usa toda a área útil da WorldBorder.
        double halfSize = Math.max(1.0D, border.getSize() / 2.0D - 16.0D);
        double centerX = borderCenter.getX();
        double centerZ = borderCenter.getZ();

        int minY = plugin.getConfig().getInt("rtp.mundos." + settings.id() + ".y-minimo", 0);
        int maxY = plugin.getConfig().getInt("rtp.mundos." + settings.id() + ".y-maximo", 320);

        findCandidate(player, world, settings, attempts, 0, centerX, centerZ, halfSize, minY, maxY, callback);
    }

    private void findCandidate(Player player, World world, WorldSettings settings, int attempts, int attempt,
                               double centerX, double centerZ, double halfSize, int minY, int maxY,
                               java.util.function.Consumer<Location> callback) {
        if (!player.isOnline() || attempt >= attempts) {
            callback.accept(null);
            return;
        }

        double x = centerX + randomBetween(-halfSize, halfSize);
        double z = centerZ + randomBetween(-halfSize, halfSize);

        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.getWorldBorder().isInside(new Location(world, blockX, 64, blockZ))) {
            retryCandidate(player, world, settings, attempts, attempt, centerX, centerZ, halfSize, minY, maxY, callback);
            return;
        }

        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        // Somente UMA chunk é carregada/verificada por tentativa.
        // Não existe preload 3x3, ticket permanente ou varredura de outras chunks.
        try {
            if (!world.isChunkGenerated(chunkX, chunkZ)) {
                // Esta é a única operação de geração do candidato. Ela é executada
                // uma vez, e somente para a chunk escolhida aleatoriamente.
                world.loadChunk(chunkX, chunkZ, true);
            } else if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.loadChunk(chunkX, chunkZ, false);
            }

            Location safe = analyzeChunkForSurface(world, chunkX, chunkZ, minY, maxY);
            if (safe != null) {
                callback.accept(safe);
                return;
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Falha ao preparar a única chunk do RTP em "
                    + chunkX + "," + chunkZ + " no mundo " + world.getName()
                    + ": " + throwable.getMessage());
        }

        retryCandidate(player, world, settings, attempts, attempt, centerX, centerZ, halfSize, minY, maxY, callback);
    }

    private void retryCandidate(Player player, World world, WorldSettings settings, int attempts, int attempt,
                                double centerX, double centerZ, double halfSize, int minY, int maxY,
                                java.util.function.Consumer<Location> callback) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> findCandidate(player, world, settings, attempts, attempt + 1,
                        centerX, centerZ, halfSize, minY, maxY, callback), 1L);
    }

    private Location analyzeChunkForSurface(World world, int chunkX, int chunkZ, int minY, int maxY) {
        org.bukkit.Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
        org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);

        int start = ThreadLocalRandom.current().nextInt(256);

        // O snapshot já informa o maior bloco não-ar de cada coluna.
        // Limitamos a análise a 64 colunas para não fazer uma varredura pesada
        // no thread principal. Como o candidato precisa ser o maior bloco não-ar
        // e os dois blocos acima precisam ser ar, teto de caverna não passa.
        for (int offset = 0; offset < 64; offset++) {
            int index = (start + offset) & 255;
            int localX = index & 15;
            int localZ = index >> 4;

            int highest = snapshot.getHighestBlockYAt(localX, localZ);
            int worldMaxY = world.getMaxHeight();
            if (highest < minY || highest >= maxY) continue;
            if (highest < world.getMinHeight() || highest + 2 >= worldMaxY) continue;

            Material floor = snapshot.getBlockType(localX, highest, localZ);
            Material feet = snapshot.getBlockType(localX, highest + 1, localZ);
            Material head = snapshot.getBlockType(localX, highest + 2, localZ);

            if (!isValidSurface(world.getEnvironment(), floor)) continue;
            if (!isSafeMaterials(floor, feet, head)) continue;

            if (world.getEnvironment() == World.Environment.NETHER) {
                boolean open = true;
                for (int y = highest + 1; y <= highest + 8 && y < maxY; y++) {
                    if (!isAirLike(snapshot.getBlockType(localX, y, localZ))) {
                        open = false;
                        break;
                    }
                }
                if (!open) continue;
            }

            return new Location(world,
                    chunkX * 16 + localX + 0.5D,
                    highest + 1.0D,
                    chunkZ * 16 + localZ + 0.5D);
        }

        return null;
    }

    private boolean isValidSurface(World.Environment environment, Material material) {
        if (material == null || material.isAir()) return false;

        return switch (environment) {
            case NORMAL -> isOverworldSurface(material);
            case NETHER -> isNetherSurface(material);
            case THE_END -> material == Material.END_STONE;
            default -> false;
        };
    }

    private boolean isOverworldSurface(Material material) {
        String name = material.name();
        return name.equals("GRASS_BLOCK")
                || name.equals("DIRT")
                || name.equals("COARSE_DIRT")
                || name.equals("ROOTED_DIRT")
                || name.equals("PODZOL")
                || name.equals("MYCELIUM")
                || name.equals("SAND")
                || name.equals("RED_SAND")
                || name.equals("GRAVEL")
                || name.equals("CLAY")
                || name.equals("MOSS_BLOCK")
                || name.equals("SNOW_BLOCK")
                || name.equals("MUD")
                || name.equals("PACKED_MUD")
                || name.endsWith("_LEAVES");
    }

    private boolean isNetherSurface(Material material) {
        String name = material.name();
        return name.equals("NETHERRACK")
                || name.equals("CRIMSON_NYLIUM")
                || name.equals("WARPED_NYLIUM")
                || name.equals("SOUL_SAND")
                || name.equals("SOUL_SOIL")
                || name.equals("BLACKSTONE")
                || name.equals("BASALT");
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
        return material.isAir();
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
        pendingLocations.remove(uuid);
        delays.remove(uuid);
        delayExpired.remove(uuid);
        pendingTeleport.remove(uuid);
        processing.remove(uuid);

    }

    private void message(Player player, String key, String fallback, String placeholder, String value) {
        String message = plugin.getConfig().getString("mensagens." + key, fallback);
        if (placeholder != null) message = message.replace("{" + placeholder + "}", value);
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
    }

    private record RtpRequest(UUID playerId, String worldId) {}
}
