package dev.kcpreview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Mock data for the templates. Files are looked up first in the bundled defaults (classpath {@code mocks/})
 * and then in the user's mock folder; everything found is deep-merged in this order:
 * <pre>
 *   login/_common.json → login/&lt;page&gt;.json → login/&lt;page&gt;.&lt;variant&gt;.json
 * </pre>
 * e.g. {@code login/login.json}, {@code login/login.error.json}, {@code email/password-reset.json}.
 */
public final class MockStore {

    private final Path userDir;

    public MockStore(Path userDir) {
        this.userDir = userDir;
    }

    public Path userDir() {
        return userDir;
    }

    public Map<String, Object> load(String type, String page, String variant) {
        Map<String, Object> result = new LinkedHashMap<>();
        merge(result, type + "/_common.json");
        merge(result, type + "/" + page + ".json");
        if (variant != null && !variant.isBlank() && !"default".equals(variant)) {
            merge(result, type + "/" + page + "." + variant + ".json");
        }
        return result;
    }

    /** Available variants for a page ("default" plus every {@code <page>.<variant>.json}). */
    public Set<String> variants(String type, String page) {
        Set<String> out = new TreeSet<>();
        String prefix = page + ".";
        for (String f : bundledIndex()) {
            if (f.startsWith(type + "/" + prefix) && f.endsWith(".json")
                    && f.length() > type.length() + 1 + prefix.length() + 5) {
                out.add(f.substring(type.length() + 1 + prefix.length(), f.length() - 5));
            }
        }
        Path d = userDir == null ? null : userDir.resolve(type);
        if (d != null && Files.isDirectory(d)) {
            try (Stream<Path> s = Files.list(d)) {
                s.map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith(prefix) && n.endsWith(".json") && n.length() > prefix.length() + 5)
                        .forEach(n -> out.add(n.substring(prefix.length(), n.length() - 5)));
            } catch (IOException ignored) {
                // no variants
            }
        }
        out.removeIf(v -> v.isBlank() || v.contains("."));
        return out;
    }

    public long lastModified() {
        if (userDir == null || !Files.isDirectory(userDir)) return 0;
        try (Stream<Path> s = Files.walk(userDir)) {
            return s.mapToLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private void merge(Map<String, Object> target, String relPath) {
        try (InputStream in = MockStore.class.getClassLoader().getResourceAsStream("mocks/" + relPath)) {
            if (in != null) Json.deepMerge(target, parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "classpath:mocks/" + relPath));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (userDir != null) {
            Path p = userDir.resolve(relPath);
            if (Files.isRegularFile(p)) {
                try {
                    Json.deepMerge(target, parse(Files.readString(p), p.toString()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static Map<String, Object> parse(String text, String source) {
        try {
            return Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid mock file " + source + ": " + e.getMessage(), e);
        }
    }

    private static Set<String> bundledIndex;

    /** Relative paths of all bundled mock files (works for a classes folder and for a jar). */
    private static synchronized Set<String> bundledIndex() {
        if (bundledIndex != null) return bundledIndex;
        Set<String> out = new TreeSet<>();
        try {
            java.net.URL u = MockStore.class.getClassLoader().getResource("mocks/login/_common.json");
            if (u != null && "jar".equals(u.getProtocol())) {
                java.net.JarURLConnection c = (java.net.JarURLConnection) u.openConnection();
                c.setUseCaches(false);
                try (java.util.jar.JarFile jar = c.getJarFile()) {
                    jar.stream().map(java.util.zip.ZipEntry::getName)
                            .filter(n -> n.startsWith("mocks/") && n.endsWith(".json"))
                            .forEach(n -> out.add(n.substring("mocks/".length())));
                }
            } else if (u != null) {
                Path root = Path.of(u.toURI()).getParent().getParent();
                try (Stream<Path> s = Files.walk(root)) {
                    s.filter(Files::isRegularFile).forEach(f -> out.add(root.relativize(f).toString().replace('\\', '/')));
                }
            }
        } catch (IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("Cannot list bundled mocks", e);
        }
        bundledIndex = out;
        return out;
    }
}
