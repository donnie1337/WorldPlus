package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RtpCommand implements CommandExecutor, TabCompleter, Listener {
    private final WorldPlus plugin;
    private final RtpManager manager;

    public RtpCommand(WorldPlus plugin, RtpManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openMenu(Player player) {
        int size = Math.max(9, Math.min(54, ((plugin.getWorlds().size() + 8) / 9) * 9));
        Inventory inventory = Bukkit.createInventory(null, size, color(plugin.getConfig().getString("rtp.gui.titulo", "&8&lRTP • Escolha o mundo")));
        int slot = 0;
        for (WorldSettings settings : plugin.getWorlds().values()) {
            if (!plugin.getConfig().getBoolean("rtp.mundos." + settings.id() + ".habilitado", true)) continue;
            Material material = switch (settings.environment()) {
                case NETHER -> Material.NETHERRACK;
                case THE_END -> Material.END_STONE;
                default -> Material.GRASS_BLOCK;
            };
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(color("&b" + settings.id()));
                meta.setLore(List.of(
                        color("&7Mundo: &f" + settings.name()),
                        color("&7Raio: &f" + format(plugin.getConfig().getDouble("rtp.mundos." + settings.id() + ".raio-maximo", settings.size() / 2))),
                        color("&aClique para usar o RTP")
                ));
                item.setItemMeta(meta);
            }
            inventory.setItem(slot++, item);
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = color(plugin.getConfig().getString("rtp.gui.titulo", "&8&lRTP • Escolha o mundo"));
        if (!event.getView().getTitle().equals(title)) return;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getDisplayName() == null) return;
        String id = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (plugin.getSettings(id) != null) {
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

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
