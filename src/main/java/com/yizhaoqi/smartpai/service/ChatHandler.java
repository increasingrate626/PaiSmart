package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagRequest;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private static final int COMPLETION_INITIAL_DELAY_MS = 3000;
    private static final int COMPLETION_STABLE_WINDOW_MS = 2000;
    private static final int COMPLETION_MAX_CHECKS = 5;

    private final RedisTemplate<String, String> redisTemplate;
    private final AgenticRagService agenticRagService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, String>> sessionReferenceMappings = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       AgenticRagService agenticRagService,
                       DeepSeekClient deepSeekClient) {
        this.redisTemplate = redisTemplate;
        this.agenticRagService = agenticRagService;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = new ObjectMapper();
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("Start chat processing: userId={}, sessionId={}", userId, sessionId);

        try {
            String conversationId = getOrCreateConversationId(userId);
            responseBuilders.put(sessionId, new StringBuilder());
            stopFlags.remove(sessionId);

            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(sessionId, responseFuture);

            List<Map<String, String>> history = getConversationHistory(conversationId);
            AgenticRagResult ragResult = agenticRagService.run(
                    new AgenticRagRequest(userId, userMessage, history, sessionId)
            );
            saveReferenceMapping(sessionId, ragResult.getReferenceMapping());

            deepSeekClient.streamResponse(
                    userMessage,
                    ragResult.getFinalContext(),
                    history,
                    chunk -> handleStreamChunk(session, chunk),
                    error -> handleStreamError(session, responseFuture, error)
            );

            startCompletionWatcher(session, conversationId, userId, userMessage, responseFuture);
        } catch (Exception e) {
            logger.error("Chat processing failed: {}", e.getMessage(), e);
            handleError(session, e);
            cleanupSession(sessionId);
        }
    }

    private void handleStreamChunk(WebSocketSession session, String chunk) {
        String sessionId = session.getId();
        if (Boolean.TRUE.equals(stopFlags.get(sessionId))) {
            logger.debug("Skip chunk because stop flag is set: sessionId={}", sessionId);
            return;
        }

        StringBuilder responseBuilder = responseBuilders.get(sessionId);
        if (responseBuilder != null) {
            responseBuilder.append(chunk);
        }
        sendResponseChunk(session, chunk);
    }

    private void handleStreamError(WebSocketSession session,
                                   CompletableFuture<String> responseFuture,
                                   Throwable error) {
        handleError(session, error);
        sendCompletionNotification(session);
        responseFuture.completeExceptionally(error);
        cleanupSession(session.getId());
    }

    private void startCompletionWatcher(WebSocketSession session,
                                        String conversationId,
                                        String userId,
                                        String userMessage,
                                        CompletableFuture<String> responseFuture) {
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(COMPLETION_INITIAL_DELAY_MS);
                if (responseFuture.isDone()) {
                    return;
                }

                String sessionId = session.getId();
                StringBuilder responseBuilder = responseBuilders.get(sessionId);

                if (responseBuilder == null) {
                    RuntimeException error = new RuntimeException("response builder missing");
                    if (responseFuture.completeExceptionally(error)) {
                        handleError(session, error);
                    }
                    return;
                }

                for (int i = 0; i < COMPLETION_MAX_CHECKS && !responseFuture.isDone(); i++) {
                    int lastLength = responseBuilder.length();
                    Thread.sleep(COMPLETION_STABLE_WINDOW_MS);
                    if (responseBuilder.length() == lastLength) {
                        finishResponse(session, conversationId, userId, userMessage, responseFuture);
                        return;
                    }
                }

                if (!responseFuture.isDone()) {
                    finishResponse(session, conversationId, userId, userMessage, responseFuture);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                responseFuture.completeExceptionally(e);
            } catch (Exception e) {
                logger.error("Completion watcher failed: {}", e.getMessage(), e);
                responseFuture.completeExceptionally(e);
                cleanupSession(session.getId());
            }
        }, "chat-completion-" + session.getId());
        watcher.setDaemon(true);
        watcher.start();
    }

    private void finishResponse(WebSocketSession session,
                                String conversationId,
                                String userId,
                                String userMessage,
                                CompletableFuture<String> responseFuture) {
        String sessionId = session.getId();
        StringBuilder responseBuilder = responseBuilders.get(sessionId);
        String completeResponse = responseBuilder != null ? responseBuilder.toString() : "";

        if (responseFuture.complete(completeResponse)) {
            sendCompletionNotification(session);
            updateConversationHistory(conversationId, userMessage, completeResponse);
            logger.info("Chat processing completed: userId={}, sessionId={}, responseLength={}",
                    userId, sessionId, completeResponse.length());
            cleanupSession(sessionId);
        }
    }

    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
        }
        return conversationId;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse conversation history: conversationId={}", conversationId, e);
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response) {
        String key = "conversation:" + conversationId;
        List<Map<String, String>> history = getConversationHistory(conversationId);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        userMsg.put("timestamp", timestamp);
        history.add(userMsg);

        Map<String, String> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", response);
        assistantMsg.put("timestamp", timestamp);
        history.add(assistantMsg);

        if (history.size() > 20) {
            history = new ArrayList<>(history.subList(history.size() - 20, history.size()));
        }

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), Duration.ofDays(7));
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize conversation history: conversationId={}", conversationId, e);
        }
    }

    private void saveReferenceMapping(String sessionId, Map<Integer, String> referenceMapping) {
        Map<Integer, String> safeMapping = referenceMapping != null ? new HashMap<>(referenceMapping) : new HashMap<>();
        sessionReferenceMappings.put(sessionId, safeMapping);
        logger.info("Reference mapping saved: sessionId={}, size={}", sessionId, safeMapping.size());
    }

    private void sendResponseChunk(WebSocketSession session, String chunk) {
        try {
            if (!session.isOpen()) {
                logger.debug("Skip chunk because session is closed: sessionId={}", session.getId());
                return;
            }
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("chunk", chunk))));
        } catch (Exception e) {
            logger.error("Failed to send response chunk: {}", e.getMessage(), e);
        }
    }

    private void sendCompletionNotification(WebSocketSession session) {
        try {
            if (!session.isOpen()) {
                logger.debug("Skip completion notification because session is closed: sessionId={}", session.getId());
                return;
            }
            Map<String, Object> notification = Map.of(
                    "type", "completion",
                    "status", "finished",
                    "message", "response finished",
                    "timestamp", System.currentTimeMillis(),
                    "date", LocalDateTime.now().toString()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("Failed to send completion notification: {}", e.getMessage(), e);
        }
    }

    private void handleError(WebSocketSession session, Throwable error) {
        logger.error("AI service error: {}", error.getMessage(), error);
        try {
            if (!session.isOpen()) {
                logger.debug("Skip error message because session is closed: sessionId={}", session.getId());
                return;
            }
            Map<String, String> errorResponse = Map.of("error", "AI service is temporarily unavailable. Please try again later.");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorResponse)));
        } catch (Exception e) {
            logger.error("Failed to send error message: {}", e.getMessage(), e);
        }
    }

    public void stopResponse(String userId, WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("Stop requested: userId={}, sessionId={}", userId, sessionId);
        stopFlags.put(sessionId, true);

        try {
            Map<String, Object> response = Map.of(
                    "type", "stop",
                    "message", "response stopped",
                    "timestamp", System.currentTimeMillis(),
                    "date", java.time.Instant.now().toString()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (Exception e) {
            logger.error("Failed to send stop confirmation: {}", e.getMessage(), e);
        }
    }

    public String getReferenceMd5(String sessionId, int referenceNumber) {
        Map<Integer, String> referenceMapping = sessionReferenceMappings.get(sessionId);
        if (referenceMapping == null) {
            logger.warn("Reference mapping not found: sessionId={}", sessionId);
            return null;
        }
        return referenceMapping.get(referenceNumber);
    }

    public void clearSessionReferenceMapping(String sessionId) {
        Map<Integer, String> removed = sessionReferenceMappings.remove(sessionId);
        logger.info("Reference mapping cleared: sessionId={}, removed={}", sessionId, removed != null);
        cleanupSession(sessionId);
    }

    private void cleanupSession(String sessionId) {
        responseBuilders.remove(sessionId);
        responseFutures.remove(sessionId);
        stopFlags.remove(sessionId);
    }
}
