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
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
    private Jedis publisherConnection;  // Dedicated connection for publishing
    private JedisPubSub subscriber;
    private ExecutorService subscriberExecutor;
    private ExecutorService publisherExecutor;  // Dedicated thread for async publishing
    private final BlockingQueue<String> publishQueue = new LinkedBlockingQueue<>(10000);  // Async message queue
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
            // Create Jedis pool with optimized settings
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(2);
            // Don't test on every borrow/return - too slow
            poolConfig.setTestOnBorrow(false);
            poolConfig.setTestOnReturn(false);
            // Test idle connections periodically instead
            poolConfig.setTestWhileIdle(true);
            poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
            poolConfig.setMinEvictableIdleTime(Duration.ofMinutes(1));
            // Block briefly if pool exhausted instead of failing immediately
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setMaxWait(Duration.ofMillis(100));

            jedisPool = new JedisPool(poolConfig, URI.create(redisUrl));

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            // Start async publisher thread
            publisherExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "InteractiveChat-Redis-Publisher");
                t.setDaemon(true);
                return t;
            });

            publisherExecutor.submit(this::publisherLoop);

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
                try {
                    if (!started.get() || !connected.get()) {
                        return;
                    }
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

    /**
     * Background loop that consumes messages from the queue and publishes to Redis.
     * Runs on a dedicated thread to avoid blocking the main server thread.
     */
    private void publisherLoop() {
        while (started.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Wait for a message (blocks until available or interrupted)
                String message = publishQueue.take();

                // Skip if we're shutting down
                if (!started.get()) {
                    break;
                }

                // Ensure we have a valid connection
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) {
                    continue;
                }

                // Get or create publisher connection
                if (publisherConnection == null || !publisherConnection.isConnected()) {
                    if (publisherConnection != null) {
                        try { publisherConnection.close(); } catch (Exception ignored) {}
                    }
                    publisherConnection = pool.getResource();
                }

                // Publish the message
                try {
                    publisherConnection.publish(channelName, message);
                } catch (Exception e) {
                    // Connection might be broken, close and retry next iteration
                    try { publisherConnection.close(); } catch (Exception ignored) {}
                    publisherConnection = null;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log but don't crash the publisher thread
                if (started.get()) {
                    e.printStackTrace();
                }
            }
        }

        // Cleanup publisher connection on exit
        if (publisherConnection != null) {
            try { publisherConnection.close(); } catch (Exception ignored) {}
            publisherConnection = null;
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

        // Shutdown publisher executor (this will also close the publisher connection in publisherLoop)
        if (publisherExecutor != null) {
            publisherExecutor.shutdownNow();
            publisherExecutor = null;
        }

        // Clear any pending messages
        publishQueue.clear();

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
        if (!started.get() || !connected.get()) {
            return false;
        }

        // Non-blocking: just add to queue, publisher thread handles the rest
        String message = serverName + "|" + Base64.getEncoder().encodeToString(data);
        return publishQueue.offer(message);  // Returns false if queue is full (shouldn't happen with 10k capacity)
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
