package com.skywing.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * File-backed java.util.prefs for automated UI tests, so a SkyWing test run never reads or writes the user's registry
 * preferences. Install it in the app under test with
 * {@code -Djava.util.prefs.PreferencesFactory=com.skywing.testsupport.IsolatedPreferencesFactory} and
 * {@code -Dskywing.test.preferencesFile=<file>}; the class is loaded from {@code -Xbootclasspath/a}, so it uses only
 * the JDK. Every change is written through to the file atomically, which lets a test seed values before launch and
 * read them while the app runs or after it exits.
 */
public final class IsolatedPreferencesFactory implements PreferencesFactory {
    public static final String FILE_PROPERTY = "skywing.test.preferencesFile";

    private static Store store;

    private static synchronized Store store() {
        if (store == null) {
            String file = System.getProperty(FILE_PROPERTY);
            if (file == null || file.isEmpty()) throw new IllegalStateException(FILE_PROPERTY + " is not set");
            store = new Store(Paths.get(file));
        }
        return store;
    }

    @Override public Preferences userRoot() { return store().user; }

    @Override public Preferences systemRoot() { return store().system; }

    /** Opens the user root of a preferences file directly, for tests that seed or inspect it from outside the app. */
    public static Preferences openUserRoot(Path file) { return new Store(file).user; }

    private static final class Store {
        private final Path file;
        private final Properties data = new Properties();
        final Node user;
        final Node system;

        Store(Path file) {
            this.file = file;
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) { data.load(in); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            user = new Node(this, null, "", "user:");
            system = new Node(this, null, "", "system:");
        }

        synchronized void save() {
            try {
                Path parent = file.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                Path temp = Files.createTempFile(parent, "prefs", ".tmp");
                Map<Object, Object> sorted = new TreeMap<>(data);
                Properties copy = new Properties();
                copy.putAll(sorted);
                try (OutputStream out = Files.newOutputStream(temp)) { copy.store(out, "SkyWing isolated test preferences"); }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class Node extends AbstractPreferences {
        private final Store store;
        private final String scope;

        Node(Store store, Node parent, String name, String scope) {
            super(parent, name);
            this.store = store;
            this.scope = scope;
            synchronized (store) {
                if (store.data.putIfAbsent(nodeKey(), "") == null && parent != null) store.save();
            }
        }

        private String nodeKey() { return "N\t" + scope + absolutePath(); }

        private String valueKey(String key) { return "V\t" + scope + absolutePath() + "\t" + key; }

        @Override protected void putSpi(String key, String value) {
            synchronized (store) { store.data.setProperty(valueKey(key), value); store.save(); }
        }

        @Override protected String getSpi(String key) {
            synchronized (store) { return store.data.getProperty(valueKey(key)); }
        }

        @Override protected void removeSpi(String key) {
            synchronized (store) { if (store.data.remove(valueKey(key)) != null) store.save(); }
        }

        @Override protected void removeNodeSpi() {
            synchronized (store) {
                String values = "V\t" + scope + absolutePath() + "\t";
                store.data.keySet().removeIf(k -> k.equals(nodeKey()) || ((String) k).startsWith(values));
                store.save();
            }
        }

        @Override protected String[] keysSpi() {
            String prefix = "V\t" + scope + absolutePath() + "\t";
            List<String> keys = new ArrayList<>();
            synchronized (store) {
                for (Object k : store.data.keySet()) if (((String) k).startsWith(prefix)) keys.add(((String) k).substring(prefix.length()));
            }
            return keys.toArray(new String[0]);
        }

        @Override protected String[] childrenNamesSpi() {
            String path = absolutePath();
            String prefix = "N\t" + scope + (path.equals("/") ? "/" : path + "/");
            Set<String> names = new LinkedHashSet<>();
            synchronized (store) {
                for (Object k : store.data.keySet()) {
                    String key = (String) k;
                    if (key.startsWith(prefix) && key.length() > prefix.length() && key.indexOf('/', prefix.length()) < 0) {
                        names.add(key.substring(prefix.length()));
                    }
                }
            }
            return names.toArray(new String[0]);
        }

        @Override protected AbstractPreferences childSpi(String name) { return new Node(store, this, name, scope); }

        @Override protected void syncSpi() { }

        @Override protected void flushSpi() { }
    }
}
