package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Ponte específica para o sistema de chunks do servidor Spigot 26.2.
 *
 * O Bukkit API de Spigot não expõe uma operação assíncrona de geração de
 * chunks. O servidor, porém, possui o ServerChunkCache#getChunkFuture,
 * que agenda a geração no pipeline interno de chunks e devolve um future.
 *
 * Tudo que toca o Bukkit/World novamente é devolvido ao thread principal.
 */
public final class RtpChunkLoader {
    private static volatile boolean initialized;
    private static volatile Method worldHandleMethod;
    private static volatile Method getChunkSourceMethod;
    private static volatile Method getChunkFutureMethod;
    private static volatile Field fullStatusField;
    private static volatile Method isSuccessMethod;

    private RtpChunkLoader() {
    }

    public static boolean request(Plugin plugin, World world, int chunkX, int chunkZ,
                                  Consumer<Boolean> callback) {
        try {
            initialize(world);

            Object handle = worldHandleMethod.invoke(world);
            Object chunkSource = getChunkSourceMethod.invoke(handle);
            Object fullStatus = fullStatusField.get(null);

            Object futureObject = getChunkFutureMethod.invoke(
                    chunkSource, chunkX, chunkZ, fullStatus, true
            );

            if (!(futureObject instanceof CompletableFuture<?> future)) {
                throw new IllegalStateException("ServerChunkCache#getChunkFuture não retornou CompletableFuture.");
            }

            future.whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (throwable != null || result == null) {
                    callback.accept(false);
                    return;
                }

                try {
                    if (!isSuccessMethod.invoke(result).equals(Boolean.TRUE)) {
                        callback.accept(false);
                        return;
                    }
                } catch (Throwable ignored) {
                    // Se a implementação não expuser isSuccess(), a existência
                    // de um resultado FULL já é suficiente para continuar.
                }

                try {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
                    callback.accept(chunk != null && chunk.isGenerated());
                } catch (Throwable exception) {
                    callback.accept(false);
                }
            }));

            return true;
        } catch (Throwable exception) {
            plugin.getLogger().warning(
                    "Falha ao solicitar chunk assíncrona para RTP em " + world.getName()
                            + " (" + chunkX + "," + chunkZ + "): "
                            + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return false;
        }
    }

    private static synchronized void initialize(World world) throws Exception {
        if (initialized) {
            return;
        }

        worldHandleMethod = world.getClass().getMethod("getHandle");
        Object handle = worldHandleMethod.invoke(world);
        getChunkSourceMethod = handle.getClass().getMethod("getChunkSource");

        Object chunkSource = getChunkSourceMethod.invoke(handle);
        Class<?> chunkStatusClass = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus");

        getChunkFutureMethod = chunkSource.getClass().getMethod(
                "getChunkFuture",
                int.class,
                int.class,
                chunkStatusClass,
                boolean.class
        );

        fullStatusField = chunkStatusClass.getField("FULL");

        Class<?> chunkResultClass = Class.forName("net.minecraft.server.level.ChunkResult");
        isSuccessMethod = chunkResultClass.getMethod("isSuccess");

        initialized = true;
    }
}
