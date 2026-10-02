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
 * @param streamedMaxTokens   plafond de sortie d'un appel <b>en flux</b> (F-172 / SF-172-05), défaut
 *                       32 768. Les appels non streamés gardent {@code agent-max-tokens} : plus long,
 *                       un appel non streamé dépasserait le délai HTTP
 * @param escalatedMaxTokens  plafond d'un appel en flux à l'effort {@code xhigh} ou {@code max},
 *                       défaut 65 536 — c'est là que le raisonnement est le plus long
 * @param explicitEffortModels modèles dont l'effort par défaut du fournisseur n'est pas {@code high}
 *                       (Opus 5.5 : {@code medium}) : un appel qui n'en précise aucun part avec
 *                       {@code defaultEffort}
 * @param defaultEffort  effort posé sur ces appels, défaut {@code high} (le défaut d'Opus 5) : la
 *                       bascule ne baisse pas le raisonnement en silence
 * @param bindingControlModels modèles dont les blocs de raisonnement sont liés à la conversation
 *                       (D5) : la requête pose {@code prefix_mismatch_behavior: "drop_block"}, et
 *                       chaque bloc perdu est compté
 */
@ConfigurationProperties(prefix = "app.agent.api-features")
public record AgentApiFeaturesProperties(
        Boolean fallbacks,
        List<String> fallbackModels,
        List<String> progressUpdateModels,
        Integer streamedMaxTokens,
        Integer escalatedMaxTokens,
        List<String> explicitEffortModels,
        String defaultEffort,
        List<String> bindingControlModels) {

    static final int DEFAULT_STREAMED_MAX_TOKENS = 32_768;
    static final int DEFAULT_ESCALATED_MAX_TOKENS = 65_536;
    static final String DEFAULT_EFFORT = "high";
    static final List<String> DEFAULT_EXPLICIT_EFFORT_MODELS = List.of("claude-opus-5-5");
    static final List<String> DEFAULT_BINDING_CONTROL_MODELS = List.of("claude-opus-5-5", "claude-fable-5-1");

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
        streamedMaxTokens = streamedMaxTokens == null || streamedMaxTokens <= 0
                ? DEFAULT_STREAMED_MAX_TOKENS : streamedMaxTokens;
        escalatedMaxTokens = escalatedMaxTokens == null || escalatedMaxTokens <= 0
                ? DEFAULT_ESCALATED_MAX_TOKENS : escalatedMaxTokens;
        explicitEffortModels = explicitEffortModels == null
                ? DEFAULT_EXPLICIT_EFFORT_MODELS : List.copyOf(explicitEffortModels);
        defaultEffort = defaultEffort == null || defaultEffort.isBlank() ? DEFAULT_EFFORT : defaultEffort.trim();
        bindingControlModels = bindingControlModels == null
                ? DEFAULT_BINDING_CONTROL_MODELS : List.copyOf(bindingControlModels);
    }

    /** Réglages par défaut — ceux d'un environnement qui ne configure rien. */
    public static AgentApiFeaturesProperties defaults() {
        return new AgentApiFeaturesProperties(null, null, null, null, null, null, null, null);
    }

    /** Vrai si la requête vers ce modèle doit porter le repli côté serveur. */
    public boolean fallbacksFor(String model) {
        return Boolean.TRUE.equals(fallbacks) && listed(fallbackModels, model);
    }

    /** Vrai si la requête vers ce modèle doit demander les notes de progression (SF-172-04). */
    public boolean progressUpdatesFor(String model) {
        return listed(progressUpdateModels, model);
    }

    /** Vrai si un appel sans effort vers ce modèle doit recevoir {@link #defaultEffort()} (SF-172-05). */
    public boolean explicitEffortFor(String model) {
        return listed(explicitEffortModels, model);
    }

    /** Vrai si la requête vers ce modèle pose le contrôle de liaison du raisonnement (D5). */
    public boolean bindingControlsFor(String model) {
        return listed(bindingControlModels, model);
    }

    static boolean listed(List<String> models, String model) {
        return model != null && models.contains(model.trim());
    }
}
