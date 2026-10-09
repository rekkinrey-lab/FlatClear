package com.example.flatclear;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;

public class FlatClear extends JavaPlugin {

    private BukkitTask repeatingTask;
    private BukkitTask workerTask;
    private final Deque<int[]> pending = new ArrayDeque<>();

    private String worldName;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private long intervalTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        startSchedule();
    }

    @Override
    public void onDisable() {
        if (repeatingTask != null) repeatingTask.cancel();
        if (workerTask != null) workerTask.cancel();
        pending.clear();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        worldName = c.getString("world", "FLAT");
        intervalTicks = Math.max(1L, (long) (c.getDouble("interval-minutes", 5.0) * 60.0 * 20.0));

        double x1 = c.getDouble("pos1.x"), y1 = c.getDouble("pos1.y"), z1 = c.getDouble("pos1.z");
        double x2 = c.getDouble("pos2.x"), y2 = c.getDouble("pos2.y"), z2 = c.getDouble("pos2.z");

        // Coordinates are floored to the block that contains them.
        int bx1 = (int) Math.floor(x1), by1 = (int) Math.floor(y1), bz1 = (int) Math.floor(z1);
        int bx2 = (int) Math.floor(x2), by2 = (int) Math.floor(y2), bz2 = (int) Math.floor(z2);

        minX = Math.min(bx1, bx2);
        maxX = Math.max(bx1, bx2);
        minY = Math.min(by1, by2);
        maxY = Math.max(by1, by2);
        minZ = Math.min(bz1, bz2);
        maxZ = Math.max(bz1, bz2);
    }

    private void startSchedule() {
        if (repeatingTask != null) repeatingTask.cancel();
        repeatingTask = Bukkit.getScheduler().runTaskTimer(this, this::queueClear, intervalTicks, intervalTicks);
        getLogger().info("Clearing " + describeRegion() + " in world '" + worldName
                + "' every " + (intervalTicks / 1200.0) + " minute(s).");
    }

    private String describeRegion() {
        return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ")";
    }

    /**
     * Splits the region into chunk-sized pieces and processes one piece per tick,
     * so a large region never freezes the server.
     */
    private boolean queueClear() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("World '" + worldName + "' is not loaded; skipping this run.");
            return false;
        }
        if (workerTask != null && !workerTask.isCancelled() && !pending.isEmpty()) {
            getLogger().warning("Previous clear is still running; skipping this run.");
            return false;
        }

        pending.clear();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                int x1 = Math.max(minX, cx << 4);
                int x2 = Math.min(maxX, (cx << 4) + 15);
                int z1 = Math.max(minZ, cz << 4);
                int z2 = Math.min(maxZ, (cz << 4) + 15);
                pending.add(new int[]{x1, x2, z1, z2});
            }
        }

        final int minWorldY = world.getMinHeight();
        final int maxWorldY = world.getMaxHeight() - 1;
        final int y1 = Math.max(minY, minWorldY);
        final int y2 = Math.min(maxY, maxWorldY);

        workerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int[] piece = pending.poll();
            if (piece == null) {
                workerTask.cancel();
                return;
            }
            int cleared = 0;
            for (int x = piece[0]; x <= piece[1]; x++) {
                for (int z = piece[2]; z <= piece[3]; z++) {
                    for (int y = y1; y <= y2; y++) {
                        var block = world.getBlockAt(x, y, z);
                        if (block.getType() != Material.AIR) {
                            block.setType(Material.AIR, false);
                            cleared++;
                        }
                    }
                }
            }
            if (pending.isEmpty()) {
                workerTask.cancel();
                if (getConfig().getBoolean("log-runs", false)) {
                    getLogger().info("Clear pass finished.");
                }
            }
        }, 0L, 1L);
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("flatclear.admin")) {
            sender.sendMessage("You don't have permission to do that.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /" + label + " <now|reload|info>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "now" -> sender.sendMessage(queueClear()
                    ? "Clearing started."
                    : "Couldn't start a clear (see console).");
            case "reload" -> {
                loadSettings();
                startSchedule();
                sender.sendMessage("FlatClear config reloaded.");
            }
            case "info" -> sender.sendMessage("World: " + worldName + " | Region: " + describeRegion()
                    + " | Every " + (intervalTicks / 1200.0) + " min");
            default -> sender.sendMessage("Usage: /" + label + " <now|reload|info>");
        }
        return true;
    }
}
