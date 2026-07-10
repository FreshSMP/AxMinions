package com.artillexstudios.axminions.listeners

import com.artillexstudios.axapi.scheduler.Scheduler
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.minions.Minions
import java.util.logging.Level
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent

class ChunkListener : Listener {

    @EventHandler
    fun onChunkLoadEvent(event: ChunkLoadEvent) {
        val chunk = event.chunk
        try {
            Minions.startTicking(chunk)
        } catch (throwable: Throwable) {
            AxMinionsPlugin.INSTANCE.logger.log(
                Level.SEVERE,
                "[${Thread.currentThread().name}] Exception in ChunkLoadEvent handler for chunk ${chunk.world.name} [${chunk.x}, ${chunk.z}], retrying startTicking via global scheduler",
                throwable
            )

            // An exception here must never silently leave the chunk non-ticking.
            Scheduler.get().run { _ ->
                try {
                    Minions.startTicking(chunk)
                } catch (retryThrowable: Throwable) {
                    AxMinionsPlugin.INSTANCE.logger.log(
                        Level.SEVERE,
                        "[${Thread.currentThread().name}] Retry of startTicking failed for chunk ${chunk.world.name} [${chunk.x}, ${chunk.z}]",
                        retryThrowable
                    )
                }
            }
        }
    }

    @EventHandler
    fun onChunkUnloadEvent(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        try {
            Minions.stopTicking(chunk)
        } catch (throwable: Throwable) {
            AxMinionsPlugin.INSTANCE.logger.log(
                Level.SEVERE,
                "[${Thread.currentThread().name}] Exception in ChunkUnloadEvent handler for chunk ${chunk.world.name} [${chunk.x}, ${chunk.z}]",
                throwable
            )
        }
    }
}
