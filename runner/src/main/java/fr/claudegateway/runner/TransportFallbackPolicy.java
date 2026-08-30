package fr.claudegateway.runner;

import java.time.Duration;

/**
 * Décide quand abandonner le WebSocket pour le repli long-polling (F-38 / SF-38-09).
 *
 * <p>Le symptôme visé n'est pas seulement « l'{@code Upgrade} est refusé ». Un proxy d'entreprise
 * accepte souvent l'upgrade puis <b>coupe la socket aussitôt</b> : le runner boucle alors en
 * reconnexion sans jamais basculer, ce qui est précisément le blocage que cette subfeature doit
 * supprimer. Une session qui meurt en moins de {@link #SHORT_SESSION} est donc comptée comme un
 * échec de transport au même titre qu'un handshake refusé.</p>
 *
 * <p>Un refus d'authentification n'entre <b>jamais</b> ici : un jeton périmé n'est pas un problème de
 * tuyau, et se replier ne le réparerait pas (SF-38-03 efface le jeton et s'arrête).</p>
 *
 * <p>Le repli est <b>unidirectionnel</b> : une fois engagé, il le reste jusqu'au redémarrage du
 * runner. Revenir au WebSocket à chaud demanderait une sonde périodique pour un gain nul.</p>
 */
public final class TransportFallbackPolicy {

    /** En deçà, une session ouverte puis fermée est une coupure de proxy, pas une session utile. */
    public static final Duration SHORT_SESSION = Duration.ofSeconds(5);

    /** Échecs consécutifs avant bascule : un incident réseau isolé ne change pas de transport. */
    public static final int FAILURES_BEFORE_FALLBACK = 2;

    private final RunnerConfig.Transport mode;
    private int consecutiveFailures;
    private boolean fellBack;

    public TransportFallbackPolicy(RunnerConfig.Transport mode) {
        this.mode = mode == null ? RunnerConfig.Transport.AUTO : mode;
    }

    /** Vrai si le runner doit démarrer directement en long-polling ({@code --transport polling}). */
    public boolean startsWithPolling() {
        return mode == RunnerConfig.Transport.POLLING;
    }

    /** Échec de transport observé (handshake refusé, connexion impossible). */
    public void recordTransportFailure() {
        consecutiveFailures++;
    }

    /**
     * Fin d'une session WebSocket. Une session <b>trop courte</b> compte comme un échec de
     * transport ; une session qui a réellement vécu remet le compteur à zéro (le réseau fonctionne,
     * la coupure était accidentelle).
     */
    public void recordSessionEnded(Duration lifetime) {
        if (lifetime == null || lifetime.compareTo(SHORT_SESSION) < 0) {
            consecutiveFailures++;
        } else {
            consecutiveFailures = 0;
        }
    }

    /**
     * Vrai quand il faut basculer sur le long-polling. Jamais vrai en mode {@code websocket} : un
     * opérateur qui a explicitement demandé la socket doit voir l'échec, pas un contournement
     * silencieux.
     */
    public boolean shouldFallBack() {
        if (mode != RunnerConfig.Transport.AUTO) {
            return false;
        }
        if (consecutiveFailures >= FAILURES_BEFORE_FALLBACK) {
            fellBack = true;
        }
        return fellBack;
    }

    /** Nombre d'échecs consécutifs observés (diagnostic et tests). */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }
}
