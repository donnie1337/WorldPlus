package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RtpCommand implements CommandExecutor, TabCompleter, Listener {
    private final WorldPlus plugin;
    private final RtpManager manager;
    private final NamespacedKey worldKey;

    public RtpCommand(WorldPlus plugin, RtpManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.worldKey = new NamespacedKey(plugin, "rtp-world");
    }

    public void openMenu(Player player) {
        int size = plugin.getConfig().getInt("rtp.gui.tamanho", 27);
        if (size < 9) size = 9;
        if (size > 54) size = 54;
        size = (size / 9) * 9;

        String title = plugin.getConfig().getString(
                "rtp.gui.titulo", "&8Teleporte Aleatório"
        );
        Inventory inventory = Bukkit.createInventory(null, size, color(title));

        String fillerMaterial = plugin.getConfig().getString("rtp.gui.preenchimento.material", "GRAY_STAINED_GLASS_PANE");
        String fillerName = plugin.getConfig().getString("rtp.gui.preenchimento.nome", " ");
        if (plugin.getConfig().getBoolean("rtp.gui.preenchimento.habilitado", true)) {
            Material filler = material(fillerMaterial, Material.GRAY_STAINED_GLASS_PANE);
            ItemStack fillerItem = new ItemStack(filler);
            ItemMeta fillerMeta = fillerItem.getItemMeta();
            if (fillerMeta != null) {
                fillerMeta.setDisplayName(color(fillerName));
                fillerItem.setItemMeta(fillerMeta);
            }
            for (int slot = 0; slot < size; slot++) {
                inventory.setItem(slot, fillerItem.clone());
            }
        }

        for (WorldSettings settings : plugin.getWorlds().values()) {
            String path = "rtp.gui.mundos." + settings.id();
            if (!plugin.getConfig().getBoolean(
                    "rtp.mundos." + settings.id() + ".habilitado", true)) {
                continue;
            }

            int slot = plugin.getConfig().getInt(path + ".slot", -1);
            if (slot < 0 || slot >= size) {
                continue;
            }

            String defaultMaterial = switch (settings.environment()) {
                case NETHER -> "NETHERRACK";
                case THE_END -> "END_STONE";
                default -> "GRASS_BLOCK";
            };

            Material material = material(
                    plugin.getConfig().getString(path + ".material", defaultMaterial),
                    Material.GRASS_BLOCK
            );

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            String defaultName = switch (settings.environment()) {
                case NETHER -> "&cNether";
                case THE_END -> "&5The End";
                default -> settings.id().equalsIgnoreCase("mineracao")
                        ? "&eMineração"
                        : "&aMundo Normal";
            };

            meta.setDisplayName(color(plugin.getConfig().getString(
                    path + ".nome", defaultName
            )));

            meta.getPersistentDataContainer().set(
                    worldKey, PersistentDataType.STRING, settings.id()
            );

            List<String> lore = plugin.getConfig().getStringList(path + ".lore");
            if (lore.isEmpty()) {
                lore = List.of(
                        "",
                        "&7Mundo: &f{mundo}",
                        "&7Tamanho do mundo: &f{tamanho} blocos",
                        "",
                        "&8Clique para se teleportar."
                );
            }

            List<String> finalLore = lore.stream()
                    .map(line -> line
                            .replace("{mundo}", displayWorldName(settings))
                            .replace("{id}", settings.id())
                            .replace("{tamanho}", format(settings.size()) + " x " + format(settings.size())))
                    .map(this::color)
                    .toList();

            meta.setLore(finalLore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }

        player.openInventory(inventory);
    }

    private Material material(String value, Material fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Material.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Material inválido no GUI do RTP: " + value);
            return fallback;
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = color(plugin.getConfig().getString("rtp.gui.titulo", "&8&lRTP • Escolha o mundo"));
        if (!event.getView().getTitle().equals(title)) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getDisplayName() == null) return;
        String id = item.getItemMeta().getPersistentDataContainer().get(worldKey, PersistentDataType.STRING);
        if (id != null && plugin.getSettings(id) != null) {
            // Fecha o GUI imediatamente no próprio evento de clique.
            // O pedido do RTP começa no mesmo tick, sem tarefa intermediária.
            player.closeInventory();
            manager.request(player, id);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("worldplus.rtp.use")) {
            sender.sendMessage(color(plugin.getConfig().getString("mensagens.sem-permissao-rtp", "&cVocê não tem permissão para usar o RTP.")));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cEste comando precisa ser executado por um jogador."));
            return true;
        }
        if (args.length == 0) {
            openMenu(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload") && sender.hasPermission("worldplus.rtp.admin")) {
            plugin.reloadConfig();
            sender.sendMessage(color("&aConfiguração do RTP recarregada."));
            return true;
        }
        String id = args[0].equalsIgnoreCase("world") ? "overworld" : args[0];
        if (plugin.getSettings(id) == null) {
            player.sendMessage(color("&cMundo de RTP não encontrado: &f" + args[0] + "&c."));
            return true;
        }
        manager.request(player, id);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        List<String> values = new ArrayList<>(plugin.getWorlds().keySet());
        if (sender.hasPermission("worldplus.rtp.admin")) values.add("reload");
        return values.stream().filter(v -> v.toLowerCase().startsWith(args[0].toLowerCase())).sorted().toList();
    }

    private String displayWorldName(WorldSettings settings) {
        if (settings.id().equalsIgnoreCase("overworld") || settings.name().equalsIgnoreCase("world")) {
            return "Overworld";
        }
        if (settings.id().equalsIgnoreCase("mineracao") || settings.name().equalsIgnoreCase("mining")) {
            return "Mineração";
        }
        if (settings.id().equalsIgnoreCase("end")
                || settings.name().equalsIgnoreCase("world_the_end")
                || settings.environment() == org.bukkit.World.Environment.THE_END) {
            return "End";
        }

        String name = settings.name();
        if (name == null || name.isBlank()) {
            return settings.id();
        }

        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
