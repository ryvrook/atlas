package dev.ryvrook.atlas;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * The plugins that list Atlas as a dependency. After a {@code /plugin reload} of Atlas their linked
 * api classes belong to the old instance, so the {@code AtlasApi.onEnable} hooks they hold never see the
 * new one and their layers, links and routes stay dark until they are reloaded. On a fresh boot none of them is enabled yet
 * when Atlas starts, because the dependency orders the load, so an enabled dependent at start time
 * means Atlas itself was reloaded and the dependents are reloaded for the owner one after another, each
 * after the dependents it reads itself. The same class as TrackerAPI's and Coinage's, with the same suite lock.
 */
final class Dependents {
    /** A dependent and the instance that was enabled when Atlas started. */
    record Dependent(PluginIdentifier id, PluginBase instance) {
    }

    /** How long a reload that finishes on a future may take before the owner is told to do it by hand. */
    static final long SETTLE_MILLIS = 15_000;
    /**
     * The system properties key of the lock every reloader of the suite takes for its whole pass. A JDK
     * lock in the JVM wide properties is the one object TrackerAPI, Coinage and Atlas all see, whatever loaded them.
     */
    static final String LOCK_KEY = "ryvrook.dependents.lock";
    /** How long a pass waits for another reloader's pass before it goes ahead on the instance check alone. */
    static final long LOCK_SECONDS = 30;

    private Dependents() {
    }

    /**
     * The enabled plugins whose manifest names {@code self} under its dependencies, ordered so a plugin
     * comes after the others in the list that it names itself. SocialBridge reads Dominion, so Dominion is
     * reloaded before it.
     */
    static List<Dependent> enabled(PluginIdentifier self, HytaleLogger logger) {
        List<Dependent> found = new ArrayList<>();
        Map<String, Dependent> byKey = new HashMap<>();
        Map<String, Set<String>> names = new HashMap<>();
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null || self == null) {
                return found;
            }
            for (PluginBase plugin : manager.getPlugins()) {
                try {
                    if (plugin == null || plugin.getState() != PluginState.ENABLED) {
                        continue;
                    }
                    PluginManifest manifest = plugin.getManifest();
                    if (manifest == null || !dependsOn(manifest, self)) {
                        continue;
                    }
                    PluginIdentifier id = plugin.getIdentifier();
                    if (id != null && !id.equals(self)) {
                        String key = key(id);
                        Dependent d = new Dependent(id, plugin);
                        found.add(d);
                        byKey.put(key, d);
                        Set<String> deps = new HashSet<>();
                        addKeys(manifest.getDependencies(), deps);
                        addKeys(manifest.getOptionalDependencies(), deps);
                        names.put(key, deps);
                    }
                } catch (RuntimeException | LinkageError e) {
                    // one odd plugin never stops the walk
                    logger.at(Level.FINE).withCause(e).log("Atlas skipped a plugin it could not read while looking for dependents");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            logger.at(Level.FINE).withCause(e).log("Atlas could not list the plugins to find its dependents");
            return found;
        }
        List<String> keys = new ArrayList<>(found.size());
        for (Dependent d : found) {
            keys.add(key(d.id()));
        }
        List<Dependent> out = new ArrayList<>(found.size());
        for (String key : order(keys, names)) {
            out.add(byKey.get(key));
        }
        return out;
    }

    /**
     * Orders the keys so each comes after every other key in the list it depends on, and otherwise keeps
     * the given order, which is the load order. A cycle is broken at the first key reached again.
     */
    static List<String> order(List<String> keys, Map<String, Set<String>> deps) {
        Set<String> visiting = new HashSet<>();
        Set<String> done = new LinkedHashSet<>();
        for (String key : keys) {
            visit(key, keys, deps, visiting, done);
        }
        return new ArrayList<>(done);
    }

    private static void visit(String key, List<String> keys, Map<String, Set<String>> deps, Set<String> visiting,
                              Set<String> done) {
        if (done.contains(key) || !visiting.add(key)) {
            return;
        }
        Set<String> mine = deps.getOrDefault(key, Set.of());
        for (String other : keys) {
            if (mine.contains(other)) {
                visit(other, keys, deps, visiting, done);
            }
        }
        visiting.remove(key);
        done.add(key);
    }

    private static String key(PluginIdentifier id) {
        return (id.getGroup() + ":" + id.getName()).toLowerCase(Locale.ROOT);
    }

    private static void addKeys(Map<PluginIdentifier, ?> deps, Set<String> into) {
        if (deps != null) {
            for (PluginIdentifier id : deps.keySet()) {
                if (id != null) {
                    into.add(key(id));
                }
            }
        }
    }

    private static boolean dependsOn(PluginManifest manifest, PluginIdentifier self) {
        return names(manifest.getDependencies(), self) || names(manifest.getOptionalDependencies(), self);
    }

    private static boolean names(Map<PluginIdentifier, ?> deps, PluginIdentifier self) {
        if (deps == null) {
            return false;
        }
        for (PluginIdentifier id : deps.keySet()) {
            if (id != null && id.getGroup().equalsIgnoreCase(self.getGroup()) && id.getName().equalsIgnoreCase(self.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reloads each plugin through the plugin manager, logging one line per plugin. A plugin whose instance
     * changed since Atlas started was reloaded by someone else meanwhile, such as TrackerAPI or Coinage
     * reloading their own dependents after they reloaded Atlas, and is left alone. The manager answers false
     * when a plugin's preLoad future was not done yet, and finishes the setup and start on that future
     * later. So a false is followed by a wait of up to {@link #SETTLE_MILLIS} for the new instance to be
     * enabled, which also keeps the order, a plugin is not reloaded before the ones it reads are up. Runs on
     * the short lived {@code Atlas-dependents} thread, never the scheduler or a world thread. Returns how many
     * are linked to the new instance.
     *
     * <p>The whole pass runs under the suite lock, so when TrackerAPI reloads Atlas and Atlas starts its
     * own pass two seconds later, Atlas waits for TrackerAPI to finish and then finds every shared
     * dependent already on a new instance. Each plugin starts once.
     */
    static int reload(List<Dependent> dependents, HytaleLogger logger) {
        PluginManager manager;
        try {
            manager = PluginManager.get();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
        if (manager == null) {
            return 0;
        }
        Lock lock = sharedLock();
        boolean held;
        try {
            held = lock.tryLock(LOCK_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        try {
            return reloadAll(manager, dependents, logger);
        } finally {
            if (held) {
                lock.unlock();
            }
        }
    }

    /** The lock shared by every reloader in the JVM, see {@link #LOCK_KEY}. */
    static Lock sharedLock() {
        Object o = System.getProperties().computeIfAbsent(LOCK_KEY, k -> new ReentrantLock());
        return o instanceof Lock l ? l : new ReentrantLock();
    }

    private static int reloadAll(PluginManager manager, List<Dependent> dependents, HytaleLogger logger) {
        int n = 0;
        for (Dependent d : dependents) {
            PluginIdentifier id = d.id();
            if (dev.ryvrook.atlas.integration.PluginPresence.serverStopping()) {
                logger.at(Level.FINE).log("Atlas stops reloading its dependents because the server is stopping");
                break;
            }
            try {
                if (replaced(manager, d)) {
                    logger.at(Level.FINE).log("Atlas leaves %s alone, it was reloaded since Atlas started", id);
                    n++;
                    continue;
                }
                logger.at(Level.INFO).log("Atlas was reloaded, reloading %s so it links to the new instance", id);
                if (manager.reload(id) || settled(manager, d)) {
                    n++;
                } else {
                    logger.at(Level.WARNING).log("Atlas could not reload %s, run /plugin reload %s yourself", id, id);
                }
            } catch (RuntimeException | LinkageError e) {
                logger.at(Level.WARNING).withCause(e).log("Atlas could not reload %s, run /plugin reload %s yourself", id, id);
            }
        }
        return n;
    }

    /** Waits for a reload the manager finishes on a future. True once the new instance is enabled. */
    private static boolean settled(PluginManager manager, Dependent d) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_MILLIS);
        while (!replaced(manager, d)) {
            if (System.nanoTime() > until || dev.ryvrook.atlas.integration.PluginPresence.serverStopping()) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * True when the manager holds a different, enabled instance of the plugin than the one seen at start. Asked
     * through the gated lookup, so a poll during another plugin's reload never parks on the manager's lock.
     */
    private static boolean replaced(PluginManager manager, Dependent d) {
        PluginBase now = dev.ryvrook.atlas.integration.PluginPresence.plugin(d.id());
        return now != null && now != d.instance() && now.getState() == PluginState.ENABLED;
    }
}
