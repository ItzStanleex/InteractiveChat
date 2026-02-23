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

package com.loohp.interactivechat.listeners.packet;

import com.loohp.interactivechat.InteractiveChat;
import com.loohp.interactivechat.api.InteractiveChatAPI;
import com.loohp.interactivechat.objectholders.ICPlayer;
import com.loohp.interactivechat.objectholders.ICPlayerFactory;
import com.loohp.interactivechat.utils.ChatColorUtils;
import com.loohp.interactivechat.utils.ComponentStyling;
import com.loohp.interactivechat.utils.PlaceholderParser;
import com.loohp.interactivechat.utils.PlayerUtils;
import com.loohp.platformscheduler.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class OutTabCompletePacketHandler {

    public static final AtomicReference<Map<String, UUID>> playerNames = new AtomicReference<>(Collections.emptyMap());
    // Cached parsed tooltips per player UUID - updated asynchronously to avoid synchronous PlaceholderAPI parsing
    private static final Map<UUID, Component> cachedTooltips = new ConcurrentHashMap<>();

    public static void init() {
        schedulePlayerNamesUpdate();
    }

    private static void schedulePlayerNamesUpdate() {
        Scheduler.runTaskTimerAsynchronously(InteractiveChat.plugin, () -> {
            if (InteractiveChat.useTooltipOnTab) {
                Map<String, UUID> names = new HashMap<>();
                Map<UUID, Component> tooltips = new HashMap<>();
                for (ICPlayer player : ICPlayerFactory.getOnlineICPlayers()) {
                    addPlayerNames(names, player);
                    // Pre-parse tooltips asynchronously to avoid blocking main thread
                    try {
                        Component tooltip = PlaceholderParser.parse(player, InteractiveChat.tabTooltip);
                        tooltips.put(player.getUniqueId(), tooltip);
                    } catch (Exception e) {
                        // Ignore parsing errors for individual players
                    }
                }
                Scheduler.runTask(InteractiveChat.plugin, () -> {
                    playerNames.set(names);
                    cachedTooltips.clear();
                    cachedTooltips.putAll(tooltips);
                });
            }
        }, 0, 100);
    }

    private static void addPlayerNames(Map<String, UUID> playernames, ICPlayer player) {
        playernames.put(ChatColorUtils.stripColor(player.getName()), player.getUniqueId());
        if (!player.getName().equals(player.getDisplayName())) {
            playernames.put(ChatColorUtils.stripColor(player.getDisplayName()), player.getUniqueId());
        }
        List<String> names = InteractiveChatAPI.getNicknames(player.getUniqueId());
        for (String name : names) {
            playernames.put(ChatColorUtils.stripColor(name), player.getUniqueId());
        }
    }

    public static ICPlayer findICPlayer(String text) {
        for (Map.Entry<String, UUID> entry : playerNames.get().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(text)) {
                return ICPlayerFactory.getICPlayer(entry.getValue());
            }
        }
        return null;
    }

    public static Component createComponent(ICPlayer icplayer, Player tabCompleter) {
        // Use cached tooltip to avoid synchronous PlaceholderAPI parsing on main thread
        Component component = cachedTooltips.get(icplayer.getUniqueId());
        if (component == null) {
            // Fallback: return simple name if not cached yet (first 5 seconds after join)
            component = Component.text(icplayer.getDisplayName());
        }
        if (!PlayerUtils.canChatColor(tabCompleter)) {
            component = ComponentStyling.stripColor(component);
        }
        return component;
    }
}
