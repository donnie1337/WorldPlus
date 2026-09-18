package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldBorder;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorldPlus extends JavaPlugin {
    private final Map<String, WorldSettings> worlds = new LinkedHashMap<>();

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
        getLogger().info("WorldPlus ativado.");
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
            boolean pvp = getConfig().getBoolean(path + ".pvp", true);
            boolean keepInventory = getConfig().getBoolean(path + ".manter-inventario", true);
            Difficulty difficulty = parseDifficulty(getConfig().getString(path + ".dificuldade", "NORMAL"));
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
                    structures, removeStrongholds, pvp, keepInventory, difficulty,
                    spawnX, spawnY, spawnZ, spawnYaw, spawnPitch));
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

    public WorldSettings getSettings(String id) {
        return id == null ? null : worlds.get(id.toLowerCase());
    }

    public World createOrLoadWorld(WorldSettings settings) {
        World existing = Bukkit.getWorld(settings.name());
        if (existing != null) {
            applySettings(existing, settings);
            return existing;
        }
        WorldCreator creator = new WorldCreator(settings.name())
                .environment(settings.environment())
                .seed(settings.seed())
                .generateStructures(settings.structures());
        World world = creator.createWorld();
        if (world != null) {
            applySettings(world, settings);
            if (getConfig().getBoolean("configuracao.mensagem-console", true))
                getLogger().info("Mundo carregado: " + settings.id() + " -> " + settings.name());
        }
        return world;
    }

    public void applySettings(World world, WorldSettings settings) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(settings.size());
        world.setPVP(settings.pvp());
        world.setDifficulty(settings.difficulty());
        world.setSpawnLocation(settings.spawnX(), settings.spawnY(), settings.spawnZ(), settings.spawnYaw());
        world.setGameRuleValue("keepInventory", Boolean.toString(settings.keepInventory()));
    }

    public void setSpawn(WorldSettings settings, World world, org.bukkit.Location location) {
        getConfig().set("mundos." + settings.id() + ".spawn.x", location.getBlockX());
        getConfig().set("mundos." + settings.id() + ".spawn.y", location.getBlockY());
        getConfig().set("mundos." + settings.id() + ".spawn.z", location.getBlockZ());
        getConfig().set("mundos." + settings.id() + ".spawn.yaw", location.getYaw());
        getConfig().set("mundos." + settings.id() + ".spawn.pitch", location.getPitch());
        saveConfig();
        WorldSettings updated = new WorldSettings(settings.id(), settings.name(), settings.environment(), settings.seed(),
                settings.size(), settings.structures(), settings.removeStrongholds(), settings.pvp(),
                settings.keepInventory(), settings.difficulty(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), location.getYaw(), location.getPitch());
        worlds.put(settings.id(), updated);
        world.setSpawnLocation(location.getBlockX(), location.getBlockY(), location.getBlockZ(), location.getYaw());
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
