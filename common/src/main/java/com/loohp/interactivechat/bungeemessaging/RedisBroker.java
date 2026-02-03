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
import com.loohp.platformscheduler.ScheduledTask;
import com.loohp.platformscheduler.Scheduler;
import org.bukkit.Bukkit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.net.URI;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Data broker implementation using Redis Pub/Sub.
 * Allows direct server-to-server communication without requiring a proxy plugin.
 */
public class RedisBroker implements DataBroker {

    private static final String CHANNEL_PREFIX = "interactivechat:";

    private final InteractiveChat plugin;
    private final String serverName;
    private final String redisUrl;
    private final String channelName;

    private JedisPool jedisPool;
    private JedisPubSub subscriber;
    private ExecutorService subscriberExecutor;
    private ScheduledTask playerListBroadcastTask;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public RedisBroker(InteractiveChat plugin, String serverName, String redisUrl, String channelSuffix) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.redisUrl = redisUrl;
        this.channelName = CHANNEL_PREFIX + channelSuffix;
    }

    @Override
    public boolean start() {
        if (started.get()) {
            return true;
        }

        try {
            // Create Jedis pool
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);

            jedisPool = new JedisPool(poolConfig, URI.create(redisUrl));

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            // Create message listener
            InteractiveChat.bungeeMessageListener = new BungeeMessageListener(plugin);

            // Create subscriber
            subscriber = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    if (!channel.equals(channelName)) {
                        return;
                    }

                    try {
                        // Parse message: serverName|base64data
                        int separatorIndex = message.indexOf('|');
                        if (separatorIndex == -1) {
                            return;
                        }

                        String senderServer = message.substring(0, separatorIndex);

                        // Ignore messages from ourselves
                        if (senderServer.equals(serverName)) {
                            return;
                        }

                        String base64Data = message.substring(separatorIndex + 1);
                        byte[] data = Base64.getDecoder().decode(base64Data);

                        // Process the message using BungeeMessageListener
                        processIncomingData(data);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onSubscribe(String channel, int subscribedChannels) {
                    connected.set(true);
                }

                @Override
                public void onUnsubscribe(String channel, int subscribedChannels) {
                    connected.set(false);
                }
            };

            // Start subscriber in separate thread
            subscriberExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "InteractiveChat-Redis-Subscriber");
                t.setDaemon(true);
                return t;
            });

            subscriberExecutor.submit(() -> {
                while (started.get() && !Thread.currentThread().isInterrupted()) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.subscribe(subscriber, channelName);
                    } catch (Exception e) {
                        if (started.get()) {
                            Bukkit.getConsoleSender().sendMessage("[InteractiveChat] Redis connection lost, reconnecting in 5 seconds...");
                            connected.set(false);
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            });

            started.set(true);

            // Start periodic player list broadcasting (every 2 seconds = 40 ticks)
            playerListBroadcastTask = Scheduler.runTaskTimerAsynchronously(plugin, () -> {
                if (!started.get() || !connected.get()) {
                    return;
                }
                try {
                    BungeeMessageSender.broadcastPlayerList(System.currentTimeMillis(), serverName, Bukkit.getOnlinePlayers());
                } catch (Exception e) {
                    // Silently ignore periodic broadcast errors
                }
            }, 40, 40);

            // Broadcast player list immediately after startup
            Scheduler.runTaskLaterAsynchronously(plugin, () -> {
                if (started.get() && connected.get()) {
                    try {
                        BungeeMessageSender.broadcastPlayerList(System.currentTimeMillis(), serverName, Bukkit.getOnlinePlayers());
                    } catch (Exception e) {
                        // Silently ignore
                    }
                }
            }, 20);

            Bukkit.getConsoleSender().sendMessage("[InteractiveChat] Redis data broker started (Server: " + serverName + ", Channel: " + channelName + ")");
            return true;

        } catch (Exception e) {
            Bukkit.getConsoleSender().sendMessage("[InteractiveChat] Failed to connect to Redis: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void processIncomingData(byte[] bytes) {
        Scheduler.runTaskAsynchronously(plugin, () -> {
            try {
                if (InteractiveChat.bungeeMessageListener == null) {
                    return;
                }
                InteractiveChat.bungeeMessageListener.onPluginMessageReceived("interchat:main", null, bytes);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void stop() {
        if (!started.getAndSet(false)) {
            return;
        }

        // Cancel scheduled tasks first
        if (playerListBroadcastTask != null) {
            playerListBroadcastTask.cancel();
            playerListBroadcastTask = null;
        }

        try {
            if (subscriber != null && subscriber.isSubscribed()) {
                subscriber.unsubscribe();
            }
        } catch (Exception e) {
            // Ignore unsubscribe errors during shutdown
        }

        if (subscriberExecutor != null) {
            subscriberExecutor.shutdownNow();
            subscriberExecutor = null;
        }

        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            jedisPool = null;
        }

        subscriber = null;
        connected.set(false);
        Bukkit.getConsoleSender().sendMessage("[InteractiveChat] Redis data broker stopped");
    }

    @Override
    public boolean sendData(byte[] data) {
        if (!started.get() || jedisPool == null || jedisPool.isClosed()) {
            return false;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String message = serverName + "|" + Base64.getEncoder().encodeToString(data);
            jedis.publish(channelName, message);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        return started.get() && connected.get();
    }

    @Override
    public DataBrokerType getType() {
        return DataBrokerType.REDIS;
    }

    @Override
    public String getServerName() {
        return serverName;
    }

}
