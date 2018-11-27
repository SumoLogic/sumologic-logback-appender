/**
 *    _____ _____ _____ _____    __    _____ _____ _____ _____
 *   |   __|  |  |     |     |  |  |  |     |   __|     |     |
 *   |__   |  |  | | | |  |  |  |  |__|  |  |  |  |-   -|   --|
 *   |_____|_____|_|_|_|_____|  |_____|_____|_____|_____|_____|
 *
 *                UNICORNS AT WARP SPEED SINCE 2010
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.sumologic.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.status.ErrorStatus;
import com.sumologic.http.aggregation.SumoBufferFlusher;
import com.sumologic.http.queue.BufferWithEviction;
import com.sumologic.http.queue.BufferWithFifoEviction;
import com.sumologic.http.queue.CostBoundedConcurrentQueue;
import com.sumologic.http.sender.ProxySettings;
import com.sumologic.http.sender.SumoHttpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Logback Appender that sends log messages to Sumo Logic.
 *
 * @author Ryan Miller (rmiller@sumologic.com)
 */
public class SumoLogicAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private Logger logger = LoggerFactory.getLogger(getClass());
    private LayoutWrappingEncoder<ILoggingEvent> encoder = null;
    private String url = null;

    private String proxyHost = null;
    private int proxyPort = -1;
    private String proxyAuth = null;
    private String proxyUser = null;
    private String proxyPassword = null;
    private String proxyDomain = null;

    private int connectionTimeoutMs = 1000;
    private int socketTimeoutMs = 60000;
    private int retryIntervalMs = 10000;        // Once a request fails, how often until we retry.
    private boolean flushAllBeforeStopping = true; // When true, perform a final flush on shutdown

    private long messagesPerRequest = 100;    // How many messages need to be in the queue before we flush
    private long maxFlushIntervalMs = 10000;    // Maximum interval between flushes (ms)
    private long flushingAccuracyMs = 250;      // How often the flusher thread looks into the message queue (ms)

    private String sourceName = null;
    private String sourceHost = null;
    private String sourceCategory = null;

    private long maxQueueSizeBytes = 1000000;

    private SumoHttpSender sender;
    private SumoBufferFlusher flusher;
    volatile private BufferWithEviction<String> queue;
    private static final String CLIENT_NAME = "logback-appender";

    // All the parameters exposed to Logback

    public LayoutWrappingEncoder<ILoggingEvent> getEncoder() {
        return this.encoder;
    }

    public void setEncoder(LayoutWrappingEncoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    public String getUrl() { return this.url; }

    public void setUrl(String url) {
        this.url = url;
    }

    public long getMaxQueueSizeBytes() { return this.maxQueueSizeBytes; }

    public void setMaxQueueSizeBytes(long maxQueueSizeBytes) {
        this.maxQueueSizeBytes = maxQueueSizeBytes;
    }

    public long getMessagesPerRequest() { return this.messagesPerRequest; }

    public void setMessagesPerRequest(long messagesPerRequest) {
        this.messagesPerRequest = messagesPerRequest;
    }

    public long getMaxFlushIntervalMs() { return this.maxFlushIntervalMs; }

    public void setMaxFlushIntervalMs(long maxFlushIntervalMs) {
        this.maxFlushIntervalMs = maxFlushIntervalMs;
    }

    public String getSourceName() { return this.sourceName; }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceHost() { return this.sourceHost; }

    public void setSourceHost(String sourceHost) {
        this.sourceHost = sourceHost;
    }

    public String getSourceCategory() { return this.sourceCategory; }

    public void setSourceCategory(String sourceCategory) {
        this.sourceCategory = sourceCategory;
    }

    public long getFlushingAccuracyMs() { return this.flushingAccuracyMs; }

    public void setFlushingAccuracyMs(long flushingAccuracyMs) {
        this.flushingAccuracyMs = flushingAccuracyMs;
    }

    public int getConnectionTimeoutMs() { return this.connectionTimeoutMs; }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getSocketTimeoutMs() { return this.socketTimeoutMs; }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public int getRetryIntervalMs() { return this.retryIntervalMs; }

    public void setRetryIntervalMs(int retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getProxyAuth() {
        return proxyAuth;
    }

    public void setProxyAuth(String proxyAuth) {
        this.proxyAuth = proxyAuth;
    }

    public String getProxyUser() {
        return proxyUser;
    }

    public void setProxyUser(String proxyUser) {
        this.proxyUser = proxyUser;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }

    public void setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
    }

    public String getProxyDomain() {
        return proxyDomain;
    }

    public void setProxyDomain(String proxyDomain) {
        this.proxyDomain = proxyDomain;
    }

    public boolean getFlushAllBeforeStopping() {
        return flushAllBeforeStopping;
    }

    public void setFlushAllBeforeStopping(boolean flushAllBeforeStopping) {
        this.flushAllBeforeStopping = flushAllBeforeStopping;
    }

    @Override
    public void start() {
        int errors = 0;
        if (this.encoder == null) {
            this.addStatus(new ErrorStatus("No encoder set for the appender named \"" + this.name + "\".", this));
            ++errors;
        }
        if (this.url == null) {
            this.addStatus(new ErrorStatus("No url set for the appender named \"" + this.name + "\".", this));
            ++errors;
        }

        if (errors > 0) {
            return;
        }

        super.start();
        logger.debug("Starting appender");

        // Initialize queue
        if (queue == null) {
            queue = new BufferWithFifoEviction<String>(maxQueueSizeBytes, new CostBoundedConcurrentQueue.CostAssigner<String>() {
                @Override
                public long cost(String e) {
                    // Note: This is only an estimate for total byte usage, since in UTF-8 encoding,
                    // the size of one character may be > 1 byte.
                    return e.length();
                }
            });
        } else {
            queue.setCapacity(maxQueueSizeBytes);
        }

        // Initialize sender
        if (sender == null) {
            sender = new SumoHttpSender();
        }

        sender.setRetryInterval(retryIntervalMs);
        sender.setConnectionTimeout(connectionTimeoutMs);
        sender.setSocketTimeout(socketTimeoutMs);
        sender.setUrl(url);
        sender.setSourceHost(sourceHost);
        sender.setSourceName(sourceName);
        sender.setSourceCategory(sourceCategory);
        sender.setProxySettings(new ProxySettings(
                proxyHost,
                proxyPort,
                proxyAuth,
                proxyUser,
                proxyPassword,
                proxyDomain));
        sender.setClientHeaderValue(CLIENT_NAME);
        sender.init();

        // Initialize flusher
        if (flusher != null) {
            flusher.stop();
        }

        flusher = new SumoBufferFlusher(flushingAccuracyMs,
                messagesPerRequest,
                maxFlushIntervalMs,
                sender,
                queue,
                flushAllBeforeStopping);
        flusher.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            queue.add(convertToString(event));
        } catch (Exception e) {
            logger.error("Unable to insert log entry into log queue.", e);
        }
    }

    @Override
    public void stop() {
        super.stop();
        logger.debug("Closing SumoLogicAppender: " + getName());
        try {
            if (flusher != null) {
                flusher.stop();
                flusher = null;
            }
            if (sender != null) {
                sender.close();
                sender = null;
            }
        } catch (IOException e) {
            logger.error("Unable to close appender", e);
        }
    }

    private String convertToString(ILoggingEvent event) {
        if (encoder.getCharset() == null) {
            return new String(encoder.encode(event), Charset.defaultCharset());
        } else {
            return new String(encoder.encode(event), encoder.getCharset());
        }
    }
}
