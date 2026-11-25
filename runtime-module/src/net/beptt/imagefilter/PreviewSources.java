package net.beptt.imagefilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

final class PreviewSources {
    private final Map<String, Boolean> decisions = new HashMap<>();

    interface Extractor {
        List<?> extract(String source) throws ReflectiveOperationException;
    }

    void record(BepttUrlRules rules, String text, List<?> candidates, Extractor extractor)
            throws ReflectiveOperationException {
        if (candidates.isEmpty()) return;
        Matcher matcher = BepttUrlRules.sourceUrls(text);
        List<String> sources = new ArrayList<>();
        boolean listed = false;
        while (matcher.find()) {
            String source = matcher.group();
            sources.add(source);
            listed |= rules.urls.contains(source);
        }
        if (!listed) {
            for (Object candidate : candidates) if (candidate instanceof String) decisions.put((String) candidate, false);
            return;
        }

        Set<Object> blocked = new HashSet<>(), allowed = new HashSet<>();
        for (String source : sources) {
            List<?> extracted = source.equals(text) ? candidates : extractor.extract(source);
            (rules.urls.contains(source) ? blocked : allowed).addAll(extracted);
        }
        for (Object candidate : candidates) {
            if (candidate instanceof String) {
                decisions.merge((String) candidate, blocked.contains(candidate) && !allowed.contains(candidate),
                        (prior, current) -> prior && current);
            }
        }
    }

    boolean hides(String url, boolean defaultDecision) {
        return decisions.getOrDefault(url, defaultDecision);
    }

    void clear() {
        decisions.clear();
    }
}
