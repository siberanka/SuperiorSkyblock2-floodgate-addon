package com.siberanka.ssbgeyser.form;

import com.siberanka.ssbgeyser.SSBGeyser;
import com.siberanka.ssbgeyser.config.ConfigManager;
import com.siberanka.ssbgeyser.util.BedrockTextFormatter;
import com.siberanka.ssbgeyser.util.TextureMapper;
import com.bgsoftware.superiorskyblock.api.menu.Menu;
import com.bgsoftware.superiorskyblock.api.menu.view.MenuView;
import org.bukkit.Bukkit;
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
    private final Map<UUID, Long> lastClickMillis = new ConcurrentHashMap<>();

    public FormManager(SSBGeyser plugin, ConfigManager configManager, TextureMapper textureMapper) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.textureMapper = textureMapper;
    }

    /**
     * Translates a Java Chest GUI into a Bedrock SimpleForm and sends it.
     */
    public void sendForm(Player player, Inventory inventory, String title) {
        if (!plugin.isEnabled() || !player.isOnline() || inventory == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        FloodgatePlayer floodgatePlayer;
        try {
            floodgatePlayer = FloodgateApi.getInstance().getPlayer(uuid);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Floodgate API was unavailable while preparing a Bedrock form for " + safeForLog(player.getName()) + ".", ex);
            return;
        }
        if (floodgatePlayer == null) return;

        ActiveFormSession previousSession = activeSessions.remove(uuid);
        if (previousSession != null) {
            dispatchCloseOnce(player, previousSession);
        }

        SimpleForm.Builder formBuilder = SimpleForm.builder();
        formBuilder.title(sanitizeFormText(title, MAX_TITLE_LENGTH, false));
        formBuilder.content(sanitizeFormText(configManager.getMessage("menu-header-info", "Select an option below:"), MAX_CONTENT_LENGTH, false));

        List<ButtonTarget> buttonTargets = new ArrayList<>();
        int size = inventory.getSize();
        MenuView<?, ?> superiorMenuView = getSuperiorMenuView(inventory);
        boolean bankLogsMenu = isBankLogsMenu(superiorMenuView);

        for (int slot = 0; slot < size; slot++) {
            try {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType().isAir()) {
                    continue;
                }

                String matName = item.getType().name();
                if (configManager.isHideFillerItems() && isLayoutFillerMaterial(matName)) {
                    continue;
                }

                ItemMeta meta = item.getItemMeta();
                String displayName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : formatItemName(matName);
                List<String> lore = (meta != null && meta.hasLore()) ? meta.getLore() : new ArrayList<>();

                String buttonText = buildButtonText(displayName, lore,
                        bankLogsMenu && item.getType() == Material.PAPER);

                if (configManager.isHideEmptyButtons() && !BedrockTextFormatter.hasVisibleText(buttonText)) {
                    continue;
                }

                // Map texture
                TextureMapper.TextureResult texture = textureMapper.getTexture(item);
                if (texture != null) {
                    formBuilder.button(buttonText, texture.getType(), texture.getPath());
                } else {
                    formBuilder.button(buttonText);
                }
                buttonTargets.add(new ButtonTarget(slot, item.getType()));
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Skipped an invalid Bedrock form item at slot " + slot + " for " + safeForLog(player.getName()) + ".", ex);
            }
        }

        if (buttonTargets.isEmpty()) {
            InventoryView emptyView = createMockView(player, inventory, title);
            dispatchCloseOnce(player, new ActiveFormSession(inventory, title, buttonTargets, emptyView, superiorMenuView));
            return;
        }

        // Create the custom dynamic view proxy
        InventoryView mockView = createMockView(player, inventory, title);
        ActiveFormSession session = new ActiveFormSession(inventory, title, buttonTargets, mockView, superiorMenuView);
        activeSessions.put(uuid, session);

        formBuilder.validResultHandler(response -> {
            int buttonId = response.clickedButtonId();
            if (buttonId >= 0 && buttonId < buttonTargets.size()) {
                simulateClick(player, uuid, buttonTargets.get(buttonId), session);
            } else {
                simulateClose(player, uuid, session);
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
            plugin.getLogger().log(Level.SEVERE, "Failed to send Bedrock form for player " + safeForLog(player.getName()), ex);
        }
    }

    /**
     * Simulates the inventory click on the main server thread.
     */
    private void simulateClick(Player player, UUID uuid, ButtonTarget target, ActiveFormSession session) {
        runOnMainThread(() -> {
            if (!session.markResponseHandled() || activeSessions.get(uuid) != session) {
                return;
            }

            if (!player.isOnline()) {
                activeSessions.remove(uuid, session);
                return;
            }

            if (isClickRateLimited(uuid) || session.isExpired(configManager.getFormSessionTimeoutMillis()) || !isClickTargetStillValid(session, target)) {
                activeSessions.remove(uuid, session);
                dispatchCloseOnce(player, session);
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
                        target.getSlot(),
                        ClickType.LEFT,
                        InventoryAction.PICKUP_ALL
                );

                dispatchClick(clickEvent, session);
            } catch (Exception e) {
                player.sendMessage(configManager.getMessage("prefix") + configManager.getMessage("error-occurred"));
                plugin.getLogger().log(Level.SEVERE, "Error simulating inventory click for Bedrock player " + safeForLog(player.getName()), e);
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
                plugin.getLogger().log(Level.SEVERE, "Error simulating inventory close for Bedrock player " + safeForLog(player.getName()), e);
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
        lastClickMillis.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    public void handlePlayerDisconnect(Player player) {
        ActiveFormSession session = activeSessions.remove(player.getUniqueId());
        lastClickMillis.remove(player.getUniqueId());
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
        return configManager.isFillerMaterial(materialName);
    }

    private boolean isBankLogsMenu(MenuView<?, ?> menuView) {
        if (menuView == null || menuView.getMenu() == null) {
            return false;
        }

        String identifier = menuView.getMenu().getIdentifier();
        return "MenuBankLogs".equalsIgnoreCase(identifier)
                || "bank-logs".equalsIgnoreCase(identifier)
                || "MenuBankLogs".equals(menuView.getMenu().getClass().getSimpleName());
    }

    private String buildButtonText(String displayName, List<String> lore, boolean compactBankLog) {
        StringBuilder buttonText = new StringBuilder(MAX_BUTTON_TEXT_LENGTH);
        appendButtonLine(buttonText, displayName, configManager.getButtonTitleColor());

        if (lore == null || lore.isEmpty() || buttonText.length() >= MAX_BUTTON_TEXT_LENGTH) {
            return buttonText.toString();
        }

        List<String> visibleLore = new ArrayList<>();
        for (String line : lore) {
            if (visibleLore.size() >= MAX_LORE_LINES) {
                break;
            }
            if (!sanitizeFormText(line, MAX_BUTTON_TEXT_LENGTH, false).isBlank()) {
                visibleLore.add(line);
            }
        }

        if (compactBankLog && visibleLore.size() >= 4) {
            appendCombinedButtonLine(buttonText, visibleLore.get(0), visibleLore.get(1));
            appendCombinedButtonLine(buttonText, visibleLore.get(2), visibleLore.get(3));
        } else {
            for (String line : visibleLore) {
                appendButtonLine(buttonText, line, configManager.getButtonLoreColor());
            }
        }

        return buttonText.toString();
    }

    private void appendCombinedButtonLine(StringBuilder buttonText, String first, String second) {
        int remainingLength = getRemainingButtonTextLength(buttonText);
        if (remainingLength <= 0) {
            return;
        }

        String formattedFirst = BedrockTextFormatter.formatButtonLine(first, remainingLength,
                configManager.getButtonLoreColor(), configManager.isRemapLowContrastText());
        String formattedSecond = BedrockTextFormatter.formatButtonLine(second, remainingLength,
                configManager.getButtonLoreColor(), configManager.isRemapLowContrastText());
        String combinedLine = BedrockTextFormatter.formatButtonLine(formattedFirst + " | " + formattedSecond,
                remainingLength, configManager.getButtonLoreColor(), configManager.isRemapLowContrastText());
        appendFormattedButtonLine(buttonText, combinedLine);
    }

    private void appendButtonLine(StringBuilder buttonText, String rawLine, char defaultColor) {
        int remainingLength = getRemainingButtonTextLength(buttonText);
        if (remainingLength <= 0) {
            return;
        }

        String formatted = BedrockTextFormatter.formatButtonLine(rawLine, remainingLength, defaultColor,
                configManager.isRemapLowContrastText());
        appendFormattedButtonLine(buttonText, formatted);
    }

    private void appendFormattedButtonLine(StringBuilder buttonText, String formattedLine) {
        if (!BedrockTextFormatter.hasVisibleText(formattedLine)) {
            return;
        }

        if (buttonText.length() > 0) {
            buttonText.append('\n');
        }
        buttonText.append(formattedLine);
    }

    private int getRemainingButtonTextLength(StringBuilder buttonText) {
        return MAX_BUTTON_TEXT_LENGTH - buttonText.length() - (buttonText.length() == 0 ? 0 : 1);
    }

    private boolean isClickRateLimited(UUID uuid) {
        long minIntervalMillis = configManager.getMinClickIntervalMillis();
        if (minIntervalMillis <= 0L) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long previous = lastClickMillis.put(uuid, now);
        return previous != null && now - previous < minIntervalMillis;
    }

    private boolean isClickTargetStillValid(ActiveFormSession session, ButtonTarget target) {
        Inventory inventory = session.getInventory();
        int slot = target.getSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return false;
        }

        MenuView<?, ?> menuView = session.getSuperiorMenuView();
        if (menuView != null && inventory.getHolder() != menuView) {
            return false;
        }

        ItemStack currentItem = inventory.getItem(slot);
        if (currentItem == null || currentItem.getType().isAir() || currentItem.getType() != target.getMaterial()) {
            return false;
        }

        if (configManager.isHideFillerItems() && isLayoutFillerMaterial(currentItem.getType().name())) {
            return false;
        }

        ItemMeta meta = currentItem.getItemMeta();
        String displayName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : formatItemName(currentItem.getType().name());
        if (!sanitizeFormText(displayName, MAX_BUTTON_TEXT_LENGTH, false).isBlank()) {
            return true;
        }

        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return !configManager.isHideEmptyButtons();
        }

        for (String line : meta.getLore()) {
            if (!sanitizeFormText(line, MAX_BUTTON_TEXT_LENGTH, false).isBlank()) {
                return true;
            }
        }

        return !configManager.isHideEmptyButtons();
    }

    private String sanitizeFormText(String input, int maxLength, boolean allowNewLines) {
        if (input == null || maxLength <= 0) {
            return "";
        }

        return BedrockTextFormatter.plainText(input, maxLength, allowNewLines);
    }

    private String safeForLog(String input) {
        return sanitizeFormText(input, 64, false);
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

    private static class ButtonTarget {
        private final int slot;
        private final Material material;

        private ButtonTarget(int slot, Material material) {
            this.slot = slot;
            this.material = material;
        }

        public int getSlot() {
            return slot;
        }

        public Material getMaterial() {
            return material;
        }
    }

    private static class ActiveFormSession {
        private final Inventory inventory;
        private final String title;
        private final List<ButtonTarget> buttonTargets;
        private final InventoryView mockView;
        private final MenuView<?, ?> superiorMenuView;
        private final long createdAtMillis;
        private final AtomicBoolean responseHandled = new AtomicBoolean(false);
        private final AtomicBoolean closeDispatched = new AtomicBoolean(false);

        public ActiveFormSession(Inventory inventory, String title, List<ButtonTarget> buttonTargets, InventoryView mockView, MenuView<?, ?> superiorMenuView) {
            this.inventory = inventory;
            this.title = title;
            this.buttonTargets = Collections.unmodifiableList(new ArrayList<>(buttonTargets));
            this.mockView = mockView;
            this.superiorMenuView = superiorMenuView;
            this.createdAtMillis = System.currentTimeMillis();
        }

        public Inventory getInventory() {
            return inventory;
        }

        public String getTitle() {
            return title;
        }

        public List<ButtonTarget> getButtonTargets() {
            return buttonTargets;
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

        public boolean isExpired(long timeoutMillis) {
            return timeoutMillis > 0L && System.currentTimeMillis() - createdAtMillis > timeoutMillis;
        }

        public boolean markCloseDispatched() {
            return closeDispatched.compareAndSet(false, true);
        }
    }
}
