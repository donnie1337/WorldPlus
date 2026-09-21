package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class RtpManager implements Listener {
    private final WorldPlus plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, String> pending = new HashMap<>();
    private final Map<UUID, Location> teleportTargets = new HashMap<>();
    private final Map<String, Long> heatmap = new HashMap<>();
    private final Deque<ChunkLoadRequest> chunkLoadQueue = new ArrayDeque<>();
    private int activeChunkLoads;

    public RtpManager(WorldPlus plugin) { this.plugin = plugin; }

    public void request(Player player, String id) {
        WorldSettings settings = plugin.getSettings(id);
        if (settings == null) { msg(player, "mundo-nao-encontrado", "&cMundo de RTP não encontrado: &f{id}&c.", "id", id); return; }
        String path = "rtp.mundos." + settings.id();
        if (!plugin.getConfig().getBoolean(path + ".habilitado", true)) { msg(player, "mundo-desativado", "&cO RTP está desativado neste mundo.", null, null); return; }
        if (!player.hasPermission("worldplus.rtp.world." + settings.id()) && !player.hasPermission("worldplus.rtp.world.*")) {
            msg(player, "sem-permissao-rtp", "&cVocê não tem permissão para usar o RTP neste mundo.", null, null); return;
        }
        if (pending.containsKey(player.getUniqueId())) { msg(player, "rtp-em-andamento", "&cAguarde para se teleportar novamente.", null, null); return; }
        long cooldown = cooldownRemaining(player);
        if (cooldown > 0L) { msg(player, "cooldown", "&cAguarde &f{tempo} segundos &cpara usar o teleporte novamente.", "tempo", Long.toString(cooldown)); return; }

        pending.put(player.getUniqueId(), settings.id());
        debug(player, "Início: mundo selecionado=" + settings.id() + " (" + settings.name() + ").");
        if (plugin.getTitleManager() != null) plugin.getTitleManager().showRtpLoading(player, 10);
        long delay = Math.max(0L, plugin.getConfig().getLong("rtp.geral.atraso-segundos", 3L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !pending.containsKey(player.getUniqueId())) return;
            World world = Bukkit.getWorld(settings.name());
            if (world == null) world = plugin.createOrLoadWorld(settings);
            if (world == null) { debug(player, "Falha: mundo não pôde ser carregado."); msg(player, "erro", "&cNão foi possível carregar o mundo de RTP.", null, null); clear(player); return; }
            int attempts = Math.max(1, plugin.getConfig().getInt("rtp.geral.max-tentativas", 32));
            debug(player, "Mundo carregado. Iniciando busca com " + attempts + " tentativas.");
            find(player, settings, world, attempts);
        }, delay * 20L);
    }

    private void find(Player player, WorldSettings settings, World world, int remaining) {
        if (!player.isOnline() || !pending.containsKey(player.getUniqueId())) { clear(player); return; }
        if (remaining <= 0) { debug(player, "Fim: todas as tentativas foram consumidas."); msg(player, "local-nao-encontrado", "&cNão foi possível encontrar um local seguro para o RTP.", null, null); clear(player); return; }

        Location point = randomPoint(world, settings);
        if (point == null) { debug(player, "Coordenada sorteada fora da borda; sorteando novamente."); retry(player, settings, world, remaining); return; }
        debug(player, "Tentativa " + remaining + ": coordenada X=" + point.getBlockX() + ", Z=" + point.getBlockZ()
                + ", chunk=" + (point.getBlockX() >> 4) + "," + (point.getBlockZ() >> 4) + ".");

        // Carregamento assíncrono no Paper; em Spigot puro o fallback usa a
        // API síncrona uma única vez, sem iniciar novas cargas se o teleporte
        // for recusado por outro sistema.
        debug(player, "Solicitando carregamento da chunk.");
        loadChunk(world, point.getBlockX() >> 4, point.getBlockZ() >> 4, chunk -> {
            if (!player.isOnline() || !pending.containsKey(player.getUniqueId())) {
                clear(player);
                return;
            }

            debug(player, "Chunk carregada: " + chunk.getX() + "," + chunk.getZ() + ".");
            Location safe = safeAt(world, chunk, point.getBlockX(), point.getBlockZ(), settings);
            if (safe == null) {
                debug(player, "Coordenada rejeitada: sem superfície segura.");
                retry(player, settings, world, remaining);
                return;
            }

            debug(player, "Destino seguro: X=" + safe.getBlockX() + ", Y=" + safe.getBlockY() + ", Z=" + safe.getBlockZ() + ".");
            if (player.isInsideVehicle()) {
                debug(player, "Jogador estava em veículo; removendo antes do teleporte.");
                player.leaveVehicle();
            }
            int passengerCount = player.getPassengers().size();
            if (passengerCount > 0) {
                // O Bukkit recusa o teleporte antes de disparar o evento quando
                // a entidade ainda está transportando passageiros.
                debug(player, "Jogador tinha " + passengerCount + " passageiro(s); ejetando antes do teleporte.");
                player.eject();
            }
            debug(player, "Estado antes do teleporte: dead=" + player.isDead()
                    + ", veículos-passageiros=" + player.getPassengers().size() + ".");
            teleportTargets.put(player.getUniqueId(), safe);
            boolean teleported = player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
            teleportTargets.remove(player.getUniqueId());

            if (!teleported) {
                debug(player, "Falha: Player#teleport retornou false.");
                msg(player, "teleporte-cancelado",
                        "&cO teleporte foi bloqueado por outro sistema do servidor.", null, null);
                clear(player);
                return;
            }

            debug(player, "Sucesso: jogador teleportado.");
            completeTeleport(player, settings, safe);
        }, () -> retry(player, settings, world, remaining));
    }

    private void loadChunk(World world, int chunkX, int chunkZ,
                           Consumer<Chunk> loaded, Runnable failed) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            plugin.getLogger().info("[RTP DEBUG] Chunk já estava carregada: " + world.getName()
                    + " " + chunkX + "," + chunkZ + ".");
            loaded.accept(world.getChunkAt(chunkX, chunkZ));
            return;
        }

        int maximumQueueSize = Math.max(1, plugin.getConfig().getInt(
                "rtp.desempenho.tamanho-maximo-da-fila", 200));
        if (chunkLoadQueue.size() >= maximumQueueSize) {
            plugin.getLogger().warning("[RTP DEBUG] Fila de RTP cheia; carga recusada para "
                    + world.getName() + " " + chunkX + "," + chunkZ + ".");
            failed.run();
            return;
        }

        chunkLoadQueue.addLast(new ChunkLoadRequest(world, chunkX, chunkZ, loaded, failed));
        plugin.getLogger().info("[RTP DEBUG] Chunk adicionada à fila de RTP: " + world.getName()
                + " " + chunkX + "," + chunkZ + " (aguardando=" + chunkLoadQueue.size() + ").");
        processChunkLoadQueue();
    }

    private void processChunkLoadQueue() {
        int concurrentLoads = Math.max(1, plugin.getConfig().getInt(
                "rtp.desempenho.cargas-simultaneas-maximas", 1));

        while (activeChunkLoads < concurrentLoads && !chunkLoadQueue.isEmpty()) {
            ChunkLoadRequest request = chunkLoadQueue.removeFirst();
            activeChunkLoads++;
            startChunkLoad(request);
        }
    }

    private void startChunkLoad(ChunkLoadRequest request) {
        World world = request.world();
        int chunkX = request.chunkX();
        int chunkZ = request.chunkZ();

        if (world.isChunkLoaded(chunkX, chunkZ)) {
            completeChunkLoad(request, world.getChunkAt(chunkX, chunkZ));
            return;
        }

        // Paper disponibiliza uma API pública de chunk realmente assíncrona.
        // A reflexão mantém o jar compatível com Spigot sem depender do Paper.
        try {
            var method = world.getClass().getMethod("getChunkAtAsync",
                    int.class, int.class, boolean.class);
            Object result = method.invoke(world, chunkX, chunkZ, true);
            if (result instanceof CompletableFuture<?> future) {
                plugin.getLogger().info("[RTP DEBUG] Carregamento assíncrono do Paper solicitado: "
                        + world.getName() + " " + chunkX + "," + chunkZ + ".");
                future.whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !(chunk instanceof Chunk loadedChunk)) {
                        failChunkLoad(request, "API assíncrona do Paper não retornou uma chunk.");
                        return;
                    }
                    completeChunkLoad(request, loadedChunk);
                }));
                return;
            }
        } catch (ReflectiveOperationException ignored) {
            // Spigot não possui API pública para gerar chunks fora do thread principal.
        }

        // Nunca gera uma chunk inédita em Spigot: isso bloqueia todos os ticks
        // do servidor (foi exatamente a origem dos avisos "Can't keep up").
        // Chunks pré-geradas são carregadas do disco sem executar a geração.
        if (!isChunkGenerated(world, chunkX, chunkZ)) {
            failChunkLoad(request, "chunk ainda não foi pré-gerada; geração síncrona bloqueada.");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
                if (chunk == null || !chunk.isGenerated()) {
                    failChunkLoad(request, "chunk pré-gerada não pôde ser carregada.");
                    return;
                }
                plugin.getLogger().info("[RTP DEBUG] Chunk pré-gerada carregada: " + world.getName()
                        + " " + chunkX + "," + chunkZ + ".");
                completeChunkLoad(request, chunk);
            } catch (Throwable exception) {
                failChunkLoad(request, "erro ao carregar chunk pré-gerada: "
                        + exception.getClass().getSimpleName());
            }
        });
    }

    private boolean isChunkGenerated(World world, int chunkX, int chunkZ) {
        try {
            var method = world.getClass().getMethod("isChunkGenerated", int.class, int.class);
            Object result = method.invoke(world, chunkX, chunkZ);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException ignored) {
            // Se o fork não expõe a consulta, a opção sem risco é aceitar
            // apenas chunks já carregadas, verificadas antes deste método.
            return false;
        }
    }

    private void completeChunkLoad(ChunkLoadRequest request, Chunk chunk) {
        try {
            request.loaded().accept(chunk);
        } finally {
            finishChunkLoad();
        }
    }

    private void failChunkLoad(ChunkLoadRequest request, String reason) {
        plugin.getLogger().warning("[RTP DEBUG] Falha na carga RTP de " + request.world().getName()
                + " " + request.chunkX() + "," + request.chunkZ() + ": " + reason);
        try {
            request.failed().run();
        } finally {
            finishChunkLoad();
        }
    }

    private void finishChunkLoad() {
        activeChunkLoads = Math.max(0, activeChunkLoads - 1);
        Bukkit.getScheduler().runTask(plugin, this::processChunkLoadQueue);
    }

    private record ChunkLoadRequest(World world, int chunkX, int chunkZ,
                                    Consumer<Chunk> loaded, Runnable failed) {
    }

    private void completeTeleport(Player player, WorldSettings settings, Location safe) {
        long cooldown = Math.max(0L, plugin.getConfig().getLong(
                "rtp.mundos." + settings.id() + ".cooldown-segundos",
                plugin.getConfig().getLong("rtp.geral.cooldown-segundos", 5L)));
        if (cooldown > 0L) {
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
        }

        clear(player);
        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().endRtpTitle(player);
            plugin.getTitleManager().showBiomeAfterRtp(player, safe);
        }
        heatmap.merge(safe.getWorld().getName(), 1L, Long::sum);
    }

    private void retry(Player p, WorldSettings s, World w, int remaining) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> find(p, s, w, remaining - 1), 1L);
    }

    private Location randomPoint(World world, WorldSettings settings) {
        String path = "rtp.mundos." + settings.id();
        int min = Math.max(0, plugin.getConfig().getInt(path + ".raio-minimo", 100));
        int max = Math.max(min + 1, plugin.getConfig().getInt(path + ".raio-maximo", (int) (world.getWorldBorder().getSize() / 2D)));
        double cx = plugin.getConfig().getDouble(path + ".centro-x", world.getWorldBorder().getCenter().getX());
        double cz = plugin.getConfig().getDouble(path + ".centro-z", world.getWorldBorder().getCenter().getZ());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double radius = Math.sqrt(r.nextDouble((double) min * min, (double) max * max));
        double angle = r.nextDouble(Math.PI * 2D);
        Location point = new Location(world, Math.floor(cx + Math.cos(angle) * radius) + .5D, world.getMinHeight(), Math.floor(cz + Math.sin(angle) * radius) + .5D);
        return world.getWorldBorder().isInside(point) ? point : null;
    }

    private Location safeAt(World world, Chunk ignored, int x, int z, WorldSettings settings) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            int minY = Math.max(world.getMinHeight() + 1, plugin.getConfig().getInt(
                    "rtp.mundos." + settings.id() + ".y-minimo", 32));
            int maxY = Math.min(world.getMaxHeight() - 3, plugin.getConfig().getInt(
                    "rtp.mundos." + settings.id() + ".y-maximo", 120));
            for (int y = maxY; y >= minY; y--) {
                Location safe = check(world, x, y, z);
                if (safe != null) return safe;
            }
            return null;
        }

        // Para mundos com superfície, a altura real da coordenada já vem da
        // chunk carregada. Só rejeitamos água, lava, ar e perigos explícitos.
        Block highest = world.getHighestBlockAt(x, z);
        Material ground = highest.getType();
        if (ground.isAir() || dangerous(ground)) {
            plugin.getLogger().info("[RTP DEBUG] Superfície rejeitada em " + world.getName() + " X=" + x + " Z=" + z
                    + ": " + ground + ".");
            return null;
        }
        plugin.getLogger().info("[RTP DEBUG] Superfície aceita em " + world.getName() + " X=" + x + " Y="
                + highest.getY() + " Z=" + z + ": " + ground + ".");
        return new Location(world, x + .5D, highest.getY() + 1.0D, z + .5D);
    }

    private Location check(World w, int x, int y, int z) {
        Material ground = w.getBlockAt(x, y, z).getType();
        Material feet = w.getBlockAt(x, y + 1, z).getType();
        Material head = w.getBlockAt(x, y + 2, z).getType();
        if (!ground.isSolid() || dangerous(ground) || !passable(feet) || !passable(head)) return null;
        return new Location(w, x + .5D, y + 1D, z + .5D);
    }

    private boolean passable(Material m) { return !m.isSolid() && !dangerous(m); }
    private boolean dangerous(Material m) {
        return m == Material.WATER || m == Material.LAVA || m.name().endsWith("_WATER")
                || m == Material.CACTUS || m == Material.FIRE || m == Material.SOUL_FIRE
                || m == Material.MAGMA_BLOCK || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE
                || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH
                || m == Material.POINTED_DRIPSTONE || m == Material.BEDROCK;
    }

    private long cooldownRemaining(Player p) { return Math.max(0L, (cooldowns.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis() + 999L) / 1000L); }
    public long cooldownRemainingSeconds(Player p) { return cooldownRemaining(p); }
    public Map<String, Long> getHeatmap() { return Collections.unmodifiableMap(heatmap); }
    WorldPlus getPlugin() {
        return plugin;
    }

    Location getPendingDestination(UUID playerId) { return teleportTargets.get(playerId); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.getConfig().getBoolean("rtp.geral.cancelar-ao-mover", false) || e.getTo() == null) return;
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        if (pending.remove(e.getPlayer().getUniqueId()) != null) msg(e.getPlayer(), "atraso-cancelado", "&cRTP cancelado porque você se moveu.", null, null);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { clear(e.getPlayer()); cooldowns.remove(e.getPlayer().getUniqueId()); }
    public void shutdown() { pending.clear(); }
    private void clear(Player p) { pending.remove(p.getUniqueId()); if (plugin.getTitleManager() != null) plugin.getTitleManager().endRtpTitle(p); }
    private void debug(Player player, String message) {
        if (plugin.getConfig().getBoolean("rtp.debug", true)) {
            plugin.getLogger().info("[RTP DEBUG] " + player.getName() + " • " + message);
        }
    }

    private void msg(Player p, String key, String fallback, String placeholder, String value) {
        String text = plugin.getConfig().getString("rtp.mensagens." + key, plugin.getConfig().getString("mensagens." + key, fallback));
        if (placeholder != null) text = text.replace("{" + placeholder + "}", value);
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }
}
