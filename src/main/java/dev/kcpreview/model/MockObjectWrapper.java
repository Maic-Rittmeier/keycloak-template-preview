package dev.kcpreview.model;

import freemarker.template.AdapterTemplateModel;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.TemplateBooleanModel;
import freemarker.template.TemplateCollectionModel;
import freemarker.template.TemplateHashModelEx2;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;
import freemarker.template.TemplateModelIterator;
import freemarker.template.TemplateNumberModel;
import freemarker.template.TemplateScalarModel;
import freemarker.template.TemplateSequenceModel;
import freemarker.template.Version;
import freemarker.template.utility.DeepUnwrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps plain JSON mock data (Maps, Lists, Strings, …) so that it behaves like Keycloak's Java beans inside templates:
 * <ul>
 *   <li>{@code auth.showUsername} and {@code auth.showUsername()} both work (every value is also callable)</li>
 *   <li>{@code event.getDetail("x")} resolves to key {@code detail} and then to {@code detail.x}</li>
 *   <li>{@code resource.hasDefer()} is true when key {@code defer} has content</li>
 * </ul>
 * Anything else (java.util.Locale, Date, real beans) is wrapped like Keycloak does it.
 */
public class MockObjectWrapper extends DefaultObjectWrapper {

    private static final Pattern GETTER = Pattern.compile("^(get|is)([A-Z].*)$");
    private static final Pattern HAS = Pattern.compile("^has([A-Z].*)$");

    public MockObjectWrapper(Version incompatibleImprovements) {
        super(incompatibleImprovements);
    }

    @Override
    public TemplateModel wrap(Object o) throws TemplateModelException {
        if (o == null) return null;
        if (o instanceof TemplateModel tm) return tm;
        if (o instanceof Map<?, ?> m) return new MockHash(m, this);
        if (o instanceof List<?> l) return new MockSeq(l, this);
        if (o instanceof String s) return new MockScalar(s);
        if (o instanceof Boolean b) return new MockBool(b);
        if (o instanceof Integer || o instanceof Long || o instanceof java.math.BigDecimal || o instanceof Double) {
            return new MockNumber((Number) o);
        }
        return super.wrap(o);
    }

    static Object unwrapArg(Object arg) throws TemplateModelException {
        return arg instanceof TemplateModel tm ? DeepUnwrap.unwrap(tm) : arg;
    }

    static boolean hasContent(Object v) {
        if (v == null) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        if (v instanceof Boolean b) return b;
        return true;
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ models

    /** Every mock value can also be called like a getter: {@code x.foo()} returns {@code x.foo}. */
    abstract static class Callable implements TemplateMethodModelEx, AdapterTemplateModel {
        @Override
        public Object exec(@SuppressWarnings("rawtypes") List args) throws TemplateModelException {
            return this;
        }

        @Override
        public Object getAdaptedObject(@SuppressWarnings("rawtypes") Class hint) {
            return raw();
        }

        abstract Object raw();
    }

    static final class MockScalar extends Callable implements TemplateScalarModel {
        private final String v;
        MockScalar(String v) { this.v = v; }
        @Override public String getAsString() { return v; }
        @Override Object raw() { return v; }
    }

    static final class MockBool extends Callable implements TemplateBooleanModel {
        private final boolean v;
        MockBool(boolean v) { this.v = v; }
        @Override public boolean getAsBoolean() { return v; }
        @Override Object raw() { return v; }
    }

    static final class MockNumber extends Callable implements TemplateNumberModel {
        private final Number v;
        MockNumber(Number v) { this.v = v; }
        @Override public Number getAsNumber() { return v; }
        @Override Object raw() { return v; }
    }

    static final class MockSeq extends Callable implements TemplateSequenceModel {
        private final List<?> v;
        private final MockObjectWrapper w;
        MockSeq(List<?> v, MockObjectWrapper w) { this.v = v; this.w = w; }
        @Override public TemplateModel get(int i) throws TemplateModelException { return i < v.size() ? w.wrap(v.get(i)) : null; }
        @Override public int size() { return v.size(); }
        @Override Object raw() { return v; }
    }

    static final class MockHash extends Callable implements TemplateHashModelEx2 {
        private final Map<?, ?> v;
        private final MockObjectWrapper w;

        MockHash(Map<?, ?> v, MockObjectWrapper w) { this.v = v; this.w = w; }

        @Override Object raw() { return v; }

        /** {@code x.getDetail("foo")} → {@code detail.foo}; {@code realm.getAttribute("k")} → {@code attribute.k}. */
        @Override
        public Object exec(@SuppressWarnings("rawtypes") List args) throws TemplateModelException {
            if (args.size() == 1) return get(String.valueOf(unwrapArg(args.get(0))));
            return this;
        }

        @Override
        public TemplateModel get(String key) throws TemplateModelException {
            if (v.containsKey(key)) return w.wrap(v.get(key));
            Matcher g = GETTER.matcher(key);
            if (g.matches()) {
                String k = decapitalize(g.group(2));
                if (v.containsKey(k)) return w.wrap(v.get(k));
                if (v.containsKey(k + "s")) return w.wrap(v.get(k + "s"));
            }
            Matcher h = HAS.matcher(key);
            if (h.matches()) {
                return new MockBool(hasContent(v.get(decapitalize(h.group(1)))));
            }
            return null;
        }

        @Override public boolean isEmpty() { return v.isEmpty(); }
        @Override public int size() { return v.size(); }

        @Override
        public TemplateCollectionModel keys() throws TemplateModelException {
            return collection(new ArrayList<>(v.keySet().stream().map(String::valueOf).toList()));
        }

        @Override
        public TemplateCollectionModel values() throws TemplateModelException {
            return collection(new ArrayList<>(v.values()));
        }

        private TemplateCollectionModel collection(List<?> items) {
            return () -> new TemplateModelIterator() {
                private final Iterator<?> it = items.iterator();
                @Override public TemplateModel next() throws TemplateModelException { return w.wrap(it.next()); }
                @Override public boolean hasNext() { return it.hasNext(); }
            };
        }

        @Override
        public KeyValuePairIterator keyValuePairIterator() {
            Iterator<? extends Map.Entry<?, ?>> it = v.entrySet().iterator();
            return new KeyValuePairIterator() {
                @Override public boolean hasNext() { return it.hasNext(); }
                @Override public KeyValuePair next() throws TemplateModelException {
                    Map.Entry<?, ?> e = it.next();
                    TemplateModel k = w.wrap(String.valueOf(e.getKey()));
                    TemplateModel val = w.wrap(e.getValue());
                    return new KeyValuePair() {
                        @Override public TemplateModel getKey() { return k; }
                        @Override public TemplateModel getValue() { return val; }
                    };
                }
            };
        }
    }
}
