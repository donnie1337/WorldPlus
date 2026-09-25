package com.donnie1337.worldplus;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Planeja construções fixas para cada mundo e gera os chunks necessários uma
 * única vez. O RTP apenas encontra locais já existentes; ele não dispara novas
 * construções.
 */
public final class WorldStructureGenerator {
    private static final String GENERATION_VERSION = "fixed-structures-v2";

    private final WorldPlus plugin;
    private final NamespacedKey structureKey;
    private final NamespacedKey worldGenerationKey;

    public WorldStructureGenerator(WorldPlus plugin) {
        this.plugin = plugin;
        this.structureKey = new NamespacedKey(plugin, "generated-structure");
        this.worldGenerationKey = new NamespacedKey(plugin, "structures-generation");
    }

    public void generateWorld(WorldSettings settings, World world) {
        if (settings == null || world == null) return;
        String id = settings.id();
        if (!plugin.getConfig().getBoolean("construcoes.habilitado", true)
                || !plugin.getConfig().getBoolean("construcoes.mundos." + id + ".habilitado", true)) {
            return;
        }

        String version = world.getPersistentDataContainer().get(worldGenerationKey, PersistentDataType.STRING);
        if (GENERATION_VERSION.equals(version)) return;

        List<PlannedStructure> plan = plan(settings);
        if (plan.isEmpty()) {
            markWorldComplete(world);
            return;
        }

        plugin.getLogger().info("WorldPlus: planejando " + plan.size()
                + " construções fixas no mundo " + id + ".");
        generateNext(settings, world, plan, 0);
    }

    private List<PlannedStructure> plan(WorldSettings settings) {
        String path = "construcoes.mundos." + settings.id();
        int amount = Math.max(1, plugin.getConfig().getInt(path + ".quantidade", 12));
        double halfBorder = Math.max(256.0D, settings.size() / 2.0D - 256.0D);
        Random random = new Random(settings.seed() ^ settings.id().hashCode());
        List<PlannedStructure> result = new ArrayList<>();
        Set<Long> usedChunks = new HashSet<>();

        for (int index = 0; index < amount; index++) {
            double angle = index * 2.399963229728653D;
            double radius = halfBorder * (0.22D + 0.68D * ((index + 1.0D) / amount));
            radius *= 0.90D + random.nextDouble() * 0.20D;
            int blockX = (int) Math.round(Math.cos(angle) * radius);
            int blockZ = (int) Math.round(Math.sin(angle) * radius);
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            if (!usedChunks.add(key)) continue;
            result.add(new PlannedStructure(chunkX, chunkZ, index % 3));
        }
        return result;
    }

    private void generateNext(WorldSettings settings, World world,
                              List<PlannedStructure> plan, int index) {
        if (index >= plan.size()) {
            markWorldComplete(world);
            plugin.getLogger().info("WorldPlus: construções fixas concluídas em " + settings.id() + ".");
            return;
        }

        PlannedStructure planned = plan.get(index);
        boolean requested = RtpChunkLoader.request(plugin, world, planned.chunkX(), planned.chunkZ(), ready -> {
            if (ready) placePlanned(settings, world, planned);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> generateNext(settings, world, plan, index + 1), 2L);
        });

        if (!requested) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> generateNext(settings, world, plan, index + 1), 2L);
        }
    }

    private void placePlanned(WorldSettings settings, World world, PlannedStructure planned) {
        Chunk chunk = world.getChunkAt(planned.chunkX(), planned.chunkZ(), false);
        if (chunk == null || !chunk.isGenerated()) return;

        String structure = structureName(settings.id(), planned.variant());
        String existing = chunk.getPersistentDataContainer().get(structureKey, PersistentDataType.STRING);
        if (existing != null && !existing.isBlank()) return;

        Random random = new Random(world.getSeed()
                ^ ((long) planned.chunkX() * 341873128712L)
                ^ ((long) planned.chunkZ() * 132897987541L)
                ^ (long) planned.variant() * 104729L);
        int footprint = footprint(settings.id(), planned.variant());
        int centerX = planned.chunkX() * 16 + 8;
        int centerZ = planned.chunkZ() * 16 + 8;
        Site site = findSite(world, settings.id(), centerX, centerZ, footprint);
        if (site == null) {
            plugin.getLogger().fine("WorldPlus: terreno inválido/irregular para " + structure
                    + " em " + world.getName() + " " + planned.chunkX() + "," + planned.chunkZ()
                    + "; estrutura ignorada para não ficar flutuando.");
            return;
        }

        MaterialPalette palette = palette(world, settings.id(), site.originX() + footprint / 2,
                site.baseY() - 1, site.originZ() + footprint / 2);
        prepareFoundation(world, site, footprint, palette.foundation());
        generate(settings.id(), world, site.originX(), site.baseY(), site.originZ(), planned.variant(), random);
        decorateSite(world, settings.id(), site, footprint, palette, planned.variant(), random);
        populateChests(world, settings.id(), site, footprint, random);

        chunk.getPersistentDataContainer().set(structureKey, PersistentDataType.STRING, structure);
        if (plugin.getTitleManager() != null) {
            plugin.getTitleManager().announceStructure(chunk, structure);
        }
    }

    private int footprint(String id, int variant) {
        return switch (id) {
            case "overworld" -> variant == 2 ? 10 : 12;
            case "mineracao" -> 12;
            case "nether" -> variant == 1 ? 10 : 12;
            case "end" -> variant == 2 ? 10 : 12;
            default -> 12;
        };
    }

    private Site findSite(World world, String id, int centerX, int centerZ, int footprint) {
        int[][] offsets = {
                {0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {3, 3}, {-3, 3}, {3, -3}, {-3, -3}
        };
        for (int[] offset : offsets) {
            int originX = centerX - footprint / 2 + offset[0];
            int originZ = centerZ - footprint / 2 + offset[1];
            int minGround = Integer.MAX_VALUE;
            int maxGround = Integer.MIN_VALUE;
            boolean valid = true;
            for (int x = originX; x < originX + footprint && valid; x++) {
                for (int z = originZ; z < originZ + footprint; z++) {
                    int ground = surfaceY(world, x, z);
                    if (ground <= world.getMinHeight()
                            || ground >= world.getMaxHeight() - 16
                            || !isUsableGround(world.getBlockAt(x, ground, z).getType())) {
                        valid = false;
                        break;
                    }
                    minGround = Math.min(minGround, ground);
                    maxGround = Math.max(maxGround, ground);
                }
            }
            if (!valid || maxGround - minGround > 3) continue;

            int baseY = maxGround + 1;
            int sampleX = originX + footprint / 2;
            int sampleZ = originZ + footprint / 2;
            if (!validBase(world, id, sampleX, baseY - 1, sampleZ)) continue;
            return new Site(originX, originZ, baseY);
        }
        return null;
    }

    private int surfaceY(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = Math.min(100, world.getMaxHeight() - 2);
                 y > world.getMinHeight(); y--) {
                Material current = world.getBlockAt(x, y, z).getType();
                Material above = world.getBlockAt(x, y + 1, z).getType();
                if (isUsableGround(current) && above.isAir()) return y;
            }
            return -1;
        }
        int y = world.getHighestBlockYAt(x, z);
        while (y > world.getMinHeight() && !isUsableGround(world.getBlockAt(x, y, z).getType())) y--;
        return y;
    }

    private boolean isUsableGround(Material material) {
        if (material.isAir() || material == Material.WATER || material == Material.LAVA
                || material == Material.POWDER_SNOW) return false;
        String name = material.name();
        return !name.endsWith("_LEAVES") && !name.endsWith("_LOG")
                && !name.endsWith("_WOOD") && material != Material.CACTUS;
    }

    private void prepareFoundation(World world, Site site, int footprint, Material foundation) {
        for (int x = site.originX(); x < site.originX() + footprint; x++) {
            for (int z = site.originZ(); z < site.originZ() + footprint; z++) {
                int ground = surfaceY(world, x, z);
                for (int y = ground + 1; y < site.baseY(); y++) {
                    block(world, x, y, z, foundation);
                }
            }
        }
    }

    private MaterialPalette palette(World world, String id, int x, int y, int z) {
        if ("nether".equals(id) || world.getEnvironment() == World.Environment.NETHER) {
            return new MaterialPalette(Material.BLACKSTONE, Material.SOUL_SOIL, Material.GILDED_BLACKSTONE);
        }
        if ("end".equals(id) || world.getEnvironment() == World.Environment.THE_END) {
            return new MaterialPalette(Material.END_STONE, Material.PURPUR_BLOCK, Material.OBSIDIAN);
        }

        Biome biome = world.getBiome(x, y, z);
        String name = biome.name();
        if (name.contains("DESERT") || name.contains("BADLANDS")) {
            return new MaterialPalette(Material.SANDSTONE, Material.SAND, Material.RED_SANDSTONE);
        }
        if (name.contains("SNOW") || name.contains("ICE") || name.contains("FROZEN")) {
            return new MaterialPalette(Material.SPRUCE_PLANKS, Material.SNOW_BLOCK, Material.PACKED_ICE);
        }
        if (name.contains("JUNGLE") || name.contains("BAMBOO")) {
            return new MaterialPalette(Material.JUNGLE_PLANKS, Material.MOSS_BLOCK, Material.BAMBOO_BLOCK);
        }
        if (name.contains("TAIGA") || name.contains("GROVE")) {
            return new MaterialPalette(Material.SPRUCE_PLANKS, Material.PODZOL, Material.SPRUCE_LOG);
        }
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            return new MaterialPalette(Material.MANGROVE_PLANKS, Material.MUD, Material.MANGROVE_LOG);
        }
        return new MaterialPalette(Material.OAK_PLANKS, Material.DIRT_PATH, Material.OAK_LOG);
    }

    private void decorateSite(World world, String id, Site site, int footprint,
                              MaterialPalette palette, int variant, Random random) {
        int middle = site.originX() + footprint / 2;
        int front = site.originZ() + footprint - 1;
        for (int i = 1; i < footprint - 1; i++) {
            if (world.getBlockAt(middle, site.baseY() + 1, site.originZ() + i).getType().isAir()
                    && i % 2 == 0) {
                block(world, middle, site.baseY() + 1, site.originZ() + i, palette.path());
            }
        }

        for (int[] corner : new int[][]{
                {site.originX() + 1, site.originZ() + 1},
                {site.originX() + footprint - 2, site.originZ() + 1},
                {site.originX() + 1, front - 1},
                {site.originX() + footprint - 2, front - 1}}) {
            if (random.nextBoolean()) {
                pillar(world, corner[0], site.baseY() + 1, corner[1],
                        1 + random.nextInt(3), palette.accent());
            }
        }

        if ("overworld".equals(id)) {
            Material plant = palette.path() == Material.SNOW_BLOCK ? Material.SNOW : Material.GRASS;
            for (int i = 0; i < 3; i++) {
                int x = site.originX() + 1 + random.nextInt(Math.max(1, footprint - 2));
                int z = site.originZ() + 1 + random.nextInt(Math.max(1, footprint - 2));
                if (world.getBlockAt(x, site.baseY() + 1, z).getType().isAir()) {
                    block(world, x, site.baseY() + 1, z, plant);
                }
            }
        }
    }

    private void populateChests(World world, String id, Site site, int footprint, Random random) {
        List<Material> loot = switch (id) {
            case "mineracao" -> List.of(Material.COAL, Material.RAW_IRON, Material.COPPER_INGOT,
                    Material.REDSTONE, Material.LAPIS_LAZULI, Material.TORCH, Material.RAIL,
                    Material.IRON_PICKAXE, Material.GOLD_INGOT);
            case "nether" -> List.of(Material.GOLD_NUGGET, Material.QUARTZ, Material.OBSIDIAN,
                    Material.FIRE_CHARGE, Material.GLOWSTONE_DUST, Material.NETHER_WART,
                    Material.BASALT, Material.GOLDEN_SWORD);
            case "end" -> List.of(Material.ENDER_PEARL, Material.CHORUS_FRUIT, Material.PURPUR_BLOCK,
                    Material.SHULKER_SHELL, Material.OBSIDIAN, Material.END_ROD,
                    Material.DIAMOND, Material.ENDER_EYE);
            default -> List.of(Material.BREAD, Material.APPLE, Material.TORCH, Material.IRON_INGOT,
                    Material.EMERALD, Material.ARROW, Material.LEATHER, Material.WHEAT_SEEDS,
                    Material.LANTERN, Material.IRON_SWORD);
        };

        for (int x = site.originX(); x < site.originX() + footprint; x++) {
            for (int y = site.baseY(); y < site.baseY() + 14; y++) {
                for (int z = site.originZ(); z < site.originZ() + footprint; z++) {
                    if (!(world.getBlockAt(x, y, z).getState() instanceof Chest chest)) continue;
                    Inventory inventory = chest.getInventory();
                    int entries = 2 + random.nextInt(4);
                    for (int entry = 0; entry < entries; entry++) {
                        Material item = loot.get(random.nextInt(loot.size()));
                        int amount = 1 + random.nextInt(item.getMaxStackSize() >= 16 ? 8 : 3);
                        inventory.addItem(new ItemStack(item, amount));
                    }
                }
            }
        }
    }

    private record Site(int originX, int originZ, int baseY) {}

    private record MaterialPalette(Material foundation, Material path, Material accent) {}

    private void markWorldComplete(World world) {
        world.getPersistentDataContainer().set(
                worldGenerationKey, PersistentDataType.STRING, GENERATION_VERSION);
        world.save();
    }

    private record PlannedStructure(int chunkX, int chunkZ, int variant) {}

    private int baseY(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = Math.min(100, world.getMaxHeight() - 2); y >= Math.max(20, world.getMinHeight()); y--) {
                if (!world.getBlockAt(x, y, z).getType().isAir()
                        && world.getBlockAt(x, y + 1, z).getType().isSolid()) continue;
                if (!world.getBlockAt(x, y, z).getType().isAir()
                        && world.getBlockAt(x, y + 1, z).getType().isAir()) return y + 1;
            }
            return -1;
        }
        return world.getHighestBlockYAt(x, z) + 1;
    }

    private boolean validBase(World world, String id, int x, int y, int z) {
        if (y <= world.getMinHeight() + 2 || y >= world.getMaxHeight() - 16) return false;
        Material floor = world.getBlockAt(x, y, z).getType();
        if (floor.isAir() || floor == Material.WATER || floor == Material.LAVA) return false;
        if ("overworld".equals(id) && y < 45) return false;
        if ("end".equals(id) && y < 30) return false;
        return true;
    }

    private String structureName(String id, int variant) {
        return switch (id) {
            case "overworld" -> variant == 0 ? "posto-avancado" : variant == 1 ? "ruina-antiga" : "templo-da-floresta";
            case "mineracao" -> variant == 0 ? "mina-abandonada" : variant == 1 ? "pedreira" : "cidade-subterranea";
            case "nether" -> variant == 0 ? "acampamento-piglin" : variant == 1 ? "santuario-das-almas" : "arena-do-nether";
            case "end" -> variant == 0 ? "torre-do-end" : variant == 1 ? "templo-do-vazio" : "santuario-dos-shulkers";
            default -> "construcao";
        };
    }

    private void generate(String id, World world, int x, int y, int z, int variant, Random random) {
        switch (id) {
            case "overworld" -> {
                if (variant == 0) buildOverworldOutpost(world, x, y, z);
                else if (variant == 1) buildAncientRuin(world, x, y, z, random);
                else buildForestTemple(world, x, y, z);
            }
            case "mineracao" -> {
                if (variant == 0) buildAbandonedMine(world, x, y, z);
                else if (variant == 1) buildQuarry(world, x, y, z);
                else buildUndergroundCity(world, x, y, z);
            }
            case "nether" -> {
                if (variant == 0) buildPiglinCamp(world, x, y, z);
                else if (variant == 1) buildSoulShrine(world, x, y, z);
                else buildNetherArena(world, x, y, z);
            }
            case "end" -> {
                if (variant == 0) buildEndTower(world, x, y, z);
                else if (variant == 1) buildVoidTemple(world, x, y, z);
                else buildShulkerSanctuary(world, x, y, z);
            }
            default -> {
            }
        }
    }

    private void buildOverworldOutpost(World w, int x, int y, int z) {
        fill(w, x, y, z, x + 11, y, z + 11, Material.COBBLESTONE);
        for (int[] corner : corners(x, z)) pillar(w, corner[0], y + 1, corner[1], 5, Material.OAK_LOG);
        fill(w, x + 1, y + 1, z + 1, x + 10, y + 1, z + 1, Material.OAK_PLANKS);
        fill(w, x + 10, y + 1, z + 1, x + 10, y + 1, z + 10, Material.OAK_PLANKS);
        fill(w, x + 1, y + 1, z + 10, x + 10, y + 1, z + 10, Material.OAK_PLANKS);
        fill(w, x + 1, y + 1, z + 1, x + 1, y + 1, z + 10, Material.OAK_PLANKS);
        fill(w, x + 1, y + 5, z + 1, x + 10, y + 5, z + 10, Material.DARK_OAK_PLANKS);
        block(w, x + 5, y + 1, z + 5, Material.CRAFTING_TABLE);
        block(w, x + 6, y + 1, z + 5, Material.CHEST);
        chest(w, x + 6, y + 1, z + 5, new ItemStack(Material.BREAD, 3), new ItemStack(Material.IRON_NUGGET, 4));
        block(w, x + 2, y + 4, z + 2, Material.LANTERN);
        block(w, x + 9, y + 4, z + 9, Material.LANTERN);
    }

    private void buildAncientRuin(World w, int x, int y, int z, Random random) {
        fill(w, x + 1, y, z + 1, x + 10, y, z + 10, Material.MOSSY_COBBLESTONE);
        pillar(w, x + 1, y + 1, z + 1, 4, Material.STONE_BRICKS);
        pillar(w, x + 10, y + 1, z + 1, 3, Material.CRACKED_STONE_BRICKS);
        pillar(w, x + 1, y + 1, z + 10, 2, Material.MOSSY_STONE_BRICKS);
        pillar(w, x + 10, y + 1, z + 10, 5, Material.STONE_BRICKS);
        fill(w, x + 3, y + 1, z + 3, x + 8, y + 1, z + 3, Material.STONE_BRICKS);
        block(w, x + 5, y + 1, z + 6, Material.CHEST);
        chest(w, x + 5, y + 1, z + 6, new ItemStack(Material.IRON_INGOT, 2), new ItemStack(Material.BREAD, 2));
    }

    private void buildForestTemple(World w, int x, int y, int z) {
        fill(w, x + 2, y, z + 2, x + 9, y, z + 9, Material.STONE_BRICKS);
        for (int[] corner : new int[][]{{x + 2, z + 2}, {x + 9, z + 2}, {x + 2, z + 9}, {x + 9, z + 9}}) {
            pillar(w, corner[0], y + 1, corner[1], 4, Material.SPRUCE_LOG);
        }
        fill(w, x + 2, y + 4, z + 2, x + 9, y + 4, z + 9, Material.SPRUCE_PLANKS);
        block(w, x + 5, y + 1, z + 5, Material.ENCHANTING_TABLE);
        block(w, x + 4, y + 1, z + 5, Material.CHEST);
        chest(w, x + 4, y + 1, z + 5, new ItemStack(Material.EXPERIENCE_BOTTLE, 2), new ItemStack(Material.BOOK));
        block(w, x + 3, y + 3, z + 3, Material.LANTERN);
    }

    private void buildAbandonedMine(World w, int x, int y, int z) {
        fill(w, x, y, z + 5, x + 11, y, z + 7, Material.DIRT);
        for (int pos = 2; pos <= 9; pos += 3) {
            pillar(w, x + pos, y + 1, z + 3, 4, Material.SPRUCE_LOG);
            pillar(w, x + pos, y + 1, z + 8, 4, Material.SPRUCE_LOG);
            fill(w, x + pos, y + 4, z + 3, x + pos, y + 4, z + 8, Material.SPRUCE_LOG);
        }
        for (int pos = 2; pos <= 9; pos++) block(w, x + pos, y + 1, z + 6, Material.RAIL);
        block(w, x + 5, y + 1, z + 4, Material.CHEST);
        chest(w, x + 5, y + 1, z + 4, new ItemStack(Material.COAL, 8), new ItemStack(Material.IRON_NUGGET, 5));
        block(w, x + 3, y + 3, z + 6, Material.LANTERN);
        block(w, x + 8, y + 3, z + 6, Material.LANTERN);
    }

    private void buildQuarry(World w, int x, int y, int z) {
        fill(w, x, y, z, x + 11, y, z + 11, Material.COBBLESTONE);
        clear(w, x + 2, y - 4, z + 2, x + 9, y - 1, z + 9);
        fill(w, x + 2, y - 4, z + 2, x + 9, y - 4, z + 9, Material.DEEPSLATE);
        block(w, x + 4, y - 3, z + 4, Material.COAL_ORE);
        block(w, x + 7, y - 3, z + 7, Material.IRON_ORE);
        block(w, x + 5, y - 3, z + 8, Material.COPPER_ORE);
        block(w, x + 6, y - 3, z + 5, Material.GOLD_ORE);
        block(w, x + 5, y + 1, z + 5, Material.CHEST);
        chest(w, x + 5, y + 1, z + 5, new ItemStack(Material.TORCH, 8), new ItemStack(Material.IRON_PICKAXE));
    }

    private void buildUndergroundCity(World w, int x, int y, int z) {
        fill(w, x, y, z, x + 11, y, z + 11, Material.DEEPSLATE_BRICKS);
        building(w, x + 1, y + 1, z + 1, 4, Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_TILES);
        building(w, x + 7, y + 1, z + 1, 4, Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_TILES);
        building(w, x + 4, y + 1, z + 6, 4, Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_TILES);
        block(w, x + 5, y + 2, z + 5, Material.CHEST);
        chest(w, x + 5, y + 2, z + 5, new ItemStack(Material.LAPIS_LAZULI, 6), new ItemStack(Material.REDSTONE, 8));
    }

    private void buildPiglinCamp(World w, int x, int y, int z) {
        fill(w, x, y, z, x + 11, y, z + 11, Material.BLACKSTONE);
        tent(w, x + 1, y + 1, z + 1, Material.CRIMSON_PLANKS);
        tent(w, x + 7, y + 1, z + 1, Material.WARPED_PLANKS);
        block(w, x + 5, y + 1, z + 6, Material.GOLD_BLOCK);
        block(w, x + 5, y + 1, z + 5, Material.CHEST);
        chest(w, x + 5, y + 1, z + 5, new ItemStack(Material.GOLD_NUGGET, 8), new ItemStack(Material.QUARTZ, 4));
        block(w, x + 3, y + 1, z + 8, Material.SOUL_LANTERN);
    }

    private void buildSoulShrine(World w, int x, int y, int z) {
        fill(w, x + 1, y, z + 1, x + 10, y, z + 10, Material.SOUL_SOIL);
        pillar(w, x + 2, y + 1, z + 2, 5, Material.OBSIDIAN);
        pillar(w, x + 9, y + 1, z + 2, 5, Material.OBSIDIAN);
        pillar(w, x + 2, y + 1, z + 9, 5, Material.OBSIDIAN);
        pillar(w, x + 9, y + 1, z + 9, 5, Material.OBSIDIAN);
        block(w, x + 5, y + 1, z + 5, Material.SOUL_FIRE);
        block(w, x + 5, y + 1, z + 6, Material.CHEST);
        chest(w, x + 5, y + 1, z + 6, new ItemStack(Material.SOUL_TORCH, 4), new ItemStack(Material.QUARTZ, 3));
    }

    private void buildNetherArena(World w, int x, int y, int z) {
        fill(w, x, y, z, x + 11, y, z + 11, Material.BASALT);
        for (int i = 0; i < 12; i++) {
            block(w, x + i, y + 1, z, Material.BLACKSTONE);
            block(w, x + i, y + 1, z + 11, Material.BLACKSTONE);
            block(w, x, y + 1, z + i, Material.BLACKSTONE);
            block(w, x + 11, y + 1, z + i, Material.BLACKSTONE);
        }
        pillar(w, x + 1, y + 1, z + 1, 4, Material.BASALT);
        pillar(w, x + 10, y + 1, z + 1, 4, Material.BASALT);
        pillar(w, x + 1, y + 1, z + 10, 4, Material.BASALT);
        pillar(w, x + 10, y + 1, z + 10, 4, Material.BASALT);
        fill(w, x + 4, y + 1, z + 4, x + 7, y + 1, z + 7, Material.MAGMA_BLOCK);
    }

    private void buildEndTower(World w, int x, int y, int z) {
        fill(w, x + 2, y, z + 2, x + 9, y, z + 9, Material.END_STONE);
        pillar(w, x + 4, y + 1, z + 4, 8, Material.PURPUR_BLOCK);
        pillar(w, x + 7, y + 1, z + 4, 8, Material.PURPUR_BLOCK);
        pillar(w, x + 4, y + 1, z + 7, 8, Material.PURPUR_BLOCK);
        pillar(w, x + 7, y + 1, z + 7, 8, Material.PURPUR_BLOCK);
        fill(w, x + 4, y + 8, z + 4, x + 7, y + 8, z + 7, Material.PURPUR_SLAB);
        block(w, x + 5, y + 1, z + 5, Material.CHEST);
        chest(w, x + 5, y + 1, z + 5, new ItemStack(Material.CHORUS_FRUIT, 3), new ItemStack(Material.ENDER_PEARL, 1));
    }

    private void buildVoidTemple(World w, int x, int y, int z) {
        fill(w, x + 1, y, z + 1, x + 10, y, z + 10, Material.OBSIDIAN);
        for (int[] corner : corners(x + 1, z + 1)) pillar(w, corner[0], y + 1, corner[1], 5, Material.END_STONE_BRICKS);
        fill(w, x + 3, y + 1, z + 3, x + 8, y + 1, z + 8, Material.END_STONE_BRICKS);
        block(w, x + 5, y + 2, z + 5, Material.CRYING_OBSIDIAN);
        block(w, x + 5, y + 1, z + 4, Material.CHEST);
        chest(w, x + 5, y + 1, z + 4, new ItemStack(Material.PURPUR_BLOCK, 8), new ItemStack(Material.ENDER_PEARL, 1));
    }

    private void buildShulkerSanctuary(World w, int x, int y, int z) {
        fill(w, x + 2, y, z + 2, x + 9, y, z + 9, Material.PURPUR_BLOCK);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0 * i / 8.0;
            int px = x + 5 + (int) Math.round(Math.cos(angle) * 4);
            int pz = z + 5 + (int) Math.round(Math.sin(angle) * 4);
            pillar(w, px, y + 1, pz, 3, Material.PURPUR_PILLAR);
            block(w, px, y + 4, pz, Material.END_ROD);
        }
        block(w, x + 5, y + 1, z + 5, Material.CHEST);
        chest(w, x + 5, y + 1, z + 5, new ItemStack(Material.SHULKER_SHELL, 1), new ItemStack(Material.CHORUS_FLOWER, 2));
    }

    private void tent(World w, int x, int y, int z, Material material) {
        fill(w, x, y, z, x + 4, y, z + 4, material);
        fill(w, x + 1, y + 1, z + 1, x + 3, y + 1, z + 3, material);
        fill(w, x + 2, y + 2, z + 2, x + 2, y + 2, z + 2, material);
    }

    private void building(World w, int x, int y, int z, int size, Material wall, Material roof) {
        fill(w, x, y, z, x + size - 1, y, z + size - 1, wall);
        fill(w, x, y + 1, z, x + size - 1, y + 3, z, wall);
        fill(w, x, y + 1, z + size - 1, x + size - 1, y + 3, z + size - 1, wall);
        fill(w, x, y + 1, z, x, y + 3, z + size - 1, wall);
        fill(w, x + size - 1, y + 1, z, x + size - 1, y + 3, z + size - 1, wall);
        fill(w, x, y + 4, z, x + size - 1, y + 4, z + size - 1, roof);
    }

    private void pillar(World w, int x, int y, int z, int height, Material material) {
        fill(w, x, y, z, x, y + height - 1, z, material);
    }

    private void fill(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) block(w, x, y, z, material);
            }
        }
    }

    private void clear(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) block(w, x, y, z, Material.AIR);
            }
        }
    }

    private void block(World w, int x, int y, int z, Material material) {
        if (y >= w.getMinHeight() && y < w.getMaxHeight()) w.getBlockAt(x, y, z).setType(material, false);
    }

    private void chest(World w, int x, int y, int z, ItemStack... items) {
        Block block = w.getBlockAt(x, y, z);
        if (!(block.getState() instanceof Chest chest)) return;
        for (ItemStack item : items) chest.getInventory().addItem(item);
    }

    private int[][] corners(int x, int z) {
        return new int[][]{{x + 1, z + 1}, {x + 10, z + 1}, {x + 1, z + 10}, {x + 10, z + 10}};
    }
}
