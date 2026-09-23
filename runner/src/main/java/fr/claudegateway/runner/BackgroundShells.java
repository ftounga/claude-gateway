package fr.claudegateway.runner;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registre <b>machine</b> des commandes lancées en arrière-plan (F-121 / SF-121-07).
 *
 * <p>Un serveur de dev ou un build long appartient à la <b>machine</b>, pas à un dossier de projet :
 * ce registre est donc un singleton partagé par tous les {@link ProjectScopes routeurs de projet},
 * pour que {@code bash_output} et {@code kill_shell} retrouvent un processus quel que soit le projet
 * du tour qui le relit.</p>
 *
 * <p>Il est <b>borné</b> à {@link #MAX_SHELLS} commandes de fond simultanées : au-delà, on fait
 * d'abord le ménage des processus terminés, puis on refuse — une machine ne doit pas se remplir de
 * processus détachés qu'on n'observe plus.</p>
 */
public final class BackgroundShells {

    /** Nombre maximal de commandes de fond simultanées sur une machine. */
    static final int MAX_SHELLS = 8;

    private final Map<String, BackgroundShell> shells = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * Enregistre un processus déjà démarré et rend son identifiant, ou {@link Optional#empty()} si le
     * registre est plein (après ménage). L'appelant ({@link BashTool}) a construit et démarré le
     * processus ; ici on ne fait que le suivre.
     */
    public synchronized Optional<String> register(String command, Process process) {
        // Ménage SEULEMENT sous pression : un processus terminé garde sa sortie finale (et son code)
        // relisible par bash_output tant qu'il reste de la place — on ne l'efface que pour libérer un
        // emplacement au profit d'une nouvelle commande.
        if (shells.size() >= MAX_SHELLS) {
            reap();
        }
        if (shells.size() >= MAX_SHELLS) {
            return Optional.empty();
        }
        String id = "bash_" + counter.incrementAndGet();
        shells.put(id, new BackgroundShell(id, command, process));
        return Optional.of(id);
    }

    /** Sortie nouvelle d'une commande de fond, ou {@link Optional#empty()} si l'identifiant est inconnu. */
    public Optional<String> output(String id) {
        BackgroundShell shell = shells.get(id);
        return shell == null ? Optional.empty() : Optional.of(shell.drain());
    }

    /** Tue une commande de fond, ou {@link Optional#empty()} si l'identifiant est inconnu. */
    public Optional<String> kill(String id) {
        BackgroundShell shell = shells.get(id);
        return shell == null ? Optional.empty() : Optional.of(shell.kill());
    }

    /** Nombre de commandes actuellement suivies — pour les tests et le ménage. */
    int size() {
        return shells.size();
    }

    /** Retire du registre les processus terminés ou tués : ils ne sont plus à observer. */
    private void reap() {
        Iterator<Map.Entry<String, BackgroundShell>> it = shells.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().finished()) {
                it.remove();
            }
        }
    }
}
