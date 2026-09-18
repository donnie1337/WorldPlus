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

    public WorldCommand(WorldPlus plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("lista")) {
            if (!sender.hasPermission("worldplus.use")) return noPermission(sender);
            sender.sendMessage(color("&7&lᴡᴏʀʟᴅᴘʟᴜs &8• &r&fMundos configurados:"));
            for (WorldSettings settings : plugin.getWorlds().values()) {
                boolean loaded = Bukkit.getWorld(settings.name()) != null;
                sender.sendMessage(color("&8• &f" + settings.id() + " &8→ &7" + settings.name()
                        + " &8[" + (loaded ? "&aCARREGADO" : "&cNÃO CARREGADO") + "&8]"));
            }
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "info" -> {
                if (!sender.hasPermission("worldplus.use")) return noPermission(sender);
                if (args.length < 2) return usage(sender);
                WorldSettings s = plugin.getSettings(args[1]);
                if (s == null) return notFound(sender, args[1]);
                World world = Bukkit.getWorld(s.name());
                sender.sendMessage(color(msg("info-cabecalho", "&7&lᴡᴏʀʟᴅᴘʟᴜs &8• &f{id}").replace("{id}", s.id())));
                sender.sendMessage(color(msg("info-linha", "&8• &7Nome: &f{name} &8| &7Ambiente: &f{environment} &8| &7Borda: &f{size}")
                        .replace("{name}", s.name()).replace("{environment}", s.environment().name()).replace("{size}", format(s.size()))));
                sender.sendMessage(color(msg("info-status", "&8• &7Status: {status}")
                        .replace("{status}", world == null ? "&cNÃO CARREGADO" : "&aCARREGADO")));
                sender.sendMessage(color(msg("info-regras", "&8• &7PVP: {pvp} &8| &7Inventário: {inventory} &8| &7Dificuldade: &f{difficulty}")
                        .replace("{pvp}", s.pvp() ? "&aATIVO" : "&cDESATIVADO")
                        .replace("{inventory}", s.keepInventory() ? "&aPRESERVADO" : "&cNORMAL")
                        .replace("{difficulty}", s.difficulty().name())));
                return true;
            }
            case "tp", "teleportar" -> {
                if (!sender.hasPermission("worldplus.teleport")) return noPermission(sender);
                if (args.length < 2) return usage(sender);
                WorldSettings s = plugin.getSettings(args[1]);
                if (s == null) return notFound(sender, args[1]);
                World world = Bukkit.getWorld(s.name());
                if (world == null) world = plugin.createOrLoadWorld(s);
                if (world == null) return error(sender, s.id());
                Player target = sender instanceof Player player ? player : null;
                if (args.length >= 3) {
                    if (!sender.hasPermission("worldplus.admin")) return noPermission(sender);
                    target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) {
                        sender.sendMessage(color("&cJogador não encontrado: &f" + args[2] + "&c."));
                        return true;
                    }
                }
                if (target == null) {
                    sender.sendMessage(color(msg("jogador-apenas", "&cEste comando precisa ser executado por um jogador.")));
                    return true;
                }
                target.teleport(world.getSpawnLocation());
                target.sendMessage(color(msg("teleporte", "&aTeleportado para &f{id}&a.").replace("{id}", s.id())));
                if (target != sender) sender.sendMessage(color("&aJogador &f" + target.getName() + " &ateleportado para &f" + s.id() + "&a."));
                return true;
            }
            case "spawn" -> {
                if (!sender.hasPermission("worldplus.teleport")) return noPermission(sender);
                if (!(sender instanceof Player player)) return jogadorApenas(sender);
                String id = args.length >= 2 ? args[1] : findCurrentWorldId(player);
                if (id == null) return notFound(sender, "");
                WorldSettings s = plugin.getSettings(id);
                if (s == null) return notFound(sender, id);
                World world = Bukkit.getWorld(s.name());
                if (world == null) world = plugin.createOrLoadWorld(s);
                if (world == null) return error(sender, s.id());
                player.teleport(world.getSpawnLocation());
                player.sendMessage(color(msg("teleporte", "&aTeleportado para &f{id}&a.").replace("{id}", s.id())));
                return true;
            }
            case "setspawn" -> {
                if (!sender.hasPermission("worldplus.admin")) return noPermission(sender);
                if (!(sender instanceof Player player)) return jogadorApenas(sender);
                if (args.length < 2) return usage(sender);
                WorldSettings s = plugin.getSettings(args[1]);
                if (s == null) return notFound(sender, args[1]);
                World world = Bukkit.getWorld(s.name());
                if (world == null) world = plugin.createOrLoadWorld(s);
                if (world == null) return error(sender, s.id());
                plugin.setSpawn(s, world, player.getLocation());
                player.sendMessage(color(msg("spawn-definido", "&aSpawn do mundo &f{id} &adefinido na sua posição.").replace("{id}", s.id())));
                return true;
            }
            case "criar" -> {
                if (!sender.hasPermission("worldplus.admin")) return noPermission(sender);
                if (args.length < 2) return usage(sender);
                WorldSettings s = plugin.getSettings(args[1]);
                if (s == null) return notFound(sender, args[1]);
                if (Bukkit.getWorld(s.name()) != null) {
                    sender.sendMessage(color(msg("mundo-ja-carregado", "&eMundo &f{id} &ejá está carregado.").replace("{id}", s.id())));
                    return true;
                }
                World world = plugin.createOrLoadWorld(s);
                sender.sendMessage(color(msg(world == null ? "erro" : "mundo-criado",
                        "&cNão foi possível criar/carregar o mundo &f{id}&c.").replace("{id}", s.id())));
                return true;
            }
            case "recarregar", "reload" -> {
                if (!sender.hasPermission("worldplus.admin")) return noPermission(sender);
                plugin.reloadConfig();
                plugin.loadWorldSettings();
                if (plugin.getConfig().getBoolean("configuracao.aplicar-configuracoes-ao-carregar", true)) {
                    for (WorldSettings s : plugin.getWorlds().values()) {
                        World world = Bukkit.getWorld(s.name());
                        if (world != null) plugin.applySettings(world, s);
                    }
                }
                sender.sendMessage(color(msg("recarregado", "&aConfiguração recarregada.")));
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    private String findCurrentWorldId(Player player) {
        for (WorldSettings s : plugin.getWorlds().values())
            if (s.name().equalsIgnoreCase(player.getWorld().getName())) return s.id();
        return null;
    }

    private boolean noPermission(CommandSender sender) {
        sender.sendMessage(color(msg("sem-permissao", "&cVocê não tem permissão para executar este comando.")));
        return true;
    }

    private boolean jogadorApenas(CommandSender sender) {
        sender.sendMessage(color(msg("jogador-apenas", "&cEste comando precisa ser executado por um jogador.")));
        return true;
    }

    private boolean notFound(CommandSender sender, String id) {
        sender.sendMessage(color(msg("mundo-nao-encontrado", "&cMundo configurado não encontrado: &f{id}&c.").replace("{id}", id)));
        return true;
    }

    private boolean error(CommandSender sender, String id) {
        sender.sendMessage(color(msg("erro", "&cNão foi possível criar/carregar o mundo &f{id}&c.").replace("{id}", id)));
        return true;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(color(msg("uso", "&cUso: /mundos <lista|info|tp|spawn|setspawn|criar|recarregar>")));
        return true;
    }

    private String msg(String path, String fallback) {
        return plugin.getConfig().getString("mensagens." + path, fallback);
    }

    private String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("worldplus.use")) return Collections.emptyList();
        if (args.length == 1) return partial(List.of("lista", "info", "tp", "spawn", "setspawn", "criar", "recarregar"), args[0]);
        if (args.length == 2 && List.of("info", "tp", "spawn", "setspawn", "criar").contains(args[0].toLowerCase()))
            return partial(new ArrayList<>(plugin.getWorlds().keySet()), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("tp") && sender.hasPermission("worldplus.admin"))
            return partial(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        return Collections.emptyList();
    }

    private List<String> partial(List<String> values, String input) {
        return values.stream().filter(v -> v.toLowerCase().startsWith(input.toLowerCase())).sorted().toList();
    }

    private String color(String message) { return ChatColor.translateAlternateColorCodes('&', message); }
}
