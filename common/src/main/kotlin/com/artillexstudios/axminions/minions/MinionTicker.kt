package com.artillexstudios.axminions.minions

import com.artillexstudios.axapi.scheduler.Scheduler
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.utils.fastFor
import java.util.logging.Level

object MinionTicker {
    private var tick = 0L

    private inline fun tickAll() {
        Minions.get { minions ->
            minions.fastFor { pos ->
                if (!pos.ticking) return@fastFor
                pos.minions.fastFor { minion ->
                    try {
                        minion.tick()
                    } catch (throwable: Throwable) {
                        // One broken minion must never disrupt the whole tick pass.
                        val location = minion.getLocation()
                        AxMinionsPlugin.INSTANCE.logger.log(
                            Level.SEVERE,
                            "[${Thread.currentThread().name}] Exception while ticking minion" +
                                " (type=${minion.getType().getName()}, owner=${minion.getOwner()?.name}," +
                                " location=${location.world?.name} ${location.blockX},${location.blockY},${location.blockZ})",
                            throwable
                        )
                    }
                }
            }
        }

        tick++
    }

    fun startTicking() {
        Scheduler.get().runTimer({ _ ->
            tickAll()
        }, 1, 1)
    }

    fun getTick(): Long {
        return this.tick
    }
}
