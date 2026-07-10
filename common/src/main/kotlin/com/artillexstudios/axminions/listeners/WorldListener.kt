package com.artillexstudios.axminions.listeners

import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.minions.miniontype.MinionTypes
import com.artillexstudios.axminions.api.utils.fastFor
import com.artillexstudios.axminions.minions.Minions
import java.util.logging.Level
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent

class WorldListener : Listener {

    @EventHandler
    fun onWorldLoadEvent(event: WorldLoadEvent) {
        try {
            MinionTypes.loadForWorld(event.world)

            event.world.loadedChunks.fastFor {
                try {
                    Minions.startTicking(it)
                } catch (throwable: Throwable) {
                    AxMinionsPlugin.INSTANCE.logger.log(
                        Level.SEVERE,
                        "[${Thread.currentThread().name}] Exception starting ticking for chunk ${it.world.name} [${it.x}, ${it.z}] during WorldLoadEvent for world ${event.world.name}",
                        throwable
                    )
                }
            }
        } catch (throwable: Throwable) {
            AxMinionsPlugin.INSTANCE.logger.log(
                Level.SEVERE,
                "[${Thread.currentThread().name}] Exception in WorldLoadEvent handler for world ${event.world.name}",
                throwable
            )
        }
    }

    @EventHandler
    fun onWorldUnload(event: WorldUnloadEvent) {
        try {
            val worldUUID = event.world.uid
            Minions.getMinions().fastFor {
                if (it.getLocation().world?.uid == worldUUID) {
                    Minions.remove(it)
                }
            }
        } catch (throwable: Throwable) {
            AxMinionsPlugin.INSTANCE.logger.log(
                Level.SEVERE,
                "[${Thread.currentThread().name}] Exception in WorldUnloadEvent handler for world ${event.world.name}",
                throwable
            )
        }
    }
}
