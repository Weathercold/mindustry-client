package mindustry.client.utils

import arc.math.*
import arc.struct.*
import arc.util.*
import mindustry.Vars.*
import mindustry.client.ClientVars.*
import mindustry.client.navigation.MinePath
import mindustry.client.navigation.Navigation
import mindustry.gen.*
import mindustry.graphics.*
import mindustry.type.*
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.power.NuclearReactor.*
import mindustry.world.consumers.*
import kotlin.math.*

/** An auto transfer setup based on Ferlern/extended-ui */
class AutoTransfer {
    companion object Settings {
        @JvmField var enabled = false
        var fromCores = true
        var minCoreItems = 100
        var delay = 60F
        var debug = false
        var minTransferItems = 7 
    }

    private val dest = Seq<Building>()
    private var item: Item? = null
    private var timer = 0F
    private val counts = IntArray(content.items().size); private val countsAdditional = IntArray(content.items().size)

    fun draw() {
        if (!debug || player.unit().item() == null) return
        dest.forEach {
            val accepted = it.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit())
            Drawf.select(it.x, it.y, it.block.size * tilesize / 2f + 2f, if (accepted >= Mathf.clamp(player.unit().stack.amount, 1, 5)) Pal.place else Pal.noplace)
        }
    }

    fun update() {
        if (!enabled) return
        if (ratelimitRemaining <= 1) return
        player.unit().item() ?: return
        timer += Time.delta
        if (timer < delay) return
        timer = 0F

        val buildings = player.team().data().buildingTree ?: return
        val core = if (fromCores) player.closestCore() else null
        if (Navigation.currentlyFollowing is MinePath) { // Only allow autotransfer + minepath when within mineTransferRange
            if (core != null && (Navigation.currentlyFollowing as MinePath).tile?.within(core, mineTransferRange - tilesize * 10) != true) return
        } // Ngl this looks spaghetti

        val buildings = player.team().data().buildings ?: return
        var held = player.unit().stack.amount

        counts.fill(0) // reset needed item counters
        countsAdditional.fill(0)
        buildings.intersect(player.x - itemTransferRange, player.y - itemTransferRange, itemTransferRange * 2, itemTransferRange * 2, dest.clear())
        dest.filter { it.block.findConsumer<Consume?> { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic } != null && it !is NuclearReactorBuild && player.within(it, itemTransferRange) }
        .sort { b -> b.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit()).toFloat() }
        .forEach {
            if (ratelimitRemaining <= 1) return@forEach

            val accepted = it.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit())
            if (accepted > 0 && held > 0) {
                Call.transferInventory(player, it)
                held -= accepted
                ratelimitRemaining--
            }

            if (item == null && core != null) { // Automatically take needed item from core, only request once
                when (val cons = it.block.findConsumer<Consume> { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic }) { // Cursed af
                    is ConsumeItems -> {
                        cons.items.forEach { i ->
                            val acceptedC = it.acceptStack(i.item, it.getMaximumAccepted(i.item), player.unit())
                            if (acceptedC > 0 && core.items.has(i.item, max(i.amount, minCoreItems))) {
                                counts[i.item.id.toInt()] += acceptedC
                            }
                        }
                    }
                    is ConsumeItemFilter -> {
                        content.items().forEach { i ->
                            val acceptedC = it.acceptStack(i, Int.MAX_VALUE, player.unit())
                            if (acceptedC > 0 && it.block.consumes.consumesItem(i) && core.items.has(i, minCoreItems)) {
                                val turretC = (it.block as? ItemTurret)?.ammoTypes?.get(i)?.damage?.toInt() ?: 1 // Sort based on damage for turrets
                                counts[i.id.toInt()] += acceptedC
                                countsAdditional[i.id.toInt()] += acceptedC * turretC
                            }
                        }
                    }
                    is ConsumeItemDynamic -> {
                        cons.items.get(it).forEach { i -> // Get the current requirements
                            val acceptedC = it.acceptStack(i.item, i.amount, player.unit())
                            if (acceptedC > 0 && core.items.has(i.item, max(i.amount, minCoreItems))) {
                                counts[i.item.id.toInt()] += acceptedC
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("This should never happen. Report this.")
                }
            }
        }
        var maxID = 0; var maxCount = 0
        for (i in 1 until counts.size) {
            val count = counts[i] + countsAdditional[i]
            if (count > maxCount) {
                maxID = i
                maxCount = count
            }
        }
        if (counts[maxID] >= minTransferItems) item = content.item(maxID)

        Time.run(delay/2F) {
            if (item != null && core != null && player.within(core, itemTransferRange) && ratelimitRemaining > 1) {
                if (held > 0 && item != player.unit().stack.item) Call.transferInventory(player, core)
                else Call.requestItem(player, core, item, Int.MAX_VALUE)
                item = null
                ratelimitRemaining--
            }
        }
    }
}
