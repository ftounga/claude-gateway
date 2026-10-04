package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * <b>La carte d'une attente dans le fil</b> (F-175 / SF-175-05, décision D7) : ce que l'écran affiche
 * quand l'agent inscrit une attente, en reconnaît une déjà là, la fait passer à « Demandé », ou propose
 * de la fermer.
 *
 * <p>Instantané pris au moment de l'appel : rangé dans la transcription du tour, il survit au
 * rechargement. L'état <b>vivant</b> (la proposition est-elle encore en attente ?) se relit sur le
 * tableau des attentes — la carte ne ment pas sur un geste déjà fait.</p>
 *
 * <p>Jamais d'identifiant de compte ; l'{@code actionId} et le {@code workspaceId} ne servent qu'aux
 * routes déjà isolées par le serveur.</p>
 *
 * @param kind  {@code ADDED} (inscrite), {@code ALREADY} (déjà là), {@code REQUESTED} (passée à
 *              « Demandé »), {@code PROPOSED} (fermeture proposée — [Confirmer] [Pas encore])
 * @param match comment une attente déjà là a été reconnue : {@code KEY}, {@code KEY_ON_HOST},
 *              {@code MEANING} ; {@code NONE} sinon
 */
public record AttenteBlock(
        UUID actionId,
        UUID workspaceId,
        String kind,
        String match,
        String description,
        TerminalActionStatus status,
        String requestedTo,
        String requestedAt,
        String channel,
        TerminalActionStatus proposedStatus,
        String proposedReason,
        String createdAt) {

    public static final String ADDED = "ADDED";
    public static final String ALREADY = "ALREADY";
    public static final String REQUESTED = "REQUESTED";
    public static final String PROPOSED = "PROPOSED";

    /** L'instantané d'une attente, pour une carte de ce genre. {@code null} si pas d'attente. */
    public static AttenteBlock of(TerminalAction action, String kind, String match) {
        if (action == null || kind == null) {
            return null;
        }
        return new AttenteBlock(action.getId(), action.getWorkspaceId(), kind,
                match == null ? "NONE" : match, action.getDescription(), action.getStatus(),
                action.getRequestedTo(), iso(action.getRequestedAt()), action.getChannel(),
                action.getProposedStatus(), action.getProposedReason(), iso(action.getCreatedAt()));
    }

    /**
     * Les dates voyagent en <b>texte ISO</b> : la transcription du tour est sérialisée par un
     * {@code ObjectMapper} nu (sans module des dates) — un {@code OffsetDateTime} y ferait échouer
     * l'écriture de la transcription entière, et le fil perdrait tout au rechargement.
     */
    private static String iso(OffsetDateTime at) {
        return at == null ? null : at.toString();
    }
}
