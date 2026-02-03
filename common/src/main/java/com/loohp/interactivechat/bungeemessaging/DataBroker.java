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

/**
 * Interface for cross-server data communication.
 * Implementations can use different backends like plugin messages or Redis.
 */
public interface DataBroker {

    /**
     * Initialize the data broker and start listening for incoming messages.
     * @return true if initialization was successful
     */
    boolean start();

    /**
     * Stop the data broker and clean up resources.
     */
    void stop();

    /**
     * Send raw data to other servers.
     * @param data the data to send
     * @return true if the data was sent successfully
     */
    boolean sendData(byte[] data);

    /**
     * Check if the data broker is currently connected/active.
     * @return true if connected
     */
    boolean isConnected();

    /**
     * Get the type of this data broker.
     * @return the broker type
     */
    DataBrokerType getType();

    /**
     * Get the server name for this broker instance.
     * @return the server name
     */
    String getServerName();

}
