package com.siberanka.ssbgeyser.form;

import com.siberanka.ssbgeyser.SSBGeyser;
import com.siberanka.ssbgeyser.config.ConfigManager;
import com.siberanka.ssbgeyser.util.TextureMapper;
import com.bgsoftware.superiorskyblock.api.menu.Menu;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class FormManager {

    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_CONTENT_LENGTH = 256;
    private static final int MAX_BUTTON_TEXT_LENGTH = 768;
    private static final int MAX_LORE_LINES = 12;
    private static final long POST_CLICK_CLEANUP_DELAY_TICKS = 2L;

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

        ActiveFormSession previousSession = activeSessions.remove(uuid);
        if (previousSession != null) {
            dispatchCloseOnce(player, previousSession);
        }

        SimpleForm.Builder formBuilder = SimpleForm.builder();
        formBuilder.title(sanitizeFormText(title, MAX_TITLE_LENGTH, false));
        formBuilder.content(sanitizeFormText(configManager.getMessage("menu-header-info", "Select an option below:"), MAX_CONTENT_LENGTH, false));

        List<Integer> buttonToSlotMap = new ArrayList<>();
        int size = inventory.getSize();
        MenuView<?, ?> superiorMenuView = getSuperiorMenuView(inventory);

        for (int slot = 0; slot < size; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            if (superiorMenuView != null && !hasSuperiorButton(superiorMenuView, slot)) {
                continue;
            }

            String matName = item.getType().name();
            if (configManager.isHideFillerItems() && isLayoutFillerMaterial(matName)) {
                continue;
            }

            ItemMeta meta = item.getItemMeta();
            String displayName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : formatItemName(matName);
            List<String> lore = (meta != null && meta.hasLore()) ? meta.getLore() : new ArrayList<>();

            // Build button text (Display name + Lore as description)
            StringBuilder buttonText = new StringBuilder(sanitizeFormText(displayName, MAX_BUTTON_TEXT_LENGTH, false));
            if (lore != null && !lore.isEmpty()) {
                int loreLines = 0;
                for (String line : lore) {
                    if (loreLines++ >= MAX_LORE_LINES || buttonText.length() >= MAX_BUTTON_TEXT_LENGTH) {
                        break;
                    }
                    int remainingLength = MAX_BUTTON_TEXT_LENGTH - buttonText.length() - 1;
                    if (remainingLength <= 0) {
                        break;
                    }
                    String sanitizedLine = sanitizeFormText(line, remainingLength, false);
                    if (!sanitizedLine.isBlank()) {
                        buttonText.append("\n").append(sanitizedLine);
                    }
                }
            }

            if (configManager.isHideEmptyButtons() && buttonText.toString().isBlank()) {
                continue;
            }

            // Map texture
            TextureMapper.TextureResult texture = textureMapper.getTexture(item);
            if (texture != null) {
                formBuilder.button(buttonText.toString(), texture.getType(), texture.getPath());
            } else {
                formBuilder.button(buttonText.toString());
            }
            buttonToSlotMap.add(slot);
        }

        // Create the custom dynamic view proxy
        InventoryView mockView = createMockView(player, inventory, title);
        ActiveFormSession session = new ActiveFormSession(inventory, title, buttonToSlotMap, mockView, superiorMenuView);
        activeSessions.put(uuid, session);

        formBuilder.validResultHandler(response -> {
            int buttonId = response.clickedButtonId();
            if (buttonId >= 0 && buttonId < buttonToSlotMap.size()) {
                int slotIndex = buttonToSlotMap.get(buttonId);
                simulateClick(player, uuid, slotIndex, session);
            }
        });

        formBuilder.closedResultHandler(response -> simulateClose(player, uuid, session));
        formBuilder.invalidResultHandler(response -> simulateClose(player, uuid, session));

        // Send form using Floodgate Player
        try {
            floodgatePlayer.sendForm(formBuilder.build());
        } catch (RuntimeException ex) {
            activeSessions.remove(uuid, session);
            dispatchCloseOnce(player, session);
            plugin.getLogger().log(Level.SEVERE, "Failed to send Bedrock form for player " + player.getName(), ex);
        }
    }

    /**
     * Simulates the inventory click on the main server thread.
     */
    private void simulateClick(Player player, UUID uuid, int slotIndex, ActiveFormSession session) {
        runOnMainThread(() -> {
            if (!session.markResponseHandled() || activeSessions.get(uuid) != session) {
                return;
            }

            if (!player.isOnline()) {
                activeSessions.remove(uuid, session);
                return;
            }

            // Play Click Sound
            Sound clickSound = configManager.getClickSound();
            if (clickSound != null) {
                player.playSound(player.getLocation(), clickSound, 1.0f, 1.0f);
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

                dispatchClick(clickEvent, session);
            } catch (Exception e) {
                player.sendMessage(configManager.getMessage("prefix") + configManager.getMessage("error-occurred"));
                plugin.getLogger().log(Level.SEVERE, "Error simulating inventory click for Bedrock player " + player.getName(), e);
            } finally {
                closeSessionLaterIfStillActive(player, uuid, session);
            }
        });
    }

    /**
     * Simulates the inventory close event to let plugins clean up menu state.
     */
    private void simulateClose(Player player, UUID uuid, ActiveFormSession session) {
        runOnMainThread(() -> {
            if (!session.markResponseHandled()) {
                return;
            }

            if (!activeSessions.remove(uuid, session)) {
                return;
            }

            if (!player.isOnline()) return;

            try {
                dispatchCloseOnce(player, session);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error simulating inventory close for Bedrock player " + player.getName(), e);
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
                } catch (Throwable ignored) {
                    // Fallback to closing standard inventory
                    player.closeInventory();
                }

                ActiveFormSession session = activeSessions.get(uuid);
                if (session != null) {
                    dispatchCloseOnce(player, session);
                }
            }
        }
        activeSessions.clear();
    }

    public void handlePlayerDisconnect(Player player) {
        ActiveFormSession session = activeSessions.remove(player.getUniqueId());
        if (session != null) {
            dispatchCloseOnce(player, session);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void dispatchClick(InventoryClickEvent clickEvent, ActiveFormSession session) {
        MenuView menuView = session.getSuperiorMenuView();
        if (menuView != null) {
            Menu menu = menuView.getMenu();
            if (menu != null) {
                clickEvent.setCancelled(true);
                menu.onClick(clickEvent, menuView);
                return;
            }
        }

        Bukkit.getPluginManager().callEvent(clickEvent);
    }

    private void dispatchCloseOnce(Player player, ActiveFormSession session) {
        if (!session.markCloseDispatched()) {
            return;
        }

        dispatchClose(player, session);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void dispatchClose(Player player, ActiveFormSession session) {
        InventoryCloseEvent closeEvent = new InventoryCloseEvent(session.getMockView());
        MenuView menuView = session.getSuperiorMenuView();
        if (menuView != null) {
            Menu menu = menuView.getMenu();
            if (menu != null) {
                menu.onClose(closeEvent, menuView);
                return;
            }
        }

        Bukkit.getPluginManager().callEvent(closeEvent);
    }

    private void closeSessionLaterIfStillActive(Player player, UUID uuid, ActiveFormSession session) {
        if (!plugin.isEnabled()) {
            activeSessions.remove(uuid, session);
            dispatchCloseOnce(player, session);
            return;
        }

        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (activeSessions.remove(uuid, session)) {
                    dispatchCloseOnce(player, session);
                }
            }, POST_CLICK_CLEANUP_DELAY_TICKS);
        } catch (IllegalStateException ignored) {
            activeSessions.remove(uuid, session);
        }
    }

    private void runOnMainThread(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalStateException ignored) {
            // Server is shutting down or the plugin was disabled between the response and scheduling.
        }
    }

    private MenuView<?, ?> getSuperiorMenuView(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof MenuView<?, ?> menuView ? menuView : null;
    }

    private boolean isLayoutFillerMaterial(String materialName) {
        return configManager.isFillerMaterial(materialName)
                || materialName.endsWith("_STAINED_GLASS_PANE")
                || materialName.equals("GLASS_PANE");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean hasSuperiorButton(MenuView<?, ?> menuView, int slot) {
        try {
            MenuView rawView = menuView;
            Menu menu = rawView.getMenu();
            return menu != null && menu.getLayout() != null && menu.getLayout().getButton(slot) != null;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to inspect a SuperiorSkyblock menu button at slot " + slot, ex);
            return false;
        }
    }

    private String sanitizeFormText(String input, int maxLength, boolean allowNewLines) {
        if (input == null || maxLength <= 0) {
            return "";
        }

        String stripped = ChatColor.stripColor(input);
        if (stripped == null) {
            return "";
        }

        StringBuilder sanitized = new StringBuilder(Math.min(stripped.length(), maxLength));
        for (int index = 0; index < stripped.length() && sanitized.length() < maxLength; index++) {
            char ch = stripped.charAt(index);
            if (ch == '\n' && allowNewLines) {
                sanitized.append(ch);
            } else if (ch >= 32 && ch != 127) {
                sanitized.append(ch);
            }
        }

        return sanitized.toString();
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
                        case "toString":
                            return "SSBGeyserInventoryView{title=" + title + ", player=" + player.getName() + "}";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
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
                            int rawSlot = (int) args[0];
                            return rawSlot < inventory.getSize() ? rawSlot : rawSlot - inventory.getSize();
                        case "getInventory":
                            int inventorySlot = (int) args[0];
                            if (inventorySlot < 0) {
                                return null;
                            }
                            if (inventorySlot < inventory.getSize()) {
                                return inventory;
                            }
                            return inventorySlot < inventory.getSize() + player.getInventory().getSize() ? player.getInventory() : null;
                        case "getSlotType":
                            int slotTypeIndex = (int) args[0];
                            if (slotTypeIndex < 0 || slotTypeIndex >= inventory.getSize() + player.getInventory().getSize()) {
                                return InventoryType.SlotType.OUTSIDE;
                            }
                            if (slotTypeIndex < inventory.getSize()) {
                                return InventoryType.SlotType.CONTAINER;
                            }
                            return InventoryType.SlotType.QUICKBAR;
                        case "countSlots":
                            return inventory.getSize() + player.getInventory().getSize();
                        case "close":
                            player.closeInventory();
                            return null;
                        case "getCursor":
                            return player.getItemOnCursor();
                        case "setCursor":
                            ItemStack cursorItem = args[0] instanceof ItemStack ? (ItemStack) args[0] : new ItemStack(Material.AIR);
                            player.setItemOnCursor(cursorItem);
                            return null;
                        case "setProperty":
                            return false;
                        case "setItem":
                            int setSlot = (int) args[0];
                            ItemStack setItem = (ItemStack) args[1];
                            if (setSlot < 0) {
                                return null;
                            } else if (setSlot < inventory.getSize()) {
                                inventory.setItem(setSlot, setItem);
                            } else if (setSlot < inventory.getSize() + player.getInventory().getSize()) {
                                player.getInventory().setItem(setSlot - inventory.getSize(), setItem);
                            }
                            return null;
                        case "getItem":
                            int getSlot = (int) args[0];
                            if (getSlot < 0) {
                                return null;
                            } else if (getSlot < inventory.getSize()) {
                                return inventory.getItem(getSlot);
                            } else if (getSlot < inventory.getSize() + player.getInventory().getSize()) {
                                return player.getInventory().getItem(getSlot - inventory.getSize());
                            }
                            return null;
                        case "getOriginalTitle":
                            return title;
                        case "setTitle":
                            return null;
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
        private final MenuView<?, ?> superiorMenuView;
        private final AtomicBoolean responseHandled = new AtomicBoolean(false);
        private final AtomicBoolean closeDispatched = new AtomicBoolean(false);

        public ActiveFormSession(Inventory inventory, String title, List<Integer> buttonToSlotMap, InventoryView mockView, MenuView<?, ?> superiorMenuView) {
            this.inventory = inventory;
            this.title = title;
            this.buttonToSlotMap = Collections.unmodifiableList(new ArrayList<>(buttonToSlotMap));
            this.mockView = mockView;
            this.superiorMenuView = superiorMenuView;
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

        public MenuView<?, ?> getSuperiorMenuView() {
            return superiorMenuView;
        }

        public boolean markResponseHandled() {
            return responseHandled.compareAndSet(false, true);
        }

        public boolean markCloseDispatched() {
            return closeDispatched.compareAndSet(false, true);
        }
    }
}
