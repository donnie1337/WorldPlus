package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldBorder;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldPlus extends JavaPlugin implements Listener {
    private final Map<String, WorldSettings> worlds = new LinkedHashMap<>();
    private TitleManager titleManager;
    private RtpManager rtpManager;
    private WorldTimeManager worldTimeManager;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        reloadConfig();
        loadWorldSettings();
        if (getConfig().getBoolean("configuracao.aplicar-datapack-sem-stronghold", true)) {
            for (WorldSettings settings : worlds.values()) {
                try { installStrongholdDatapack(settings); }
                catch (IOException exception) {
                    getLogger().severe("Não foi possível preparar o datapack do mundo '" + settings.id() + "': " + exception.getMessage());
                }
            }
        }
    }

    @Override
    public void onDisable() {
        if (worldTimeManager != null) {
            worldTimeManager.stop();
            worldTimeManager = null;
        }
        if (rtpManager != null) {
            rtpManager.shutdown();
            rtpManager = null;
        }
    }

    @Override
    public void onEnable() {
        WorldCommand command = new WorldCommand(this);
        PluginCommand mundos = getCommand("mundos");
        if (mundos != null) {
            mundos.setExecutor(command);
            mundos.setTabCompleter(command);
        }
        if (getConfig().getBoolean("configuracao.criar-mundos-automaticamente", true)) {
            for (WorldSettings settings : worlds.values()) createOrLoadWorld(settings);
        }
        worldTimeManager = new WorldTimeManager(this);
        for (String id : new String[]{"overworld", "mineracao"}) {
            WorldSettings settings = getSettings(id);
            if (settings != null) {
                World world = Bukkit.getWorld(settings.name());
                if (world != null) worldTimeManager.apply(world, id);
            }
        }
        worldTimeManager.start();
        titleManager = new TitleManager(this);
        rtpManager = new RtpManager(this);
        RtpCommand rtpCommand = new RtpCommand(this, rtpManager);
        PluginCommand rtp = getCommand("rtp");
        if (rtp != null) {
            rtp.setExecutor(rtpCommand);
            rtp.setTabCompleter(rtpCommand);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(titleManager, this);
        getServer().getPluginManager().registerEvents(rtpManager, this);
        getServer().getPluginManager().registerEvents(rtpCommand, this);
        getServer().getPluginManager().registerEvents(new WorldPortalListener(this), this);
        getLogger().info("WorldPlus: portais de Nether e End configurados.");
        getLogger().info("WorldPlus: sistema de RTP configurado.");
        getLogger().info("WorldPlus ativado.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        // O RTP é público: mantenha-o no pacote de comandos enviado ao cliente.
        event.getCommands().add("rtp");
    }

    public void loadWorldSettings() {
        worlds.clear();
        if (!getConfig().isConfigurationSection("mundos")) {
            getLogger().warning("Nenhum mundo foi configurado em config.yml.");
            return;
        }
        for (String id : getConfig().getConfigurationSection("mundos").getKeys(false)) {
            String path = "mundos." + id;
            String name = getConfig().getString(path + ".nome", id);
            String environmentName = getConfig().getString(path + ".ambiente", "NORMAL");
            long seed = getConfig().getLong(path + ".seed", 0L);
            double size = getConfig().getDouble(path + ".tamanho", 10000D);
            boolean structures = getConfig().getBoolean(path + ".estruturas", true);
            boolean removeStrongholds = getConfig().getBoolean(path + ".remover-strongholds", false);
            boolean removeEndDragon = getConfig().getBoolean(path + ".remover-dragao", false);
            boolean removeEndTowers = getConfig().getBoolean(path + ".remover-torres", false);
            boolean removeEndBedrock = getConfig().getBoolean(path + ".remover-bedrock", false);
            boolean pvp = getConfig().getBoolean(path + ".pvp", true);
            boolean keepInventory = getConfig().getBoolean(path + ".manter-inventario", true);
            Difficulty difficulty = parseDifficulty(getConfig().getString(path + ".dificuldade", "NORMAL"));
            boolean customSpawn = getConfig().contains(path + ".spawn.x");
            int spawnX = getConfig().getInt(path + ".spawn.x", 0);
            int spawnY = getConfig().getInt(path + ".spawn.y", 0);
            int spawnZ = getConfig().getInt(path + ".spawn.z", 0);
            float spawnYaw = (float) getConfig().getDouble(path + ".spawn.yaw", 0D);
            float spawnPitch = (float) getConfig().getDouble(path + ".spawn.pitch", 0D);
            World.Environment environment;
            try { environment = World.Environment.valueOf(environmentName.toUpperCase()); }
            catch (IllegalArgumentException exception) {
                getLogger().warning("Ambiente inválido em mundos." + id + ".ambiente: " + environmentName);
                continue;
            }
            worlds.put(id.toLowerCase(), new WorldSettings(id.toLowerCase(), name, environment, seed, size,
                    structures, removeStrongholds, removeEndDragon, removeEndTowers, removeEndBedrock,
                    pvp, keepInventory, difficulty, customSpawn, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch));
        }
    }

    private Difficulty parseDifficulty(String value) {
        try { return Difficulty.valueOf(value.toUpperCase()); }
        catch (IllegalArgumentException exception) {
            getLogger().warning("Dificuldade inválida: " + value + ". Usando NORMAL.");
            return Difficulty.NORMAL;
        }
    }

    public Map<String, WorldSettings> getWorlds() { return worlds; }

    public World getWorldById(String id) {
        WorldSettings settings = getSettings(id);
        return settings == null ? null : Bukkit.getWorld(settings.name());
    }

    public TitleManager getTitleManager() {
        return titleManager;
    }


    public World getDimensionWorld(String id, World.Environment environment) {
        WorldSettings settings = getSettings(id);
        if (settings == null || settings.environment() != environment) {
            return null;
        }
        World world = Bukkit.getWorld(settings.name());
        return world != null ? world : createOrLoadWorld(settings);
    }

    public WorldSettings getSettings(String id) {
        return id == null ? null : worlds.get(id.toLowerCase());
    }

    public World createOrLoadWorld(WorldSettings settings) {
        World existing = Bukkit.getWorld(settings.name());
        if (existing != null) {
            applySettings(existing, settings);
            return existing;
        }
        File worldFolder = new File(Bukkit.getWorldContainer(), settings.name());
        boolean newWorld = !worldFolder.exists();

        WorldCreator creator = new WorldCreator(settings.name())
                .environment(settings.environment())
                .seed(settings.seed())
                .generateStructures(settings.structures());
        World world = creator.createWorld();
        if (world != null) {
            applySettings(world, settings);
            if (world.getEnvironment() == World.Environment.THE_END && newWorld) {
                configureEnd(world, settings);
            }
            if (getConfig().getBoolean("configuracao.mensagem-console", true))
                getLogger().info("Mundo carregado: " + settings.id() + " -> " + settings.name());
        }
        return world;
    }

    private void configureEnd(World world, WorldSettings settings) {
        if (!settings.removeEndDragon() && !settings.removeEndTowers() && !settings.removeEndBedrock()) {
            return;
        }

        if (settings.removeEndDragon()) {
            DragonBattle battle = world.getEnderDragonBattle();
            if (battle != null) {
                battle.setPreviouslyKilled(true);
                if (battle.getEnderDragon() != null) {
                    battle.getEnderDragon().remove();
                }
            }

            for (var entity : world.getEntities()) {
                if (entity instanceof EnderDragon || entity.getType() == EntityType.END_CRYSTAL) {
                    entity.remove();
                }
            }
        }

        if (settings.removeEndTowers() || settings.removeEndBedrock()) {
            // Executado apenas no End recém-criado. A limpeza fica restrita à arena central,
            // preservando End Cities e as demais estruturas vanilla do End.
            final int radius = 64;
            final int minY = Math.max(world.getMinHeight(), 0);
            final int maxY = Math.min(world.getMaxHeight() - 1, 128);

            for (int chunkX = -4; chunkX <= 4; chunkX++) {
                for (int chunkZ = -4; chunkZ <= 4; chunkZ++) {
                    var chunk = world.getChunkAt(chunkX, chunkZ);
                    for (int x = chunk.getX() * 16; x < chunk.getX() * 16 + 16; x++) {
                        for (int z = chunk.getZ() * 16; z < chunk.getZ() * 16 + 16; z++) {
                            if ((long) x * x + (long) z * z > (long) radius * radius) continue;
                            for (int y = minY; y <= maxY; y++) {
                                var block = world.getBlockAt(x, y, z);
                                Material material = block.getType();

                                if (settings.removeEndTowers() && material == Material.OBSIDIAN) {
                                    block.setType(Material.AIR, false);
                                } else if (settings.removeEndBedrock() && material == Material.BEDROCK) {
                                    block.setType(Material.AIR, false);
                                }
                            }
                        }
                    }
                }
            }
        }

        world.save();
    }

    public void applySettings(World world, WorldSettings settings) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(settings.size());
        world.setPVP(settings.pvp());
        world.setDifficulty(settings.difficulty());
        if (settings.customSpawn()) {
            world.setSpawnLocation(settings.spawnX(), settings.spawnY(), settings.spawnZ(), settings.spawnYaw());
        }
        world.setGameRule(GameRule.KEEP_INVENTORY, settings.keepInventory());
        if (worldTimeManager != null) worldTimeManager.apply(world, settings.id());
    }

    public void setSpawn(WorldSettings settings, World world, org.bukkit.Location location) {
        getConfig().set("mundos." + settings.id() + ".spawn.x", location.getBlockX());
        getConfig().set("mundos." + settings.id() + ".spawn.y", location.getBlockY());
        getConfig().set("mundos." + settings.id() + ".spawn.z", location.getBlockZ());
        getConfig().set("mundos." + settings.id() + ".spawn.yaw", location.getYaw());
        getConfig().set("mundos." + settings.id() + ".spawn.pitch", location.getPitch());
        saveConfig();
        WorldSettings updated = new WorldSettings(settings.id(), settings.name(), settings.environment(), settings.seed(),
                settings.size(), settings.structures(), settings.removeStrongholds(), settings.removeEndDragon(),
                settings.removeEndTowers(), settings.removeEndBedrock(), settings.pvp(), settings.keepInventory(),
                settings.difficulty(), true, location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), location.getYaw(), location.getPitch());
        worlds.put(settings.id(), updated);
        world.setSpawnLocation(location.getBlockX(), location.getBlockY(), location.getBlockZ(), location.getYaw());
    }

    public boolean deleteWorld(WorldSettings settings) {
        World world = Bukkit.getWorld(settings.name());

        World destination = Bukkit.getWorld("world");
        if (destination == null) {
            for (World candidate : Bukkit.getWorlds()) {
                if (!candidate.getName().equalsIgnoreCase(settings.name())) {
                    destination = candidate;
                    break;
                }
            }
        }

        if (world != null) {
            if (destination == null) {
                getLogger().warning("Não foi possível excluir '" + settings.id() + "' porque não existe outro mundo para receber os jogadores.");
                return false;
            }

            for (org.bukkit.entity.Player player : world.getPlayers()) {
                player.teleport(destination.getSpawnLocation());
            }

            if (!Bukkit.unloadWorld(world, true)) {
                getLogger().warning("Não foi possível descarregar o mundo '" + settings.name() + "'.");
                return false;
            }
        }

        Path worldFolder = new File(Bukkit.getWorldContainer(), settings.name()).toPath();
        try {
            if (Files.exists(worldFolder)) {
                try (var stream = Files.walk(worldFolder)) {
                    stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new WorldDeletionException(exception);
                        }
                    });
                }
            }

            getConfig().set("mundos." + settings.id(), null);
            saveConfig();
            worlds.remove(settings.id());
            return true;
        } catch (WorldDeletionException exception) {
            getLogger().warning("Falha ao excluir o mundo '" + settings.name() + "': " + exception.getCause().getMessage());
            return false;
        } catch (IOException exception) {
            getLogger().warning("Falha ao acessar os arquivos do mundo '" + settings.name() + "': " + exception.getMessage());
            return false;
        }
    }

    private static final class WorldDeletionException extends RuntimeException {
        private WorldDeletionException(IOException cause) { super(cause); }
    }

    private void installStrongholdDatapack(WorldSettings settings) throws IOException {
        File worldFolder = new File(Bukkit.getWorldContainer(), settings.name());
        if (!settings.removeStrongholds()) {
            removeWorldPlusDatapack(worldFolder.toPath());
            return;
        }
        Path datapackFolder = worldFolder.toPath().resolve("datapacks").resolve("zzz_worldplus_no_strongholds");
        Path dataFolder = datapackFolder.resolve("data").resolve("minecraft").resolve("worldgen").resolve("structure_set");
        Files.createDirectories(dataFolder);
        String packMeta = """
                {
                  "pack": {
                    "min_format": [107, 1],
                    "max_format": 107,
                    "description": "WorldPlus - Strongholds desativadas"
                  }
                }
                """;
        String strongholds = """
                {
                  "placement": {
                    "type": "minecraft:concentric_rings",
                    "count": 1,
                    "distance": 32,
                    "preferred_biomes": "#minecraft:stronghold_biased_to",
                    "salt": 0,
                    "spread": 3,
                    "frequency": 0.0
                  },
                  "structures": [
                    {
                      "structure": "minecraft:stronghold",
                      "weight": 1
                    }
                  ]
                }
                """;
        Files.writeString(datapackFolder.resolve("pack.mcmeta"), packMeta, StandardCharsets.UTF_8);
        Files.writeString(dataFolder.resolve("strongholds.json"), strongholds, StandardCharsets.UTF_8);
    }

    private void removeWorldPlusDatapack(Path worldFolder) throws IOException {
        Path datapack = worldFolder.resolve("datapacks").resolve("zzz_worldplus_no_strongholds");
        if (!Files.exists(datapack)) return;
        try (var stream = Files.walk(datapack)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) {
                    getLogger().warning("Não foi possível remover " + path + ": " + exception.getMessage());
                }
            });
        }
    }
}
