package dev.kcpreview;

import dev.kcpreview.ThemeRepository.ThemeRef;
import dev.kcpreview.model.Methods;
import dev.kcpreview.model.MockObjectWrapper;
import freemarker.cache.URLTemplateLoader;
import freemarker.core.HTMLOutputFormat;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import freemarker.template.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Bidi;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a Keycloak-like data model from theme + mock data and renders login pages and emails. */
public final class PreviewRenderer {

    /** Same "incompatible improvements" level Keycloak uses (2.3.32), capped to the FreeMarker version on the classpath. */
    static final Version FM_VERSION = Configuration.getVersion().intValue() >= 2_003_032
            ? new Version(2, 3, 32) : Configuration.getVersion();

    /** Templates in the login theme that are pages (and not macros / includes). */
    private static final Set<String> LOGIN_PARTIALS = Set.of("template.ftl", "footer.ftl", "field.ftl", "buttons.ftl",
            "divider.ftl", "password-commons.ftl", "register-commons.ftl", "user-profile-commons.ftl",
            "social-providers.ftl", "passkeys.ftl", "theme-resources.ftl", "qr-code.ftl", "password-validation.ftl");

    public static final String SUBMIT_PATH = "/__submit";

    private final ThemeRepository repo;
    private final MockStore mocks;
    private final String baseUrl;

    public PreviewRenderer(ThemeRepository repo, MockStore mocks, String baseUrl) {
        this.repo = repo;
        this.mocks = mocks;
        this.baseUrl = baseUrl;
    }

    public ThemeRepository repo() {
        return repo;
    }

    public MockStore mocks() {
        return mocks;
    }

    public record Email(String subject, String html, String text) {
    }

    // ------------------------------------------------------------------ catalog

    public List<String> loginPages(String theme) {
        List<ThemeRef> chain = repo.chain(theme, "login");
        List<ThemeRef> listChain = listingChain(theme, chain);
        List<String> pages = new ArrayList<>();
        for (String t : repo.templates(listChain, "")) {
            if (LOGIN_PARTIALS.contains(t)) continue;
            String src = read(repo.template(chain, t));
            if (src.contains("registrationLayout") || src.contains("<html") || src.contains("<!DOCTYPE")) pages.add(t);
        }
        return pages;
    }

    public List<String> emailTemplates(String theme) {
        List<ThemeRef> chain = repo.chain(theme, "email");
        List<ThemeRef> listChain = listingChain(theme, chain);
        List<String> out = new ArrayList<>(repo.templates(listChain, "html/"));
        out.addAll(repo.templates(listChain, "text/"));
        return out.stream().distinct().filter(t -> !t.equals("template.ftl")).sorted().toList();
    }

    /**
     * A building-block theme (e.g. email-otp: {@code buildingBlock=true} in its own theme.properties)
     * only has a parent/import so it can also render standalone - for the catalog, only list what it
     * actually ships itself, not everything it inherits. A normal theme lists its full chain as usual.
     */
    private List<ThemeRef> listingChain(String theme, List<ThemeRef> chain) {
        boolean buildingBlock = "true".equals(repo.ownProperties(chain.get(0)).getProperty("buildingBlock"));
        return buildingBlock ? chain.subList(0, 1) : chain;
    }

    /** Locales offered by the theme (theme.properties "locales"), ordered en/de first. */
    public List<String> locales(String theme, String type) {
        String l = repo.properties(repo.chain(theme, type)).getProperty("locales", "en");
        List<String> list = new ArrayList<>(Arrays.stream(l.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        list.sort(Comparator.comparingInt((String s) -> s.equals("de") ? 0 : s.equals("en") ? 1 : 2).thenComparing(s -> s));
        return list;
    }

    // ------------------------------------------------------------------ login

    public String renderLogin(String theme, String template, String lang, String variant) throws IOException, TemplateException {
        List<ThemeRef> chain = repo.chain(theme, "login");
        Properties props = repo.properties(chain);
        Properties messages = repo.messages(chain, lang);
        Locale locale = Locale.forLanguageTag(lang);
        String pageId = template.substring(0, template.length() - 4);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", urls(theme, chain, "login"));
        m.put("properties", toMap(props));
        m.put("themeResources", themeResources(props));
        m.put("darkMode", "true".equals(props.getProperty("darkMode")));
        m.put("lang", lang);
        m.put("scripts", new ArrayList<>());
        m.put("templateName", template);
        m.put("pageId", pageId);

        Json.deepMerge(m, mocks.load("login", pageId, variant));

        // locale bean (supported locales come from the mock: "_supportedLocales")
        Object sup = m.remove("_supportedLocales");
        List<Map<String, Object>> supported = new ArrayList<>();
        for (Object o : sup instanceof List<?> l ? l : List.of("de", "en")) {
            String tag = String.valueOf(o);
            supported.add(Map.of("languageTag", tag, "label", localeLabel(tag, messages), "url", "?locale=" + tag));
        }
        Map<String, Object> localeBean = new LinkedHashMap<>();
        localeBean.put("current", localeLabel(lang, messages));
        localeBean.put("currentLanguageTag", lang);
        localeBean.put("rtl", !new Bidi(messages.getProperty("locale_" + lang, lang), Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isLeftToRight());
        localeBean.put("supported", supported);
        if (m.get("locale") instanceof Map<?, ?> override) {
            override.forEach((k, v) -> localeBean.put(String.valueOf(k), v));
        }
        m.put("locale", localeBean);

        // global / per-field messages may reference message keys
        if (m.get("message") instanceof Map<?, ?> msg) {
            Map<String, Object> copy = new LinkedHashMap<>();
            msg.forEach((k, v) -> copy.put(String.valueOf(k), v));
            copy.put("summary", text(copy.get("summary"), messages, locale));
            copy.putIfAbsent("type", "error");
            m.put("message", copy);
        }
        Map<?, ?> perField = m.get("messagesPerField") instanceof Map<?, ?> pf ? pf : Map.of();
        m.put("messagesPerField", new Methods.MessagesPerField(perField, s -> text(s, messages, locale)));

        if (!m.containsKey("title")) {
            Object realm = m.get("realm");
            Object displayName = realm instanceof Map<?, ?> r ? r.get("displayName") : "";
            m.put("title", MessageFormat.format(messages.getProperty("loginTitle", "Sign in to {0}"), displayName));
        }

        Object configured = m.remove("_authenticatorConfigured");
        m.put("authenticatorConfigured", new Methods.Constant(configured == null || Boolean.TRUE.equals(configured)));
        m.put("requiredActionUrl", new Methods.Constant("#"));
        putMessageMethods(m, locale, messages);

        return process(chain, template, m, locale);
    }

    // ------------------------------------------------------------------ email

    public Email renderEmail(String theme, String template, String lang, String variant) throws IOException, TemplateException {
        List<ThemeRef> chain = repo.chain(theme, "email");
        Properties props = repo.properties(chain);
        Properties messages = repo.messages(chain, lang);
        Locale locale = Locale.forLanguageTag(lang);
        String pageId = template.substring(0, template.length() - 4);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("properties", toMap(props));
        m.put("url", urls(theme, chain, "email"));
        Json.deepMerge(m, mocks.load("email", pageId, variant));

        String subjectKey = String.valueOf(m.getOrDefault("_subjectKey", subjectKeyFor(pageId)));
        Object subjectArgs = m.getOrDefault("_subjectArgs", List.of());
        m.remove("_subjectKey");
        m.remove("_subjectArgs");

        m.put("locale", locale);
        m.put("ltr", new Bidi(messages.getProperty("locale_" + locale.getLanguage(), locale.getLanguage()),
                Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isLeftToRight());
        String subject = new MessageFormat(messages.getProperty(subjectKey, subjectKey), locale)
                .format(subjectArgs instanceof List<?> l ? l.toArray() : new Object[0]);
        m.put("subject", subject);
        m.put("linkExpirationFormatter", new Methods.LinkExpirationFormatter(messages));
        putMessageMethods(m, locale, messages);

        String html = repo.template(chain, "html/" + template) != null ? process(chain, "html/" + template, m, locale) : null;
        String text = repo.template(chain, "text/" + template) != null ? process(chain, "text/" + template, m, locale) : null;
        return new Email(subject, html, text);
    }

    /** Subject keys as used by Keycloak's FreeMarkerEmailTemplateProvider. */
    static String subjectKeyFor(String pageId) {
        return switch (pageId) {
            case "email-test" -> "emailTestSubject";
            case "email-verification", "email-verification-with-code" -> "emailVerificationSubject";
            case "password-reset" -> "passwordResetSubject";
            case "executeActions" -> "executeActionsSubject";
            case "identity-provider-link" -> "identityProviderLinkSubject";
            case "email-update-confirmation" -> "emailUpdateConfirmationSubject";
            case "org-invite" -> "orgInviteSubject";
            case "verifiable-credential-offer" -> "verifiableCredentialOfferSubject";
            case "workflow-notification" -> "workflowNotificationSubject";
            default -> {
                if (pageId.startsWith("event-")) {
                    // event-update_password → eventUpdatePasswordSubject
                    StringBuilder sb = new StringBuilder("event");
                    for (String part : pageId.substring(6).split("_")) {
                        if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                    }
                    yield sb.append("Subject").toString();
                }
                yield pageId + "Subject";
            }
        };
    }

    // ------------------------------------------------------------------ helpers

    private void putMessageMethods(Map<String, Object> m, Locale locale, Properties messages) {
        m.put("msg", new Methods.Msg(locale, messages));
        m.put("advancedMsg", new Methods.AdvancedMsg(locale, messages));
        m.put("kcSanitize", new Methods.KcSanitize());
    }

    private String process(List<ThemeRef> chain, String template, Map<String, Object> model, Locale locale)
            throws IOException, TemplateException {
        Configuration cfg = new Configuration(FM_VERSION);
        cfg.setOutputFormat(HTMLOutputFormat.INSTANCE);
        cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
        cfg.setObjectWrapper(new MockObjectWrapper(FM_VERSION));
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setDefaultEncoding("UTF-8");
        cfg.setLocale(locale);
        cfg.setTemplateUpdateDelayMilliseconds(0);
        cfg.setTemplateLoader(new URLTemplateLoader() {
            @Override
            protected URL getURL(String name) {
                return repo.template(chain, name);
            }
        });
        Template t = cfg.getTemplate(template, "UTF-8");
        StringWriter out = new StringWriter();
        t.process(model, out);
        return out.toString();
    }

    private Map<String, Object> urls(String theme, List<ThemeRef> chain, String type) {
        String common = chain.stream().filter(r -> r.type().equals("common")).map(ThemeRef::name).findFirst().orElse("keycloak");
        String resourcesPath = "/resources/" + theme + "/" + type;
        String commonPath = "/resources/" + common + "/common";
        Map<String, Object> u = new LinkedHashMap<>();
        for (String k : List.of("loginAction", "registrationAction", "oauthAction", "logoutConfirmAction")) u.put(k, SUBMIT_PATH);
        for (String k : List.of("loginUrl", "loginRestartFlowUrl", "registrationUrl", "loginResetCredentialsUrl",
                "loginUsernameReminderUrl", "firstBrokerLoginUrl")) u.put(k, "#" + k);
        u.put("ssoLoginInOtherTabsUrl", "#ssoLoginInOtherTabsUrl");
        u.put("hasAction", true);
        u.put("resourcesPath", resourcesPath);
        u.put("resourcesCommonPath", commonPath);
        u.put("resourcesUrl", baseUrl + resourcesPath);
        u.put("resourcesCommonUrl", baseUrl + commonPath);
        return u;
    }

    private static Map<String, Object> toMap(Properties p) {
        Map<String, Object> m = new TreeMap<>();
        p.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
        return m;
    }

    private static final Pattern RES_KEY = Pattern.compile("^(styles|stylesCommon|scripts|favicons)\\.([^.]+)$");
    private static final Pattern RES_ATTR = Pattern.compile("^(styles|stylesCommon|scripts|favicons)\\.([^.]+)\\.(.+)$");

    /** Simplified org.keycloak.theme.ThemeResourcesParser (Keycloak 26.8+ templates use it, older ones ignore it). */
    static Map<String, Object> themeResources(Properties p) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String type : List.of("styles", "stylesCommon", "scripts", "favicons")) {
            List<Map<String, Object>> list = new ArrayList<>();
            String flat = p.getProperty(type);
            if (flat != null && !flat.isBlank()) {
                for (String path : flat.trim().split("\\s+")) list.add(new LinkedHashMap<>(Map.of("path", path)));
            }
            Map<String, Map<String, Object>> byId = new TreeMap<>();
            for (String key : p.stringPropertyNames()) {
                Matcher k = RES_KEY.matcher(key);
                if (k.matches() && k.group(1).equals(type) && !"order".equals(k.group(2))) {
                    byId.computeIfAbsent(k.group(2), x -> new LinkedHashMap<>()).put("path", p.getProperty(key));
                }
            }
            for (String key : p.stringPropertyNames()) {
                Matcher a = RES_ATTR.matcher(key);
                if (a.matches() && a.group(1).equals(type) && byId.containsKey(a.group(2))) {
                    byId.get(a.group(2)).put(a.group(3), p.getProperty(key));
                }
            }
            List<String> ids = new ArrayList<>(byId.keySet());
            String order = p.getProperty(type + ".order");
            if (order != null) {
                List<String> o = Arrays.stream(order.split(",")).map(String::trim).toList();
                ids.sort(Comparator.comparingInt(id -> o.contains(id) ? o.indexOf(id) : Integer.MAX_VALUE));
            }
            ids.forEach(id -> list.add(byId.get(id)));
            if (type.equals("favicons") && list.isEmpty() && p.getProperty("favIcon") != null) {
                Map<String, Object> f = new LinkedHashMap<>(Map.of("path", p.getProperty("favIcon")));
                if (p.getProperty("favIconType") != null) f.put("type", p.getProperty("favIconType"));
                list.add(f);
            }
            out.put(type, list);
        }
        return out;
    }

    /**
     * A mock text may be plain text, a message key ("invalidUserMessage" / "${invalidUserMessage}")
     * or a key with arguments (["invalidPasswordMinLengthMessage", 8]).
     */
    private static String text(Object o, Properties messages, Locale locale) {
        if (o == null) return "";
        Object[] args = new Object[0];
        if (o instanceof List<?> l && !l.isEmpty()) {
            args = l.subList(1, l.size()).toArray();
            o = l.get(0);
        }
        String s = String.valueOf(o);
        String key = s.startsWith("${") && s.endsWith("}") ? s.substring(2, s.length() - 1) : s;
        String v = messages.getProperty(key);
        if (v == null) return s;
        try {
            return new MessageFormat(v, locale).format(args);
        } catch (IllegalArgumentException e) {
            return v;
        }
    }

    /** Keycloak uses "locale_xx" message keys; newer versions ship them elsewhere, so fall back to the JDK name. */
    static String localeLabel(String tag, Properties messages) {
        String l = messages.getProperty("locale_" + tag);
        if (l != null) return l;
        Locale loc = Locale.forLanguageTag(tag);
        String name = loc.getDisplayName(loc);
        return name.isEmpty() ? tag : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static String read(URL u) {
        if (u == null) return "";
        try (InputStream in = u.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
