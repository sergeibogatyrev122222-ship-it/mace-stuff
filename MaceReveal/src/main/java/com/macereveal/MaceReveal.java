package com.macereveal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class MaceReveal extends JavaPlugin implements Listener {

    private static MaceReveal instance;

    private final Map<UUID, BukkitTask> actionBarTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> chestSpawnTasks = new HashMap<>();
    private final Map<UUID, Location> spawnedChests = new HashMap<>();

    private static final int[] RAINBOW = {
        0xFF0000, 0xFF4400, 0xFF8800, 0xFFCC00, 0xFFFF00,
        0x88FF00, 0x00FF00, 0x00FF88, 0x00FFFF, 0x0088FF,
        0x0000FF, 0x8800FF, 0xFF00FF, 0xFF0088
    };

    private final Random random = new Random();

    @Override
    public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MaceReveal enabled.");
    }

    @Override
    public void onDisable() {
        actionBarTasks.values().forEach(BukkitTask::cancel);
        chestSpawnTasks.values().forEach(BukkitTask::cancel);
        actionBarTasks.clear();
        chestSpawnTasks.clear();
        getLogger().info("MaceReveal disabled.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftMace(CraftItemEvent event) {
        if (event.getRecipe().getResult().getType() != Material.MACE) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        startReveal(player);

        player.sendMessage("§6[MaceReveal] §fYour location will be revealed to everyone for §e10 minutes§f!");
        Bukkit.broadcastMessage("§6[MaceReveal] §e" + player.getName()
                + " §fhas crafted a mace! Their location is now visible to all!");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null) return;
        if (event.getCurrentItem() == null) return;
        if (event.getCurrentItem().getType() != Material.MACE) return;
        if (event.getClickedInventory().getType() != InventoryType.CHEST) return;

        Block chestBlock = null;
        if (event.getView().getTopInventory().getHolder() instanceof Chest chest) {
            chestBlock = chest.getBlock();
        }
        if (chestBlock == null) return;
        if (!isTrackedChest(chestBlock.getLocation())) return;

        Location loc = chestBlock.getLocation();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (event.getClickedInventory().contains(Material.MACE)) return;
            onMaceTaken(loc);
        }, 1L);
    }

    private void startReveal(Player crafter) {
        UUID id = crafter.getUniqueId();
        cancelSession(id);

        BukkitTask actionBarTask = new BukkitRunnable() {
            int colorOffset = 0;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(id);
                String name = p != null ? p.getName() : crafter.getName();
                Location loc = p != null ? p.getLocation() : null;

                String text = loc != null
                        ? name + " | X: " + loc.getBlockX() + "  Y: " + loc.getBlockY() + "  Z: " + loc.getBlockZ()
                        : name + " | (offline)";

                Component rainbow = buildRainbow(text, colorOffset);
                colorOffset = (colorOffset + 1) % RAINBOW.length;

                for (Player online : Bukkit.getOnlinePlayers()) {
                    online.sendActionBar(rainbow);
                }
            }
        }.runTaskTimer(this, 0L, 15 * 20L);

        BukkitTask chestTask = new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    cancelSession(id);
                    return;
                }

                Location chestLoc = findChestSpot(p.getLocation());
                if (chestLoc == null) {
                    getLogger().warning("No valid chest spot found near " + p.getName());
                    return;
                }

                spawnChest(id, chestLoc);
                Bukkit.broadcastMessage("§6[MaceReveal] §fA chest with the mace has spawned near §e"
                        + p.getName() + "§f! Take it to stop the reveal!");
            }
        }.runTaskLater(this, 10 * 60 * 20L);

        actionBarTasks.put(id, actionBarTask);
        chestSpawnTasks.put(id, chestTask);
    }

    private void spawnChest(UUID id, Location loc) {
        Block block = loc.getBlock();
        block.setType(Material.CHEST);
        if (block.getState() instanceof Chest chest) {
            chest.getInventory().setItem(13, new ItemStack(Material.MACE));
            chest.update();
        }
        spawnedChests.put(id, loc.clone());
    }

    private void onMaceTaken(Location chestLoc) {
        UUID owner = null;
        for (Map.Entry<UUID, Location> entry : spawnedChests.entrySet()) {
            if (sameBlock(entry.getValue(), chestLoc)) {
                owner = entry.getKey();
                break;
            }
        }
        if (owner == null) return;

        spawnedChests.remove(owner);
        chestLoc.getBlock().setType(Material.AIR);

        BukkitTask ab = actionBarTasks.remove(owner);
        if (ab != null) ab.cancel();

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendActionBar(Component.empty());
        }

        Bukkit.broadcastMessage("§6[MaceReveal] §fThe mace has been claimed! Location reveal ended.");
    }

    private boolean isTrackedChest(Location loc) {
        for (Location cl : spawnedChests.values()) {
            if (sameBlock(cl, loc)) return true;
        }
        return false;
    }

    private Location findChestSpot(Location origin) {
        List<Location> candidates = new ArrayList<>();
        for (int dx = -15; dx <= 15; dx++) {
            for (int dz = -15; dz <= 15; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Location c = origin.clone().add(dx, dy, dz);
                    Block b = c.getBlock();
                    Block below = c.clone().add(0, -1, 0).getBlock();
                    if (b.getType() == Material.AIR && below.getType().isSolid()
                            && c.distance(origin) <= 15) {
                        candidates.add(c);
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private Component buildRainbow(String text, int offset) {
        Component result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            int hex = RAINBOW[(i + offset) % RAINBOW.length];
            result = result.append(
                Component.text(String.valueOf(text.charAt(i))).color(TextColor.color(hex))
            );
        }
        return result;
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void cancelSession(UUID id) {
        BukkitTask ab = actionBarTasks.remove(id);
        if (ab != null) ab.cancel();
        BukkitTask ct = chestSpawnTasks.remove(id);
        if (ct != null) ct.cancel();
    }
}
