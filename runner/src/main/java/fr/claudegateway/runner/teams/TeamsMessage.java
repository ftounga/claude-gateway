package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.List;

/**
 * Un message, tel que le produit le connaît (F-87 / SF-87-01). <b>L'objet central du volet</b> :
 * F-88 le remplit, F-89 l'affiche, et aucune des deux ne connaît la forme Microsoft qu'il traduit.
 *
 * <p><b>Invariant</b> : un message n'existe que <b>complet</b>. Identifiant, auteur lisible et
 * horodatage sont obligatoires — sans eux, {@link #isReadable()} est faux et l'adaptateur ne le rend
 * pas. Un message à moitié lu est un message faux.</p>
 *
 * @param id             identifiant opaque, stable pour recoller des pages sans doublon
 * @param conversationId conversation qui le porte
 * @param parentId       message racine du fil, ou {@code ""} si le message est à la racine
 * @param author         auteur ; jamais {@code null}
 * @param sentAt         envoi, en UTC ; jamais {@code null} sur un message rendu
 * @param editedAt       dernière modification, ou {@code null}
 * @param deleted        vrai si Teams l'a marqué supprimé (on le dit, on ne le cache pas)
 * @param kind           genre ; jamais deviné
 * @param text           texte plat, prêt pour un compte rendu
 * @param html           mise en forme d'origine, bornée ; {@code ""} si absente
 * @param mentions       mentions portées ; vide, jamais {@code null}
 * @param attachments    pièces jointes, par référence ; vide, jamais {@code null}
 * @param reactions      réactions ; vide, jamais {@code null}
 * @param webUrl         lien profond vers le message dans Teams, ou {@code ""} — c'est lui qui rend
 *                       une ligne de compte rendu <b>vérifiable d'un clic</b> (F-89)
 */
public record TeamsMessage(String id, String conversationId, String parentId,
        TeamsParticipant author, Instant sentAt, Instant editedAt, boolean deleted,
        TeamsMessageKind kind, String text, String html, List<TeamsMention> mentions,
        List<TeamsAttachmentRef> attachments, List<TeamsReaction> reactions, String webUrl) {

    /** Borne du HTML conservé : au-delà on coupe, on ne fragmente pas. */
    public static final int MAX_HTML_CHARS = 64 * 1024;

    public TeamsMessage {
        id = id == null ? "" : id.strip();
        conversationId = conversationId == null ? "" : conversationId.strip();
        parentId = parentId == null ? "" : parentId.strip();
        text = text == null ? "" : text;
        html = html == null ? "" : html;
        if (html.length() > MAX_HTML_CHARS) {
            html = html.substring(0, MAX_HTML_CHARS);
        }
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
        webUrl = webUrl == null ? "" : webUrl.strip();
    }

    /**
     * Vrai si le message est exploitable tel quel. L'adaptateur ne rend <b>que</b> des messages pour
     * lesquels c'est vrai : le reste devient un manque.
     */
    public boolean isReadable() {
        return !id.isEmpty() && author != null && author.isReadable() && sentAt != null
                && kind != null;
    }

    /** Vrai si ce message mentionne la personne donnée. */
    public boolean mentions(TeamsParticipant participant) {
        return mentions.stream().anyMatch(mention -> mention.targets(participant));
    }
}
