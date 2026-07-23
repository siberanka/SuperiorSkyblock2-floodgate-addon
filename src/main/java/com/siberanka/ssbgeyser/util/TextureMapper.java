package com.siberanka.ssbgeyser.util;

import com.siberanka.ssbgeyser.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.geysermc.cumulus.util.FormImage;

import java.net.URI;
import java.net.URL;
import java.util.Map;

public class TextureMapper {

    private static final int MAX_TEXTURE_VALUE_LENGTH = 512;
    private final ConfigManager configManager;

    public TextureMapper(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public TextureResult getTexture(ItemStack item, boolean islandCreationHead) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }

        Material material = item.getType();
        String matName = material.name();

        // Custom Java head skins are full skin atlases, not icon-ready images. On the
        // island creation form, replace them with a stable Bedrock world-template glyph.
        if (islandCreationHead && material == Material.PLAYER_HEAD) {
            return createSafeTextureResult(configManager.getIslandCreationIcon());
        }

        // Prefer real skin URLs for player heads; guessed Bedrock paths often render as missing textures.
        if (material == Material.PLAYER_HEAD) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof SkullMeta skullMeta) {
                try {
                    PlayerProfile profile = skullMeta.getPlayerProfile();
                    if (profile != null && profile.getTextures() != null) {
                        URL skinUrl = profile.getTextures().getSkin();
                        if (skinUrl != null && isAllowedTextureUrl(skinUrl.toString())) {
                            return new TextureResult(FormImage.Type.URL, skinUrl.toString());
                        }
                    }
                } catch (Throwable ignored) {
                    // Fallback to offline skin or default texture if profile API fails
                }
            }
        }

        // Only send explicit, validated mappings. Unknown paths are intentionally omitted
        // so Bedrock clients render a clean text button instead of the purple/black missing texture.
        Map<String, String> overrides = configManager.getTextureMappings();
        if (overrides.containsKey(matName)) {
            return createSafeTextureResult(overrides.get(matName));
        }

        return null;
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

    private TextureResult createSafeTextureResult(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String value = rawValue.trim();
        if (value.isEmpty() || value.length() > MAX_TEXTURE_VALUE_LENGTH) {
            return null;
        }

        if (isAllowedTextureUrl(value)) {
            return new TextureResult(FormImage.Type.URL, value);
        }

        if (isAllowedTexturePath(value)) {
            return new TextureResult(FormImage.Type.PATH, value);
        }

        return null;
    }

    private boolean isAllowedTextureUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && value.length() <= MAX_TEXTURE_VALUE_LENGTH;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean isAllowedTexturePath(String value) {
        if (value.length() > MAX_TEXTURE_VALUE_LENGTH || value.startsWith("/") || value.contains("..")) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            boolean allowed = Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '/' || ch == '.';
            if (!allowed) {
                return false;
            }
        }

        return value.startsWith("textures/");
    }
}
