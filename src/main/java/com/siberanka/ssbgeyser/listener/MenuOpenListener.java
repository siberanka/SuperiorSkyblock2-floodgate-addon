package com.siberanka.ssbgeyser.listener;

import com.siberanka.ssbgeyser.SSBGeyser;
import com.siberanka.ssbgeyser.form.FormManager;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.geysermc.floodgate.api.FloodgateApi;

public class MenuOpenListener implements Listener {

    private final SSBGeyser plugin;
    private final FormManager formManager;

    public MenuOpenListener(SSBGeyser plugin, FormManager formManager) {
        this.plugin = plugin;
        this.formManager = formManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!isFloodgatePlayer(player)) {
            return;
        }

        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (holder == null) {
            return;
        }

        // Check if the inventory is a SuperiorSkyblock2 menu
        if (isSuperiorSkyblockMenu(holder)) {
            // Cancel the Java inventory GUI open event
            event.setCancelled(true);

            // Fetch the compiled title of the GUI (with colors & placeholders)
            String title = event.getView().getTitle();

            // Send form in the next tick to ensure thread/packet synchronization safety
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    formManager.sendForm(player, inventory, title);
                }
            });
        }
    }

    /**
     * Checks if the inventory holder belongs to a SuperiorSkyblock2 GUI menu.
     */
    private boolean isSuperiorSkyblockMenu(InventoryHolder holder) {
        if (holder instanceof MenuView<?, ?>) {
            return true;
        }

        Class<?> clazz = holder.getClass();
        
        // Check official API interface
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.getName().equals("com.bgsoftware.superiorskyblock.api.menu.ISuperiorMenu")) {
                return true;
            }
        }

        // Fallback package check for internal SuperiorSkyblock2 menus
        String packageName = clazz.getPackageName();
        return packageName.startsWith("com.bgsoftware.superiorskyblock");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        formManager.handlePlayerDisconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        String pluginName = event.getPlugin().getName();
        if (pluginName.equalsIgnoreCase("SuperiorSkyblock2") || pluginName.equalsIgnoreCase("Floodgate")) {
            formManager.closeAllActiveForms();
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().disablePlugin(plugin));
        }
    }

    private boolean isFloodgatePlayer(Player player) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Floodgate API was unavailable while opening a menu for " + player.getName() + ".");
            return false;
        }
    }
}
