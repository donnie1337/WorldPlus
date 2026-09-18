package com.donnie1337.worldplus;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Ponte entre o RTP e o pipeline interno de chunks do Spigot 26.2.
 *
 * O Bukkit API não expõe uma operação portátil de geração assíncrona de chunks.
 * Em 26.2, o ServerChunkCache possui getChunkFuture(...), que inicia/aguarda
 * o pipeline de geração sem chamar getChunk(..., true) no thread principal.
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
                throw new IllegalStateException(
                        "getChunkFuture retornou " + (futureObject == null
                                ? "null"
                                : futureObject.getClass().getName())
                );
            }

            CompletableFuture<?> completion = future;
            completion.orTimeout(30, TimeUnit.SECONDS).whenComplete((result, throwable) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (throwable != null) {
                            plugin.getLogger().warning(
                                    "RTP: geração da chunk " + chunkX + "," + chunkZ
                                            + " falhou/expirou em " + world.getName()
                                            + ": " + throwable.getClass().getSimpleName()
                                            + ": " + String.valueOf(throwable.getMessage())
                            );
                            callback.accept(false);
                            return;
                        }

                        if (result == null) {
                            plugin.getLogger().warning(
                                    "RTP: pipeline retornou resultado nulo para "
                                            + world.getName() + " (" + chunkX + "," + chunkZ + ")."
                            );
                            callback.accept(false);
                            return;
                        }

                        if (isSuccessMethod != null) {
                            try {
                                Object success = isSuccessMethod.invoke(result);
                                if (!Boolean.TRUE.equals(success)) {
                                    plugin.getLogger().warning(
                                            "RTP: pipeline não produziu uma chunk FULL para "
                                                    + world.getName() + " (" + chunkX + "," + chunkZ + ")."
                                    );
                                    callback.accept(false);
                                    return;
                                }
                            } catch (Throwable exception) {
                                plugin.getLogger().warning(
                                        "RTP: não foi possível validar ChunkResult em "
                                                + world.getName() + ": "
                                                + exception.getClass().getSimpleName() + ": "
                                                + String.valueOf(exception.getMessage())
                                );
                                callback.accept(false);
                                return;
                            }
                        }

                        try {
                            Chunk chunk = world.getChunkAt(chunkX, chunkZ, false);
                            boolean ready = chunk != null && chunk.isGenerated();
                            if (!ready) {
                                plugin.getLogger().warning(
                                        "RTP: future concluiu, mas a chunk não está disponível no Bukkit: "
                                                + world.getName() + " (" + chunkX + "," + chunkZ + ")."
                                );
                            }
                            callback.accept(ready);
                        } catch (Throwable exception) {
                            plugin.getLogger().warning(
                                    "RTP: erro ao acessar chunk pronta em " + world.getName()
                                            + " (" + chunkX + "," + chunkZ + "): "
                                            + exception.getClass().getSimpleName() + ": "
                                            + String.valueOf(exception.getMessage())
                            );
                            callback.accept(false);
                        }
                    })
            );

            return true;
        } catch (Throwable exception) {
            initialized = false;
            plugin.getLogger().warning(
                    "RTP: não foi possível acessar o pipeline de chunks de Spigot 26.2 em "
                            + world.getName() + " (" + chunkX + "," + chunkZ + "): "
                            + exception.getClass().getName() + ": "
                            + String.valueOf(exception.getMessage())
            );
            return false;
        }
    }

    private static synchronized void initialize(World world) throws Exception {
        if (initialized) {
            return;
        }

        worldHandleMethod = findMethod(world.getClass(), "getHandle");
        Object handle = worldHandleMethod.invoke(world);
        getChunkSourceMethod = findMethod(handle.getClass(), "getChunkSource");

        Object chunkSource = getChunkSourceMethod.invoke(handle);
        Class<?> chunkStatusClass = Class.forName(
                "net.minecraft.world.level.chunk.status.ChunkStatus"
        );

        getChunkFutureMethod = findChunkFutureMethod(chunkSource.getClass(), chunkStatusClass);
        if (getChunkFutureMethod == null) {
            throw new NoSuchMethodException(
                    "ServerChunkCache#getChunkFuture(int,int,ChunkStatus,boolean) não encontrado"
            );
        }

        fullStatusField = findField(chunkStatusClass, "FULL");
        if (fullStatusField == null) {
            throw new NoSuchFieldException("ChunkStatus.FULL não encontrado");
        }

        try {
            Class<?> chunkResultClass = Class.forName(
                    "net.minecraft.server.level.ChunkResult"
            );
            isSuccessMethod = findMethod(chunkResultClass, "isSuccess");
        } catch (ClassNotFoundException exception) {
            // Algumas builds expõem o resultado do future com outro wrapper.
            // Nesse caso, o próprio resultado FULL será considerado suficiente.
            isSuccessMethod = null;
        }

        initialized = true;
    }

    private static Method findChunkFutureMethod(Class<?> type, Class<?> chunkStatusClass) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("getChunkFuture")) continue;

                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 4) continue;
                if (parameters[0] != int.class || parameters[1] != int.class) continue;
                if (!parameters[2].isAssignableFrom(chunkStatusClass)
                        && !chunkStatusClass.isAssignableFrom(parameters[2])) continue;
                if (parameters[3] != boolean.class) continue;

                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // continua nas superclasses
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "()");
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // continua nas superclasses
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }
}
