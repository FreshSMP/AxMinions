package com.artillexstudios.axminions.listeners

import com.artillexstudios.axapi.scheduler.Scheduler
import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.AxMinionsPlugin
import com.artillexstudios.axminions.api.AxMinionsAPI
import com.artillexstudios.axminions.api.config.Config
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.api.events.PreMinionPlaceEvent
import com.artillexstudios.axminions.api.minions.Direction
import com.artillexstudios.axminions.api.minions.miniontype.MinionTypes
import com.artillexstudios.axminions.api.utils.Keys
import com.artillexstudios.axminions.minions.Minion
import com.artillexstudios.axminions.minions.Minions
import java.util.logging.Level
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class MinionPlaceListener : Listener {

    @EventHandler
    fun onPlayerInteractEvent(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        if (event.clickedBlock == null) return
        val item = event.player.inventory.getItem(event.hand ?: return) ?: return
        val meta = item.itemMeta ?: return

        val type = meta.persistentDataContainer.get(Keys.MINION_TYPE, PersistentDataType.STRING) ?: return
        val minionType = MinionTypes.valueOf(type) ?: return
        event.isCancelled = true

        if (!AxMinionsPlugin.integrations.getProtectionIntegration()
                .canBuildAt(event.player, event.clickedBlock!!.location)
        ) {
            if (Config.DEBUG()) {
                event.player.sendMessage(
                    "Could not place due to protection hook!"
                )
            }
            return
        }

        val prePlaceEvent = PreMinionPlaceEvent(event.player, event.clickedBlock!!.location)

        val level = meta.persistentDataContainer.get(Keys.LEVEL, PersistentDataType.INTEGER) ?: 0
        val stats = meta.persistentDataContainer.get(Keys.STATISTICS, PersistentDataType.LONG) ?: 0
        val charge = meta.persistentDataContainer.get(Keys.CHARGE, PersistentDataType.LONG) ?: 0

        if (Config.PLACE_PERMISSION() && !event.player.hasPermission("axminions.place.${minionType.getName()}")) {
            event.player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.PLACE_MISSING_PERMISSION()))
            return
        }

        if (meta.persistentDataContainer.has(Keys.PLACED, PersistentDataType.BYTE)) return

        Bukkit.getPluginManager().callEvent(prePlaceEvent)
        if (prePlaceEvent.isCancelled) return

        meta.persistentDataContainer.set(Keys.PLACED, PersistentDataType.BYTE, 0)
        item.itemMeta = meta

        val location = event.clickedBlock!!.getRelative(event.blockFace).location

        val maxMinions = AxMinionsAPI.INSTANCE.getMinionLimit(event.player)

        val chunk = location.chunk

        // The axminions:placed marker was set on `item` above, BEFORE this async lambda runs.
        // If anything inside throws (e.g. an island integration returning null), the marker must
        // still be stripped, otherwise the item is permanently inert: placing is skipped (marker
        // present), interacting is cancelled, and dropping is cancelled. The finally-style cleanup
        // guarantees the marker is always removed on the region thread.
        AxMinionsPlugin.dataQueue.submit {
            try {
                val placed = AxMinionsPlugin.dataHandler.getMinionAmount(event.player.uniqueId)

                var islandLimit = Config.ISLAND_LIMIT()
                var islandPlaced = 0
                var islandId = ""
                if (islandLimit > 0 && AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration() != null) {
                    islandId = AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration()!!.getIslandAt(location)
                    islandLimit += AxMinionsAPI.INSTANCE.getIntegrations().getIslandIntegration()!!.getExtra(location)

                    if (Config.DEBUG()) {
                        event.player.sendMessage("Island ID: $islandId, limit: $islandLimit")
                    }

                    if (islandId.isNotBlank()) {
                        islandPlaced = AxMinionsAPI.INSTANCE.getDataHandler().getIsland(islandId)
                        if (Config.DEBUG()) {
                            event.player.sendMessage("Placed: $islandPlaced")
                        }

                        if (islandPlaced >= islandLimit && !event.player.hasPermission("axminions.limit.*")) {
                            if (Config.DEBUG()) {
                                event.player.sendMessage("Return")
                            }

                            event.player.sendMessage(
                                StringUtils.formatToString(
                                    Messages.PREFIX() + Messages.ISLAND_LIMIT_REACHED(),
                                    Placeholder.unparsed("placed", islandPlaced.toString()),
                                    Placeholder.unparsed("max", islandLimit.toString())
                                )
                            )
                            return@submit
                        } else {
                            if (Config.DEBUG()) {
                                event.player.sendMessage("Not return ${islandPlaced >= islandLimit} ${!event.player.hasPermission("axminions.limit.*")}")
                            }
                            islandPlaced++
                        }
                    }
                }

                if (placed >= maxMinions && !prePlaceEvent.getShouldOverridePlayerLimit() && !event.player.hasPermission("axminions.limit.*")) {
                    event.player.sendMessage(
                        StringUtils.formatToString(
                            Messages.PREFIX() + Messages.PLACE_LIMIT_REACHED(),
                            Placeholder.unparsed("placed", placed.toString()),
                            Placeholder.unparsed("max", maxMinions.toString())
                        )
                    )
                    return@submit
                }

                if (AxMinionsPlugin.dataHandler.isMinion(location)) {
                    event.player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + Messages.PLACE_MINION_AT_LOCATION()))
                    return@submit
                }

                val locationId = AxMinionsPlugin.dataHandler.getLocationID(location)
                val minion = Minion(
                    location,
                    event.player.uniqueId,
                    event.player,
                    minionType,
                    level,
                    ItemStack(Material.AIR),
                    null,
                    Direction.NORTH,
                    stats,
                    0.0,
                    locationId,
                    0,
                    charge
                )
                Minions.startTicking(chunk)

                Scheduler.get().run { _ ->
                    item.amount = item.amount.minus(1)
                }

                if (Config.DEBUG()) {
                    event.player.sendMessage(
                        "Placed minion $minion. Ticking? ${minion.isTicking()} Is chunk ticking? ${
                            Minions.isTicking(
                                chunk
                            )
                        } Chunk x: ${chunk.x} z: ${chunk.z} location x: ${location.x} y: ${location.y} z: ${location.z}"
                    )
                }

                minion.setOwnerOnline(true)
                AxMinionsPlugin.dataHandler.saveMinion(minion)

                if (islandId.isNotBlank()) {
                    AxMinionsPlugin.dataHandler.islandPlace(islandId)
                }

                event.player.sendMessage(
                    StringUtils.formatToString(
                        Messages.PREFIX() + Messages.PLACE_SUCCESS(),
                        Placeholder.unparsed("type", minionType.getName()),
                        Placeholder.unparsed("placed", (placed + 1).toString()),
                        Placeholder.unparsed("max", maxMinions.toString()),
                        Placeholder.unparsed("island-placed", islandPlaced.toString()),
                        Placeholder.unparsed("island-max", islandLimit.toString()),
                    )
                )
            } catch (throwable: Throwable) {
                AxMinionsPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "[${Thread.currentThread().name}] Exception while placing minion (type=${minionType.getName()}) for player ${event.player.name}" +
                        " at ${location.world?.name} ${location.blockX},${location.blockY},${location.blockZ}",
                    throwable
                )
            } finally {
                // Always strip the axminions:placed marker on the player's region thread so a failed
                // placement never leaves the held item permanently inert.
                Scheduler.get().run { _ ->
                    val currentMeta = item.itemMeta ?: return@run
                    if (currentMeta.persistentDataContainer.has(Keys.PLACED, PersistentDataType.BYTE)) {
                        currentMeta.persistentDataContainer.remove(Keys.PLACED)
                        item.itemMeta = currentMeta
                    }
                }
            }
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val blockLocation = event.block.location

        if (AxMinionsPlugin.dataHandler.isMinion(blockLocation)) {
            event.isCancelled = true
        }
    }
}
