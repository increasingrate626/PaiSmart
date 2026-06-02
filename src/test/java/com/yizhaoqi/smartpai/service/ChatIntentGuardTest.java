package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatIntentGuardTest {

    private final ChatIntentGuard guard = new ChatIntentGuard();

    @Test
    void routesShortSmallTalkAndNoOpToSmallTalk() {
        assertSmallTalk("你好");
        assertSmallTalk("hello");
        assertSmallTalk("谢谢");
        assertSmallTalk("你是谁");
        assertSmallTalk("...");
        assertSmallTalk("   ");
    }

    @Test
    void scaSignalsForceRagEvenWhenGreetingIsPresent() {
        assertRag("你好，log4j-core 2.14.1 是否受 CVE-2021-44228 影响");
        assertRag("请判断 Spring4Shell 修复版本");
        assertRag("SBOM 里这个依赖是否误报");
    }

    private void assertSmallTalk(String message) {
        ChatIntentGuard.Decision decision = guard.decide(message);
        assertEquals(ChatIntentGuard.ChatRoute.SMALL_TALK, decision.route());
        assertFalse(decision.reply().isBlank());
    }

    private void assertRag(String message) {
        ChatIntentGuard.Decision decision = guard.decide(message);
        assertEquals(ChatIntentGuard.ChatRoute.RAG, decision.route());
    }
}
