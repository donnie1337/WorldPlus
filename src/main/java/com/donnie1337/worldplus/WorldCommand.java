package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WorldCommand implements CommandExecutor, TabCompleter {

    private final WorldPlus plugin;

    public WorldCommand(WorldPlus plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("worldplus.admin")) {
            sender.sendMessage(color(plugin.getConfig().getString(
                    "mensagens.sem-permissao",
                    "&cVocê não tem permissão para executar este comando."
            )));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("lista")) {
            sender.sendMessage(color("&7&lᴡᴏʀʟᴅᴘʟᴜs &8• &r&fMundos configurados:"));

            for (WorldSettings settings : plugin.getWorlds().values()) {
                boolean loaded = Bukkit.getWorld(settings.name()) != null;
                String status = loaded ? "&aCARREGADO" : "&cNÃO CARREGADO";

                sender.sendMessage(color("&8• &f" + settings.id()
                        + " &8→ &7" + settings.name()
                        + " &8[" + status + "&8]"));
            }

            return true;
        }

        switch (args[0].toLowerCase()) {
            case "criar" -> {
                if (args.length < 2) {
                    sender.sendMessage(color(plugin.getConfig().getString(
                            "mensagens.uso",
                            "&cUso: /mundos <lista|criar|tp|recarregar>"
                    )));
                    return true;
                }

                WorldSettings settings = plugin.getSettings(args[1]);

                if (settings == null) {
                    sender.sendMessage(color(plugin.getConfig().getString(
                            "mensagens.mundo-nao-encontrado",
                            "&cMundo configurado não encontrado: &f{id}&c."
                    ).replace("{id}", args[1])));
                    return true;
                }

                if (Bukkit.getWorld(settings.name()) != null) {
                    sender.sendMessage(color(plugin.getConfig().getString(
                            "mensagens.mundo-ja-carregado",
                            "&eMundo &f{id} &ejá está carregado."
                    ).replace("{id}", settings.id())));
                    return true;
                }

                World world = plugin.createOrLoadWorld(settings);

                sender.sendMessage(color(plugin.getConfig().getString(
                        world == null ? "mensagens.erro" : "mensagens.mundo-criado",
                        "&cNão foi possível criar/carregar o mundo &f{id}&c."
                ).replace("{id}", settings.id())));

                return true;
            }

            case "tp" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(color(plugin.getConfig().getString(
                            "mensagens.jogador-apenas",
                            "&cEste comando precisa ser executado por um jogador."
                    )));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(color(plugin.getConfig().getString(
                            "mensagens.uso",
                            "&cUso: /mundos <lista|criar|tp|recarregar>"
                    )));
                    return true;
                }

                WorldSettings settings = plugin.getSettings(args[1]);

                if (settings == null) {
                    player.sendMessage(color(plugin.getConfig().getString(
                            "mensagens.mundo-nao-encontrado",
                            "&cMundo configurado não encontrado: &f{id}&c."
                    ).replace("{id}", args[1])));
                    return true;
                }

                World world = Bukkit.getWorld(settings.name());
                if (world == null) {
                    world = plugin.createOrLoadWorld(settings);
                }

                if (world == null) {
                    player.sendMessage(color(plugin.getConfig().getString(
                            "mensagens.erro",
                            "&cNão foi possível criar/carregar o mundo &f{id}&c."
                    ).replace("{id}", settings.id())));
                    return true;
                }

                player.teleport(world.getSpawnLocation());

                player.sendMessage(color(plugin.getConfig().getString(
                        "mensagens.teleporte",
                        "&aTeleportado para &f{id}&a."
                ).replace("{id}", settings.id())));

                return true;
            }

            case "recarregar" -> {
                plugin.reloadConfig();
                plugin.loadWorldSettings();

                sender.sendMessage(color(plugin.getConfig().getString(
                        "mensagens.recarregado",
                        "&aConfiguração recarregada."
                )));

                return true;
            }

            default -> {
                sender.sendMessage(color(plugin.getConfig().getString(
                        "mensagens.uso",
                        "&cUso: /mundos <lista|criar|tp|recarregar>"
                )));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("worldplus.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return partial(List.of("lista", "criar", "tp", "recarregar"), args[0]);
        }

        if (args.length == 2 &&
                (args[0].equalsIgnoreCase("criar") || args[0].equalsIgnoreCase("tp"))) {
            return partial(new ArrayList<>(plugin.getWorlds().keySet()), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> partial(List<String> values, String input) {
        return values.stream()
                .filter(value -> value.toLowerCase().startsWith(input.toLowerCase()))
                .sorted()
                .toList();
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
