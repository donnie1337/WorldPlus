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
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    private final Map<UUID, ArrayDeque<PreparedDestination>> preparedDestinations = new HashMap<>();
    private final Set<UUID> preparingWorlds = new HashSet<>();
    private static final int MAX_CONCURRENT_CHUNK_LOADS = 1;
    private static final int RTP_POOL_TARGET = 16;
    private static final long RTP_POOL_REFILL_PERIOD_TICKS = 20L;

    public RtpManager(WorldPlus plugin) {
        this.plugin = plugin;
        // O RTP nunca carrega a chunk no momento do clique. O pool é abastecido
        // gradualmente antes do uso, para que vários jogadores possam pedir RTP
        // ao mesmo tempo sem disputar o carregamento de uma chunk na Server Thread.
        Bukkit.getScheduler().runTaskTimer(plugin, this::refillPreparedPools, 20L, RTP_POOL_REFILL_PERIOD_TICKS);
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

        // Reserva imediatamente a janela do RTP. Isso impede que cliques
        // consecutivos iniciem múltiplas buscas antes do primeiro teleporte
        // terminar. Em caso de sucesso, o prazo é renovado a partir do
        // teleporte concluído; em caso de falha, a reserva é removida.
        long reservation = Math.max(1L, cooldownSeconds(settings));
        cooldowns.put(uuid, System.currentTimeMillis() + reservation * 1000L);
        pendingWorlds.put(uuid, settings.id());

        // O GUI já foi fechado pelo evento de clique. Agora o title aparece
        // imediatamente e a preparação do RTP começa 0,5s depois.
        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().showRtpLoading(player, 10);
        }

        delays.put(uuid, System.currentTimeMillis() + 500L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !pendingWorlds.containsKey(uuid)) return;
            delays.remove(uuid);
            waitForPreparedDestination(player, settings);
        }, 10L);
    }


    /**
     * Aguarda um destino que já foi sorteado, carregado e validado pelo pool.
     * Nenhuma chunk é carregada pelo clique do jogador.
     */
    private void waitForPreparedDestination(Player player, WorldSettings settings) {
        if (!player.isOnline() || !pendingWorlds.containsKey(player.getUniqueId())) {
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

        ArrayDeque<PreparedDestination> pool = preparedDestinations.get(world.getUID());
        PreparedDestination destination = pool == null ? null : pool.pollFirst();

        if (destination == null) {
            // O pool continua sendo abastecido em paralelo. O jogador fica
            // aguardando sem iniciar qualquer carregamento de chunk.
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> waitForPreparedDestination(player, settings), 5L);
            return;
        }

        completePreparedTeleport(player, settings, destination);
    }

    private void completePreparedTeleport(Player player, WorldSettings settings,
                                           PreparedDestination destination) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !pendingWorlds.containsKey(uuid)) {
                releasePreparedDestination(destination);
                clear(player);
                return;
            }

            Location location = destination.location();
            boolean teleported = player.teleport(
                    location,
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN
            );

            if (!teleported) {
                releasePreparedDestination(destination);
                cooldowns.remove(uuid);
                message(player, "local-nao-encontrado",
                        "&cNão foi possível concluir o teleporte para o local preparado.", null, null);
                clear(player);
                return;
            }

            long cooldown = cooldownSeconds(settings);
            cooldowns.put(uuid, System.currentTimeMillis() + cooldown * 1000L);
            completeTeleport(player, settings, location);

            // O jogador já está no destino; o ticket é mantido por alguns
            // segundos para evitar uma descarga imediata da chunk recém-usada.
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> releasePreparedDestination(destination), 40L);
        });
    }

    private void refillPreparedPools() {
        for (WorldSettings settings : plugin.getWorlds().values()) {
            World world = Bukkit.getWorld(settings.name());
            if (world == null) continue;

            ArrayDeque<PreparedDestination> pool =
                    preparedDestinations.computeIfAbsent(world.getUID(), ignored -> new ArrayDeque<>());

            if (pool.size() >= RTP_POOL_TARGET || preparingWorlds.contains(world.getUID())) {
                continue;
            }

            prepareRandomDestination(world);
            // Somente uma preparação por tick. O carregamento de uma chunk pode
            // ser pesado, então não empilhamos várias cargas no mesmo tick.
            break;
        }
    }

    private void prepareRandomDestination(World world) {
        UUID worldId = world.getUID();
        preparingWorlds.add(worldId);

        int[] coordinates = randomWorldCoordinate(world);
        int x = coordinates[0];
        int z = coordinates[1];
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        ChunkKey key = new ChunkKey(worldId, chunkX, chunkZ);

        if (!world.getWorldBorder().isInside(new Location(world, x + 0.5D, world.getMinHeight(), z + 0.5D))) {
            preparingWorlds.remove(worldId);
            return;
        }

        // Nunca usar uma chunk que ainda não existe para o pool. Assim o
        // preparador não gera terreno novo durante a operação de RTP.
        if (!world.isChunkGenerated(chunkX, chunkZ)) {
            preparingWorlds.remove(worldId);
            Bukkit.getScheduler().runTaskLater(plugin, () -> refillPreparedPools(), 1L);
            return;
        }

        try {
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
        } catch (Throwable ignored) {
            preparingWorlds.remove(worldId);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Chunk chunk = null;
            for (Chunk loaded : world.getLoadedChunks()) {
                if (loaded.getX() == chunkX && loaded.getZ() == chunkZ) {
                    chunk = loaded;
                    break;
                }
            }

            if (chunk == null) {
                releasePreparedTicket(world, chunkX, chunkZ);
                preparingWorlds.remove(worldId);
                return;
            }

            ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Location safe = findSafeColumn(world, snapshot);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    preparingWorlds.remove(worldId);

                    if (safe == null) {
                        releasePreparedTicket(world, chunkX, chunkZ);
                        return;
                    }

                    preparedDestinations
                            .computeIfAbsent(worldId, ignored -> new ArrayDeque<>())
                            .addLast(new PreparedDestination(
                                    world, chunkX, chunkZ, safe));
                });
            });
        }, 1L);
    }

    private int[] randomWorldCoordinate(World world) {
        var border = world.getWorldBorder();
        double half = border.getSize() / 2.0D;
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();

        int minX = (int) Math.ceil(centerX - half);
        int maxXExclusive = (int) Math.ceil(centerX + half);
        int minZ = (int) Math.ceil(centerZ - half);
        int maxZExclusive = (int) Math.ceil(centerZ + half);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new int[] {
                random.nextInt(minX, maxXExclusive),
                random.nextInt(minZ, maxZExclusive)
        };
    }

    private void releasePreparedDestination(PreparedDestination destination) {
        releasePreparedTicket(destination.world(), destination.chunkX(), destination.chunkZ());
    }

    private void releasePreparedTicket(World world, int chunkX, int chunkZ) {
        try {
            world.removePluginChunkTicket(chunkX, chunkZ, plugin);
        } catch (Throwable ignored) {
        }
    }

    private record PreparedDestination(World world, int chunkX, int chunkZ, Location location) {}

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

                // O destino já foi preparado, mas a troca de dimensão pode
                // disparar carregamento do chunk pelo próprio teleport. Mantemos
                // a chunk de destino presa pelo ticket até depois do teleport.
                boolean teleported = player.teleport(
                        location,
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN
                );

                if (!teleported) {
                    cooldowns.remove(player.getUniqueId());
                    message(player, "local-nao-encontrado",
                            "&cNão foi possível concluir o teleporte para o local preparado.", null, null);
                    clear(player);
                    return;
                }

                // Renova o cooldown a partir do teleporte efetivamente concluído.
                long cooldown = cooldownSeconds(settings);
                cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
                completeTeleport(player, settings, location);
            });
        });
    }

    /**
     * Sorteia a coordenada primeiro, sem consultar as chunks carregadas.
     * A chunk sorteada recebe um ticket temporário do plugin para que o
     * sistema de chunks a carregue. Assim, uma chunk já carregada não tem
     * qualquer vantagem na seleção do destino.
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
        if (selected != null) startChunkPreparation(selected);
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
            Chunk chunk = null;
            for (Chunk loaded : world.getLoadedChunks()) {
                if (loaded.getX() == chunkX && loaded.getZ() == chunkZ) {
                    chunk = loaded;
                    break;
                }
            }
            if (chunk != null) {
                inspectLoadedChunk(request.player(), world, request.settings(), request.maxAttempts(),
                        request.attempt(), chunk, request.callback(), chunkX, chunkZ);
                processChunkQueue();
                return;
            }
        }

        // Nunca gere uma chunk durante o /rtp. Somente chunks previamente
        // geradas podem ser carregadas.
        if (!world.isChunkGenerated(chunkX, chunkZ)) {
            retry(request.player(), world, request.settings(), request.maxAttempts(), request.attempt(),
                    request.callback());
            return;
        }

        activeLoadsByWorld.merge(world.getUID(), 1, Integer::sum);
        pendingChunks.put(request.key(), request);
        try {
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
        } catch (Throwable ignored) {
            finishChunkLoad(request);
            pendingChunks.remove(request.key());
            retry(request.player(), world, request.settings(), request.maxAttempts(), request.attempt(),
                    request.callback());
            return;
        }

        // Se o carregamento foi concluído imediatamente, o ChunkLoadEvent pode
        // já ter acontecido. Fazemos uma checagem no próximo tick.
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            Bukkit.getScheduler().runTask(plugin, () -> onExpectedChunkLoaded(request.key()));
        }
    }

    private void onExpectedChunkLoaded(ChunkKey key) {
        ChunkRequest request = pendingChunks.get(key);
        if (request == null || !request.world().isChunkLoaded(key.x(), key.z())) return;
        Chunk chunk = null;
        for (Chunk loaded : request.world().getLoadedChunks()) {
            if (loaded.getX() == key.x() && loaded.getZ() == key.z()) {
                chunk = loaded;
                break;
            }
        }
        if (chunk != null) handleLoadedChunk(request, chunk);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        ChunkRequest request = pendingChunks.get(new ChunkKey(event.getWorld().getUID(), chunk.getX(), chunk.getZ()));
        if (request != null) handleLoadedChunk(request, chunk);
    }

    private void handleLoadedChunk(ChunkRequest request, Chunk chunk) {
        if (pendingChunks.remove(request.key()) == null) return;
        finishChunkLoad(request);
        inspectLoadedChunk(request.player(), request.world(), request.settings(), request.maxAttempts(),
                request.attempt(), chunk, request.callback(), request.key().x(), request.key().z());
        processChunkQueue();
    }

    private void finishChunkLoad(ChunkRequest request) {
        UUID worldId = request.world().getUID();
        activeLoadsByWorld.computeIfPresent(worldId, (id, count) -> count <= 1 ? null : count - 1);
    }

    private record ChunkKey(UUID worldId, int x, int z) {}
    private record ChunkRequest(Player player, World world, WorldSettings settings, int maxAttempts,
                                int attempt, Consumer<Location> callback, ChunkKey key) {}

    private void inspectLoadedChunk(Player player, World world, WorldSettings settings,
                                    int maxAttempts, int attempt, Chunk chunk,
                                    Consumer<Location> callback, int ticketChunkX, int ticketChunkZ) {
        // O snapshot é criado uma única vez na thread principal e todo o trabalho
        // pesado de leitura/procura é feito fora dela. ChunkSnapshot é thread-safe
        // por definição da API do Spigot.
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Location safe = findSafeColumn(world, snapshot);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    callback.accept(null);
                    return;
                }

                if (safe != null) {
                    removeRtpTicket(world, ticketChunkX, ticketChunkZ);
                    callback.accept(safe);
                    return;
                }

                removeRtpTicket(world, ticketChunkX, ticketChunkZ);
                retry(player, world, settings, maxAttempts, attempt, callback);
            });
        });
    }

    private void removeRtpTicket(World world, int chunkX, int chunkZ) {
        try {
            world.removePluginChunkTicket(chunkX, chunkZ, plugin);
        } catch (Throwable ignored) {
        }
    }

    private Location findSafeColumn(World world, ChunkSnapshot snapshot) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean nether = world.getEnvironment() == World.Environment.NETHER;

        // A busca é feita no snapshot, não através de World#getBlockAt.
        // Isso evita centenas/milhares de acessos ao mundo na thread principal.
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

            if (floor == Material.BEDROCK
                    || isLiquid(feet) || isLiquid(head)
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
        UUID uuid = player.getUniqueId();
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
        preparedDestinations.clear();
        preparingWorlds.clear();
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
}
