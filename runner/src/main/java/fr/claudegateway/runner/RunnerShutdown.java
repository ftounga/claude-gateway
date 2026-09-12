package fr.claudegateway.runner;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Le crochet d'arrêt du runner (F-82 / SF-82-01) : ce que {@code Ctrl-C} déclenche, et ce qu'il dit
 * quand la fermeture propre n'aboutit pas.
 *
 * <p><b>Le défaut corrigé.</b> Le crochet appelait {@code stopped.await()} <b>sans limite de
 * temps</b>. Or {@code stopped} n'est libéré que dans le {@code finally} d'{@code execute()},
 * c'est-à-dire quand la session a rendu la main. Une session bloquée dans une lecture réseau —
 * long-poll en attente, socket muette — faisait donc attendre le crochet <b>pour toujours</b>.
 * {@code Ctrl-C} avait bien déclenché l'arrêt ; c'est l'arrêt qui attendait. Et un second
 * {@code Ctrl-C} n'y changeait rien : la JVM ne relance pas un crochet déjà en cours. L'utilisateur
 * voyait un terminal figé, sans un mot.</p>
 *
 * <p>Les deux ordres d'arrêt envoyés juste avant sont <b>coopératifs</b> : {@code
 * PollingConnection.stop()} bascule un drapeau, mais le {@code poll} HTTP en vol ne rend la main
 * qu'au bout de son délai (25 s d'attente serveur plus 20 s de marge de lecture). Même sans panne,
 * le chemin nominal du repli pouvait donc faire attendre trois quarts de minute en silence.</p>
 *
 * <p><b>La règle retenue.</b> Une fermeture propre est souhaitable ; un terminal qu'on ne peut plus
 * reprendre ne l'est pas. L'attente est bornée à {@link #GRACE}, l'échec est <b>dit</b>, et la main
 * est rendue quand même.</p>
 *
 * <p>La logique vit ici, dans un {@link Runnable} rendu par {@link #task}, et non dans une lambda
 * enfouie au milieu d'{@code execute()} : c'est <b>ce</b> {@code Runnable} que la JVM exécute et que
 * le test exécute. Un test qui rejouerait une imitation du crochet passerait au vert avec le défaut
 * toujours en place — c'est la leçon de SF-79-02.</p>
 */
public final class RunnerShutdown {

    /**
     * Plafond de l'attente. Cinq secondes : assez pour qu'une boucle qui coopère sorte (elle se
     * réveille sur un {@code countDown} ou une socket fermée), trop peu pour qu'un terminal paraisse
     * figé. Au-delà, on a affaire à une lecture réseau bloquée, et attendre davantage n'apporte
     * rien : la seule chose qui reste à faire est de le dire et de rendre la main.
     */
    public static final Duration GRACE = Duration.ofSeconds(5);

    private RunnerShutdown() {
    }

    /**
     * Le corps du crochet d'arrêt, tel que la JVM l'exécutera.
     *
     * @param shuttingDown   drapeau lu par la session pour ne pas enchaîner sur un repli après un
     *                       {@code Ctrl-C} ; posé <b>en premier</b>, avant tout ordre d'arrêt
     * @param stopTransports envoie les ordres d'arrêt aux transports actifs (socket, long-polling)
     * @param stopped        libéré par le {@code finally} d'{@code execute()} quand la session a
     *                       réellement rendu la main
     * @param grace          plafond de l'attente ; {@link #GRACE} en exploitation
     * @param console        là où l'échec de fermeture est dit
     */
    public static Runnable task(AtomicBoolean shuttingDown, Runnable stopTransports,
            CountDownLatch stopped, Duration grace, Console console) {
        return () -> {
            shuttingDown.set(true);
            stopTransports.run();
            try {
                if (!stopped.await(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                    // Rien n'est ajouté sur le chemin nominal : ces lignes ne tombent QUE lorsque
                    // l'attente a expiré, et elles disent pourquoi on rend la main malgré tout.
                    unfinishedLines(grace).forEach(console::warn);
                }
            } catch (InterruptedException e) {
                // Interrompu pendant l'attente : ce n'est pas un échec de fermeture, c'est un ordre
                // de plus. On repose le drapeau et on rend la main sans rien dire.
                Thread.currentThread().interrupt();
            }
        };
    }

    /**
     * Ce que le runner dit quand la fermeture propre n'a pas abouti dans le délai.
     *
     * <p>Trois lignes, et pas une de plus : ce qui s'est passé, la cause probable, et ce que cela
     * coûte. Le délai est <b>nommé</b> — sans lui, « le runner rend la main » ne dit pas si
     * l'utilisateur a attendu trop peu.</p>
     */
    static List<String> unfinishedLines(Duration grace) {
        return List.of(
                "La session ne s'est pas fermée dans les " + grace.toSeconds()
                        + " s : le runner rend la main sans attendre davantage.",
                "Une opération réseau était probablement en cours (long-poll, poignée de main, "
                        + "envoi de trame) — elle est abandonnée.",
                "Rien n'est perdu sur cette machine, et la gateway refermera la liaison d'elle-même "
                        + "(balayage d'inactivité).");
    }
}
