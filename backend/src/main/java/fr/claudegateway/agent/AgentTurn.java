package fr.claudegateway.agent;

import java.util.List;

/**
 * Résultat d'UN tour d'agent (F-28).
 *
 * <p>Deux cas : soit l'assistant a terminé ({@code finished=true}, {@code text} porte la réponse),
 * soit il demande des outils ({@code finished=false}, {@code toolCalls} non vides). Les compteurs de
 * tokens servent à la comptabilisation du quota (F-10).</p>
 *
 * <p><b>Troisième cas, à ne pas confondre avec le premier</b> (SF-28-18) : la réponse a été
 * <b>coupée</b> au plafond de tokens de sortie. Elle est alors incomplète — une phrase d'intention
 * sans le bloc {@code tool_use} qui allait suivre, ou un appel d'outil dont les arguments s'arrêtent
 * au milieu. {@code finished} vaut vrai (le fournisseur n'attend rien de nous), mais rien de ce
 * qu'elle contient n'est exploitable : c'est ce que dit {@code truncated}.</p>
 *
 * @param text         texte de l'assistant (réponse finale, ou texte intermédiaire éventuel)
 * @param toolCalls    appels d'outils demandés (vide si terminé)
 * @param finished     vrai si l'assistant a terminé (stop_reason end_turn)
 * @param inputTokens  tokens d'entrée consommés par ce tour
 * @param outputTokens tokens de sortie consommés par ce tour
 * @param truncated    vrai si la réponse a été coupée au plafond de sortie ({@code max_tokens}) :
 *                     son contenu est incomplet et ne doit être ni exécuté, ni pris pour une réponse
 * @param reasoning    blocs de raisonnement rendus par ce tour, dans leur ordre d'émission (F-39 /
 *                     SF-39-10). À remettre <b>en tête</b> du message assistant rejoué à l'itération
 *                     suivante, sans les modifier : le fournisseur vérifie leur signature
 * @param cacheReadTokens  part de {@code inputTokens} servie <b>depuis le cache</b> (F-63 /
 *                     SF-63-02). Elle est <b>déjà comptée</b> dans {@code inputTokens} — le quota a
 *                     toujours mesuré ce qui a été traité (D3 de SF-39-01) — mais elle coûte un
 *                     <b>dixième</b> du tarif d'entrée, et la décompter au plein tarif ferait payer
 *                     au client des tokens qui ne nous coûtent presque rien
 * @param cacheWriteTokens part de {@code inputTokens} <b>écrite</b> dans le cache, au tarif majoré
 *                     de l'écriture (1,25× l'entrée). Également déjà comptée dans
 *                     {@code inputTokens}
 * @param refused      vrai si le fournisseur a <b>refusé</b> de poursuivre (F-172 / SF-172-01) :
 *                     {@code finished} vaut vrai, mais sa sortie partielle a été jetée — {@code text}
 *                     est vide et aucun outil n'est demandé. Ce n'est ni une réponse, ni une coupure
 * @param refusalCategory catégorie du refus rapportée par le fournisseur ({@code cyber}, {@code bio},
 *                     {@code reasoning_extraction}…), ou {@code null} si elle n'est pas précisée.
 *                     Informative seulement : le refus se reconnaît à {@code refused}
 * @param servedModel  modèle qui a <b>servi</b> le message, tel que rapporté par le fournisseur
 *                     (F-172 / SF-172-02), ou {@code null}. Diffère du modèle demandé après un repli
 * @param usageByModel ventilation des tokens du tour par modèle servi (F-172 / SF-172-03), une
 *                     entrée par tentative ; vide quand le fournisseur ne la rapporte pas. Les totaux
 *                     ci-dessus en sont déjà la somme
 * @param narration    notes de progression écrites entre deux outils dans des blocs de raisonnement
 *                     (F-172 / SF-172-04), jointes par une ligne vide ; vide si le modèle n'en
 *                     produit pas. Elles restent AUSSI dans {@code reasoning}, inchangées, pour le rejeu
 * @param droppedThinkingBlocks blocs de raisonnement rejoués que le fournisseur a écartés pour cet
 *                     appel (F-172 / SF-172-05, D5) — historique modifié depuis leur production, ou
 *                     changement de modèle. C'est la mesure qui dira s'il faut un historique
 *                     « append-only »
 * @see #truncated()
 */
public record AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
        int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
        int cacheReadTokens, int cacheWriteTokens, int webSearchRequests, boolean refused,
        String refusalCategory, String servedModel, List<ModelUsage> usageByModel, String narration,
        int droppedThinkingBlocks, boolean paused) {

    /**
     * Forme sans pause (F-188 / SF-188-01 additif) : {@code paused} vrai seulement quand le
     * fournisseur a suspendu un tour long d'outils serveur ({@code stop_reason=pause_turn}).
     */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
            int cacheReadTokens, int cacheWriteTokens, int webSearchRequests, boolean refused,
            String refusalCategory, String servedModel, List<ModelUsage> usageByModel, String narration,
            int droppedThinkingBlocks) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning,
                cacheReadTokens, cacheWriteTokens, webSearchRequests, refused, refusalCategory,
                servedModel, usageByModel, narration, droppedThinkingBlocks, false);
    }

    /** Forme sans compte de blocs de raisonnement perdus. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
            int cacheReadTokens, int cacheWriteTokens, int webSearchRequests, boolean refused,
            String refusalCategory, String servedModel, List<ModelUsage> usageByModel, String narration) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning,
                cacheReadTokens, cacheWriteTokens, webSearchRequests, refused, refusalCategory,
                servedModel, usageByModel, narration, 0);
    }

    /** Forme sans narration — modèles qui écrivent leur narration en blocs {@code text}. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
            int cacheReadTokens, int cacheWriteTokens, int webSearchRequests, boolean refused,
            String refusalCategory, String servedModel, List<ModelUsage> usageByModel) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning,
                cacheReadTokens, cacheWriteTokens, webSearchRequests, refused, refusalCategory,
                servedModel, usageByModel, "");
    }

    /**
     * Ce que le modèle a « dit » pendant ce tour (F-172 / SF-172-04) : sa narration entre outils,
     * puis son texte. C'est ce que lisent l'utilisateur, la ré-escalade d'effort et le tri de
     * narration ; le <b>rejeu</b>, lui, n'emploie que {@link #text()} et les blocs de raisonnement.
     */
    public String spokenText() {
        if (narration.isEmpty()) {
            return text;
        }
        if (text == null || text.isBlank()) {
            return narration;
        }
        return narration + "\n\n" + text;
    }

    /**
     * Part d'un tour servie par <b>un</b> modèle (F-172 / SF-172-03) — une tentative refusée puis son
     * repli en font deux, chacune facturée au tarif du modèle qui l'a servie.
     *
     * @param model                modèle qui a servi cette part, ou {@code null} s'il est inconnu
     * @param fullPriceInputTokens entrée hors cache
     */
    public record ModelUsage(String model, int fullPriceInputTokens, int outputTokens,
            int cacheReadTokens, int cacheWriteTokens) {

        public ModelUsage {
            model = model == null || model.isBlank() ? null : model;
            fullPriceInputTokens = Math.max(0, fullPriceInputTokens);
            outputTokens = Math.max(0, outputTokens);
            cacheReadTokens = Math.max(0, cacheReadTokens);
            cacheWriteTokens = Math.max(0, cacheWriteTokens);
        }
    }

    /** Forme sans ventilation par modèle — un tour servi par un seul modèle. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
            int cacheReadTokens, int cacheWriteTokens, int webSearchRequests, boolean refused,
            String refusalCategory, String servedModel) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning,
                cacheReadTokens, cacheWriteTokens, webSearchRequests, refused, refusalCategory,
                servedModel, List.of());
    }

    /** Forme sans modèle servi — celle des fournisseurs qui ne le rapportent pas. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
            int cacheReadTokens, int cacheWriteTokens, int webSearchRequests, boolean refused,
            String refusalCategory) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning,
                cacheReadTokens, cacheWriteTokens, webSearchRequests, refused, refusalCategory, null);
    }

    /** Forme sans refus — celle de tous les tours que le fournisseur a menés à terme. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
            int cacheReadTokens, int cacheWriteTokens, int webSearchRequests) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning,
                cacheReadTokens, cacheWriteTokens, webSearchRequests, false, null, null);
    }

    /**
     * Forme sans recherche web — celle des chemins qui ne déclarent pas l'outil de recherche
     * (F-133 / SF-133-08).
     */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning,
            int cacheReadTokens, int cacheWriteTokens) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning,
                cacheReadTokens, cacheWriteTokens, 0);
    }

    public AgentTurn {
        reasoning = reasoning == null ? List.of() : List.copyOf(reasoning);
        cacheReadTokens = Math.max(0, cacheReadTokens);
        cacheWriteTokens = Math.max(0, cacheWriteTokens);
        // La recherche web est facturée À LA REQUÊTE, hors tokens (10 $ les mille) : ne pas la
        // compter revient à ignorer une dépense que rien d'autre ne révèle (F-133 / SF-133-08).
        webSearchRequests = Math.max(0, webSearchRequests);
        refusalCategory = refusalCategory == null || refusalCategory.isBlank() ? null : refusalCategory;
        servedModel = servedModel == null || servedModel.isBlank() ? null : servedModel;
        usageByModel = usageByModel == null ? List.of() : List.copyOf(usageByModel);
        narration = narration == null ? "" : narration;
        droppedThinkingBlocks = Math.max(0, droppedThinkingBlocks);
    }

    /** Forme sans ventilation de cache — conservée pour les appelants qui l'attendent. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, reasoning, 0, 0);
    }

    /**
     * Tokens d'entrée facturés au <b>plein tarif</b> : le total traité, moins ce qui a été lu ou
     * écrit dans le cache. Jamais négatif — un fournisseur qui rapporterait plus de cache que
     * d'entrée s'est trompé, et le décompte ne doit pas devenir un crédit.
     */
    public int fullPriceInputTokens() {
        return Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens);
    }

    /** Tour sans raisonnement — forme conservée pour les appelants qui l'attendent. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, List.of());
    }

    /** Tour complet (non tronqué) — forme historique, conservée pour les appelants qui l'attendent. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens) {
        this(text, toolCalls, finished, inputTokens, outputTokens, false, List.of());
    }
}
