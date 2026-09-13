package fr.claudegateway.radar.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.radar.dto.RadarViews.SyncView;

/**
 * Les vues de <b>l'onglet Radar</b> d'un client de la Vigie (F-102) : le résumé du matin (SF-102-01).
 *
 * <p>Séparées de {@link RadarViews} : la page sujet (F-103) fait évoluer ces vues-là en parallèle.</p>
 */
public final class RadarBoardViews {

    private RadarBoardViews() {
    }

    /**
     * Le résumé du matin (SF-102-01).
     *
     * @param since            début de la fenêtre « depuis hier »
     * @param sentences        ce qui a bougé, trois phrases au plus, par ordre de priorité
     * @param running          la synchro en cours, ou {@code null}
     * @param lastSync         la dernière synchro terminée, ou {@code null}
     * @param coverageComplete vrai seulement si la dernière synchro a tout lu et n'est pas ancienne
     * @param coverageWarning  la phrase à dire <b>en tête</b> quand la couverture n'est pas complète (§4.4)
     * @param coverageLines    ce qui a été lu, par source, de la dernière synchro terminée
     */
    public record BriefView(OffsetDateTime generatedAt, OffsetDateTime since, List<BriefSentence> sentences,
            BriefCounts counts, SyncView running, SyncView lastSync, boolean coverageComplete,
            String coverageWarning, List<CoverageLine> coverageLines) {
    }

    /** Une phrase du résumé, et l'objet du registre qui la fonde. */
    public record BriefSentence(BriefKind kind, String text, UUID subjectId, UUID commitmentId) {
    }

    /** Ce dont parle une phrase du résumé, dans l'ordre de priorité. */
    public enum BriefKind {
        WAKE,
        OVERDUE,
        FOLLOW_UP,
        CLOSE_PROPOSED,
        NEW_SUBJECTS,
        QUESTION,
        MOVED,
        DORMANT,
        CALM
    }

    /**
     * Les compteurs du Radar d'un client.
     *
     * @param toHandle ce qui réclame un geste : engagements à ma charge dus ou en retard, relances dues,
     *                 engagements probables non tranchés, sujets « clos ? » et sujets réveillés
     */
    public record BriefCounts(int toDoByMe, int followUpsDue, int introductions, int subjectsFollowed,
            int blockedSubjects, int toHandle) {
    }

    /**
     * Une source lue par la dernière synchro.
     *
     * @param source {@code TEAMS}, {@code MEETINGS} ou {@code RECORDINGS}
     * @param ok     faux dès qu'un manque touche la source
     */
    public record CoverageLine(String source, boolean ok, String text) {
    }
}
