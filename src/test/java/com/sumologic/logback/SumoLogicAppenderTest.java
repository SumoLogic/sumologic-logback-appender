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

import ch.qos.logback.classic.Logger;
import com.sumologic.logback.server.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;

public class SumoLogicAppenderTest {

    private static final int PORT = 10010;
    private MockHttpServer server;
    private AggregatingHttpHandler handler;

    @Before
    public void setUp() throws Exception {
        handler = new AggregatingHttpHandler();
        server = new MockHttpServer(PORT, handler);
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testMessagesWithMetadata() throws Exception {
        // See ./resources/logback.xml for definition
        Logger loggerInTest = (Logger) LoggerFactory.getLogger("TestAppender1");
        StringBuffer expected = new StringBuffer();
        for (int i = 0; i < 100; i ++) {
            String message = "info" + i;
            loggerInTest.info(message);
            expected.append("[main] INFO  TestAppender1 - " + message + "\n");
        }
        Thread.sleep(150);
        // Check headers
        for(MaterializedHttpRequest request: handler.getExchanges()) {
            assertEquals(true, request.getHeaders().getFirst("X-Sumo-Name").equals("mySource"));
            assertEquals(true, request.getHeaders().getFirst("X-Sumo-Category").equals("myCategory"));
            assertEquals(true, request.getHeaders().getFirst("X-Sumo-Host").equals("myHost"));
            assertEquals("logback-appender", request.getHeaders().getFirst("X-Sumo-Client"));
        }
        // Check body
        StringBuffer actual = new StringBuffer();
        for(MaterializedHttpRequest request: handler.getExchanges()) {
            for (String line : request.getBody().split("\n")) {
                // Strip timestamp
                int mainStart = line.indexOf("[main]");
                String trimmed = line.substring(mainStart);
                actual.append(trimmed + "\n");
            }
        }
        assertEquals(expected.toString(), actual.toString());
    }

    @Test
    public void testMessagesWithoutMetadata() throws Exception {
        // See ./resources/logback.xml for definition
        Logger loggerInTest = (Logger) LoggerFactory.getLogger("TestAppender2");
        int numMessages = 5;
        for (int i = 0; i < numMessages; i ++) {
            loggerInTest.info("info " + i);
            Thread.sleep(150);
        }
        assertEquals(numMessages, handler.getExchanges().size());
        for(MaterializedHttpRequest request: handler.getExchanges()) {
            assertEquals(true, request.getHeaders().getFirst("X-Sumo-Name") == null);
            assertEquals(true, request.getHeaders().getFirst("X-Sumo-Category") == null);
            assertEquals(true, request.getHeaders().getFirst("X-Sumo-Host") == null);
            assertEquals("logback-appender", request.getHeaders().getFirst("X-Sumo-Client"));
        }
    }

    @Test
    public void testAsciiCharset() throws Exception {
        // See ./resources/logback.xml for definition
        Logger loggerInTest = (Logger) LoggerFactory.getLogger("TestAppenderAscii");
        String message = "Hello 中国的\uD801\uDC37 World";
        loggerInTest.info(message);
        Thread.sleep(150);
        assertEquals(1, handler.getExchanges().size());
        assertEquals("Hello ???? World\n", handler.getExchanges().get(0).getBody());
    }

    @Test
    public void testUtf16Charset() throws Exception {
        // See ./resources/logback.xml for definition
        Logger loggerInTest = (Logger) LoggerFactory.getLogger("TestAppenderUtf16");
        String message = "Hello 中国的\uD801\uDC37 World";
        loggerInTest.info(message);
        Thread.sleep(150);
        assertEquals(1, handler.getExchanges().size());
        assertEquals("Hello 中国的\uD801\uDC37 World\n", handler.getExchanges().get(0).getBody());
    }

    @Test
    public void testJsonLayout() throws Exception {
        // See ./resources/logback.xml for definition
        Logger loggerInTest = (Logger) LoggerFactory.getLogger("TestAppenderJsonLayout");
        String message = "Hello World";
        String expected = "\"message\":\"Hello World\"";
        loggerInTest.info(message);
        // 2nd message to test newline message separation
        loggerInTest.info(message);
        Thread.sleep(150);
        assertEquals(1, handler.getExchanges().size());
        Pattern p = Pattern.compile(expected, Pattern.LITERAL);
        Matcher m = p.matcher(handler.getExchanges().get(0).getBody());
        int count = 0;
        while (m.find()) {
            count++;
        }
        assertEquals(2, count);
    }
}
