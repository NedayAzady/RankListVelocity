package com.customlist;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Plugin(
        id = "ranklistvelocity",
        name = "RankListVelocity",
        version = "1.0-SNAPSHOT",
        description = "Custom player list for Velocity based on LuckPerms",
        authors = {"Your Name"},
        dependencies = {@Dependency(id = "luckperms")}
)
public class RankListVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private LuckPerms luckPerms;
    private Config config;

    @Inject
    public RankListVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            logger.error("LuckPerms not found! Disabling commands.");
            return;
        }

        CommandManager commandManager = server.getCommandManager();
        commandManager.register(commandManager.metaBuilder("list").aliases("who", "online").build(), new ListCommand());
        logger.info("RankListVelocity enabled successfully.");
    }

    private void loadConfig() {
        if (!Files.exists(dataDirectory)) {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException e) {
                logger.error("Failed to create data directory", e);
                return;
            }
        }

        File configFile = new File(dataDirectory.toFile(), "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                } else {
                    logger.error("Default config.yml not found in resources");
                }
            } catch (IOException e) {
                logger.error("Failed to save default config", e);
            }
        }

        try (InputStream in = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(in);
            config = new Config(map);
        } catch (Exception e) {
            logger.error("Failed to load config", e);
            config = new Config(new HashMap<>()); // Fallback to empty map
        }
    }
    
    private Component color(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
    
    private String colorStr(String s) {
        // Simple replacer for internal string building if needed, though mostly we use Component
        return s.replace("&", "§");
    }

    private class Config {
        String defaultPrefix = "&7[Default]";
        List<String> listFormat = Arrays.asList("{header}", " ", "{players}");
        String playerFormat = "{prefix} {name}";
        String playerDelimiter = "&8, ";
        String moreFormat = "&7(+{more} more)";
        int maxDisplay = 30;
        String groupsHeader = "&cOwner&7, &4Manager&7, &cAdmin&7, &5Moderator&7, &9Helper&7, &aBuilder&7, &dPartner&7, &6Famous&7, &dMedia&7, &bACE&7, &bMVP&7, &bPRO&7, &bVIP&7, &7Default &8(&e{online}&8/&e{limit}&8)&8:";
        List<String> sortOrder = Arrays.asList(
                "owner", "manager", "admin", "moderator", "helper", "builder",
                "partner", "famous", "media", "ace", "mvp", "pro", "vip", "default"
        );

        @SuppressWarnings("unchecked")
        Config(Map<String, Object> map) {
            if (map.containsKey("default-prefix")) defaultPrefix = (String) map.get("default-prefix");
            if (map.containsKey("list-format")) listFormat = (List<String>) map.get("list-format");
            if (map.containsKey("player-format")) playerFormat = (String) map.get("player-format");
            if (map.containsKey("player-delimiter")) playerDelimiter = (String) map.get("player-delimiter");
            if (map.containsKey("more-format")) moreFormat = (String) map.get("more-format");
            if (map.containsKey("max-display")) maxDisplay = (int) map.get("max-display");
            if (map.containsKey("groups-header")) groupsHeader = (String) map.get("groups-header");
            if (map.containsKey("sort-order")) {
                sortOrder = (List<String>) map.get("sort-order");
                sortOrder.replaceAll(String::toLowerCase);
            }
        }
    }

    private class ListCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (invocation.arguments().length == 1 && invocation.arguments()[0].equalsIgnoreCase("reload")) {
                if (invocation.source().hasPermission("ranklist.reload")) {
                    loadConfig();
                    invocation.source().sendMessage(Component.text("RankListVelocity configuration reloaded."));
                    return;
                }
            }

            List<Player> players = new ArrayList<>(server.getAllPlayers());
            
            players.sort((p1, p2) -> {
                String group1 = getPrimaryGroup(p1);
                String group2 = getPrimaryGroup(p2);

                int index1 = config.sortOrder.indexOf(group1.toLowerCase());
                int index2 = config.sortOrder.indexOf(group2.toLowerCase());

                if (index1 == -1) index1 = Integer.MAX_VALUE;
                if (index2 == -1) index2 = Integer.MAX_VALUE;

                if (index1 != index2) {
                    return Integer.compare(index1, index2);
                }
                
                return p1.getUsername().compareToIgnoreCase(p2.getUsername());
            });

            int online = players.size();
            int max = server.getConfiguration().getShowMaxPlayers();

            List<String> formattedPlayers = new ArrayList<>();
            int count = 0;
            for (Player p : players) {
                if (count >= config.maxDisplay) {
                    break;
                }
                formattedPlayers.add(formatPlayer(p));
                count++;
            }

            String playerListStr = String.join(colorStr(config.playerDelimiter), formattedPlayers);

            if (online > config.maxDisplay) {
                int more = online - config.maxDisplay;
                String moreStr = config.moreFormat.replace("{more}", String.valueOf(more));
                playerListStr += " " + colorStr(moreStr);
            }

            String header = config.groupsHeader.replace("{online}", String.valueOf(online))
                    .replace("{limit}", String.valueOf(max));

            for (String line : config.listFormat) {
                line = line.replace("{header}", header)
                        .replace("{players}", playerListStr)
                        .replace("{limit}", String.valueOf(max))
                        .replace("{online}", String.valueOf(online));
                invocation.source().sendMessage(color(line));
            }
        }
    }

    private String formatPlayer(Player player) {
        String format = config.playerFormat;
        String prefix = getPrefix(player);
        if (prefix == null || prefix.isEmpty() || prefix.equals("null")) {
            prefix = config.defaultPrefix;
        }
        
        format = format.replace("{prefix}", prefix)
                .replace("{name}", player.getUsername());
        
        return colorStr(format);
    }

    private String getPrefix(Player player) {
        if (luckPerms == null) return config.defaultPrefix;
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user != null) {
            String prefix = user.getCachedData().getMetaData().getPrefix();
            return prefix != null ? prefix : "";
        }
        return "";
    }

    private String getPrimaryGroup(Player player) {
        if (luckPerms == null) return "default";
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user != null) {
            return user.getPrimaryGroup();
        }
        return "default";
    }
}
