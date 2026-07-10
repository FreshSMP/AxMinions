package com.artillexstudios.axminions.minions

import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.minions.Minion
import com.artillexstudios.axminions.api.minions.utils.ChunkPos
import com.artillexstudios.axapi.scheduler.Scheduler
import java.util.Collections
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.bukkit.Chunk
import org.bukkit.World

object Minions {
    internal val lock = ReentrantReadWriteLock()
    internal val minions = arrayListOf<ChunkPos>()

    // NOTE: All mutations of the shared `minions` list are routed unconditionally through
    // Scheduler.get().run {} so they are confined to the global region thread alongside
    // MinionTicker. Bukkit.isPrimaryThread() returns true on every Folia region thread, so
    // any "fast path" gated on it would let chunk load/unload handlers mutate the list
    // concurrently from arbitrary region threads, corrupting it (CME / duplicate ChunkPos).
    fun startTicking(chunk: Chunk) {
        startTicking(chunk.world, chunk.x, chunk.z, true)
    }

    private fun startTicking(world: World, chunkX: Int, chunkZ: Int, retry: Boolean) {
        Scheduler.get().run { _ ->
            try {
                lock.write {
                    run breaking@{
                        minions.forEach {
                            if (world.uid == it.worldUUID && it.x == chunkX && it.z == chunkZ) {
                                it.setTicking(true)
                                return@breaking
                            }
                        }
                    }
                }
            } catch (throwable: Throwable) {
                AxMinionsPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "[${Thread.currentThread().name}] Failed to start ticking minions in chunk ${world.name} [$chunkX, $chunkZ]${if (retry) ", retrying via global scheduler" else ""}",
                    throwable
                )

                // Never leave a chunk silently non-ticking: retry the mutation once.
                if (retry) {
                    startTicking(world, chunkX, chunkZ, false)
                }
            }
        }
    }

    fun isTicking(chunk: Chunk): Boolean {
        val chunkX = chunk.x
        val chunkZ = chunk.z
        val world = chunk.world

        lock.read {
            minions.forEach {
                if (world.uid == it.worldUUID && it.x == chunkX && it.z == chunkZ) {
                    return it.ticking
                }
            }
        }

        return false
    }

    fun stopTicking(chunk: Chunk) {
        val chunkX = chunk.x
        val chunkZ = chunk.z
        val world = chunk.world

        Scheduler.get().run { _ ->
            try {
                lock.write {
                    run breaking@{
                        minions.forEach {
                            if (world.uid == it.worldUUID && it.x == chunkX && it.z == chunkZ) {
                                it.setTicking(false)
                                return@breaking
                            }
                        }
                    }
                }
            } catch (throwable: Throwable) {
                AxMinionsPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "[${Thread.currentThread().name}] Failed to stop ticking minions in chunk ${world.name} [$chunkX, $chunkZ]",
                    throwable
                )
            }
        }
    }

    fun load(minion: Minion) {
        val chunkX = minion.getLocation().blockX shr 4
        val chunkZ = minion.getLocation().blockZ shr 4
        val world = minion.getLocation().world ?: return

        Scheduler.get().run { _ ->
            try {
                lock.write {
                    var pos: ChunkPos? = null
                    run breaking@{
                        minions.forEach {
                            if (world.uid == it.worldUUID && it.x == chunkX && it.z == chunkZ) {
                                pos = it
                                return@breaking
                            }
                        }
                    }

                    if (pos == null) {
                        pos = ChunkPos(world, chunkX, chunkZ, false)
                        minions.add(pos!!)
                    }

                    pos!!.addMinion(minion)
                }
            } catch (throwable: Throwable) {
                AxMinionsPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "[${Thread.currentThread().name}] Failed to load minion at ${world.name} [$chunkX, $chunkZ] (${minion.getLocation().blockX}, ${minion.getLocation().blockY}, ${minion.getLocation().blockZ}) owner=${minion.getOwner()?.name}",
                    throwable
                )
            }
        }
    }

    fun remove(minion: Minion) {
        val chunkX = minion.getLocation().blockX shr 4
        val chunkZ = minion.getLocation().blockZ shr 4
        val world = minion.getLocation().world ?: return

        Scheduler.get().run { _ ->
            try {
                lock.write {
                    val iterator = minions.iterator()
                    while (iterator.hasNext()) {
                        val next = iterator.next()

                        if (world.uid == next.worldUUID && next.x == chunkX && next.z == chunkZ) {
                            if (next.removeMinion(minion)) {
                                iterator.remove()
                            }
                            break
                        }
                    }
                }
            } catch (throwable: Throwable) {
                AxMinionsPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "[${Thread.currentThread().name}] Failed to remove minion at ${world.name} [$chunkX, $chunkZ] (${minion.getLocation().blockX}, ${minion.getLocation().blockY}, ${minion.getLocation().blockZ}) owner=${minion.getOwner()?.name}",
                    throwable
                )
            }
        }
    }

    fun getMinions(): List<Minion> {
        val list = mutableListOf<Minion>()
        lock.read {
            minions.forEach {
                list.addAll(it.minions)
            }

            return Collections.unmodifiableList(list)
        }
    }

    internal inline fun get(minions: (ArrayList<ChunkPos>) -> Unit) {
        lock.read {
            minions(this.minions)
        }
    }
}
