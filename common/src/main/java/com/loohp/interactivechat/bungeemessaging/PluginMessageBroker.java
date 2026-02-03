/*
 * This file is part of InteractiveChat4.
 *
 * Copyright (C) 2020 - 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2020 - 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.interactivechat.bungeemessaging;

import com.loohp.interactivechat.InteractiveChat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Random;

/**
 * Data broker implementation using BungeeCord/Velocity plugin messaging.
 */
public class PluginMessageBroker implements DataBroker {

    private static final String CHANNEL = "interchat:main";
    private static final Random random = new Random();

    private final InteractiveChat plugin;
    private final String serverName;
    private boolean started;

    public PluginMessageBroker(InteractiveChat plugin, String serverName) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.started = false;
    }

    @Override
    public boolean start() {
        if (started) {
            return true;
        }

        Bukkit.getConsoleSender().sendMessage("[InteractiveChat] Registering Plugin Messaging Channels for bungeecord...");
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, InteractiveChat.bungeeMessageListener = new BungeeMessageListener(plugin));

        ServerPingListener.listen();
        started = true;
        return true;
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }

        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL);
        started = false;
    }

    @Override
    public boolean sendData(byte[] data) {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return false;
        }

        Player player = players.stream().skip(random.nextInt(players.size())).findAny().orElse(null);
        if (player == null) {
            return false;
        }

        player.sendPluginMessage(plugin, CHANNEL, data);
        return true;
    }

    @Override
    public boolean isConnected() {
        return started && !Bukkit.getOnlinePlayers().isEmpty();
    }

    @Override
    public DataBrokerType getType() {
        return DataBrokerType.PLUGIN_MESSAGE;
    }

    @Override
    public String getServerName() {
        return serverName;
    }

}
