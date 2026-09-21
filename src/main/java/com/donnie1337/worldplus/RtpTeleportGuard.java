package com.donnie1337.worldplus;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Última proteção para teletransportes iniciados pelo RTP.
 * Não interfere em teleportes normais de nenhum outro sistema.
 */
final class RtpTeleportGuard implements Listener {
    private final RtpManager rtpManager;

    RtpTeleportGuard(RtpManager rtpManager) {
        this.rtpManager = rtpManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        Location destination = rtpManager.getPendingDestination(event.getPlayer().getUniqueId());
        if (destination == null) return;

        boolean wasCancelled = event.isCancelled();
        rtpManager.getPlugin().getLogger().info("[RTP DEBUG] Evento PlayerTeleportEvent: causa="
                + event.getCause() + ", cancelado-antes-do-guard=" + wasCancelled + ".");
        event.setCancelled(false);
        event.setTo(destination);
    }
}
