package com.siberanka.ssbgeyser.config;

import com.siberanka.ssbgeyser.SSBGeyser;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConfigManager {

    private final SSBGeyser plugin;
    private FileConfiguration config;
    private FileConfiguration langConfig;
    private File langFile;

    // GUI settings cache
    private boolean hideFillerItems;
    private List<String> fillerMaterials;
    private Set<String> fillerMaterialSet;
    private Map<String, String> textureMappings;
    private Sound clickSound;

    public ConfigManager(SSBGeyser plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // Load cached values
        this.hideFillerItems = config.getBoolean("gui.hide-filler-items", true);
        this.fillerMaterials = config.getStringList("gui.filler-materials");
        this.fillerMaterialSet = new HashSet<>();
        for (String fillerMaterial : this.fillerMaterials) {
            if (fillerMaterial != null) {
                this.fillerMaterialSet.add(fillerMaterial.toUpperCase(Locale.ROOT));
            }
        }
        this.clickSound = parseSound(config.getString("gui.click-sound", "UI_BUTTON_CLICK"));
        
        this.textureMappings = new HashMap<>();
        if (config.isConfigurationSection("gui.texture-mappings")) {
            for (String key : config.getConfigurationSection("gui.texture-mappings").getKeys(false)) {
                this.textureMappings.put(key.toUpperCase(), config.getString("gui.texture-mappings." + key));
            }
        }

        // Load language file
        String langCode = config.getString("language", "tr").toLowerCase();
        String langFileName = "messages_" + langCode + ".yml";
        this.langFile = new File(plugin.getDataFolder(), langFileName);

        if (!langFile.exists()) {
            plugin.saveResource(langFileName, false);
        }

        this.langConfig = YamlConfiguration.loadConfiguration(langFile);

        // Load default lang for missing keys
        InputStream defaultLangStream = plugin.getResource(langFileName);
        if (defaultLangStream != null) {
            YamlConfiguration defaultLangConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultLangStream, StandardCharsets.UTF_8)
            );
            this.langConfig.setDefaults(defaultLangConfig);
        }
    }

    public String getMessage(String key) {
        String message = langConfig.getString(key);
        if (message == null) {
            return key;
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String getMessage(String key, String def) {
        String message = langConfig.getString(key, def);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public boolean isHideFillerItems() {
        return hideFillerItems;
    }

    public List<String> getFillerMaterials() {
        return fillerMaterials;
    }

    public boolean isFillerMaterial(String materialName) {
        return materialName != null && fillerMaterialSet.contains(materialName.toUpperCase(Locale.ROOT));
    }

    public Map<String, String> getTextureMappings() {
        return textureMappings;
    }

    public Sound getClickSound() {
        return clickSound;
    }

    public FileConfiguration getConfig() {
        return config;
    }

    private Sound parseSound(String rawSound) {
        if (rawSound == null || rawSound.isBlank()) {
            return null;
        }

        try {
            return Sound.valueOf(rawSound.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid gui.click-sound value '" + rawSound + "'. Click sound disabled.");
            return null;
        }
    }
}
