package dev.kcpreview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny HTTP server (JDK built-in) serving the preview UI, rendered templates and theme resources. */
public final class PreviewServer {

    private final PreviewRenderer renderer;
    private final String defaultLang;
    private HttpServer server;

    public PreviewServer(PreviewRenderer renderer, String defaultLang) {
        this.renderer = renderer;
        this.defaultLang = defaultLang;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                sendClasspath(ex, "preview-ui/index.html");
            } else if (path.equals("/api/catalog")) {
                sendJson(ex, 200, catalog());
            } else if (path.equals("/api/templates")) {
                sendJson(ex, 200, templates(q.get("type"), q.get("theme")));
            } else if (path.equals("/api/changes")) {
                long stamp = renderer.repo().lastModifiedUserSources() + renderer.mocks().lastModified();
                sendJson(ex, 200, Map.of("stamp", stamp));
            } else if (path.startsWith("/render/login/")) {
                String[] p = path.substring("/render/login/".length()).split("/", 2);
                String html = withColorScheme(renderLoginSafe(p[0], p[1], lang(q), q.get("variant")), q.get("scheme"));
                send(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/render/email/")) {
                String[] p = path.substring("/render/email/".length()).split("/", 2);
                renderEmail(ex, p[0], p[1], lang(q), q.get("variant"), q.getOrDefault("part", "html"));
            } else if (path.startsWith("/source/login/")) {
                String[] p = path.substring("/source/login/".length()).split("/", 2);
                sendJson(ex, 200, templateSourceChain(p[0], "login", p[1]));
            } else if (path.startsWith("/source/email/")) {
                String[] p = path.substring("/source/email/".length()).split("/", 2);
                String part = q.getOrDefault("part", "html");
                sendJson(ex, 200, templateSourceChain(p[0], "email", part + "/" + p[1]));
            } else if (path.startsWith("/resources/")) {
                String[] p = path.substring("/resources/".length()).split("/", 3);
                if (p.length < 3) {
                    send(ex, 404, "text/plain", "not found".getBytes());
                    return;
                }
                URL u = renderer.repo().resource(renderer.repo().chain(p[0], p[1]), p[2]);
                if (u == null) {
                    send(ex, 404, "text/plain", ("Resource not found in theme chain: " + path).getBytes());
                } else {
                    try (InputStream in = u.openStream()) {
                        send(ex, 200, contentType(p[2]), in.readAllBytes());
                    }
                }
            } else if (path.equals(PreviewRenderer.SUBMIT_PATH)) {
                send(ex, 200, "text/html; charset=utf-8", submitted(ex).getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/favicon.ico")) {
                send(ex, 204, "text/plain", new byte[0]);
            } else {
                send(ex, 404, "text/plain", "not found".getBytes());
            }
        } catch (Exception e) {
            send(ex, 500, "text/html; charset=utf-8", ErrorPage.html(e, null).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Keycloak switches dark mode via matchMedia("(prefers-color-scheme: dark)"). To preview both modes regardless of the
     * OS setting, ?scheme=dark|light answers that media query with a fixed value.
     */
    static String withColorScheme(String html, String scheme) {
        if (!"dark".equals(scheme) && !"light".equals(scheme)) return html;
        String script = "<script>(()=>{const dark=" + "dark".equals(scheme) + ",mm=window.matchMedia.bind(window);"
                + "window.matchMedia=q=>/prefers-color-scheme/.test(q)?{matches:/dark/.test(q)===dark,media:q,onchange:null,"
                + "addEventListener(){},removeEventListener(){},addListener(){},removeListener(){},dispatchEvent(){return false}}:mm(q);"
                + "document.documentElement.style.colorScheme=dark?'dark':'light';})();</script>";
        int head = html.indexOf("<head>");
        return head < 0 ? script + html : html.substring(0, head + 6) + script + html.substring(head + 6);
    }

    private String lang(Map<String, String> q) {
        String l = q.get("locale");
        if (l == null || l.isBlank()) l = q.get("kc_locale");
        return l == null || l.isBlank() ? defaultLang : l;
    }

    private static final Pattern IMPORT_OR_INCLUDE = Pattern.compile("<#(?:import|include)\\s+\"([^\"]+)\"");

    /**
     * Raw, unrendered FTL source of a template plus everything it {@code <#import>}s/{@code <#include>}s,
     * transitively (for the "FTL" view) - so you see the full chain, not just the one page. Resolved
     * through the theme chain, same as rendering; import paths are resolved relative to the importing
     * file's own directory, same as FreeMarker itself does.
     */
    private List<Map<String, String>> templateSourceChain(String theme, String type, String name) {
        List<ThemeRepository.ThemeRef> chain = renderer.repo().chain(theme, type);
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(name);
        seen.add(name);
        while (!queue.isEmpty()) {
            String path = queue.poll();
            URL u = renderer.repo().template(chain, path);
            String src = u == null ? null : PreviewRenderer.read(u);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("source", src == null ? "(not found in the theme chain)" : src);
            out.add(entry);
            if (src == null) continue;
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
            Matcher m = IMPORT_OR_INCLUDE.matcher(src);
            while (m.find()) {
                String ref = m.group(1);
                String resolved = ref.startsWith("/") ? ref.substring(1) : dir + ref;
                if (seen.add(resolved)) queue.add(resolved);
            }
        }
        return out;
    }

    private String renderLoginSafe(String theme, String template, String lang, String variant) {
        try {
            return renderer.renderLogin(theme, template, lang, variant);
        } catch (Exception e) {
            return ErrorPage.html(e, "login/" + template.replace(".ftl", "") + ".json");
        }
    }

    private void renderEmail(HttpExchange ex, String theme, String template, String lang, String variant, String part)
            throws IOException {
        String mockHint = "email/" + template.replace(".ftl", "") + ".json";
        PreviewRenderer.Email mail;
        try {
            mail = renderer.renderEmail(theme, template, lang, variant);
        } catch (IOException | TemplateException | RuntimeException e) {
            if ("meta".equals(part)) {
                sendJson(ex, 200, Map.of("error", ErrorPage.text(e)));
            } else {
                send(ex, 200, "text/html; charset=utf-8", ErrorPage.html(e, mockHint).getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        switch (part) {
            case "meta" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subject", mail.subject());
                m.put("hasHtml", mail.html() != null);
                m.put("text", mail.text());
                sendJson(ex, 200, m);
            }
            case "text" -> send(ex, 200, "text/plain; charset=utf-8",
                    String.valueOf(mail.text()).getBytes(StandardCharsets.UTF_8));
            default -> send(ex, 200, "text/html; charset=utf-8", (mail.html() == null
                    ? "<p style='font-family:sans-serif;color:#666'>No HTML template available (text only).</p>"
                    : mail.html()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String type : List.of("login", "email")) {
            List<Map<String, Object>> themes = new ArrayList<>();
            renderer.repo().themeNames(type).forEach((name, info) -> {
                if (name.equals("base") && type.equals("login")) return; // base login is unstyled & abstract
                themes.add(Map.of("name", name, "group", info.group(), "user", info.user()));
            });
            out.put(type, themes);
        }
        out.put("defaultLocale", defaultLang);
        out.put("sources", renderer.repo().sources().stream()
                .map(s -> Map.of("label", s.label(), "group", s.group(), "user", s.isUserSource())).toList());
        out.put("freemarker", freemarker.template.Configuration.getVersion().toString());
        return out;
    }

    private Map<String, Object> templates(String type, String theme) {
        List<String> names = "email".equals(type) ? renderer.emailTemplates(theme) : renderer.loginPages(theme);
        List<Map<String, Object>> list = new ArrayList<>();
        for (String n : names) {
            List<String> variants = new ArrayList<>(List.of("default"));
            variants.addAll(renderer.mocks().variants(type, n.replace(".ftl", "")));
            list.add(Map.of("name", n, "variants", variants));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", list);
        out.put("locales", renderer.locales(theme, type));
        out.put("chain", renderer.repo().chain(theme, type).stream().map(r -> r.name() + "/" + r.type()).distinct().toList());
        return out;
    }

    private String submitted(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = query(body);
        StringBuilder rows = new StringBuilder();
        form.forEach((k, v) -> rows.append("<tr><td>").append(ErrorPage.esc(k)).append("</td><td>")
                .append(ErrorPage.esc(k.toLowerCase().contains("password") ? "••••••" : v)).append("</td></tr>"));
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><style>body{font-family:system-ui,sans-serif;padding:2rem;color:#222}"
                + "table{border-collapse:collapse}td{border:1px solid #ddd;padding:.4rem .8rem}</style></head><body>"
                + "<h2>Form submitted</h2><p>In Keycloak, the flow would now continue. Submitted fields:</p>"
                + "<table>" + (rows.isEmpty() ? "<tr><td>(none)</td></tr>" : rows) + "</table>"
                + "<p><a href='javascript:history.back()'>← back</a></p></body></html>";
    }

    // ------------------------------------------------------------------ http helpers

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.merge(k, v, (a, b) -> a + ", " + b);
        }
        return m;
    }

    private static void sendClasspath(HttpExchange ex, String res) throws IOException {
        try (InputStream in = PreviewServer.class.getClassLoader().getResourceAsStream(res)) {
            if (in == null) {
                send(ex, 404, "text/plain", "missing".getBytes());
            } else {
                send(ex, 200, contentType(res), in.readAllBytes());
            }
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        if (status != 204) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".eot")) return "application/vnd.ms-fontobject";
        return "application/octet-stream";
    }

    /** Readable error output for template problems. */
    static final class ErrorPage {
        static String text(Throwable e) {
            if (e instanceof TemplateException te) {
                return te.getMessageWithoutStackTop() + "\n\nFTL stack:\n" + te.getFTLInstructionStack();
            }
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        static String html(Throwable e, String mockHint) {
            String hint = "";
            if (e instanceof TemplateException && mockHint != null) {
                hint = "<p class='hint'>Missing a variable? Add it to <code>mocks/" + esc(mockHint)
                        + "</code> (or <code>mocks/" + esc(mockHint.split("/")[0]) + "/_common.json</code> for all pages).</p>";
            }
            return "<!DOCTYPE html><html><head><meta charset='utf-8'><style>"
                    + "body{font-family:system-ui,sans-serif;margin:0;padding:1.5rem;background:#fff5f5;color:#3b0d0d}"
                    + "h2{margin:0 0 1rem;color:#b00020}pre{white-space:pre-wrap;background:#fff;border:1px solid #f0c2c2;"
                    + "padding:1rem;border-radius:6px;font-size:13px;overflow:auto}.hint{background:#fff;border-left:4px solid #b00020;padding:.6rem 1rem}"
                    + "</style></head><body><h2>Template error</h2>" + hint + "<pre>" + esc(text(e)) + "</pre></body></html>";
        }

        static String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
