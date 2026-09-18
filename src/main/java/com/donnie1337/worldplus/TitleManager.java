package com.donnie1337.worldplus;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TitleManager implements Listener {
    private final WorldPlus plugin;
    private final Map<UUID, String> lastBiomes = new HashMap<>();
    private final Map<UUID, Boolean> firstRtpTitleShown = new HashMap<>();

    public TitleManager(WorldPlus plugin) {
        this.plugin = plugin;
    }

    public void showRtpPreparing(Player player) {
        if (!plugin.getConfig().getBoolean("titles.rtp-preparando.habilitado", true)
                || firstRtpTitleShown.getOrDefault(player.getUniqueId(), false)) {
            return;
        }

        firstRtpTitleShown.put(player.getUniqueId(), true);
        sendTitle(player,
                plugin.getConfig().getString("titles.rtp-preparando.titulo", "&b&lᴛᴇʟᴇᴘᴏʀᴛᴇ"),
                plugin.getConfig().getString("titles.rtp-preparando.subtitulo", "&7Preparando seu destino..."),
                "titles.rtp-preparando");
    }

    public void showBiome(Player player, Location location) {
        if (!plugin.getConfig().getBoolean("titles.bioma.habilitado", true) || location == null) return;

        Biome biome = location.getWorld().getBiome(location);
        UUID uuid = player.getUniqueId();
        String key = biome.getKey().toString();

        if (key.equals(lastBiomes.get(uuid))) return;
        lastBiomes.put(uuid, key);
        sendBiomeTitle(player, biome);
    }

    public void showBiomeAfterRtp(Player player, Location location) {
        if (location == null) return;
        Biome biome = location.getWorld().getBiome(location);
        lastBiomes.put(player.getUniqueId(), biome.getKey().toString());
        sendBiomeTitle(player, biome);
    }

    private void sendBiomeTitle(Player player, Biome biome) {
        String biomeName = displayName(biome);
        String article = article(biome);
        String title = plugin.getConfig().getString("titles.bioma.titulo", "&a&l{bioma}");
        String subtitle = plugin.getConfig().getString("titles.bioma.subtitulo",
                "&7Você está {artigo} &f{bioma}");

        sendTitle(player,
                title.replace("{bioma}", biomeName),
                subtitle.replace("{bioma}", biomeName).replace("{artigo}", article),
                "titles.bioma");
    }

    private void sendTitle(Player player, String title, String subtitle, String path) {
        int fadeIn = plugin.getConfig().getInt(path + ".fade-in", 10);
        int stay = plugin.getConfig().getInt(path + ".duracao", 40);
        int fadeOut = plugin.getConfig().getInt(path + ".fade-out", 10);
        player.sendTitle(color(title), color(subtitle), fadeIn, stay, fadeOut);
    }

    private String displayName(Biome biome) {
        String configured = plugin.getConfig().getString("titles.biomas." + biome.getKey().getKey());
        if (configured != null && !configured.isBlank()) return color(configured);

        String raw = biome.getKey().getKey().replace('_', ' ').toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private String article(Biome biome) {
        String configured = plugin.getConfig().getString("titles.artigos." + biome.getKey().getKey());
        if (configured != null && !configured.isBlank()) return configured;

        return switch (biome.getKey().getKey()) {
            case "savanna", "plains", "forest", "birch_forest", "dark_forest",
                 "jungle", "sparse_jungle", "bamboo_jungle", "swamp", "mangrove_swamp",
                 "taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
                 "flower_forest", "meadow", "cherry_grove", "grove", "snowy_plains",
                 "sunflower_plains", "ice_spikes", "beach", "stony_shore" -> "na";
            case "windswept_hills", "windswept_forest", "windswept_gravelly_hills",
                 "windswept_savanna", "mountains", "jagged_peaks", "frozen_peaks",
                 "stony_peaks", "dripstone_caves", "lush_caves", "deep_dark" -> "nas";
            default -> "no";
        };
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastBiomes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        showBiome(event.getPlayer(), to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastBiomes.remove(uuid);
        firstRtpTitleShown.remove(uuid);
    }
}
