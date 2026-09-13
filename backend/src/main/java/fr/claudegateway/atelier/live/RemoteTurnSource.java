package fr.claudegateway.atelier.live;

import java.util.Optional;
import java.util.UUID;

/**
 * Le tour qui tourne <b>sur un autre pod</b> (F-84 / SF-84-02).
 *
 * <p>Sous HPA, le tour tourne sur <b>un</b> pod ; une vue rouverte peut arriver sur un <b>autre</b>.
 * Le tampon, lui, vit en mémoire du pod qui exécute — décision du PO du 2026-09-12, dans la ligne
 * d'ADR-016 : aucune écriture d'événement en base sur le chemin chaud, F-76 avait déjà écarté cette
 * voie.</p>
 *
 * <p>Cette interface est le seul point par lequel le contrôleur parle d'un pod voisin. Son
 * implémentation de relais vit dans {@code runner.relay}, avec le reste du dispositif inter-pods ;
 * le paquet {@code atelier} n'en connaît que ce contrat, et n'a donc aucune raison de savoir
 * comment un pair se résout.</p>
 *
 * <p><b>Dégradation</b> : comme {@code RunnerCallRouter}, quand le relais n'est pas possible — éteint,
 * adresse inconnue, pair injoignable — on dégrade vers <b>l'état d'origine</b> : « aucun tour vivant
 * ici ». Jamais un état inventé.</p>
 */
public interface RemoteTurnSource {

    /** L'état d'un tour détenu par un pair, ou vide si personne ne le détient (ou pas de relais). */
    Optional<RemoteTurnState> findRemoteTurn(UUID userId, UUID workspaceId);

    /**
     * Relaie le flux du tour détenu par un pair vers ce spectateur, jusqu'à la fin du tour.
     *
     * @param cursor dernier numéro déjà vu par le spectateur
     * @return {@code true} si un pair détenait le tour et que son flux a été relayé ; {@code false}
     *         s'il n'y avait rien à relayer — l'appelant dit alors {@code idle}
     */
    boolean streamRemoteTurn(UUID userId, UUID workspaceId, long cursor, TurnSubscriber subscriber);

    /**
     * Dépose une <b>précision</b> dans le tour détenu par un pair (F-84 / SF-84-06).
     *
     * <p>Par défaut, aucun pair : rien n'est déposé, et l'appelant se comporte comme en mono-pod.</p>
     *
     * @return le reçu du pair détenteur, ou vide si personne ne détient le tour (ou pas de relais)
     */
    default Optional<SteerReceipt> steerRemoteTurn(UUID userId, UUID workspaceId, String message) {
        return Optional.empty();
    }

    /**
     * L'état d'un tour vivant chez un pair.
     *
     * @param turnId      identifiant du tour, tel que le pod propriétaire l'a créé
     * @param cursor      dernier numéro d'événement publié
     * @param startedAtMs instant d'ouverture du tour, en millisecondes depuis l'époque
     * @param pending     ce que ce tour attend (F-84 / SF-84-03), ou {@code null} — une attente vit
     *                    chez le pod qui exécute, et voyage donc avec son état
     */
    record RemoteTurnState(UUID turnId, long cursor, long startedAtMs, PendingApproval pending) {
    }
}
