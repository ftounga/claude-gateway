package fr.claudegateway.atelier.dashboard;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.atelier.autonomy.AutonomyService;
import fr.claudegateway.atelier.journey.ChantierUnblocker;

/**
 * <b>Le tableau de bord d'un client</b> (F-220 / SF-220-02) : tous les chantiers ouverts de tous les sujets d'un
 * poste, tels que l'écran les lit. Jamais d'identifiant de compte.
 *
 * @param dormantAfterDays au-delà de combien de jours sans geste un chantier est « dormant »
 * @param counts           les compteurs par couleur, vivants et dormants
 * @param autonomy         courses autonomes en cours sur le compte, et le plafond (F-219)
 * @param chantiers        Outil, puis Ma décision, puis Quelqu'un ; le plus dormant d'abord dans chaque groupe
 */
public record ClientBoard(UUID hostId, String hostName, OffsetDateTime generatedAt, int dormantAfterDays,
                          Counts counts, Autonomy autonomy, List<Chantier> chantiers) {

    /** Les compteurs de l'en-tête — un chantier reporté ne compte que dans {@code snoozed} (SF-220-05). */
    public record Counts(int outil, int moi, int autrui, int live, int dormant, int snoozed) {
    }

    /** Les courses autonomes du compte : en cours, et combien au plus. */
    public record Autonomy(long running, int maxParallel) {
    }

    /** La prochaine étape : la première ni faite ni vérifiée. */
    public record NextStep(int rank, String title) {
    }

    /**
     * Un chantier ouvert, sur sa carte.
     *
     * @param hostTerminal   vrai si le sujet est le terminal du poste (pas un projet)
     * @param focus          le chantier que le sujet montre (F-206)
     * @param unblocker      qui doit agir pour qu'il avance (SF-220-01)
     * @param ready          prêt à partir en autonome (préflight F-219)
     * @param missing        ce qui l'empêche, lisible (vide si prêt)
     * @param lastActivityAt son dernier geste connu
     * @param idleDays       jours entiers écoulés depuis
     * @param dormant        {@code idleDays ≥ dormantAfterDays}
     * @param live           un tour vivant (chantier au focus) ou une course autonome en cours
     * @param question       la question en suspens (chantier au focus), ou {@code null}
     * @param run            sa course autonome en cours, sinon la dernière ; {@code null} s'il n'a jamais couru
     * @param snoozedUntil   reporté jusqu'à cette date (SF-220-05), ou {@code null}
     */
    public record Chantier(UUID workspaceId, String workspaceName, boolean hostTerminal, int number, String title,
                           String phase, String phaseLabel, boolean focus, OffsetDateTime openedAt, int done,
                           int total, NextStep nextStep, ChantierUnblocker.Mark unblocker, boolean ready,
                           List<String> missing, OffsetDateTime lastActivityAt, long idleDays, boolean dormant,
                           boolean live, String question, AutonomyService.RunView run, OffsetDateTime snoozedUntil) {
    }
}
