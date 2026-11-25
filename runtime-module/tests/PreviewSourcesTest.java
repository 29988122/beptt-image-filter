package net.beptt.imagefilter;

import java.util.List;

public final class PreviewSourcesTest {
    public static void main(String[] args) throws Exception {
        String image = "https://pbs.twimg.com/media/HKnOPRCacAA85R1?format=jpg";
        String blocked = image + "&name=4096x4096";
        String other = image + "&name=small";
        String youtube = "https://youtu.be/iDzKYmwR9gM";
        String otherVideo = "https://youtu.be/f2bhTJqNwCk";
        BepttUrlRules rules = new BepttUrlRules(blocked + "\n" + youtube);
        PreviewSources.Extractor imageExtractor = source -> source.startsWith(image) ? List.of(image) : List.of();

        PreviewSources sources = new PreviewSources();
        sources.record(rules, blocked, List.of(image), imageExtractor);
        check(sources.hides(image, false), "exact source with truncated image URL");
        sources.clear();
        sources.record(rules, "文字 " + blocked, List.of(image), imageExtractor);
        check(sources.hides(image, false), "truncated image URL extracted from prose");
        sources.clear();
        sources.record(rules, blocked + " " + other, List.of(image, image), imageExtractor);
        check(!sources.hides(image, false), "other variant shares the same candidate");
        sources.clear();
        sources.record(rules, other, List.of(image), imageExtractor);
        check(!sources.hides(image, false), "different image query is unchanged");
        sources.record(rules, blocked, List.of(image), imageExtractor);
        check(!sources.hides(image, false), "deduplicated rows keep an allowed source");
        sources.clear();
        sources.record(rules, youtube + " " + otherVideo, List.of(youtube, otherVideo), List::of);
        check(sources.hides(youtube, false), "exact video preview");
        check(!sources.hides(otherVideo, false), "unlisted video preview");
        check(sources.hides("https://example.org/osc", true), "original link can supply a direct card decision");
        System.out.println("PASS: 8 preview-source checks");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
