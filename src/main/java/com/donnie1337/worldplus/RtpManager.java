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
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class RtpManager implements Listener {
    private final WorldPlus plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> delays = new HashMap<>();
    private final Map<UUID, String> pendingWorlds = new HashMap<>();
    private final Map<String, Long> heatmap = new HashMap<>();
    private final Set<UUID> processing = new HashSet<>();
    private final Queue<RtpRequest> queue = new ArrayDeque<>();
    private final Map<UUID, BukkitTask> preloadTasks = new HashMap<>();
    private final Map<UUID, List<int[]>> preloadTickets = new HashMap<>();

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

        int titleWaitTicks = Math.max(1, (delay + Math.max(1, plugin.getConfig().getInt("rtp.geral.pre-carregar-segundos", 3))) * 20);
        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().showRtpLoading(player, titleWaitTicks);
        }

        if (delay > 0 && !player.hasPermission("worldplus.rtp.bypass.delay")) {
            pendingWorlds.put(player.getUniqueId(), settings.id());
            delays.put(player.getUniqueId(), System.currentTimeMillis() + delay * 1000L);
            // O RTP usa somente title/subtitle durante o processo; não envia mensagem ao chat.
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

        // A referência usada pelo callback precisa ser final/efetivamente final.
        // A variável world acima pode ser reatribuída durante o carregamento.
        final World rtpWorld = world;

        // No primeiro uso do RTP, mostra o title de preparação.
        // O title de bioma será exibido assim que o jogador chegar ao destino.
        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.geral.max-tentativas", 32));
        findSafeLocationAsync(player, rtpWorld, settings, attempts, safe -> {
            if (safe == null) {
                message(player, "local-nao-encontrado", "&cNão foi possível encontrar um local seguro para o RTP.", null, null);
                finish(player);
                return;
            }

            preloadAndTeleport(player, rtpWorld, safe, settings);

        });
    }

    private void preloadAndTeleport(Player player, World world, Location safe, WorldSettings settings) {
        UUID uuid = player.getUniqueId();
        int seconds = Math.max(1, plugin.getConfig().getInt("rtp.geral.pre-carregar-segundos", 3));
        int waitTicks = seconds * 20;

        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().showRtpLoading(player, waitTicks);
        }

        // A chunk do destino já foi gerada/analisada antes desta etapa.
        // NÃO carregamos uma grade 3x3 aqui: loadChunk/addPluginChunkTicket
        // podem carregar uma chunk imediatamente no thread principal.
        // Isso foi a causa do travamento observado em RTP.
        int targetChunkX = safe.getBlockX() >> 4;
        int targetChunkZ = safe.getBlockZ() >> 4;
        List<int[]> tickets = new ArrayList<>();

        try {
            if (world.isChunkLoaded(targetChunkX, targetChunkZ)
                    && world.addPluginChunkTicket(targetChunkX, targetChunkZ, plugin)) {
                tickets.add(new int[]{targetChunkX, targetChunkZ});
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Falha ao manter a chunk do destino do RTP carregada em "
                    + targetChunkX + "," + targetChunkZ + " no mundo " + world.getName()
                    + ": " + throwable.getMessage());
        }

        preloadTickets.put(uuid, tickets);
        BukkitTask oldTask = preloadTasks.remove(uuid);
        if (oldTask != null) oldTask.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            preloadTasks.remove(uuid);
            if (!player.isOnline()) {
                releasePreloadTickets(world, tickets);
                finish(player);
                return;
            }

            player.teleport(safe);
            if (plugin.getTitleManager() != null) {
                plugin.getTitleManager().showBiomeAfterRtp(player, safe);
            }
            cooldowns.put(uuid, System.currentTimeMillis() + cooldownSeconds(settings) * 1000L);
            heatmap.merge(world.getName(), 1L, Long::sum);
            // Teleporte concluído sem mensagem no chat.

            releasePreloadTickets(world, tickets);
            preloadTickets.remove(uuid);
            finish(player);
        }, waitTicks);

        preloadTasks.put(uuid, task);
    }

    private void releasePreloadTickets(World world, List<int[]> tickets) {
        for (int[] ticket : tickets) {
            try {
                world.removePluginChunkTicket(ticket[0], ticket[1], plugin);
            } catch (Throwable ignored) {
            }
        }
    }

    private void finish(Player player) {
        processing.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, this::processNext);
    }

    private void findSafeLocationAsync(Player player, World world, WorldSettings settings, int attempts,
                                       java.util.function.Consumer<Location> callback) {
        // O RTP percorre a área inteira da WorldBorder, sem raio mínimo/máximo
        // configurado. O sorteio é uniforme em X/Z dentro do quadrado da borda,
        // mantendo uma pequena margem para evitar posições exatamente no limite.
        org.bukkit.WorldBorder border = world.getWorldBorder();
        Location borderCenter = border.getCenter();
        double halfSize = Math.max(1.0D, border.getSize() / 2.0D - 16.0D);
        double centerX = borderCenter.getX();
        double centerZ = borderCenter.getZ();
        boolean useBorder = true;
        double minRadius = 0.0D;
        double maxRadius = halfSize;
        String shape = "square";

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

        // Nunca usamos getChunkAt(..., true) para gerar uma chunk nova aqui.
        // Em Spigot 26.2 a geração moderna de chunks usa o pipeline assíncrono
        // interno. Forçar a geração pela API Bukkit pode fazer a etapa de estruturas
        // disparar AsyncStructureSpawnEvent em um contexto inválido e derrubar o worker.
        //
        // O WorldPlus solicita o status FULL diretamente ao pipeline NMS por reflexão,
        // sem bloquear a thread principal. O callback volta para a thread principal
        // somente depois que a CompletableFuture terminar.
        if (!world.isChunkGenerated(chunkX, chunkZ)) {
            final int nextAttempt = attempt + 1;
            requestChunkGenerationAsync(world, chunkX, chunkZ, generated -> {
                if (!player.isOnline()) {
                    callback.accept(null);
                    return;
                }

                if (!generated) {
                    findCandidate(player, world, settings, attempts, nextAttempt, minRadius, maxRadius,
                            useBorder, centerX, centerZ, shape, minY, maxY, callback);
                    return;
                }

                try {
                    Location safe = analyzeChunkForSurface(world, chunkX, chunkZ, minY, maxY);
                    if (safe != null) {
                        callback.accept(safe);
                        return;
                    }
                } catch (Throwable throwable) {
                    plugin.getLogger().warning("Falha ao analisar chunk do RTP em " + chunkX + "," + chunkZ
                            + " no mundo " + world.getName() + ": " + throwable.getMessage());
                }

                findCandidate(player, world, settings, attempts, nextAttempt, minRadius, maxRadius,
                        useBorder, centerX, centerZ, shape, minY, maxY, callback);
            });
            return;
        }

        try {
            Location safe = analyzeChunkForSurface(world, chunkX, chunkZ, minY, maxY);
            if (safe != null) {
                callback.accept(safe);
                return;
            }

            findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                    useBorder, centerX, centerZ, shape, minY, maxY, callback);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Falha ao analisar chunk do RTP em " + chunkX + "," + chunkZ
                    + " no mundo " + world.getName() + ": " + throwable.getMessage());
            findCandidate(player, world, settings, attempts, attempt + 1, minRadius, maxRadius,
                    useBorder, centerX, centerZ, shape, minY, maxY, callback);
        }
    }

    private void requestChunkGenerationAsync(World world, int chunkX, int chunkZ,
                                               java.util.function.Consumer<Boolean> callback) {
        try {
            Method getHandle = world.getClass().getMethod("getHandle");
            Object serverLevel = getHandle.invoke(world);

            Method getChunkSource = serverLevel.getClass().getMethod("getChunkSource");
            Object chunkSource = getChunkSource.invoke(serverLevel);

            Class<?> chunkStatusClass = Class.forName("net.minecraft.world.level.chunk.ChunkStatus");
            Object fullStatus = chunkStatusClass.getField("FULL").get(null);

            Method getChunkFuture = chunkSource.getClass().getMethod(
                    "getChunkFuture",
                    int.class,
                    int.class,
                    chunkStatusClass,
                    boolean.class
            );

            Object result = getChunkFuture.invoke(chunkSource, chunkX, chunkZ, fullStatus, true);
            if (!(result instanceof CompletableFuture<?> future)) {
                throw new IllegalStateException("O pipeline de chunks não retornou CompletableFuture.");
            }

            future.whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (throwable != null) {
                    plugin.getLogger().warning("Falha na geração assíncrona da chunk do RTP em "
                            + chunkX + "," + chunkZ + " no mundo " + world.getName()
                            + ": " + throwable.getMessage());
                    callback.accept(false);
                    return;
                }

                callback.accept(world.isChunkGenerated(chunkX, chunkZ));
            }));
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Não foi possível solicitar a geração assíncrona da chunk do RTP em "
                    + chunkX + "," + chunkZ + " no mundo " + world.getName()
                    + ": " + throwable.getMessage());
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(false));
        }
    }

    private Location analyzeChunkForSurface(World world, int chunkX, int chunkZ, int minY, int maxY) {
        org.bukkit.Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
        org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);

        int start = ThreadLocalRandom.current().nextInt(256);

        // Uma única chunk já possui 256 colunas. Em vez de testar apenas a
        // coluna sorteada, percorremos as colunas em ordem pseudo-aleatória.
        // O destino continua partindo de uma chunk aleatória da WorldBorder,
        // mas aproveitamos toda a área gerada para encontrar uma superfície.
        for (int offset = 0; offset < 256; offset++) {
            int index = (start + offset) & 255;
            int localX = index & 15;
            int localZ = index >> 4;

            int highest = snapshot.getHighestBlockYAt(localX, localZ);
            if (highest < minY || highest > maxY) continue;

            Location safe = findSafeSurface(snapshot, world,
                    chunkX * 16 + localX, chunkZ * 16 + localZ,
                    localX, localZ, minY, maxY);

            if (safe != null) return safe;
        }

        return null;
    }

    private Location findSafeSurface(org.bukkit.ChunkSnapshot snapshot, World world, int blockX, int blockZ,
                                      int localX, int localZ, int minY, int maxY) {
        int top = Math.min(maxY, snapshot.getHighestBlockYAt(localX, localZ));

        if (world.getEnvironment() == World.Environment.NETHER) {
            return findNetherSurface(snapshot, world, blockX, blockZ, localX, localZ, minY, top, maxY);
        }

        for (int y = top + 1; y >= minY + 1; y--) {
            Material floor = snapshot.getBlockType(localX, y - 1, localZ);
            Material feet = snapshot.getBlockType(localX, y, localZ);
            Material head = snapshot.getBlockType(localX, y + 1, localZ);

            if (!isValidSurface(world.getEnvironment(), floor)) continue;
            if (!isAirLike(feet) || !isAirLike(head)) continue;
            if (!isSafeMaterials(floor, feet, head)) continue;

            // A superfície precisa estar realmente do lado de fora. Assim uma
            // coordenada no fundo de uma caverna não passa apenas por ser o
            // bloco mais alto daquela coluna.
            if (!hasOpenSky(snapshot, localX, localZ, y, world.getMaxHeight())) continue;

            return new Location(world, blockX + 0.5D, y, blockZ + 0.5D);
        }

        return null;
    }

    private Location findNetherSurface(org.bukkit.ChunkSnapshot snapshot, World world, int blockX, int blockZ,
                                       int localX, int localZ, int minY, int top, int maxY) {
        for (int y = top + 1; y >= minY + 1; y--) {
            Material floor = snapshot.getBlockType(localX, y - 1, localZ);
            Material feet = snapshot.getBlockType(localX, y, localZ);
            Material head = snapshot.getBlockType(localX, y + 1, localZ);

            if (!isValidSurface(World.Environment.NETHER, floor)) continue;
            if (!isAirLike(feet) || !isAirLike(head)) continue;
            if (!isSafeMaterials(floor, feet, head)) continue;

            // No Nether não existe céu aberto como no Overworld. Exigimos,
            // porém, espaço vertical suficiente para não nascer dentro de
            // um túnel/caverna apertado.
            int open = 0;
            for (int checkY = y + 2; checkY <= Math.min(maxY, snapshot.getHighestBlockYAt(localX, localZ) + 16); checkY++) {
                if (!isAirLike(snapshot.getBlockType(localX, checkY, localZ))) break;
                open++;
                if (open >= 8) break;
            }
            if (open < 8) continue;

            return new Location(world, blockX + 0.5D, y, blockZ + 0.5D);
        }

        return null;
    }

    private boolean hasOpenSky(org.bukkit.ChunkSnapshot snapshot, int localX, int localZ, int y, int maxHeight) {
        int scanMax = Math.min(maxHeight - 1, 384);
        for (int checkY = y + 2; checkY <= scanMax; checkY++) {
            if (!isAirLike(snapshot.getBlockType(localX, checkY, localZ))) {
                return false;
            }
        }
        return true;
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
        delays.remove(uuid);
        processing.remove(uuid);

        BukkitTask preloadTask = preloadTasks.remove(uuid);
        if (preloadTask != null) preloadTask.cancel();

        List<int[]> tickets = preloadTickets.remove(uuid);
        if (tickets != null) {
            Player player = event.getPlayer();
            World world = player.getWorld();
            releasePreloadTickets(world, tickets);
        }
    }

    private void message(Player player, String key, String fallback, String placeholder, String value) {
        String message = plugin.getConfig().getString("mensagens." + key, fallback);
        if (placeholder != null) message = message.replace("{" + placeholder + "}", value);
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
    }

    private record RtpRequest(UUID playerId, String worldId) {}
}
