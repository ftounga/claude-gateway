package fr.claudegateway.ai.failover;

import java.util.function.Supplier;

/**
 * Le canal qui a servi le <b>dernier</b> appel IA de ce fil d'exécution (F-277 / SF-277-02, cadrage D9).
 *
 * <p>Le journal de consommation ({@code usage_turns}) et la mesure du tour ({@code atelier_turn_measures}) sont
 * écrits par le fil qui vient d'appeler le fournisseur : ils relisent ici le canal, sans que l'appel IA et
 * l'écriture aient à se le passer. Un fil qui n'a jamais appelé retombe sur la route courante (Bedrock si le
 * disjoncteur est ouvert ou le mode forcé, Anthropic sinon).</p>
 */
public final class ServedChannel {

    private static final ThreadLocal<AiChannel> LAST = new ThreadLocal<>();
    private static volatile Supplier<AiChannel> currentRoute = () -> AiChannel.ANTHROPIC;

    private ServedChannel() {
    }

    /** L'appel qui vient d'aboutir a été servi par {@code channel}. */
    static void record(AiChannel channel) {
        LAST.set(channel);
    }

    /** Route courante, pour un fil qui n'a encore rien appelé (posée par {@link ProviderFailover}). */
    static void currentRoute(Supplier<AiChannel> route) {
        currentRoute = route == null ? () -> AiChannel.ANTHROPIC : route;
    }

    /** Le canal du dernier appel de ce fil, à défaut la route courante. Jamais {@code null}. */
    public static AiChannel last() {
        AiChannel channel = LAST.get();
        if (channel != null) {
            return channel;
        }
        AiChannel route = currentRoute.get();
        return route == null ? AiChannel.ANTHROPIC : route;
    }

    /** Oublie le canal de ce fil (tests). */
    public static void clear() {
        LAST.remove();
    }
}
