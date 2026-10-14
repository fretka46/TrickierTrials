package de.t14d3.trickiertrials;

import io.papermc.paper.event.block.BlockBreakProgressUpdateEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ochrana trial chamber.
 *
 * Úprava TheCivia (1.3.2):
 * - mapy placed/broken se plní jen když je daná funkce zapnutá a vždy se čistí,
 *   dřív placedBlocks rostla donekonečna při vypnutém decay (memory leak),
 * - když není nic zapnuté, handlery končí hned a nevolají getStructures na každý blok,
 * - nastavení se čte z pluginu, takže /trickiertrials reload se projeví i tady.
 */
public class TrialChamberProtector implements Listener {

    private final TrickierTrials plugin;

    private final Map<Location, BlockData> brokenBlocks = new HashMap<>();
    private final Map<Location, BlockData> placedBlocks = new HashMap<>();

    public TrialChamberProtector(TrickierTrials plugin) {
        this.plugin = plugin;
    }

    private List<Material> protectedMaterials() {
        return plugin.getTrialChamberMaterials();
    }

    private boolean protectionActive() {
        return !protectedMaterials().isEmpty();
    }

    /** Placed bloky sledujeme jen pro decay nebo pro rozlišení "hráčem položený" u ochrany. */
    private boolean trackPlaced() {
        return plugin.isDecayPlacedBlocks() || protectionActive();
    }

    private static boolean inTrialChamber(Block block) {
        Location loc = block.getLocation();
        for (var structure : block.getChunk().getStructures(Structure.TRIAL_CHAMBERS)) {
            if (structure.getBoundingBox().contains(loc.toVector())) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location blockLocation = block.getLocation();

        // Hráčem položený blok: jen ho přestaneme sledovat (vždy, aby mapa nerostla)
        if (placedBlocks.remove(blockLocation) != null) return;

        if (!protectionActive() || !protectedMaterials().contains(block.getType())) return;
        if (!inTrialChamber(block)) return;

        boolean regenerate = plugin.isRegenerateBrokenBlocks();
        event.setDropItems(!regenerate); // bez dropu, když se blok obnoví
        if (!regenerate) return;

        brokenBlocks.put(blockLocation, block.getBlockData());
        long delay = plugin.getConfig().getInt("regenerate-delay") * 20L + (long) (Math.random() * 100);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            BlockData data = brokenBlocks.remove(blockLocation);
            if (data != null && !placedBlocks.containsKey(blockLocation) && blockLocation.isWorldLoaded()) {
                blockLocation.getBlock().setBlockData(data);
            }
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!trackPlaced()) return;

        Block block = event.getBlock();
        if (!inTrialChamber(block)) return;

        Location blockLocation = block.getLocation();
        placedBlocks.put(blockLocation, block.getBlockData());

        if (plugin.isDecayPlacedBlocks()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (placedBlocks.remove(blockLocation) != null && blockLocation.isWorldLoaded()) {
                    blockLocation.getBlock().setBlockData(Material.AIR.createBlockData());
                }
            }, plugin.getConfig().getInt("decay-delay") * 20L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreakStart(BlockBreakProgressUpdateEvent event) {
        int level = plugin.getConfig().getInt("modules.mining-fatigue");
        if (level <= 0) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!inTrialChamber(event.getBlock())) return;
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 10,
                plugin.getConfig().getInt("mining-fatigue-level", 2)));
    }

    /** Po vyložení světa nesmí mapy držet jeho lokace (a tím celý svět v paměti). */
    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        placedBlocks.keySet().removeIf(loc -> loc.getWorld() == null || loc.getWorld().equals(event.getWorld()));
        brokenBlocks.keySet().removeIf(loc -> loc.getWorld() == null || loc.getWorld().equals(event.getWorld()));
    }
}
