import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts the feature catalogue of a plugin entry class into the manifest
 * {@code features} JSON array. Single-file, JDK 17+, no third-party deps.
 *
 * <pre>
 * java PluginFeatureExtractor.java &lt;plugin-api-jar&gt; &lt;android-jar&gt; &lt;classes-dir&gt; &lt;entry-class&gt; &lt;out-json&gt; [feature-classpath...]
 * </pre>
 *
 * <p>The entry class is loaded in a {@link URLClassLoader} whose parent is this
 * tool's own class loader, instantiated with its public no-argument constructor,
 * and {@code getFeatures()} is invoked reflectively. Every returned element must
 * expose {@code getId()}, {@code getName()}, {@code getDescription()},
 * {@code isDefaultEnabled()} and {@code isImplemented()}. Optional trailing
 * classpath entries (jars or directories) are needed when the plugin entry
 * executes base implementation helpers that the signature-closed plugin-api jar
 * does not carry. Any failure exits non-zero; the tool never writes an empty
 * catalogue to hide an error.
 */
public final class PluginFeatureExtractor {
    private static final Pattern FEATURE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern ENTRY_CLASS =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final int MAX_FEATURES = 64;

    private PluginFeatureExtractor() {}

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("usage: PluginFeatureExtractor <plugin-api-jar> <android-jar> "
                    + "<classes-dir> <entry-class> <out-json> [feature-classpath...]");
            System.exit(2);
        }
        try {
            run(Paths.get(args[0]).toAbsolutePath(), Paths.get(args[1]).toAbsolutePath(),
                    Paths.get(args[2]).toAbsolutePath(), args[3], Paths.get(args[4]).toAbsolutePath(),
                    java.util.Arrays.copyOfRange(args, 5, args.length));
            System.exit(0);
        } catch (Throwable failure) {
            System.err.println("PluginFeatureExtractor: FAILED: " + failure);
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
                System.err.println("PluginFeatureExtractor:   caused by: " + cause);
            }
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(Path pluginApiJar, Path androidJar, Path classesDir, String entryClass,
                            Path outJson, String[] extraClasspath) throws Exception {
        if (!Files.isRegularFile(pluginApiJar)) {
            throw new IllegalArgumentException("plugin-api jar is missing: " + pluginApiJar);
        }
        if (!Files.isRegularFile(androidJar)) {
            throw new IllegalArgumentException("android.jar is missing: " + androidJar);
        }
        if (!Files.isDirectory(classesDir)) {
            throw new IllegalArgumentException("compiled classes directory is missing: " + classesDir);
        }
        if (entryClass == null || !ENTRY_CLASS.matcher(entryClass).matches()) {
            throw new IllegalArgumentException("entry class is not a Java binary class name: " + entryClass);
        }
        Path parent = outJson.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ClassLoader parentLoader = PluginFeatureExtractor.class.getClassLoader();
        List<URL> urls = new ArrayList<>();
        urls.add(pluginApiJar.toUri().toURL());
        urls.add(androidJar.toUri().toURL());
        urls.add(classesDir.toUri().toURL());
        for (String extra : extraClasspath) {
            if (extra == null || extra.isEmpty()) {
                throw new IllegalArgumentException("feature classpath entry must not be empty");
            }
            Path path = Paths.get(extra).toAbsolutePath();
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("feature classpath entry is missing: " + path);
            }
            if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
                throw new IllegalArgumentException("feature classpath entry is neither a jar/file nor a directory: " + path);
            }
            urls.add(path.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), parentLoader)) {
            Class<?> entry = Class.forName(entryClass, true, loader);
            if (entry.isInterface() || Modifier.isAbstract(entry.getModifiers())) {
                throw new IllegalStateException("entry class must be concrete: " + entryClass);
            }
            Constructor<?> constructor;
            try {
                constructor = entry.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("entry class needs a no-argument constructor: " + entryClass);
            }
            constructor.setAccessible(true);
            Object instance;
            try {
                instance = constructor.newInstance();
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("entry class constructor failed: "
                        + entryClass + ": " + e.getCause(), e.getCause());
            }

            Method getFeatures;
            try {
                getFeatures = entry.getMethod("getFeatures");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("entry class has no public getFeatures(): " + entryClass);
            }
            Object result;
            try {
                result = getFeatures.invoke(instance);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("getFeatures() threw: " + entryClass + ": "
                        + e.getCause(), e.getCause());
            }
            if (result == null) {
                throw new IllegalStateException("getFeatures() returned null");
            }
            if (!(result instanceof List<?> features)) {
                throw new IllegalStateException("getFeatures() must return java.util.List, got "
                        + result.getClass().getName());
            }
            if (features.size() > MAX_FEATURES) {
                throw new IllegalStateException("getFeatures() returned " + features.size()
                        + " entries; the manifest limit is " + MAX_FEATURES);
            }

            List<String> ids = new ArrayList<>();
            StringBuilder json = new StringBuilder("[");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < features.size(); i++) {
                Object feature = features.get(i);
                if (feature == null) {
                    throw new IllegalStateException("getFeatures() contains a null element at index " + i);
                }
                String id = stringValue(feature, "getId", i);
                String name = stringValue(feature, "getName", i);
                String description = stringValue(feature, "getDescription", i);
                boolean defaultEnabled = booleanValue(feature, "isDefaultEnabled", i);
                boolean implemented = booleanValue(feature, "isImplemented", i);
                if (!FEATURE_ID.matcher(id).matches()) {
                    throw new IllegalStateException("feature[" + i + "] id is not a valid feature id: " + id);
                }
                if (!seen.add(id)) {
                    throw new IllegalStateException("duplicate feature id: " + id);
                }
                ids.add(id);
                if (i > 0) {
                    json.append(',');
                }
                json.append('{')
                        .append("\"id\":").append(quote(id)).append(',')
                        .append("\"name\":").append(quote(name)).append(',')
                        .append("\"description\":").append(quote(description)).append(',')
                        .append("\"defaultEnabled\":").append(defaultEnabled).append(',')
                        .append("\"implemented\":").append(implemented)
                        .append('}');
            }
            json.append("]\n");
            Files.writeString(outJson, json.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            System.err.println("PluginFeatureExtractor: extracted " + features.size() + " feature(s) from "
                    + entryClass + " -> " + outJson);
        }
    }

    private static Method getter(Object feature, String name, int index) {
        try {
            Method method = feature.getClass().getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("feature[" + index + "] (" + feature.getClass().getName()
                    + ") has no public " + name + "()");
        }
    }

    private static Object invoke(Object feature, String name, int index) {
        Method method = getter(feature, name, index);
        try {
            return method.invoke(feature);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("feature[" + index + "]." + name + "() threw: " + e.getCause(),
                    e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("feature[" + index + "]." + name + "() is not accessible", e);
        }
    }

    private static String stringValue(Object feature, String name, int index) {
        Object value = invoke(feature, name, index);
        if (!(value instanceof String text)) {
            throw new IllegalStateException("feature[" + index + "]." + name + "() must return String, got "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return text;
    }

    private static boolean booleanValue(Object feature, String name, int index) {
        Object value = invoke(feature, name, index);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalStateException("feature[" + index + "]." + name + "() must return boolean, got "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return flag;
    }

    /** Writes JSON-safe ASCII: quotes/backslashes/control chars and all non-ASCII are escaped. */
    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
