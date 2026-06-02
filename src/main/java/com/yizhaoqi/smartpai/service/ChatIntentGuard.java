package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ChatIntentGuard {

    private static final String GREETING_REPLY = "你好，我是 PaiSmart SCA 安全助手。你可以问我组件/CVE 是否受影响、修复版本、传递依赖路径、误报判断和修复建议。";
    private static final String THANKS_REPLY = "不客气。你可以继续提供组件名、版本号、CVE 编号或扫描结果，我会帮你判断风险和修复方案。";
    private static final String CAPABILITY_REPLY = "我可以帮助分析 SCA 扫描结果、CVE 影响范围、修复版本、传递依赖路径、误报判断和修复优先级。";

    private static final int SMALL_TALK_MAX_CHARS = 30;
    private static final Pattern CVE_PATTERN = Pattern.compile("\\bcve-\\d{4}-\\d{4,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PURE_NO_OP_PATTERN = Pattern.compile("[\\p{P}\\p{S}\\s]+");
    private static final Set<String> SCA_KEYWORDS = Set.of(
            "cve", "sca", "sbom", "漏洞", "修复", "依赖", "组件", "版本", "误报",
            "许可证", "license", "log4shell", "spring4shell", "fixed version",
            "upgrade", "补丁", "传递依赖", "扫描结果", "风险", "漏洞库"
    );
    private static final Set<String> GREETING_TERMS = Set.of(
            "你好", "您好", "hi", "hello", "hey", "在吗", "哈喽"
    );
    private static final Set<String> THANKS_TERMS = Set.of(
            "谢谢", "感谢", "thanks", "thankyou", "thank you"
    );
    private static final Set<String> CAPABILITY_TERMS = Set.of(
            "你是谁", "你能做什么", "能做什么", "帮助", "help"
    );

    public Decision decide(String message) {
        String trimmed = message == null ? "" : message.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (containsScaSignal(lower)) {
            return new Decision(ChatRoute.RAG, "sca_signal", null);
        }

        if (trimmed.isEmpty() || PURE_NO_OP_PATTERN.matcher(trimmed).matches()) {
            return new Decision(ChatRoute.SMALL_TALK, "no_op", CAPABILITY_REPLY);
        }

        if (trimmed.length() > SMALL_TALK_MAX_CHARS) {
            return new Decision(ChatRoute.RAG, "default", null);
        }

        String compact = lower.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        if (matchesAny(lower, compact, THANKS_TERMS)) {
            return new Decision(ChatRoute.SMALL_TALK, "thanks", THANKS_REPLY);
        }
        if (matchesAny(lower, compact, CAPABILITY_TERMS)) {
            return new Decision(ChatRoute.SMALL_TALK, "capability", CAPABILITY_REPLY);
        }
        if (matchesAny(lower, compact, GREETING_TERMS)) {
            return new Decision(ChatRoute.SMALL_TALK, "greeting", GREETING_REPLY);
        }

        return new Decision(ChatRoute.RAG, "default", null);
    }

    private boolean containsScaSignal(String lower) {
        if (CVE_PATTERN.matcher(lower).find()) {
            return true;
        }
        return SCA_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private boolean matchesAny(String lower, String compact, Set<String> terms) {
        return terms.stream().anyMatch(term -> {
            String normalizedTerm = term.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
            return lower.equals(term) || compact.equals(normalizedTerm);
        });
    }

    public enum ChatRoute {
        RAG,
        SMALL_TALK
    }

    public record Decision(ChatRoute route, String reason, String reply) {
    }
}
