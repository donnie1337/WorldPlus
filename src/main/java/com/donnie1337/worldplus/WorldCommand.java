package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
            case "construcoes", "estruturas" -> {
                if (!sender.hasPermission("worldplus.use")) return noPermission(sender);

                String id;
                if (args.length >= 2) {
                    id = args[1].toLowerCase();
                } else if (sender instanceof Player player) {
                    id = findCurrentWorldId(player);
                    if (id == null) {
                        sender.sendMessage(color("&eVocê não está em um mundo configurado. Use &f/mundos construcoes <mundo>&e."));
                        return true;
                    }
                } else {
                    sender.sendMessage(color("&eInforme o mundo: &f/mundos construcoes <mundo>&e."));
                    return true;
                }

                WorldSettings settings = plugin.getSettings(id);
                if (settings == null) return notFound(sender, id);
                World world = Bukkit.getWorld(settings.name());
                if (world == null) {
                    sender.sendMessage(color("&eO mundo &f" + settings.id()
                            + " &eainda não está carregado."));
                    return true;
                }

                List<WorldStructureGenerator.GeneratedStructure> structures =
                        plugin.getStructureGenerator() == null
                                ? List.of()
                                : plugin.getStructureGenerator().getGeneratedStructures(world);
                if (structures.isEmpty()) {
                    sender.sendMessage(color("&eNenhuma construção foi registrada ainda em &f"
                            + settings.id() + "&e. A geração pode ainda estar em andamento."));
                    return true;
                }

                Player player = sender instanceof Player p ? p : null;
                structures = structures.stream()
                        .sorted(Comparator.comparingDouble(structure ->
                                distanceSquared(player, world, structure.x(), structure.z())))
                        .toList();

                sender.sendMessage(color("&7&lᴡᴏʀʟᴅᴘʟᴜs &8• &fConstruções em " + settings.id() + ":"));
                for (int index = 0; index < structures.size(); index++) {
                    WorldStructureGenerator.GeneratedStructure structure = structures.get(index);
                    String distance = player != null && player.getWorld().equals(world)
                            ? " &8(" + format(Math.sqrt(distanceSquared(player, world,
                            structure.x(), structure.z()))) + " blocos)"
                            : "";
                    sender.sendMessage(color("&8• &e" + (index + 1) + ". &f"
                            + structureDisplayName(structure.name()) + " &8→ &7"
                            + structure.x() + ", " + structure.y() + ", " + structure.z() + distance));
                }
                return true;
            }
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
            case "borda", "border" -> {
                if (!sender.hasPermission("worldplus.teleport")) return noPermission(sender);
                if (!(sender instanceof Player player)) return jogadorApenas(sender);

                String worldId = findCurrentWorldId(player);
                String direction = null;
                if (args.length >= 2) {
                    if (isDirection(args[1])) {
                        direction = args[1];
                    } else {
                        worldId = args[1];
                    }
                }
                if (args.length >= 3) {
                    if (!isDirection(args[2])) return usoBorda(sender);
                    direction = args[2];
                }

                if (worldId == null) return notFound(sender, "");
                WorldSettings settings = plugin.getSettings(worldId);
                if (settings == null) return notFound(sender, worldId);

                World world = Bukkit.getWorld(settings.name());
                if (world == null) world = plugin.createOrLoadWorld(settings);
                if (world == null) return error(sender, settings.id());

                Location target = borderLocation(player, world, direction);
                if (target == null) {
                    player.sendMessage(color(msg("borda-sem-local", "&cNão foi encontrado um local seguro próximo à borda de &f{id}&c.")
                            .replace("{id}", settings.id())));
                    return true;
                }

                player.teleport(target);
                player.sendMessage(color(msg("borda-teleporte", "&aTeleportado para a borda de &f{id}&a.")
                        .replace("{id}", settings.id())));
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
            case "excluir", "delete", "remover" -> {
                if (!sender.hasPermission("worldplus.admin")) return noPermission(sender);
                if (args.length < 2) return usage(sender);

                WorldSettings s = plugin.getSettings(args[1]);
                if (s == null) return notFound(sender, args[1]);

                if (s.id().equalsIgnoreCase("overworld") || s.name().equalsIgnoreCase("world")) {
                    sender.sendMessage(color(msg("mundo-protegido", "&cO mundo principal não pode ser excluído pelo WorldPlus.")));
                    return true;
                }

                if (args.length < 3 || !args[2].equalsIgnoreCase("confirmar")) {
                    sender.sendMessage(color(msg("confirmar-exclusao",
                            "&cAtenção: isso excluirá permanentemente o mundo &f{id}&c. Use &f/mundos excluir {id} confirmar &cpara confirmar.")
                            .replace("{id}", s.id())));
                    return true;
                }

                boolean deleted = plugin.deleteWorld(s);
                if (deleted) {
                    sender.sendMessage(color(msg("mundo-excluido", "&aMundo &f{id} &aexcluído permanentemente.").replace("{id}", s.id())));
                } else {
                    sender.sendMessage(color(msg("erro-exclusao", "&cNão foi possível excluir o mundo &f{id}&c. Verifique se o servidor conseguiu descarregar os arquivos.")
                            .replace("{id}", s.id())));
                }
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
            case "reset" -> {
                if (!sender.hasPermission("worldplus.admin")) return noPermission(sender);
                if (args.length < 2) return usage(sender);
                WorldSettings settings = plugin.getSettings(args[1]);
                if (settings == null) return notFound(sender, args[1]);
                if (settings.id().equalsIgnoreCase("overworld") || settings.name().equalsIgnoreCase("world")) {
                    sender.sendMessage(color("&cO Overworld não pode ser resetado."));
                    return true;
                }
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirmar")) {
                    sender.sendMessage(color("&eUse &f/mundos reset " + settings.id() + " confirmar &epara resetar este mundo agora."));
                    return true;
                }
                boolean reset = plugin.getWorldResetManager() != null && plugin.getWorldResetManager().reset(settings.id());
                sender.sendMessage(color(reset ? "&aMundo &f" + settings.id() + " &aresetado com sucesso."
                        : "&cNão foi possível resetar o mundo &f" + settings.id() + "&c."));
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    private double distanceSquared(Player player, World world, int x, int z) {
        if (player == null || !player.getWorld().equals(world)) return Double.MAX_VALUE;
        double dx = player.getLocation().getX() - x;
        double dz = player.getLocation().getZ() - z;
        return dx * dx + dz * dz;
    }

    private String structureDisplayName(String id) {
        return switch (id) {
            case "posto-avancado" -> "Posto Avançado";
            case "ruina-antiga" -> "Ruína Antiga";
            case "templo-da-floresta" -> "Templo da Floresta";
            case "mina-abandonada" -> "Mina Abandonada";
            case "pedreira" -> "Pedreira";
            case "cidade-subterranea" -> "Cidade Subterrânea";
            case "acampamento-piglin" -> "Acampamento Piglin";
            case "santuario-das-almas" -> "Santuário das Almas";
            case "arena-do-nether" -> "Arena do Nether";
            case "torre-do-end" -> "Torre do End";
            case "templo-do-vazio" -> "Templo do Vazio";
            case "santuario-dos-shulkers" -> "Santuário dos Shulkers";
            default -> id;
        };
    }

    private String findCurrentWorldId(Player player) {
        for (WorldSettings s : plugin.getWorlds().values())
            if (s.name().equalsIgnoreCase(player.getWorld().getName())) return s.id();
        return null;
    }

    private Location borderLocation(Player player, World world, String direction) {
        org.bukkit.WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2.0D;
        double minX = center.getX() - half;
        double maxX = center.getX() + half;
        double minZ = center.getZ() - half;
        double maxZ = center.getZ() + half;
        double margin = 5.0D;

        String side = direction == null ? nearestSide(player.getLocation(), minX, maxX, minZ, maxZ) : normalizeDirection(direction);
        double x = Math.max(minX + margin, Math.min(maxX - margin, player.getLocation().getX()));
        double z = Math.max(minZ + margin, Math.min(maxZ - margin, player.getLocation().getZ()));

        switch (side) {
            case "norte" -> z = minZ + margin;
            case "sul" -> z = maxZ - margin;
            case "leste" -> x = maxX - margin;
            case "oeste" -> x = minX + margin;
            default -> { return null; }
        }

        Location safe = findSafeBorderLocation(world, x, z, side);
        if (safe == null) return null;

        float yaw = switch (side) {
            case "norte" -> 180.0F;
            case "sul" -> 0.0F;
            case "leste" -> 270.0F;
            case "oeste" -> 90.0F;
            default -> player.getLocation().getYaw();
        };
        safe.setYaw(yaw);
        safe.setPitch(0.0F);
        return safe;
    }

    private String nearestSide(Location location, double minX, double maxX, double minZ, double maxZ) {
        double north = Math.abs(location.getZ() - minZ);
        double south = Math.abs(maxZ - location.getZ());
        double west = Math.abs(location.getX() - minX);
        double east = Math.abs(maxX - location.getX());
        double minimum = Math.min(Math.min(north, south), Math.min(west, east));
        if (minimum == north) return "norte";
        if (minimum == south) return "sul";
        if (minimum == east) return "leste";
        return "oeste";
    }

    private Location findSafeBorderLocation(World world, double x, double z, String side) {
        for (int offset = 0; offset <= 64; offset += 4) {
            for (int lateral = -offset; lateral <= offset; lateral += 4) {
                double candidateX = x;
                double candidateZ = z;
                if (side.equals("norte") || side.equals("sul")) {
                    candidateX += lateral;
                    if (offset > 0) candidateZ += side.equals("norte") ? offset : -offset;
                } else {
                    candidateZ += lateral;
                    if (offset > 0) candidateX += side.equals("oeste") ? offset : -offset;
                }

                org.bukkit.WorldBorder border = world.getWorldBorder();
                if (!border.isInside(new Location(world, candidateX, 0, candidateZ))) continue;

                int blockX = Location.locToBlock(candidateX);
                int blockZ = Location.locToBlock(candidateZ);
                int top = world.getMaxHeight() - 2;
                int bottom = world.getMinHeight() + 1;

                for (int y = top; y >= bottom; y--) {
                    Material floor = world.getBlockAt(blockX, y, blockZ).getType();
                    Material feet = world.getBlockAt(blockX, y + 1, blockZ).getType();
                    Material head = world.getBlockAt(blockX, y + 2, blockZ).getType();

                    if (!floor.isSolid() || floor == Material.BEDROCK) continue;
                    if (!isSafeAir(feet) || !isSafeAir(head)) continue;

                    return new Location(world, blockX + 0.5D, y + 1.0D, blockZ + 0.5D);
                }
            }
        }
        return null;
    }

    private boolean isSafeAir(Material material) {
        return material.isAir();
    }

    private String normalizeDirection(String value) {
        return switch (value.toLowerCase()) {
            case "norte", "north" -> "norte";
            case "sul", "south" -> "sul";
            case "leste", "east" -> "leste";
            case "oeste", "west" -> "oeste";
            default -> value.toLowerCase();
        };
    }

    private boolean isDirection(String value) {
        return value != null && switch (value.toLowerCase()) {
            case "norte", "north", "sul", "south", "leste", "east", "oeste", "west" -> true;
            default -> false;
        };
    }

    private boolean usoBorda(CommandSender sender) {
        sender.sendMessage(color(msg("uso-borda", "&cUso: /mundos borda [mundo] [norte|sul|leste|oeste]")));
        return true;
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
        sender.sendMessage(color(msg("uso", "&cUso: /mundos <lista|info|construcoes|tp|borda|spawn|setspawn|criar|excluir|reset|recarregar>")));
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
        if (args.length == 1) return partial(List.of("lista", "info", "construcoes", "tp", "borda", "spawn", "setspawn", "criar", "excluir", "reset", "recarregar"), args[0]);
        if (args.length == 2 && List.of("info", "construcoes", "tp", "borda", "spawn", "setspawn", "criar", "excluir", "reset").contains(args[0].toLowerCase())) {
            if (args[0].equalsIgnoreCase("borda")) {
                List<String> values = new ArrayList<>(plugin.getWorlds().keySet());
                values.addAll(List.of("norte", "sul", "leste", "oeste"));
                return partial(values, args[1]);
            }
            return partial(new ArrayList<>(plugin.getWorlds().keySet()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("borda")) {
            return partial(List.of("norte", "sul", "leste", "oeste"), args[2]);
        }
        if (args.length == 3 && List.of("excluir", "reset").contains(args[0].toLowerCase())) {
            return partial(List.of("confirmar"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("tp") && sender.hasPermission("worldplus.admin"))
            return partial(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        return Collections.emptyList();
    }

    private List<String> partial(List<String> values, String input) {
        return values.stream().filter(v -> v.toLowerCase().startsWith(input.toLowerCase())).sorted().toList();
    }

    private String color(String message) { return ChatColor.translateAlternateColorCodes('&', message); }
}
