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
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TitleManager implements Listener {
    private final WorldPlus plugin;
    private final Map<UUID, String> lastBiomes = new HashMap<>();
    private final Map<UUID, Boolean> firstRtpTitleShown = new HashMap<>();
    private final Map<UUID, Boolean> rtpTitleActive = new HashMap<>();
    private static final Set<String> IMPORTANT_BIOMES = Set.of(
            "plains", "sunflower_plains", "forest", "flower_forest", "dark_forest",
            "taiga", "snowy_plains", "cherry_grove", "jungle", "bamboo_jungle",
            "swamp", "mangrove_swamp", "savanna", "desert", "badlands",
            "meadow", "windswept_hills", "snowy_slopes", "ocean", "mushroom_fields",
            "lush_caves", "dripstone_caves", "deep_dark", "pale_garden",
            "ice_spikes", "frozen_ocean", "warm_ocean", "deep_ocean"
    );

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

    public void showRtpLoading(Player player, int stayTicks) {
        if (!plugin.getConfig().getBoolean("titles.rtp-preparando.habilitado", true)) return;

        rtpTitleActive.put(player.getUniqueId(), true);

        int fadeIn = plugin.getConfig().getInt("titles.rtp-preparando.fade-in", 10);
        int fadeOut = plugin.getConfig().getInt("titles.rtp-preparando.fade-out", 10);
        sendTitle(player,
                plugin.getConfig().getString("titles.rtp-preparando.titulo", "&b&lᴛᴇʟᴇᴘᴏʀᴛᴇ"),
                plugin.getConfig().getString("titles.rtp-preparando.subtitulo", "&7Preparando seu destino..."),
                fadeIn, 72000, fadeOut);
    }

    public void showBiome(Player player, Location location) {
        if (rtpTitleActive.getOrDefault(player.getUniqueId(), false)) return;
        if (!plugin.getConfig().getBoolean("titles.bioma.habilitado", true) || location == null) return;

        Biome biome = location.getWorld().getBiome(location);
        UUID uuid = player.getUniqueId();
        String key = biome.getKey().toString();

        if (key.equals(lastBiomes.get(uuid))) return;
        lastBiomes.put(uuid, key);
        if (!IMPORTANT_BIOMES.contains(biome.getKey().getKey())) return;
        sendBiomeTitle(player, biome);
    }

    public void endRtpTitle(Player player) {
        if (player == null) return;
        rtpTitleActive.remove(player.getUniqueId());
        player.resetTitle();
    }

    public void showBiomeAfterRtp(Player player, Location location) {
        endRtpTitle(player);
        if (location == null) return;
        Biome biome = location.getWorld().getBiome(location);
        lastBiomes.put(player.getUniqueId(), biome.getKey().toString());
        sendBiomeTitle(player, biome);
    }

    private void sendBiomeTitle(Player player, Biome biome) {
        String biomeKey = biome.getKey().getKey();
        String biomeName = displayName(biome);
        String article = article(biome);

        String message = plugin.getConfig().getString(
                "titles.bioma." + biomeKey + ".mensagem");

        if (message == null || message.isBlank()) {
            message = plugin.getConfig().getString(
                    "titles.bioma.mensagem-padrao",
                    "&fVocê está {artigo} {bioma}");
        }

        message = message.replace("{bioma}", biomeName)
                .replace("{artigo}", article);

        sendTitle(player, message, "", "titles.bioma");
    }
    private void sendTitle(Player player, String title, String subtitle, String path) {
        int fadeIn = plugin.getConfig().getInt(path + ".fade-in", 10);
        int stay = plugin.getConfig().getInt(path + ".duracao", 40);
        int fadeOut = plugin.getConfig().getInt(path + ".fade-out", 10);
        sendTitle(player, title, subtitle, fadeIn, stay, fadeOut);
    }

    private void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.sendTitle(color(title), color(subtitle), fadeIn, stay, fadeOut);
    }

    private String displayName(Biome biome) {
        String configured = plugin.getConfig().getString(
                "titles.bioma." + biome.getKey().getKey() + ".nome");
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
        String configured = plugin.getConfig().getString(
                "titles.bioma." + biome.getKey().getKey() + ".artigo");
        if (configured != null && !configured.isBlank()) return configured;
        return plugin.getConfig().getString("titles.bioma.artigo-padrao", "no");
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
        rtpTitleActive.remove(uuid);
    }
}
