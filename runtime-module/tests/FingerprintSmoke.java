package net.beptt.imagefilter;

import java.io.File;

public final class FingerprintSmoke {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        Fingerprint.Site site;
        try {
            site = Fingerprint.locate(apk);
        } catch (IllegalStateException missing) {
            if ("reject".equals(args[1])) {
                System.out.println("PASS: " + apk.getName() + " rejected: " + missing.getMessage());
                return;
            }
            throw missing;
        }
        if ("reject".equals(args[1]) || !args[1].equals(site.parser.getDefiningClass())
                || !args[2].equals(site.converter.getDefiningClass())
                || !args[3].equals(site.text.getName()) || !args[4].equals(site.links.getName())) {
            throw new AssertionError("Unexpected parser, converter or fields in " + apk.getName());
        }
        System.out.println("PASS: " + apk.getName() + " -> " + site.parser + " / " + site.converter);
    }
}
