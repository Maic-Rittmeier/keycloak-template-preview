package dev.kcpreview;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves Keycloak themes the same way Keycloak's DefaultThemeManager does:
 * theme → its import (e.g. common/keycloak) → parent → parent's import → …
 */
public final class ThemeRepository {

    /** A concrete theme of one type, e.g. ("keycloak.v2", "login"). */
    public record ThemeRef(String name, String type) {
        String dir() { return name + "/" + type + "/"; }
    }

    private final List<ThemeSource> sources;

    public ThemeRepository(List<ThemeSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public List<ThemeSource> sources() {
        return sources;
    }

    public URL find(String path) {
        for (ThemeSource s : sources) {
            URL u = s.find(path);
            if (u != null) return u;
        }
        return null;
    }

    public boolean exists(String name, String type) {
        for (ThemeSource s : sources) {
            if (!s.list(name + "/" + type + "/").isEmpty()) return true;
        }
        return false;
    }

    /** Own (non-inherited) theme.properties. */
    public Properties ownProperties(ThemeRef ref) {
        return load(find(ref.dir() + "theme.properties"));
    }

    /** Lookup chain, first entry wins. */
    public List<ThemeRef> chain(String name, String type) {
        List<ThemeRef> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = name;
        while (current != null && !current.isBlank()) {
            ThemeRef ref = new ThemeRef(current.trim(), type);
            if (!seen.add(ref.name())) throw new IllegalStateException("Theme parent cycle: " + seen);
            if (!exists(ref.name(), type)) {
                throw new IllegalStateException("Theme '" + ref.name() + "' of type '" + type + "' not found in " + labels());
            }
            chain.add(ref);
            Properties p = ownProperties(ref);
            String imp = p.getProperty("import");
            if (imp != null && !imp.isBlank()) {
                for (String i : imp.split(",")) {
                    String[] parts = i.trim().split("/");
                    if (parts.length == 2) chain.add(new ThemeRef(parts[1], parts[0]));
                }
            }
            current = p.getProperty("parent");
        }
        return chain;
    }

    /** Merged theme.properties (parents first, child overrides). */
    public Properties properties(List<ThemeRef> chain) {
        Properties merged = new Properties();
        List<ThemeRef> rev = new ArrayList<>(chain);
        Collections.reverse(rev);
        for (ThemeRef r : rev) merged.putAll(ownProperties(r));
        return merged;
    }

    /** Merged messages; English is always the fallback below the requested language. */
    public Properties messages(List<ThemeRef> chain, String lang) {
        Properties merged = new Properties();
        List<ThemeRef> rev = new ArrayList<>(chain);
        Collections.reverse(rev);
        List<String> langs = new ArrayList<>(List.of("en"));
        if (lang.contains("-") || lang.contains("_")) {
            String base = lang.split("[-_]")[0];
            if (!langs.contains(base)) langs.add(base);
        }
        if (!langs.contains(lang)) langs.add(lang);
        for (String l : langs) {
            String suffix = l.replace('-', '_');
            for (ThemeRef r : rev) {
                merged.putAll(load(find(r.dir() + "messages/messages_" + suffix + ".properties")));
            }
        }
        return merged;
    }

    public URL template(List<ThemeRef> chain, String name) {
        for (ThemeRef r : chain) {
            URL u = find(r.dir() + name);
            if (u != null) return u;
        }
        return null;
    }

    public URL resource(List<ThemeRef> chain, String path) {
        for (ThemeRef r : chain) {
            URL u = find(r.dir() + "resources/" + path);
            if (u != null) return u;
        }
        return null;
    }

    /** All .ftl files visible in the chain inside the given sub directory ("" or "html/"). */
    public Set<String> templates(List<ThemeRef> chain, String subDir) {
        Set<String> out = new TreeSet<>();
        for (ThemeRef r : chain) {
            for (ThemeSource s : sources) {
                for (String f : s.list(r.dir() + subDir)) {
                    if (f.endsWith(".ftl")) out.add(f);
                }
            }
        }
        return out;
    }

    /** Dropdown group label and whether a theme came from a {@code --themes} folder (watched, hot-reloaded). */
    public record ThemeInfo(String group, boolean user) {
    }

    /** Theme names that provide the given type; order follows source priority (first source wins on name clashes). */
    public Map<String, ThemeInfo> themeNames(String type) {
        Map<String, ThemeInfo> out = new LinkedHashMap<>();
        for (ThemeSource s : sources) {
            for (String n : s.list("")) {
                if (!n.endsWith("/")) continue;
                String name = n.substring(0, n.length() - 1);
                if (name.startsWith(".") || out.containsKey(name)) continue;
                if (!s.list(name + "/" + type + "/").isEmpty()) {
                    out.put(name, new ThemeInfo(s.group(), s.isUserSource()));
                }
            }
        }
        return out;
    }

    public long lastModifiedUserSources() {
        long m = 0;
        for (ThemeSource s : sources) if (s.isUserSource()) m += s.lastModified();
        return m;
    }

    private List<String> labels() {
        return sources.stream().map(ThemeSource::label).toList();
    }

    static Properties load(URL u) {
        Properties p = new Properties();
        if (u == null) return p;
        try (InputStream in = u.openStream(); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + u, e);
        }
        return p;
    }
}
