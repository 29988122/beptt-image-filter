import java.util.Arrays;
import java.util.Collections;

public final class BepttUrlRulesTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        String url = "https://i.imgur.com/ROoMKD3.jpeg";
        BepttUrlRules rules = new BepttUrlRules("  " + url + "\r\n\n" + url + "\n");
        check(rules.urls.size() == 1, "deduplicate and trim entries");
        check(rules.hides(url, "文字 " + url + "\n文字", Collections.emptyList()), "exact source");
        check(rules.hides(url, "OSC link", Arrays.asList(url)), "OSC8 source");
        check(!rules.hides(url, url + "?download=1", Collections.emptyList()), "query must not overmatch");
        check(!rules.hides(url, url + "#fragment", Collections.emptyList()), "fragment must not overmatch");
        check(!rules.hides(url, url + ".png", Collections.emptyList()), "longer filename");
        check(!rules.hides(url, url + "/child", Collections.emptyList()), "longer path");
        check(!rules.hides(url, ">" + url + "?x=2", Arrays.asList(url + "?x=2")), "OSC8 query mismatch");
        check(!rules.hides(url.replace("ROo", "roo"), url, Collections.emptyList()), "case sensitive");
        check(!rules.hides(url.replace("https:", "http:"), url, Collections.emptyList()), "scheme exact");
        check(!rules.hides(url.replace("i.imgur", "imgur"), url, Collections.emptyList()), "host exact");
        check(!rules.hides("https://i.imgur.com/another.jpeg", url, Collections.emptyList()), "other image unaffected");
        check(!rules.hides(url, null, null), "unknown source fails open");
        check(!new BepttUrlRules("").hides(url, url, null), "empty disables all rules");
        String query = "https://example.org/a.jpg?x=1#top";
        check(new BepttUrlRules(query).hides(query, query, null), "query retained in exact rules");
        check(rules.text().equals(url), "round trip");
        for (String invalid : Arrays.asList("imgur.com/a.jpg", "https://*.imgur.com/a.jpg", "https://example.org/a b.jpg", "file:///tmp/a.jpg", "https://user:secret@example.org/a.jpg", "https://")) {
            boolean rejected = false;
            try { new BepttUrlRules(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "reject invalid rule");
        }
        System.out.println("PASS: " + checks + " URL policy checks");
    }
}
