package dev.kcpreview;

import java.util.ArrayList;
import java.util.List;

/** {@code --check}: renders every template × variant of the user themes and reports errors (for CI). */
final class Check {

    private Check() {
    }

    static int run(PreviewRenderer r, String onlyTheme, String lang) {
        int ok = 0;
        List<String> failures = new ArrayList<>();
        for (String type : List.of("login", "email")) {
            List<String> themes = new ArrayList<>();
            r.repo().themeNames(type).forEach((name, info) -> {
                if (onlyTheme != null ? onlyTheme.equals(name) : info.user()) themes.add(name);
            });
            for (String theme : themes) {
                List<String> templates = type.equals("login") ? r.loginPages(theme) : r.emailTemplates(theme);
                for (String t : templates) {
                    List<String> variants = new ArrayList<>(List.of("default"));
                    variants.addAll(r.mocks().variants(type, t.replace(".ftl", "")));
                    for (String v : variants) {
                        String id = type + " " + theme + "/" + t + (v.equals("default") ? "" : " [" + v + "]");
                        try {
                            if (type.equals("login")) {
                                r.renderLogin(theme, t, lang, v);
                            } else {
                                r.renderEmail(theme, t, lang, v);
                            }
                            ok++;
                            System.out.println("  OK    " + id);
                        } catch (Exception e) {
                            String msg = PreviewServer.ErrorPage.text(e).lines().filter(l -> !l.isBlank())
                                    .takeWhile(l -> !l.startsWith("----") && !l.startsWith("Tip:")).limit(4)
                                    .reduce((x, y) -> x + "\n          " + y).orElse("");
                            failures.add(id);
                            System.out.println("  FAIL  " + id + "\n          " + msg);
                        }
                    }
                }
            }
        }
        System.out.printf("%n%d ok, %d failed%n", ok, failures.size());
        if (ok + failures.size() == 0) {
            System.out.println("No custom themes found – use --check=keycloak.v2 to check the Keycloak themes.");
        }
        return failures.isEmpty() ? 0 : 1;
    }
}
