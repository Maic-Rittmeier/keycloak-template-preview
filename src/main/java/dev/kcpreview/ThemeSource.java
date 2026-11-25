package dev.kcpreview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * A place where Keycloak themes live. Paths are relative to the theme root, e.g. {@code keycloak.v2/login/login.ftl}.
 */
public interface ThemeSource {

    /** First {@code --themes} folder: your own, deployable themes. */
    String GROUP_CUSTOM = "Custom themes";
    /** {@code --keycloak-themes} and the bundled {@code keycloak-themes.jar}. */
    String GROUP_KEYCLOAK = "Keycloak";

    /** Human readable label (shown in the UI). */
    String label();

    /** Dropdown group label in the UI, e.g. "Custom themes", "Plugin themes" or "Keycloak". */
    String group();

    /** True for the user's own theme folders (watched for hot reload). */
    boolean isUserSource();

    URL find(String path);

    /** Direct children (files and folders) of a directory; folder names end with '/'. */
    Set<String> list(String dir);

    /** Max modification time of all files (only meaningful for user sources). */
    long lastModified();

    // ------------------------------------------------------------------ file system

    final class Folder implements ThemeSource {
        private final Path root;
        private final boolean user;
        private final String group;

        public Folder(Path root, boolean user, String group) {
            this.root = root.toAbsolutePath().normalize();
            this.user = user;
            this.group = group;
        }

        @Override public String label() { return root.toString(); }
        @Override public String group() { return group; }
        @Override public boolean isUserSource() { return user; }

        @Override
        public URL find(String path) {
            Path p = root.resolve(path).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) return null;
            try {
                return p.toUri().toURL();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public Set<String> list(String dir) {
            Set<String> out = new TreeSet<>();
            Path p = root.resolve(dir).normalize();
            if (!p.startsWith(root) || !Files.isDirectory(p)) return out;
            try (Stream<Path> s = Files.list(p)) {
                s.forEach(c -> out.add(c.getFileName() + (Files.isDirectory(c) ? "/" : "")));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out;
        }

        @Override
        public long lastModified() {
            if (!Files.isDirectory(root)) return 0;
            try (Stream<Path> s = Files.walk(root)) {
                return s.mapToLong(f -> {
                    try { return Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { return 0; }
                }).max().orElse(0) + countFiles();
            } catch (IOException e) {
                return 0;
            }
        }

        /** Also react on deleted files. */
        private long countFiles() {
            try (Stream<Path> s = Files.walk(root)) {
                return s.count();
            } catch (IOException e) {
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ classpath (keycloak-themes.jar)

    final class Classpath implements ThemeSource {
        private static final String PREFIX = "theme/";
        private final ClassLoader cl = Classpath.class.getClassLoader();
        private final java.util.NavigableSet<String> entries = new TreeSet<>();
        private final String label;

        public Classpath() {
            String l = "classpath";
            try {
                Enumeration<URL> roots = cl.getResources("theme/base/login/template.ftl");
                while (roots.hasMoreElements()) {
                    URL u = roots.nextElement();
                    if ("jar".equals(u.getProtocol())) {
                        JarURLConnection c = (JarURLConnection) u.openConnection();
                        c.setUseCaches(false);
                        try (JarFile jar = c.getJarFile()) {
                            l = Paths.get(jar.getName()).getFileName().toString();
                            Enumeration<JarEntry> en = jar.entries();
                            while (en.hasMoreElements()) {
                                String n = en.nextElement().getName();
                                if (n.startsWith(PREFIX)) entries.add(n.substring(PREFIX.length()));
                            }
                        }
                    } else if ("file".equals(u.getProtocol())) {
                        Path base = Paths.get(u.toURI()).getParent().getParent().getParent();
                        try (Stream<Path> s = Files.walk(base)) {
                            s.forEach(f -> entries.add(base.relativize(f).toString().replace('\\', '/')
                                    + (Files.isDirectory(f) ? "/" : "")));
                        }
                        l = base.toString();
                    }
                }
            } catch (IOException | URISyntaxException e) {
                throw new IllegalStateException("Cannot scan classpath themes", e);
            }
            this.label = l;
        }

        public boolean isEmpty() { return entries.isEmpty(); }

        @Override public String label() { return label; }
        @Override public String group() { return GROUP_KEYCLOAK; }
        @Override public boolean isUserSource() { return false; }
        @Override public long lastModified() { return 0; }

        @Override
        public URL find(String path) {
            if (path.contains("..")) return null;
            return cl.getResource(PREFIX + path);
        }

        @Override
        public Set<String> list(String dir) {
            // Must stay "" for the root listing (not "/"): entries have no leading slash, so a leading
            // "/" would never match any of them via startsWith() below, silently returning nothing.
            String d = dir.isEmpty() || dir.endsWith("/") ? dir : dir + "/";
            Set<String> out = new TreeSet<>();
            for (String e : entries.tailSet(d)) {
                if (!e.startsWith(d)) break;
                String rest = e.substring(d.length());
                if (rest.isEmpty()) continue;
                int slash = rest.indexOf('/');
                out.add(slash < 0 ? rest : rest.substring(0, slash + 1));
            }
            return out;
        }
    }
}
