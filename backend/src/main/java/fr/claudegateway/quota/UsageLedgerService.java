package fr.claudegateway.quota;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Journal de consommation par tour (F-61 / SF-61-01) : il <b>ajoute</b> une mesure durable et
 * attribuable à chaque tour facturé — combien de tokens d'entrée et de sortie, pour quel projet,
 * sous quel poste.
 *
 * <p><b>Il ne remplace rien.</b> {@link QuotaService#recordUsage} continue d'incrémenter les
 * compteurs F-10 exactement comme avant : le quota, l'alerte F-42 et le rapport F-16 sont
 * inchangés. Le journal répond à une question que les compteurs ne savent pas poser — <i>pour quel
 * client&nbsp;?</i> — parce que leur grain est (utilisateur × mois).</p>
 *
 * <p><b>Il ne peut pas faire échouer un tour.</b> L'écriture est encadrée et ses erreurs sont
 * avalées (journalisées en {@code warn}). Quand elle survient, le fournisseur a déjà été appelé et
 * payé : un relevé perdu est un défaut d'information, un tour en échec serait un défaut de service
 * <b>et</b> d'argent. Même arbitrage que {@code recordSessionUsage} (F-30), pour la même raison.</p>
 *
 * <p><b>Aucun contenu n'y entre</b>, et il n'existe pas d'endroit où en mettre : {@link UsageTurn}
 * n'a aucune colonne de texte. Les écrans de F-61 montrent des volumes et des coûts, jamais des
 * contenus, et cette garantie est tenue par la structure de la table.</p>
 */
@Service
public class UsageLedgerService {

    private static final Logger log = LoggerFactory.getLogger(UsageLedgerService.class);

    private final UsageTurnWriter writer;

    UsageLedgerService(UsageTurnWriter writer) {
        this.writer = writer;
    }

    /**
     * Range le relevé d'un tour. Sans effet si le tour n'a rien consommé.
     *
     * @param userId       utilisateur du contexte de sécurité (jamais un paramètre client)
     * @param workspaceId  projet du tour, ou {@code null} pour un tour hors projet
     * @param hostId       poste du projet <b>au moment du tour</b>, ou {@code null}
     * @param inputTokens  tokens d'entrée rapportés (négatif ramené à 0)
     * @param outputTokens tokens de sortie rapportés (négatif ramené à 0)
     */
    public void recordTurn(UUID userId, UUID workspaceId, UUID hostId,
            long inputTokens, long outputTokens) {
        long input = Math.max(0L, inputTokens);
        long output = Math.max(0L, outputTokens);
        if (userId == null || (input == 0L && output == 0L)) {
            // Un tour sans consommation n'est pas une ligne de facture : ne rien écrire évite un
            // journal rempli de zéros, dans lequel la vraie consommation se lirait moins bien.
            return;
        }
        try {
            writer.write(userId, workspaceId, hostId, input, output);
        } catch (RuntimeException failure) {
            log.warn("Relevé de consommation non enregistré pour l'utilisateur {} (projet {}) :"
                    + " la consommation reste comptée par les compteurs de période.",
                    userId, workspaceId, failure);
        }
    }
}
