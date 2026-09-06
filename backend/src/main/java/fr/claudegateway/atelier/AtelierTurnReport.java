package fr.claudegateway.atelier;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Relevé d'un tour de la <b>boucle maison</b> rangé dans la colonne d'affichage existante
 * {@code atelier_messages.terminal_json} (F-39 / SF-39-15, décision D-L8-6).
 *
 * <p>La colonne existe depuis F-30 / SF-30-09 et porte exactement la forme attendue par l'écran ;
 * seul le chemin Managed Agents la renseignait. La boucle maison y écrit désormais <b>ce qu'elle
 * mesure</b> : la consommation du tour, sa durée et ses drapeaux d'arrêt. Effet immédiat — au
 * rechargement, un tour arrêté sur le plafond <b>dit encore pourquoi</b>, c'est-à-dire exactement au
 * moment où l'on se pose la question.</p>
 *
 * <p><b>Depuis SF-39-17, la transcription est réellement écrite.</b> Elle ne l'était pas, et le
 * banc d'essai a montré ce que ça coûtait : après une coupure de connexion, un rechargement ne
 * rendait plus rien — ni les commandes, ni leurs sorties. C'était l'acquis §4 n°7 de F-30
 * (« la transcription survit au rechargement ») qui ne valait pas pour le moteur qui exécute
 * réellement, exactement comme les n°5 et n°6 avant SF-39-15.</p>
 *
 * @param blocks        transcription, toujours vide ici (voir ci-dessus)
 * @param omittedBlocks blocs écartés par bornage, toujours {@code 0} ici
 * @param inputTokens   tokens d'entrée du tour, cache compris (SF-39-01)
 * @param outputTokens  tokens de sortie du tour
 * @param activeSeconds durée d'horloge du tour, en secondes
 * @param interrupted   le tour s'est arrêté sur une demande d'interruption (F-32)
 * @param budgetReached le tour s'est arrêté sur le <b>plafond de consommation</b> — jamais sur le
 *                      budget de temps, qui dit déjà sa cause dans le texte de réponse (D-L8-5)
 * @param plan          plan de travail du tour (F-39 / SF-39-13), vide si l'agent n'en a pas posé —
 *                      au rechargement, un tour montre encore ce qui avait été prévu et ce qui a été
 *                      fait, ce que la seule transcription ne dit pas
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AtelierTurnReport(List<Object> blocks, int omittedBlocks, long inputTokens,
        long outputTokens, long activeSeconds, boolean interrupted, boolean budgetReached,
        List<PlanStep> plan) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Étape de plan telle que l'écran la relit (F-39 / SF-39-13). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanStep(String title, String status) {
    }

    /**
     * Bloc de transcription tel que l'écran le relit (F-30 / SF-30-02, alimenté par SF-39-17). Les
     * noms de champs sont ceux du contrat existant : on donne à l'écran ce qu'il attend déjà, on ne
     * change pas son rendu.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Block(String tool, String command, String toolUseId, String threadId,
            String output, boolean hasOutput, boolean error, boolean expanded) {
    }

    /** Nombre de blocs conservés : au-delà, on ne relit plus une transcription, on la fouille. */
    public static final int MAX_BLOCKS = 200;

    /** Sortie conservée par bloc, <b>la fin</b> : c'est là que sont le code de sortie et l'erreur. */
    public static final int MAX_BLOCK_OUTPUT_CHARS = 4_000;

    public AtelierTurnReport(long inputTokens, long outputTokens, long activeSeconds,
            boolean interrupted, boolean budgetReached) {
        this(inputTokens, outputTokens, activeSeconds, interrupted, budgetReached, AtelierPlan.EMPTY);
    }

    public AtelierTurnReport(long inputTokens, long outputTokens, long activeSeconds,
            boolean interrupted, boolean budgetReached, AtelierPlan plan) {
        this(inputTokens, outputTokens, activeSeconds, interrupted, budgetReached, plan, List.of());
    }

    /**
     * Relevé complet, transcription comprise (SF-39-17). Les blocs sont <b>bornés ici</b>, au plus
     * près de l'écriture : un tour de trente étapes qui installe un projet entier produit des
     * mégaoctets, et une transcription non bornée ferait grossir la ligne du message sans limite.
     */
    public AtelierTurnReport(long inputTokens, long outputTokens, long activeSeconds,
            boolean interrupted, boolean budgetReached, AtelierPlan plan, List<Block> blocks) {
        this(bounded(blocks), omitted(blocks), inputTokens, outputTokens, activeSeconds, interrupted,
                budgetReached,
                plan == null ? List.of() : plan.steps().stream()
                        .map(step -> new PlanStep(step.title(), step.status().label()))
                        .toList());
    }

    /** Les {@link #MAX_BLOCKS} <b>derniers</b> blocs, sorties ramenées à leur borne. */
    private static List<Object> bounded(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        // Les DERNIERS : quand un tour a été coupé, c'est la fin qui explique pourquoi.
        List<Block> kept = blocks.size() <= MAX_BLOCKS
                ? blocks
                : blocks.subList(blocks.size() - MAX_BLOCKS, blocks.size());
        return kept.stream().map(AtelierTurnReport::boundOutput).map(Object.class::cast).toList();
    }

    private static int omitted(List<Block> blocks) {
        return blocks == null || blocks.size() <= MAX_BLOCKS ? 0 : blocks.size() - MAX_BLOCKS;
    }

    /**
     * Sortie ramenée à sa borne, <b>fin conservée</b> (décision D2) : une commande qui échoue le dit
     * à la fin — code de sortie, message d'erreur, dernière ligne de pile.
     */
    private static Block boundOutput(Block block) {
        String output = block.output() == null ? "" : block.output();
        if (output.length() <= MAX_BLOCK_OUTPUT_CHARS) {
            return block;
        }
        String tail = output.substring(output.length() - MAX_BLOCK_OUTPUT_CHARS);
        return new Block(block.tool(), block.command(), block.toolUseId(), block.threadId(),
                "… (début tronqué)\n" + tail, block.hasOutput(), block.error(), block.expanded());
    }

    /**
     * Sérialise le relevé, ou rend {@code null} si rien n'est à dire — un tour sans consommation
     * relevée et sans drapeau n'a pas de relevé, et écrire un document vide ferait afficher
     * « 0 token » là où la mesure manque (même règle que F-30 / SF-30-05).
     *
     * <p>Un échec de sérialisation rend {@code null} : un défaut d'affichage ne doit jamais faire
     * perdre le tour lui-même.</p>
     *
     * @return le document JSON, ou {@code null}
     */
    public String toJson() {
        // Un plan posé suffit à justifier un relevé : il porte l'information que l'écran doit
        // retrouver au rechargement, même si le tour n'a rien consommé de mesurable.
        // Une transcription suffit à justifier un relevé : c'est elle qu'on vient relire après une
        // coupure, même si le tour n'a rien consommé de mesurable.
        if (inputTokens <= 0 && outputTokens <= 0 && !interrupted && !budgetReached
                && plan.isEmpty() && blocks.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(this);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }
}
