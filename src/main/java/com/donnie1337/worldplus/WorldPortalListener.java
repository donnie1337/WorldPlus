package com.donnie1337.worldplus;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class WorldPortalListener implements Listener {
    private final WorldPlus plugin;

    public WorldPortalListener(WorldPlus plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            handleNetherPortal(event);
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            handleEndPortal(event);
        }
    }

    private void handleNetherPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        World current = player.getWorld();

        World overworld = plugin.getDimensionWorld("overworld", World.Environment.NORMAL);
        World nether = plugin.getDimensionWorld("nether", World.Environment.NETHER);

        if (overworld == null || nether == null) {
            plugin.getLogger().warning("Não foi possível conectar o portal do Nether: overworld ou nether não está disponível.");
            return;
        }

        if (current.getUID().equals(overworld.getUID())) {
            Location target = scaleNether(event.getFrom(), nether);
            event.setTo(target);
            event.setCanCreatePortal(true);
            return;
        }

        if (current.getUID().equals(nether.getUID())) {
            Location target = scaleOverworld(event.getFrom(), overworld);
            event.setTo(target);
            event.setCanCreatePortal(true);
        }
    }

    private void handleEndPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        World current = player.getWorld();

        World overworld = plugin.getDimensionWorld("overworld", World.Environment.NORMAL);
        World end = plugin.getDimensionWorld("end", World.Environment.THE_END);

        if (overworld == null || end == null) {
            plugin.getLogger().warning("Não foi possível conectar o portal do End: overworld ou end não está disponível.");
            return;
        }

        if (current.getUID().equals(overworld.getUID())) {
            event.setTo(end.getSpawnLocation());
            event.setCanCreatePortal(true);
            return;
        }

        if (current.getUID().equals(end.getUID())) {
            event.setTo(overworld.getSpawnLocation());
            event.setCanCreatePortal(false);
        }
    }

    private Location scaleNether(Location from, World targetWorld) {
        double x = clamp(from.getX() / 8.0D, targetWorld.getWorldBorder().getSize() / 2.0D);
        double z = clamp(from.getZ() / 8.0D, targetWorld.getWorldBorder().getSize() / 2.0D);
        return new Location(targetWorld, x, safeY(from.getY()), z, from.getYaw(), from.getPitch());
    }

    private Location scaleOverworld(Location from, World targetWorld) {
        double x = clamp(from.getX() * 8.0D, targetWorld.getWorldBorder().getSize() / 2.0D);
        double z = clamp(from.getZ() * 8.0D, targetWorld.getWorldBorder().getSize() / 2.0D);
        return new Location(targetWorld, x, safeY(from.getY()), z, from.getYaw(), from.getPitch());
    }

    private double clamp(double coordinate, double halfBorder) {
        double limit = Math.max(1.0D, halfBorder - 16.0D);
        return Math.max(-limit, Math.min(limit, coordinate));
    }

    private double safeY(double y) {
        return Math.max(-64.0D, Math.min(320.0D, y));
    }
}
