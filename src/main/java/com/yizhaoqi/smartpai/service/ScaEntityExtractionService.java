package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.agent.AgentEntities;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScaEntityExtractionService {

    private static final Pattern CVE_PATTERN = Pattern.compile("\\bCVE-\\d{4}-\\d{4,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?<![A-Za-z0-9])\\d+\\.\\d+(?:\\.\\d+)+(?:[-+][A-Za-z0-9._-]+)?\\b");
    private static final Pattern MAVEN_COORDINATE_PATTERN = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_.-]*(?:\\.[A-Za-z0-9_.-]+)+):([A-Za-z][A-Za-z0-9_.-]*)\\b");
    private static final Pattern PURL_PATTERN = Pattern.compile("\\bpkg:([A-Za-z0-9+.-]+)/([^\\s?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROJECT_PATTERN = Pattern.compile("(?i)(?:\\bproject\\b|\\bservice\\b|应用|项目|服务)\\s*[:：=]?\\s*([A-Za-z][A-Za-z0-9_.-]*)");
    private static final Pattern COMPONENT_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*(?:[-._][A-Za-z0-9]+)+\\b");

    private static final Set<String> COMPONENT_STOP_WORDS = Set.of(
            "project", "service", "application", "version", "fixed", "affected", "impact", "impacted",
            "question", "answer", "source", "chunk", "file", "trace", "session"
    );

    public AgentEntities extract(String question) {
        AgentEntities entities = new AgentEntities();
        if (question == null || question.isBlank()) {
            return entities;
        }

        List<Span> reservedSpans = new ArrayList<>();
        entities.setCves(extractCves(question));
        entities.setVersions(extractVersions(question));
        entities.setProjects(extractProjects(question, reservedSpans));
        entities.setComponents(extractComponents(question, reservedSpans));
        return entities;
    }

    private List<String> extractCves(String text) {
        OrderedValues values = new OrderedValues(true);
        Matcher matcher = CVE_PATTERN.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values.values();
    }

    private List<String> extractVersions(String text) {
        OrderedValues values = new OrderedValues(false);
        Matcher matcher = VERSION_PATTERN.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values.values();
    }

    private List<String> extractProjects(String text, List<Span> reservedSpans) {
        OrderedValues values = new OrderedValues(false);
        Matcher matcher = PROJECT_PATTERN.matcher(text);
        while (matcher.find()) {
            String project = normalizeName(matcher.group(1));
            if (!project.isBlank()) {
                values.add(project);
                reservedSpans.add(new Span(matcher.start(), matcher.end()));
            }
        }
        return values.values();
    }

    private List<String> extractComponents(String text, List<Span> reservedSpans) {
        OrderedValues values = new OrderedValues(false);

        Matcher purlMatcher = PURL_PATTERN.matcher(text);
        while (purlMatcher.find()) {
            reservedSpans.add(new Span(purlMatcher.start(), purlMatcher.end()));
            String artifact = componentFromPurlPath(purlMatcher.group(2));
            addComponent(values, artifact);
        }

        Matcher mavenMatcher = MAVEN_COORDINATE_PATTERN.matcher(text);
        while (mavenMatcher.find()) {
            reservedSpans.add(new Span(mavenMatcher.start(), mavenMatcher.end()));
            addComponent(values, mavenMatcher.group());
            addComponent(values, mavenMatcher.group(2));
        }

        Matcher tokenMatcher = COMPONENT_TOKEN_PATTERN.matcher(text);
        while (tokenMatcher.find()) {
            if (overlapsAny(tokenMatcher.start(), tokenMatcher.end(), reservedSpans)) {
                continue;
            }
            addComponent(values, tokenMatcher.group());
        }
        return values.values();
    }

    private String componentFromPurlPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path;
        int versionIndex = normalized.indexOf('@');
        if (versionIndex >= 0) {
            normalized = normalized.substring(0, versionIndex);
        }
        String[] parts = normalized.split("/");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private void addComponent(OrderedValues values, String rawValue) {
        String value = normalizeComponent(rawValue);
        if (value.isBlank() || isStopComponent(value)) {
            return;
        }
        values.add(value);
    }

    private boolean isStopComponent(String value) {
        String key = value.toLowerCase(Locale.ROOT);
        return COMPONENT_STOP_WORDS.contains(key)
                || CVE_PATTERN.matcher(value).matches()
                || VERSION_PATTERN.matcher(value).matches()
                || value.chars().allMatch(Character::isDigit);
    }

    private String normalizeComponent(String value) {
        return normalizeName(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private boolean overlapsAny(int start, int end, List<Span> spans) {
        for (Span span : spans) {
            if (start < span.end() && end > span.start()) {
                return true;
            }
        }
        return false;
    }

    private record Span(int start, int end) {
    }

    private static class OrderedValues {
        private final boolean uppercase;
        private final Set<String> keys = new LinkedHashSet<>();
        private final List<String> values = new ArrayList<>();

        private OrderedValues(boolean uppercase) {
            this.uppercase = uppercase;
        }

        private void add(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return;
            }
            String value = rawValue.trim().replaceAll("\\s+", " ");
            if (uppercase) {
                value = value.toUpperCase(Locale.ROOT);
            }
            String key = value.toLowerCase(Locale.ROOT);
            if (keys.add(key)) {
                values.add(value);
            }
        }

        private List<String> values() {
            return values;
        }
    }
}
