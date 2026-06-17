package com.siberanka.ssbgeyser.form;

import com.siberanka.ssbgeyser.SSBGeyser;
import com.siberanka.ssbgeyser.config.ConfigManager;
import com.siberanka.ssbgeyser.util.TextureMapper;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FormManager {

    private final SSBGeyser plugin;
    private final ConfigManager configManager;
    private final TextureMapper textureMapper;
    
    // Tracks active Bedrock form sessions to allow click simulation and clean up
    private final Map<UUID, ActiveFormSession> activeSessions = new ConcurrentHashMap<>();

    public FormManager(SSBGeyser plugin, ConfigManager configManager, TextureMapper textureMapper) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.textureMapper = textureMapper;
    }

    /**
     * Translates a Java Chest GUI into a Bedrock SimpleForm and sends it.
     */
    public void sendForm(Player player, Inventory inventory, String title) {
        UUID uuid = player.getUniqueId();
        FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(uuid);
        if (floodgatePlayer == null) return;

        SimpleForm.Builder formBuilder = SimpleForm.builder();
        formBuilder.title(ChatColor.stripColor(title));
        formBuilder.content(configManager.getMessage("menu-header-info", "§eAşağıdan bir seçenek belirleyin:"));

        List<Integer> buttonToSlotMap = new ArrayList<>();
        int size = inventory.getSize();

        for (int slot = 0; slot < size; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            String matName = item.getType().name();
            if (configManager.isHideFillerItems() && configManager.getFillerMaterials().contains(matName)) {
                continue;
            }

            ItemMeta meta = item.getItemMeta();
            String displayName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : formatItemName(matName);
            List<String> lore = (meta != null && meta.hasLore()) ? meta.getLore() : new ArrayList<>();

            // Build button text (Display name + Lore as description)
            StringBuilder buttonText = new StringBuilder(displayName);
            if (lore != null && !lore.isEmpty()) {
                for (String line : lore) {
                    buttonText.append("\n").append(line);
                }
            }

            // Map texture
            TextureMapper.TextureResult texture = textureMapper.getTexture(item);
            formBuilder.button(buttonText.toString(), texture.getType(), texture.getPath());
            buttonToSlotMap.add(slot);
        }

        // Create the custom dynamic view proxy
        InventoryView mockView = createMockView(player, inventory, title);
        ActiveFormSession session = new ActiveFormSession(inventory, title, buttonToSlotMap, mockView);
        activeSessions.put(uuid, session);

        formBuilder.validResultHandler(response -> {
            int buttonId = response.clickedButtonId();
            if (buttonId >= 0 && buttonId < buttonToSlotMap.size()) {
                int slotIndex = buttonToSlotMap.get(buttonId);
                simulateClick(player, uuid, slotIndex, session);
            }
        });

        formBuilder.closedResultHandler(response -> {
            simulateClose(player, uuid, session);
        });

        // Send form using Floodgate Player
        floodgatePlayer.sendForm(formBuilder.build());
    }

    /**
     * Simulates the inventory click on the main server thread.
     */
    private void simulateClick(Player player, UUID uuid, int slotIndex, ActiveFormSession session) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                activeSessions.remove(uuid);
                return;
            }

            // Play Click Sound
            String soundName = configManager.getClickSound();
            if (soundName != null && !soundName.isEmpty()) {
                try {
                    player.playSound(player.getLocation(), Sound.valueOf(soundName.toUpperCase()), 1.0f, 1.0f);
                } catch (IllegalArgumentException ignored) {}
            }

            try {
                // Construct the click event using our custom view proxy
                InventoryClickEvent clickEvent = new InventoryClickEvent(
                        session.getMockView(),
                        InventoryType.SlotType.CONTAINER,
                        slotIndex,
                        ClickType.LEFT,
                        InventoryAction.PICKUP_ALL
                );

                // Call the click event globally
                Bukkit.getPluginManager().callEvent(clickEvent);
            } catch (Exception e) {
                player.sendMessage(configManager.getMessage("prefix") + configManager.getMessage("error-occurred"));
                plugin.getLogger().severe("Error simulating inventory click for Bedrock player " + player.getName());
                e.printStackTrace();
            }
        });
    }

    /**
     * Simulates the inventory close event to let plugins clean up menu state.
     */
    private void simulateClose(Player player, UUID uuid, ActiveFormSession session) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            activeSessions.remove(uuid);
            if (!player.isOnline()) return;

            try {
                InventoryCloseEvent closeEvent = new InventoryCloseEvent(session.getMockView());
                Bukkit.getPluginManager().callEvent(closeEvent);
            } catch (Exception e) {
                plugin.getLogger().severe("Error simulating inventory close for Bedrock player " + player.getName());
                e.printStackTrace();
            }
        });
    }

    /**
     * Closes all active Bedrock forms for players (useful when plugin disables or errors).
     */
    public void closeAllActiveForms() {
        for (UUID uuid : activeSessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                // Try Geyser API closeForm
                try {
                    Class.forName("org.geysermc.geyser.api.GeyserApi");
                    org.geysermc.geyser.api.connection.GeyserConnection connection =
                            org.geysermc.geyser.api.GeyserApi.api().connectionByUuid(uuid);
                    if (connection != null) {
                        connection.closeForm();
                    }
                } catch (ClassNotFoundException ignored) {
                    // Fallback to closing standard inventory
                    player.closeInventory();
                }
            }
        }
        activeSessions.clear();
    }

    /**
     * Cleans up string item names for display (e.g. DIAMOND_SWORD -> Diamond Sword).
     */
    private String formatItemName(String raw) {
        String[] parts = raw.split("_");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            name.append(Character.toUpperCase(part.charAt(0)))
                .append(part.substring(1).toLowerCase())
                .append(" ");
        }
        return name.toString().trim();
    }

    /**
     * Creates a dynamic proxy for the InventoryView class/interface to bypass paper 1.21 changes.
     */
    private InventoryView createMockView(Player player, Inventory inventory, String title) {
        return (InventoryView) Proxy.newProxyInstance(
                InventoryView.class.getClassLoader(),
                new Class<?>[]{InventoryView.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTopInventory":
                            return inventory;
                        case "getBottomInventory":
                            return player.getInventory();
                        case "getPlayer":
                            return player;
                        case "getType":
                            return inventory.getType();
                        case "getTitle":
                            return title;
                        case "convertSlot":
                            return args[0]; // Raw slot index equals local slot index in simplified chest views
                        case "setItem":
                            int setSlot = (int) args[0];
                            ItemStack setItem = (ItemStack) args[1];
                            if (setSlot < inventory.getSize()) {
                                inventory.setItem(setSlot, setItem);
                            } else {
                                player.getInventory().setItem(setSlot - inventory.getSize(), setItem);
                            }
                            return null;
                        case "getItem":
                            int getSlot = (int) args[0];
                            if (getSlot < inventory.getSize()) {
                                return inventory.getItem(getSlot);
                            } else {
                                return player.getInventory().getItem(getSlot - inventory.getSize());
                            }
                        default:
                            Class<?> returnType = method.getReturnType();
                            if (returnType == int.class || returnType == Integer.class) {
                                return 0;
                            } else if (returnType == boolean.class || returnType == Boolean.class) {
                                return false;
                            }
                            return null;
                    }
                }
        );
    }

    public static class ActiveFormSession {
        private final Inventory inventory;
        private final String title;
        private final List<Integer> buttonToSlotMap;
        private final InventoryView mockView;

        public ActiveFormSession(Inventory inventory, String title, List<Integer> buttonToSlotMap, InventoryView mockView) {
            this.inventory = inventory;
            this.title = title;
            this.buttonToSlotMap = buttonToSlotMap;
            this.mockView = mockView;
        }

        public Inventory getInventory() {
            return inventory;
        }

        public String getTitle() {
            return title;
        }

        public List<Integer> getButtonToSlotMap() {
            return buttonToSlotMap;
        }

        public InventoryView getMockView() {
            return mockView;
        }
    }
}
