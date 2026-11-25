package dev.kcpreview.model;

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Re-implementations of the template methods Keycloak puts into the data model. */
public final class Methods {

    private Methods() {
    }

    /** {@code ${...}} replacement, identical to org.keycloak.theme.TemplatingUtil. */
    public static String resolveVariables(String text, Properties props) {
        int e = 0;
        int s = text.indexOf("${");
        if (s == -1) return text;
        StringBuilder sb = new StringBuilder();
        do {
            if (e < s) sb.append(text, e, s);
            e = text.indexOf("}", s + 2);
            if (e != -1) {
                String key = text.substring(s + 2, e);
                sb.append(props.getProperty(key, key));
                e += 1;
                s = text.indexOf("${", e);
            } else {
                e = s;
                break;
            }
        } while (s != -1);
        if (e < text.length()) sb.append(text.substring(e));
        return sb.toString();
    }

    private static List<Object> args(List<?> list) throws TemplateModelException {
        List<Object> out = new ArrayList<>();
        for (Object o : list) out.add(MockObjectWrapper.unwrapArg(o));
        return out;
    }

    /** {@code msg("key", args…)} – like org.keycloak.theme.beans.MessageFormatterMethod. */
    public static final class Msg implements TemplateMethodModelEx {
        private final Locale locale;
        private final Properties messages;

        public Msg(Locale locale, Properties messages) {
            this.locale = locale;
            this.messages = messages;
        }

        @Override
        public Object exec(@SuppressWarnings("rawtypes") List list) throws TemplateModelException {
            if (list.isEmpty()) return null;
            List<Object> a = args(list);
            String key = String.valueOf(a.get(0));
            List<Object> rest = new ArrayList<>();
            for (Object o : a.subList(1, a.size())) {
                rest.add(o instanceof String s ? resolveVariables(s, messages) : o);
            }
            String value = resolveVariables(messages.getProperty(key, key), messages);
            try {
                return new MessageFormat(value, locale).format(rest.toArray());
            } catch (IllegalArgumentException e) {
                return value;
            }
        }
    }

    /** {@code advancedMsg("${key}")} – like AdvancedMessageFormatterMethod. */
    public static final class AdvancedMsg implements TemplateMethodModelEx {
        private final Locale locale;
        private final Properties messages;

        public AdvancedMsg(Locale locale, Properties messages) {
            this.locale = locale;
            this.messages = messages;
        }

        @Override
        public Object exec(@SuppressWarnings("rawtypes") List list) throws TemplateModelException {
            if (list.isEmpty()) return null;
            List<Object> a = args(list);
            String key = String.valueOf(a.get(0));
            Object[] rest = a.subList(1, a.size()).toArray();
            if (key.startsWith("${") && key.endsWith("}")) {
                key = key.substring(2, key.length() - 1);
                return new MessageFormat(messages.getProperty(key, key), locale).format(rest);
            } else if (rest.length > 0) {
                return new MessageFormat(key, locale).format(rest);
            }
            return key;
        }
    }

    /**
     * {@code kcSanitize(html)}. Keycloak runs the OWASP HTML sanitizer here; the preview only strips the
     * obviously dangerous parts, so markup that Keycloak would remove may still show up here.
     */
    public static final class KcSanitize implements TemplateMethodModelEx {
        @Override
        public Object exec(@SuppressWarnings("rawtypes") List list) throws TemplateModelException {
            if (list.isEmpty() || list.get(0) == null) return null;
            String html = String.valueOf(MockObjectWrapper.unwrapArg(list.get(0)));
            return html.replaceAll("(?is)<script.*?</script>", "")
                    .replaceAll("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
        }
    }

    /** {@code linkExpirationFormatter(minutes)} – like LinkExpirationFormatterMethod. */
    public static final class LinkExpirationFormatter implements TemplateMethodModelEx {
        private final Properties messages;

        public LinkExpirationFormatter(Properties messages) {
            this.messages = messages;
        }

        @Override
        public Object exec(@SuppressWarnings("rawtypes") List list) throws TemplateModelException {
            Object val = list.isEmpty() ? null : MockObjectWrapper.unwrapArg(list.get(0));
            if (val == null) return "";
            long value;
            try {
                value = Long.parseLong(val.toString().trim()) * 60;
            } catch (NumberFormatException e) {
                return val.toString();
            }
            String unit = "seconds";
            if (value > 0 && value % 60 == 0) {
                unit = "minutes";
                value /= 60;
                if (value % 60 == 0) {
                    unit = "hours";
                    value /= 60;
                    if (value % 24 == 0) {
                        unit = "days";
                        value /= 24;
                    }
                }
            }
            String pattern = messages.getProperty("linkExpirationFormatter.timePeriodUnit." + unit, unit);
            return value + " " + MessageFormat.format(pattern, value);
        }
    }

    /** Method that always returns the same value (e.g. requiredActionUrl, authenticatorConfigured). */
    public static final class Constant implements TemplateMethodModelEx {
        private final Object value;

        public Constant(Object value) {
            this.value = value;
        }

        @Override
        public Object exec(@SuppressWarnings("rawtypes") List list) {
            return value;
        }
    }

    /** Same public API as org.keycloak.theme.beans.MessagesPerFieldBean; wrapped as a Java bean. */
    public static final class MessagesPerField {
        private final Map<String, String[]> messages = new HashMap<>();

        /** @param raw field → "text", ["key", arg…] or {summary, type} */
        public MessagesPerField(Map<?, ?> raw, java.util.function.Function<Object, String> resolver) {
            if (raw == null) return;
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map<?, ?> m) {
                    messages.put(String.valueOf(e.getKey()), new String[]{
                            resolver.apply(m.get("summary")),
                            String.valueOf(m.containsKey("type") ? m.get("type") : "error")});
                } else if (v != null) {
                    messages.put(String.valueOf(e.getKey()), new String[]{resolver.apply(v), "error"});
                }
            }
        }

        public boolean exists(String field) {
            return messages.containsKey(field);
        }

        public boolean existsError(String... fields) {
            for (String f : fields) {
                if (exists(f) && "error".equals(messages.get(f)[1])) return true;
            }
            return false;
        }

        public String getFirstError(String... fields) {
            for (String f : fields) {
                if (existsError(f)) return get(f);
            }
            return "";
        }

        public String get(String field) {
            String[] m = messages.get(field);
            return m == null ? "" : m[0];
        }

        public String printIfExists(String field, String text) {
            return exists(field) ? text : "";
        }
    }
}
