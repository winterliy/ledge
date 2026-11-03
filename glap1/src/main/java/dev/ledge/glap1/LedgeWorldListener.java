package dev.ledge.glap1;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

final class LedgeWorldListener implements Listener {
    private final MapTileEngine tiles;

    LedgeWorldListener(MapTileEngine tiles) {
        this.tiles = tiles;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        invalidate(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        invalidate(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        invalidate(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> invalidate(block.getWorld(), block.getX(), block.getZ()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> invalidate(block.getWorld(), block.getX(), block.getZ()));
    }

    private void invalidate(org.bukkit.World world, int x, int z) {
        if (MapTileEngine.configFor(world).liveUpdates()) {
            tiles.invalidate(world, x, z);
        }
    }
}
