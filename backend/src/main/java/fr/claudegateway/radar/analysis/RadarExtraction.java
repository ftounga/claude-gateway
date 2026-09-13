package fr.claudegateway.radar.analysis;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import fr.claudegateway.radar.RadarRole;
import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.analysis.RadarExtractionContext.FactSnapshot;
import fr.claudegateway.radar.analysis.RadarExtractionContext.MessageEntry;
import fr.claudegateway.radar.analysis.RadarExtractionContext.PersonEntry;
import fr.claudegateway.radar.analysis.RadarExtractionContext.SubjectEntry;

/**
 * Une sortie d'extraction <b>lue et vérifiée</b> (F-101 / SF-101-03) : tous les libellés sont résolus,
 * toutes les valeurs sont dans leurs énumérations et leurs bornes, chaque fait a ses preuves. Rien de ce
 * qui est ici ne vient d'un identifiant proposé par le modèle.
 *
 * @param subjects les sujets touchés
 * @param quotes   citations proposées, par libellé de message — relues dans le message avant usage
 */
public record RadarExtraction(List<SubjectItem> subjects, Map<String, String> quotes) {

    public RadarExtraction {
        subjects = List.copyOf(subjects);
        quotes = Map.copyOf(quotes);
    }

    /**
     * Un sujet touché.
     *
     * @param existing  le sujet suivi, ou {@code null} pour un sujet à créer
     * @param newName   le nom du sujet à créer ; {@code null} sinon
     * @param evidence  les messages qui en parlent (au moins un)
     * @param aliases   autres noms proposés
     * @param state     nouvel état, ou {@code null}
     * @param nextStep  prochaine étape, ou {@code null}
     * @param due       échéance, ou {@code null}
     * @param summary   résumé complet de remplacement, ou {@code null} s'il n'est pas touché
     * @param roles       rôles des personnes
     * @param commitments engagements lus (SF-101-04)
     * @param follows     suivis d'engagements ouverts (SF-101-04)
     * @param closure     preuves d'un signal de clôture, ou {@code null} (SF-101-04)
     */
    public record SubjectItem(SubjectEntry existing, String newName, List<MessageEntry> evidence,
            List<String> aliases, Valued<RadarSubjectState> state, Valued<String> nextStep,
            Valued<LocalDate> due, List<SummaryItem> summary, List<RoleItem> roles,
            List<CommitmentItem> commitments, List<FollowItem> follows, List<MessageEntry> closure) {

        /** Vrai si l'extraction rattache à un sujet suivi. */
        public boolean attached() {
            return existing != null;
        }
    }

    /** Une valeur et ses preuves ; {@code value} peut être {@code null} (effacer). */
    public record Valued<T>(T value, List<MessageEntry> evidence) {
    }

    /** Une phrase de résumé : la reprise d'une phrase existante, ou une phrase nouvelle et ses preuves. */
    public record SummaryItem(FactSnapshot reprise, String text, List<MessageEntry> evidence) {
    }

    /** Le rôle d'une personne sur le sujet. */
    public record RoleItem(PersonEntry person, RadarRole role, List<MessageEntry> evidence) {
    }

    /**
     * Un engagement lu (SF-101-04). Les personnes vides, c'est « moi » — voir le sens.
     *
     * @param debtor      qui doit ({@code autre_vers_moi})
     * @param beneficiary à qui ({@code moi_vers_autre}, facultatif) ; la personne A d'une mise en relation
     * @param other       la personne B d'une mise en relation
     * @param dueDeduced  échéance déduite (« jeudi ») plutôt qu'écrite
     */
    public record CommitmentItem(fr.claudegateway.radar.RadarCommitmentDirection direction, String description,
            PersonEntry debtor, PersonEntry beneficiary, PersonEntry other, LocalDate dueDate, boolean dueDeduced,
            fr.claudegateway.radar.RadarCertainty certainty, List<MessageEntry> evidence) {
    }

    /** Le suivi d'un engagement ouvert montré (SF-101-04). */
    public record FollowItem(RadarExtractionContext.CommitmentSnapshot commitment,
            fr.claudegateway.radar.RadarCommitmentStatus status, List<MessageEntry> evidence) {
    }

    /** Sujets rattachés à un sujet suivi. */
    public int attachedCount() {
        return (int) subjects.stream().filter(SubjectItem::attached).count();
    }

    /** Sujets à créer. */
    public int createdCount() {
        return subjects.size() - attachedCount();
    }
}
