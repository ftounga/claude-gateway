package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>La preuve d'une écriture des outils Radar</b> (F-104 / SF-104-01) : la parole de l'utilisateur.
 *
 * <p>Ce n'est jamais le modèle qui la fournit. Dans le terminal Teams, c'est le message du tour ; dans
 * <i>Donner la nouvelle</i>, le texte donné (ou le courriel collé, daté du courriel). Le modèle choisit
 * <b>quoi</b> écrire ; il ne peut pas fabriquer <b>ce qui le justifie</b> (cadrage §4.1).</p>
 *
 * <p>La note n'est enregistrée qu'à la première écriture, et de façon idempotente par
 * {@code sourceRef} : plusieurs écritures d'un même message partagent une seule preuve, et une lecture
 * n'en crée aucune.</p>
 *
 * @param source         {@link RadarEvidenceSource#USER_NOTE} ou {@link RadarEvidenceSource#PASTED_MAIL}
 * @param sourceRef      identifiant stable de la nouvelle
 * @param occurredAt     instant de la nouvelle (date du courriel pour un courriel collé)
 * @param quote          citation courte, tronquée à la borne du Radar
 * @param authorKey      clé d'annuaire de l'auteur (l'expéditeur d'un courriel), ou {@code null}
 * @param authorName     nom affiché de l'auteur, ou {@code null} : l'auteur n'est rattaché à l'annuaire
 *                       qu'à la première écriture — une nouvelle qui n'écrit rien ne crée personne
 */
public record RadarNote(RadarEvidenceSource source, String sourceRef, OffsetDateTime occurredAt, String quote,
        String authorKey, String authorName) {

    public RadarNote {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (source != RadarEvidenceSource.USER_NOTE && source != RadarEvidenceSource.PASTED_MAIL) {
            throw new IllegalArgumentException("Une nouvelle est une note ou un courriel collé.");
        }
    }

    /** La note d'un message du terminal : le message lui-même, à l'ouverture du tour. */
    public static RadarNote ofTerminalMessage(UUID messageId, String text, OffsetDateTime at) {
        return new RadarNote(RadarEvidenceSource.USER_NOTE, "atelier-message:" + messageId, at, text, null, null);
    }

    /** Vrai si la note nomme un auteur à rattacher à l'annuaire. */
    public boolean hasAuthor() {
        return authorKey != null && !authorKey.isBlank() && authorName != null && !authorName.isBlank();
    }

    RadarRegistry.EvidenceInput input(UUID authorPersonId) {
        return new RadarRegistry.EvidenceInput(source, sourceRef, occurredAt, quote, null, authorPersonId);
    }
}
