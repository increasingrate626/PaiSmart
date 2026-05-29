package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.entity.agent.AgenticRagResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AgenticRagService agenticRagService;

    @Mock
    private DeepSeekClient deepSeekClient;

    @Mock
    private WebSocketSession session;

    private ChatHandler chatHandler;
    private ObjectMapper objectMapper;
    private List<String> sentMessages;
    private AtomicReference<String> savedConversation;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        sentMessages = new ArrayList<>();
        savedConversation = new AtomicReference<>();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user:alice:current_conversation")).thenReturn("conversation-1");
        when(valueOperations.get("conversation:conversation-1")).thenReturn(null);
        doAnswer(invocation -> {
            if ("conversation:conversation-1".equals(invocation.getArgument(0))) {
                savedConversation.set(invocation.getArgument(1));
            }
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            sentMessages.add(message.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        chatHandler = new ChatHandler(redisTemplate, agenticRagService, deepSeekClient);
    }

    @Test
    void sendsEvidenceSummaryFallbackWhenFinalStreamFailsWithEvidence() throws Exception {
        SearchResult evidence = new SearchResult("file-a", 1, "Log4j 2.14.1 is affected and should be upgraded.", 0.9, "owner", "org", true, "advisory.txt", "trc-ingest-file-a");
        AgenticRagResult ragResult = new AgenticRagResult(
                "[1] (advisory.txt | MD5:file-a) Log4j 2.14.1 is affected and should be upgraded.\n",
                List.of(evidence),
                List.of(),
                Map.of(1, "file-a")
        );
        when(agenticRagService.run(any())).thenReturn(ragResult);
        doAnswer(invocation -> {
            Consumer<Throwable> onError = invocation.getArgument(4);
            onError.accept(new RuntimeException("deepseek unavailable"));
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), any(), any(), any());

        chatHandler.processMessage("alice", "log4j 怎么修", session);

        assertTrue(sentMessages.stream().anyMatch(message ->
                message.contains("AI 生成暂时不可用")
                        && message.contains("以下是本次检索到的相关证据摘要")
                        && message.contains("来源#1: advisory.txt")));
        assertTrue(sentMessages.stream().anyMatch(message ->
                message.contains("\"type\":\"completion\"")
                        && message.contains("\"status\":\"finished\"")));
        assertFalse(sentMessages.stream().anyMatch(message -> message.contains("AI service is temporarily unavailable")));
        assertTrue(savedConversation.get().contains("AI 生成暂时不可用"));

        List<Map<String, String>> history = objectMapper.readValue(savedConversation.get(), new TypeReference<>() {
        });
        assertEquals("user", history.get(0).get("role"));
        assertEquals("assistant", history.get(1).get("role"));
    }

    @Test
    void sendsExistingErrorWhenFinalStreamFailsWithoutEvidence() throws Exception {
        AgenticRagResult ragResult = new AgenticRagResult("", List.of(), List.of(), Map.of());
        when(agenticRagService.run(any())).thenReturn(ragResult);
        doAnswer(invocation -> {
            Consumer<Throwable> onError = invocation.getArgument(4);
            onError.accept(new RuntimeException("deepseek unavailable"));
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), any(), any(), any());

        chatHandler.processMessage("alice", "没有证据的问题", session);

        assertTrue(sentMessages.stream().anyMatch(message -> message.contains("AI service is temporarily unavailable")));
        assertFalse(sentMessages.stream().anyMatch(message -> message.contains("来源#1")));
        verify(valueOperations, never()).set(eq("conversation:conversation-1"), anyString(), any(Duration.class));
    }

    @Test
    void doesNotSendEvidenceFallbackAfterStopWasRequested() throws Exception {
        SearchResult evidence = new SearchResult("file-a", 1, "usable evidence", 0.9, "owner", "org", true, "a.txt", "trc-ingest-file-a");
        AgenticRagResult ragResult = new AgenticRagResult(
                "[1] (a.txt | MD5:file-a) usable evidence\n",
                List.of(evidence),
                List.of(),
                Map.of(1, "file-a")
        );
        when(agenticRagService.run(any())).thenReturn(ragResult);
        doAnswer(invocation -> {
            chatHandler.stopResponse("alice", session);
            Consumer<Throwable> onError = invocation.getArgument(4);
            onError.accept(new RuntimeException("deepseek unavailable"));
            return null;
        }).when(deepSeekClient).streamResponse(anyString(), anyString(), any(), any(), any());

        chatHandler.processMessage("alice", "停止中的问题", session);

        assertFalse(sentMessages.stream().anyMatch(message -> message.contains("AI 生成暂时不可用")));
    }
}
