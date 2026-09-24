package fr.claudegateway.bilan;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.user.UserRole;

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

    public SessionBilanTriggerService(SessionLedgerService ledgers,
                                      SessionSuggestionService suggestions,
                                      SessionBilanProperties settings) {
        this.ledgers = ledgers;
        this.suggestions = suggestions;
        this.settings = settings;
    }

    /**
     * Décide du sort de la session qui se ferme.
     *
     * <p><b>À appeler avant</b> que la frontière de rejeu ne bouge : après, il n'y aurait plus rien
     * à relever.</p>
     *
     * @param role  rôle de l'utilisateur courant — seul l'administrateur a des bilans
     * @param from  début de la session : la frontière précédente, ou la création du projet
     */
    public Decision decide(UUID userId, UUID workspaceId, UserRole role, OffsetDateTime from,
                           OffsetDateTime to) {
        if (role != UserRole.ADMIN) {
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
            return new Decision(worthIt ? BilanTrigger.AUTOMATIQUE : BilanTrigger.PROPOSE,
                    ledger, verdict);
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
     */
    public record Decision(BilanTrigger trigger, SessionLedger ledger,
                           SessionSuggestionService.Verdict verdict) {

        static Decision none() {
            return new Decision(BilanTrigger.AUCUN, null, null);
        }
    }
}
