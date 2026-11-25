import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact URL rules. No host, prefix, wildcard, case, or query normalization. */
public final class BepttUrlRules {
    private static final Pattern TOKEN = Pattern.compile("https?://[^\\s\\u0000<>\"\\u001b]+" );
    public final Set<String> urls;

    public BepttUrlRules(String text) {
        LinkedHashSet<String> parsed = new LinkedHashSet<String>();
        for (String row : text.split("\\r?\\n")) {
            String url = row.trim();
            if (url.length() == 0) continue;
            try {
                URI uri = new URI(url);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null
                        || url.indexOf('*') >= 0) throw new IllegalArgumentException();
            } catch (Exception invalid) {
                throw new IllegalArgumentException("請每行填一個完整 http(s) 網址；不支援萬用字元：\n" + url);
            }
            parsed.add(url);
        }
        urls = Collections.unmodifiableSet(parsed);
    }

    public boolean hides(String candidate, String rawText, List<?> links) {
        if (!urls.contains(candidate)) return false;
        // BePTT's image regex can remove query strings. Require an exact source
        // token as well so ?query, #fragment and longer paths cannot overmatch.
        if (containsSource(rawText, candidate)) return true;
        if (links != null) for (Object link : links) {
            if (candidate.equals(link)) return true;
        }
        return false;
    }

    private static boolean containsSource(String raw, String expected) {
        if (raw == null) return false;
        Matcher matcher = sourceUrls(raw);
        while (matcher.find()) if (expected.equals(matcher.group())) return true;
        return false;
    }

    static Matcher sourceUrls(String raw) {
        return TOKEN.matcher(raw);
    }

    public String text() {
        StringBuilder out = new StringBuilder();
        for (String url : urls) {
            if (out.length() != 0) out.append('\n');
            out.append(url);
        }
        return out.toString();
    }
}
