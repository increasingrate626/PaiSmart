package com.yizhaoqi.smartpai.client;

import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeAnswerReturnsNonStreamingChatContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = startServer(200, """
                {"choices":[{"message":{"content":"请升级到 2.15.0 (source#1: a.txt)"}}]}
                """, requestBody);
        DeepSeekClient client = new DeepSeekClient(serverUrl(), "test-key", "deepseek-chat", aiProperties());

        Optional<String> answer = client.completeAnswer(
                "Is log4j-core 2.14.1 affected?",
                "[1] log4j-core fixed in 2.15.0",
                List.of(Map.of("role", "assistant", "content", "history"))
        );

        assertEquals(Optional.of("请升级到 2.15.0 (source#1: a.txt)"), answer);
        assertTrue(requestBody.get().contains("\"stream\":false"));
        assertTrue(requestBody.get().contains("log4j-core fixed in 2.15.0"));
        assertTrue(requestBody.get().contains("history"));
    }

    @Test
    void completeAnswerReturnsEmptyWhenProviderFails() throws Exception {
        server = startServer(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", new AtomicReference<>());
        DeepSeekClient client = new DeepSeekClient(serverUrl(), "test-key", "deepseek-chat", aiProperties());

        Optional<String> answer = client.completeAnswer("question", "context", List.of());

        assertTrue(answer.isEmpty());
    }

    private HttpServer startServer(int status, String response, AtomicReference<String> requestBody) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        httpServer.start();
        return httpServer;
    }

    private String serverUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private AiProperties aiProperties() {
        AiProperties properties = new AiProperties();
        properties.getPrompt().setRules("规则");
        properties.getPrompt().setRefStart("<<REF>>");
        properties.getPrompt().setRefEnd("<<END>>");
        properties.getPrompt().setNoResultText("无结果");
        return properties;
    }
}
