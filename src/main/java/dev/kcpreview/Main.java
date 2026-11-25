package dev.kcpreview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keycloak Template Preview – renders Keycloak login and email themes (FreeMarker) without a running Keycloak.
 *
 * <pre>
 * java -jar keycloak-template-preview.jar [--themes=./themes] [--mocks=./mocks] [--port=8090]
 *      [--host=127.0.0.1] [--locale=de] [--keycloak-themes=/path/to/theme-folder] [--check[=theme]]
 * </pre>
 */
public final class Main {

    public static void main(String[] argv) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        Map<String, String> a = parse(argv);
        if (a.containsKey("help") || a.containsKey("h")) {
            System.out.println(usage());
            return;
        }
        int port = Integer.parseInt(a.getOrDefault("port", env("KC_PREVIEW_PORT", "8090")));
        String host = a.getOrDefault("host", env("KC_PREVIEW_HOST", "127.0.0.1"));
        String lang = a.getOrDefault("locale", env("KC_PREVIEW_LOCALE", "de"));

        List<ThemeSource> sources = new ArrayList<>();
        String themesOpt = a.getOrDefault("themes", env("KC_PREVIEW_THEMES", null));
        List<String> themeDirs = new ArrayList<>();
        if (themesOpt != null) {
            themeDirs.addAll(List.of(themesOpt.split(",")));
        } else {
            // No --themes/KC_PREVIEW_THEMES given: default to ./themes, plus ./plugin-themes if present
            // (building-block themes like a plugin's login/email page - see README).
            themeDirs.add("./themes");
            if (Files.isDirectory(Path.of("./plugin-themes"))) themeDirs.add("./plugin-themes");
        }
        for (int i = 0; i < themeDirs.size(); i++) {
            Path p = Path.of(themeDirs.get(i).trim());
            if (Files.isDirectory(p)) {
                // First --themes folder is "your" themes, shown as "Custom themes". Any further folder
                // gets its own dropdown group named after itself - a plugin's building-block themes
                // (plugin-themes/), a separate private customer-theme repo, whatever it is.
                sources.add(new ThemeSource.Folder(p, true, i == 0 ? ThemeSource.GROUP_CUSTOM : groupNameFor(p)));
            } else {
                System.err.println("WARN: Theme folder " + p.toAbsolutePath() + " does not exist.");
            }
        }
        String kc = a.getOrDefault("keycloak-themes", env("KC_PREVIEW_KEYCLOAK_THEMES", null));
        if (kc != null) {
            sources.add(new ThemeSource.Folder(Path.of(kc), false, ThemeSource.GROUP_KEYCLOAK));
        }
        ThemeSource.Classpath cp = new ThemeSource.Classpath();
        if (!cp.isEmpty()) sources.add(cp);
        if (kc == null && cp.isEmpty()) {
            System.err.println("ERROR: No Keycloak base themes found. Either put org.keycloak:keycloak-themes "
                    + "on the classpath (default in the Gradle build) or pass --keycloak-themes=<folder>.");
            System.exit(2);
        }

        Path mocksDir = Path.of(a.getOrDefault("mocks", env("KC_PREVIEW_MOCKS", "./mocks")));
        ThemeRepository repo = new ThemeRepository(sources);
        PreviewRenderer renderer = new PreviewRenderer(repo, new MockStore(mocksDir), "http://localhost:" + port);

        if (a.containsKey("check")) {
            System.exit(Check.run(renderer, a.get("check"), lang));
        }

        new PreviewServer(renderer, lang).start(host, port);
        System.out.println("""

                  Keycloak Template Preview running:  http://localhost:%d

                  Themes:     %s
                  Mocks:      %s
                  FreeMarker: %s
                """.formatted(port, sources.stream().map(ThemeSource::label).toList(),
                mocksDir.toAbsolutePath().normalize(), freemarker.template.Configuration.getVersion()));
    }

    /** Dropdown group label for a non-primary --themes folder, derived from its directory name. */
    private static String groupNameFor(Path dir) {
        String name = dir.toAbsolutePath().normalize().getFileName().toString().replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static Map<String, String> parse(String[] argv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : argv) {
            if (!s.startsWith("--") && !s.startsWith("-")) continue;
            String t = s.replaceFirst("^--?", "");
            int i = t.indexOf('=');
            m.put(i < 0 ? t : t.substring(0, i), i < 0 ? null : t.substring(i + 1));
        }
        return m;
    }

    private static String usage() {
        return """
                Keycloak Template Preview

                  --themes=DIR[,DIR]       custom themes (default ./themes), reloaded live
                  --mocks=DIR              custom mock data (default ./mocks)
                  --keycloak-themes=DIR    Keycloak base themes from a folder instead of keycloak-themes.jar
                  --port=8090 --host=127.0.0.1 --locale=de
                  --check[=THEME]          render all templates, print errors, exit code 1 on failure (CI)
                """;
    }
}
