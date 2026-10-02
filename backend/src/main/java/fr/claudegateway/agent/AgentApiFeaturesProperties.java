package fr.claudegateway.agent;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Capacités de l'API du fournisseur que la boucle d'agent <b>relaie</b>, modèle par modèle
 * (F-172). Provider-First : le repli sur refus est fourni par le fournisseur, on l'active plutôt
 * que de le réécrire.
 *
 * <p>Chaque capacité est attachée à une <b>liste de modèles</b> : un modèle qui ne la connaît pas
 * reçoit une requête strictement inchangée — la sous-boucle d'exploration sur Sonnet 5 (D4), les
 * appels utilitaires sur Haiku. La liste vit en configuration, pour suivre le catalogue du
 * fournisseur sans livraison.</p>
 *
 * <p>Pas de constructeur de commodité : un second constructeur rendrait la liaison de
 * configuration ambiguë et le contexte refuserait de démarrer. Les tests passent par
 * {@link #defaults()}.</p>
 *
 * @param fallbacks      coupe-circuit du repli côté serveur (F-172 / SF-172-02, décision D1) —
 *                       {@code APP_ATELIER_FALLBACKS}, défaut {@code true}
 * @param fallbackModels modèles pour lesquels la requête porte {@code fallbacks: "default"}
 * @param progressUpdateModels modèles qui écrivent leur narration entre deux outils dans des blocs
 *                       {@code thinking} de progression (F-172 / SF-172-04) : la requête leur
 *                       demande {@code display: "updates"}
 */
@ConfigurationProperties(prefix = "app.agent.api-features")
public record AgentApiFeaturesProperties(
        Boolean fallbacks,
        List<String> fallbackModels,
        List<String> progressUpdateModels) {

    /** Modèles à notes de progression (doc fournisseur, relevé du 2026-10-02). */
    static final List<String> DEFAULT_PROGRESS_UPDATE_MODELS =
            List.of("claude-opus-5-5", "claude-fable-5-1", "claude-fable-5", "claude-sonnet-5-5");

    /** Modèles qui acceptent le repli {@code "default"} (doc fournisseur, relevé du 2026-10-02). */
    static final List<String> DEFAULT_FALLBACK_MODELS =
            List.of("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-fable-5");

    public AgentApiFeaturesProperties {
        fallbacks = fallbacks == null ? Boolean.TRUE : fallbacks;
        fallbackModels = fallbackModels == null ? DEFAULT_FALLBACK_MODELS : List.copyOf(fallbackModels);
        progressUpdateModels = progressUpdateModels == null
                ? DEFAULT_PROGRESS_UPDATE_MODELS
                : List.copyOf(progressUpdateModels);
    }

    /** Réglages par défaut — ceux d'un environnement qui ne configure rien. */
    public static AgentApiFeaturesProperties defaults() {
        return new AgentApiFeaturesProperties(null, null, null);
    }

    /** Vrai si la requête vers ce modèle doit porter le repli côté serveur. */
    public boolean fallbacksFor(String model) {
        return Boolean.TRUE.equals(fallbacks) && listed(fallbackModels, model);
    }

    /** Vrai si la requête vers ce modèle doit demander les notes de progression (SF-172-04). */
    public boolean progressUpdatesFor(String model) {
        return listed(progressUpdateModels, model);
    }

    static boolean listed(List<String> models, String model) {
        return model != null && models.contains(model.trim());
    }
}
