package fr.claudegateway.bilan;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


/**
 * <b>Le déclenchement du bilan</b> (F-155 / SF-155-03) : au moment où l'utilisateur ferme une
 * session (« Nouveau départ », F-117), décider s'il y a lieu de la relever.
 *
 * <p><b>Automatique au-delà d'un seuil en euros OU en tours</b>, le premier atteint : une session
 * courte mais coûteuse le mérite, une session longue et bon marché aussi. En dessous des deux, le
 * bilan est <b>proposé</b> — une session de trois tours à quelques centimes ne mérite pas qu'on
 * dépense pour l'analyser.</p>
 *
 * <p><b>Réservé à l'administrateur</b> (arbitrage du PO). Pour les autres, rien n'est même
 * calculé.</p>
 */
@Service
public class SessionBilanTriggerService {

    private static final Logger log = LoggerFactory.getLogger(SessionBilanTriggerService.class);

    private final SessionLedgerService ledgers;
    private final SessionSuggestionService suggestions;
    private final SessionBilanProperties settings;
    private final SessionBilanStore store;

    public SessionBilanTriggerService(SessionLedgerService ledgers,
                                      SessionSuggestionService suggestions,
                                      SessionBilanProperties settings,
                                      SessionBilanStore store) {
        this.ledgers = ledgers;
        this.suggestions = suggestions;
        this.settings = settings;
        this.store = store;
    }

    /**
     * Décide du sort de la session qui se ferme.
     *
     * <p><b>À appeler avant</b> que la frontière de rejeu ne bouge : après, il n'y aurait plus rien
     * à relever.</p>
     *
     * @param admin l'appelant est-il administrateur ? <b>Décidé par la définition unique</b>
     *              ({@code AdminService.isAdmin}), jamais par une comparaison de rôle refaite ici —
     *              le super-admin par e-mail n'aurait autrement jamais de bilan
     * @param from  début de la session : la frontière précédente, ou la création du projet
     */
    public Decision decide(UUID userId, UUID workspaceId, boolean admin, OffsetDateTime from,
                           OffsetDateTime to) {
        return decide(userId, workspaceId, null, admin, from, to);
    }

    /**
     * Même décision, en <b>nommant le projet</b> (F-155 / SF-155-07). Le nom n'était pas transmis :
     * tous les bilans gardés depuis SF-155-04 portent une colonne {@code workspace_name} vide, et
     * une liste de bilans sans nom de projet ne se lit pas.
     */
    public Decision decide(UUID userId, UUID workspaceId, String workspaceName, boolean admin,
                           OffsetDateTime from, OffsetDateTime to) {
        if (!admin) {
            return Decision.none(); // pas un calcul de moins : AUCUN calcul du tout
        }
        try {
            SessionLedger ledger = ledgers.of(userId, workspaceId, from, to);
            if (ledger.isEmpty()) {
                return Decision.none();
            }
            SessionSuggestionService.Verdict verdict = suggestions.examine(ledger);
            if (verdict.isClean() && verdict.discarded() == 0) {
                return Decision.none(); // littéralement rien à montrer
            }
            boolean worthIt = ledger.costEur().compareTo(settings.autoEuros()) >= 0
                    || ledger.turns() >= settings.autoTurns();
            if (!worthIt) {
                return new Decision(BilanTrigger.PROPOSE, ledger, verdict, null);
            }
            // F-155 / SF-155-04 : l'automatique est GARDÉ au moment où il est décidé — un bilan
            // qu'on ne relit pas ne se compare pas, et comparer est tout l'intérêt.
            SessionBilan kept =
                    store.keep(userId, workspaceId, workspaceName, "AUTOMATIQUE", ledger, verdict);
            return new Decision(BilanTrigger.AUTOMATIQUE, ledger, verdict, kept.getId());
        } catch (RuntimeException e) {
            // On perd un bilan ; on ne perd pas le geste de l'utilisateur.
            log.warn("Bilan de session impossible au nouveau départ du projet {}", workspaceId, e);
            return Decision.none();
        }
    }

    /**
     * Ce que la fermeture a décidé, et la matière produite en chemin.
     *
     * @param trigger l'issue
     * @param ledger  le relevé, {@code null} quand il n'y a rien
     * @param verdict les suggestions, {@code null} quand il n'y a rien
     * @param bilanId l'artefact gardé, {@code null} sauf pour un automatique
     */
    public record Decision(BilanTrigger trigger, SessionLedger ledger,
                           SessionSuggestionService.Verdict verdict, UUID bilanId) {

        /** Rien à relever — et c'est une conclusion valide, pas un échec. */
        public static Decision none() {
            return new Decision(BilanTrigger.AUCUN, null, null, null);
        }
    }
}
