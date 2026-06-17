package com.siberanka.ssbgeyser.util;

import com.siberanka.ssbgeyser.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.geysermc.cumulus.util.FormImage;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class TextureMapper {

    private final ConfigManager configManager;
    private static final Map<String, String> VANILLA_CORRECTIONS = new HashMap<>();

    static {
        // Map common Java materials that differ from Bedrock texture file names
        VANILLA_CORRECTIONS.put("SLIME_BALL", "slimeball");
        VANILLA_CORRECTIONS.put("REDSTONE", "redstone_dust");
        VANILLA_CORRECTIONS.put("GLISTERING_MELON_SLICE", "melon_speckled");
        VANILLA_CORRECTIONS.put("GOLDEN_CARROT", "carrot_golden");
        VANILLA_CORRECTIONS.put("EYE_OF_ENDER", "ender_eye");
        VANILLA_CORRECTIONS.put("FERMENTED_SPIDER_EYE", "spider_eye_fermented");
        VANILLA_CORRECTIONS.put("MAP", "map_empty");
        VANILLA_CORRECTIONS.put("FILLED_MAP", "map_filled");
        VANILLA_CORRECTIONS.put("WRITABLE_BOOK", "book_writable");
        VANILLA_CORRECTIONS.put("WRITTEN_BOOK", "book_written");
        VANILLA_CORRECTIONS.put("COMPASS", "compass_item");
        VANILLA_CORRECTIONS.put("CLOCK", "clock_item");
        VANILLA_CORRECTIONS.put("FIREWORK_ROCKET", "fireworks");
        VANILLA_CORRECTIONS.put("FIREWORK_STAR", "fireworks_charge");
        VANILLA_CORRECTIONS.put("NETHER_STAR", "nether_star");
    }

    public TextureMapper(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public TextureResult getTexture(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return new TextureResult(FormImage.Type.PATH, "textures/blocks/glass");
        }

        Material material = item.getType();
        String matName = material.name();

        // 1. Check user config overrides
        Map<String, String> overrides = configManager.getTextureMappings();
        if (overrides.containsKey(matName)) {
            String overridePath = overrides.get(matName);
            FormImage.Type type = overridePath.startsWith("http") ? FormImage.Type.URL : FormImage.Type.PATH;
            return new TextureResult(type, overridePath);
        }

        // 2. Check if it's a player head with custom skin URL
        if (material == Material.PLAYER_HEAD) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof SkullMeta skullMeta) {
                try {
                    PlayerProfile profile = skullMeta.getPlayerProfile();
                    if (profile != null && profile.getTextures() != null) {
                        URL skinUrl = profile.getTextures().getSkin();
                        if (skinUrl != null) {
                            return new TextureResult(FormImage.Type.URL, skinUrl.toString());
                        }
                    }
                } catch (Throwable ignored) {
                    // Fallback to offline skin or default texture if profile API fails
                }
            }
        }

        // 3. Fallback to standard item/block path
        String textureName = matName.toLowerCase();
        if (VANILLA_CORRECTIONS.containsKey(matName)) {
            textureName = VANILLA_CORRECTIONS.get(matName);
        }

        if (material.isBlock()) {
            return new TextureResult(FormImage.Type.PATH, "textures/blocks/" + textureName);
        } else {
            return new TextureResult(FormImage.Type.PATH, "textures/items/" + textureName);
        }
    }

    public static class TextureResult {
        private final FormImage.Type type;
        private final String path;

        public TextureResult(FormImage.Type type, String path) {
            this.type = type;
            this.path = path;
        }

        public FormImage.Type getType() {
            return type;
        }

        public String getPath() {
            return path;
        }
    }
}
