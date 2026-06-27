package com.siberanka.ssbgeyser;

import com.siberanka.ssbgeyser.config.ConfigManager;
import com.siberanka.ssbgeyser.form.FormManager;
import com.siberanka.ssbgeyser.listener.MenuOpenListener;
import com.siberanka.ssbgeyser.util.TextureMapper;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SSBGeyser extends JavaPlugin implements TabExecutor {

    private ConfigManager configManager;
    private TextureMapper textureMapper;
    private FormManager formManager;

    @Override
    public void onEnable() {
        if (!hasRequiredPlugin("SuperiorSkyblock2") || !hasRequiredPlugin("floodgate")) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 1. Initialize configuration and languages
        this.configManager = new ConfigManager(this);

        // 2. Initialize utility mappers and form managers
        this.textureMapper = new TextureMapper(configManager);
        this.formManager = new FormManager(this, configManager, textureMapper);

        // 3. Register Event Listeners
        getServer().getPluginManager().registerEvents(new MenuOpenListener(this, formManager), this);

        // 4. Register commands
        if (getCommand("ssbgeyser") != null) {
            getCommand("ssbgeyser").setExecutor(this);
            getCommand("ssbgeyser").setTabCompleter(this);
        }

        getLogger().info("SSBGeyser Addon has been successfully enabled! Supported version: SuperiorSkyblock2 26.1.2");
    }

    @Override
    public void onDisable() {
        // Gracefully and instantly terminate all opened Bedrock forms to prevent menu state lock or inventory desync
        if (formManager != null) {
            formManager.closeAllActiveForms();
        }
        getLogger().info("SSBGeyser Addon has been successfully disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("ssbgeyser.admin")) {
                sender.sendMessage(configManager.getMessage("prefix") + configManager.getMessage("no-permission"));
                return true;
            }

            configManager.reload();
            sender.sendMessage(configManager.getMessage("prefix") + configManager.getMessage("reloaded"));
            return true;
        }

        sender.sendMessage(configManager.getMessage("prefix") + ChatColor.YELLOW + "SSBGeyser Addon - Usage: /ssbgeyser reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("ssbgeyser.admin")) {
                completions.add("reload");
            }
            return completions;
        }
        return Collections.emptyList();
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public FormManager getFormManager() {
        return formManager;
    }

    private boolean hasRequiredPlugin(String pluginName) {
        if (getServer().getPluginManager().isPluginEnabled(pluginName)) {
            return true;
        }

        getLogger().severe("Required dependency is missing or disabled: " + pluginName);
        return false;
    }
}
