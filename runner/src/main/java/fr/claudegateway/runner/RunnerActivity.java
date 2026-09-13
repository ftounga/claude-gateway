package fr.claudegateway.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Ce que le runner est <b>en train de faire</b> (F-111 / SF-111-04) — la question que pose une mise à
 * jour avant de redémarrer : « puis-je m'arrêter sans rien casser ? ».
 *
 * <p>Deux façons de le dire :</p>
 * <ul>
 *   <li>une <b>sonde</b> ({@link #probe}) pour ce qui a un état : des appels d'outils en vol, une capture
 *       qui enregistre, une synchro du Radar — la dernière sonde posée sous un nom remplace la
 *       précédente (un transport qui se remonte remonte ses outils) ;</li>
 *   <li>un <b>compteur</b> ({@link #begin}) pour ce qui se déroule dans un bloc : un téléchargement
 *       d'outil, ouvert et fermé au même endroit.</li>
 * </ul>
 */
public final class RunnerActivity {

    public static final String COMMAND = "commande";
    public static final String CAPTURE = "capture";
    public static final String SYNC = "synchro";
    public static final String DOWNLOAD = "téléchargement";

    private static final Map<String, BooleanSupplier> PROBES = new ConcurrentSkipListMap<>();
    private static final Map<String, AtomicInteger> COUNTERS = new ConcurrentHashMap<>();

    private RunnerActivity() {
    }

    /** Pose la sonde d'une activité à état. */
    public static void probe(String label, BooleanSupplier busy) {
        PROBES.put(label, busy);
    }

    /** Ouvre une activité de bloc ; la fermer (try-with-resources) la termine. */
    public static Scope begin(String label) {
        AtomicInteger counter = COUNTERS.computeIfAbsent(label, key -> new AtomicInteger());
        counter.incrementAndGet();
        return counter::decrementAndGet;
    }

    /** Les activités en cours, par leur nom ; vide quand le runner est calme. */
    public static List<String> busy() {
        List<String> labels = new ArrayList<>();
        PROBES.forEach((label, probe) -> {
            try {
                if (probe.getAsBoolean()) {
                    labels.add(label);
                }
            } catch (RuntimeException e) {
                // Une sonde qui lève ne doit ni bloquer ni forcer une mise à jour : elle se tait.
            }
        });
        COUNTERS.forEach((label, counter) -> {
            if (counter.get() > 0 && !labels.contains(label)) {
                labels.add(label);
            }
        });
        return labels;
    }

    /** Oublie tout (tests). */
    static void reset() {
        PROBES.clear();
        COUNTERS.clear();
    }

    /** Une activité de bloc en cours. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
