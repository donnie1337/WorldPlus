package com.donnie1337.worldplus;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Random;

/**
 * Gera construções pequenas e determinísticas em chunks novos.
 *
 * As estruturas ficam limitadas ao próprio chunk para não depender de chunks
 * vizinhos e nunca são reaplicadas em chunks que já existiam antes do plugin.
 */
public final class WorldStructureGenerator implements Listener {
    private final WorldPlus plugin;

    public WorldStructureGenerator(JavaPlugin plugin) {
        this.plugin = (WorldPlus) plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        String id = worldId(world);
        if (id == null || !enabled(id)) return;

        Random random = new Random(seed(world, chunk));
        double chance = plugin.getConfig().getDouble(
                "construcoes.mundos." + id + ".chance-por-chunk",
                plugin.getConfig().getDouble("construcoes.chance-por-chunk", 0.0125D)
        );
        if (random.nextDouble() >= Math.max(0.0D, Math.min(1.0D, chance))) return;

        int x = chunk.getX() * 16 + 2;
        int z = chunk.getZ() * 16 + 2;
        int baseY = baseY(world, x + 6, z + 6);
        if (!validBase(world, id, x + 6, baseY - 1, z + 6)) return;

        // Aguarda o tick seguinte para não alongar o carregamento síncrono do chunk.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!chunk.isLoaded()) return;
            generate(id, world, x, baseY, z, random);
        });
    }

    private boolean enabled(String id) {
        if (!plugin.getConfig().getBoolean("construcoes.habilitado", true)) return false;
        return plugin.getConfig().getBoolean("construcoes.mundos." + id + ".habilitado", true);
    }

    private String worldId(World world) {
        for (Map.Entry<String, WorldSettings> entry : plugin.getWorlds().entrySet()) {
            if (entry.getValue().name().equals(world.getName())) return entry.getKey();
        }
        return null;
    }

    private long seed(World world, Chunk chunk) {
        long value = world.getSeed();
        value ^= (long) chunk.getX() * 341873128712L;
        value ^= (long) chunk.getZ() * 132897987541L;
        return value ^ 0x5DEECE66DL;
    }

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
        if (floor.isAir() || floor.isLiquid()) return false;
        if ("overworld".equals(id) && y < 45) return false;
        if ("end".equals(id) && y < 30) return false;
        return true;
    }

    private void generate(String id, World world, int x, int y, int z, Random random) {
        int variant = random.nextInt(3);
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
