package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>Le parcours d'un terminal tel que l'écran le lit</b> (F-176). Jamais d'identifiant de compte.
 *
 * @param mode             {@code LIBRE} ou {@code GUIDE}
 * @param phase            la phase courante ; {@code null} si le sujet n'a jamais été guidé
 * @param phaseLabel       son libellé (« Investigation »…), {@code null} sans phase
 * @param phaseChangedAt   depuis quand le sujet est dans cette phase
 * @param guidedProposal   la proposition du mode guidé qui attend un geste, ou {@code null} (SF-176-02)
 * @param guidedDeclined   vrai si l'utilisateur a choisi de rester libre sur ce sujet (SF-176-02)
 * @param plan             le plan structuré, ou {@code null} s'il n'y en a pas (SF-176-03)
 * @param gateClosed       la porte refuse les modifications (SF-176-07) — même calcul que la boucle
 * @param gateMessage      le message exact du refus, ou {@code null} si la porte est ouverte
 * @param chantier         le chantier en FOCUS (F-206), ou {@code null} si le sujet n'a jamais été guidé
 * @param chantiers        les chantiers OUVERTS du sujet, focus compris, par numéro croissant (F-206)
 */
public record SubjectJourneyResponse(
        String mode,
        String phase,
        String phaseLabel,
        OffsetDateTime phaseChangedAt,
        GuidedProposal guidedProposal,
        boolean guidedDeclined,
        Plan plan,
        Diagnosis diagnosis,
        boolean closeProposed,
        boolean gateClosed,
        String gateMessage,
        Chantier chantier,
        long closedChantiers,
        List<OpenChantier> chantiers) {

    /**
     * Un chantier ouvert du sujet (F-206), tel que la bande du parcours le montre.
     *
     * @param focus              vrai pour le chantier affiché (celui que visent les gestes sans numéro)
     * @param awaitingValidation son plan attend le clic de l'utilisateur
     * @param diagnosisPending   « Prêt à planifier » attend le geste de l'utilisateur
     * @param closeProposed      la clôture est proposée
     * @param executing          en Exécution sur son plan validé — il ouvre la porte (SF-206-02)
     * @param done               étapes faites ou vérifiées
     * @param total              étapes du plan
     */
    public record OpenChantier(int number, String title, OffsetDateTime openedAt, String phase, String phaseLabel,
                               boolean focus, int planVersion, Integer validatedVersion,
                               boolean awaitingValidation, boolean diagnosisPending, boolean closeProposed,
                               boolean executing, int done, int total) {

        static OpenChantier of(int number, String title, OffsetDateTime openedAt, JourneyPhase phase, boolean focus,
                               int planVersion, Integer validatedVersion, String planJson,
                               OffsetDateTime diagnosisProposedAt, OffsetDateTime closeProposedAt) {
            boolean current = validatedVersion != null && validatedVersion == planVersion;
            JourneyPlan plan = JourneyPlan.fromJson(planJson);
            int done = 0;
            for (JourneyPlan.Step s : plan.steps()) {
                if (s.status() == JourneyPlan.StepStatus.FAIT || s.status() == JourneyPlan.StepStatus.VERIFIE) {
                    done++;
                }
            }
            return new OpenChantier(number, title, openedAt,
                    phase == null ? null : phase.name(), phase == null ? null : phase.label(), focus,
                    planVersion, validatedVersion,
                    phase == JourneyPhase.PLAN && !plan.isEmpty() && !current,
                    phase == JourneyPhase.INVESTIGATION && diagnosisProposedAt != null,
                    closeProposedAt != null,
                    phase == JourneyPhase.EXECUTION && current,
                    done, plan.steps().size());
        }
    }

    /** Les chantiers ouverts du sujet, focus compris, par numéro croissant (F-206). */
    static List<OpenChantier> openChantiers(SubjectJourney journey) {
        List<OpenChantier> open = new ArrayList<>();
        if (journey.hasOpenFocus()) {
            open.add(OpenChantier.of(Math.max(1, journey.getChantierNumber()), journey.getChantierTitle(),
                    journey.getChantierOpenedAt(), journey.getPhase(), true, journey.getPlanVersion(),
                    journey.getValidatedVersion(), journey.getPlanJson(), journey.getDiagnosisProposedAt(),
                    journey.getCloseProposedAt()));
        }
        for (SubjectJourneyChantier c : journey.getOtherOpenChantiers()) {
            open.add(OpenChantier.of(c.getNumber(), c.getTitle(), c.getOpenedAt(), c.getPhase(), false,
                    c.getPlanVersion() == null ? 0 : c.getPlanVersion(), c.getValidatedVersion(), c.getPlanJson(),
                    c.getDiagnosisProposedAt(), c.getCloseProposedAt()));
        }
        open.sort(java.util.Comparator.comparingInt(OpenChantier::number));
        return List.copyOf(open);
    }

    /**
     * Le chantier courant du sujet (SF-176-11), ou {@code null} si le sujet n'a jamais été guidé.
     *
     * @param number   1, 2… — un nouveau chantier à chaque passage en Guidé après une clôture
     * @param title    son titre
     * @param openedAt quand il a été ouvert
     */
    public record Chantier(int number, String title, OffsetDateTime openedAt) {
    }

    /**
     * Un chantier clos, tel que la liste de l'en-tête le montre (SF-176-11).
     *
     * @param plan les étapes du plan validé final (lecture seule)
     */
    public record ClosedChantier(int number, String title, OffsetDateTime openedAt, OffsetDateTime closedAt,
                                 String diagnosis, String diagnosisConfidence, Integer planVersion,
                                 List<Step> plan) {

        public static ClosedChantier from(SubjectJourneyChantier c) {
            List<Step> steps = new ArrayList<>();
            for (JourneyPlan.Step s : JourneyPlan.fromJson(c.getPlanJson()).steps()) {
                steps.add(new Step(s.title(), s.risk().name(), s.risk().label(), s.verify(), s.rollback(),
                        s.waitsOn(), null, s.status().name(), s.evidence(), false));
            }
            return new ClosedChantier(c.getNumber(), c.getTitle(), c.getOpenedAt(), c.getClosedAt(),
                    c.getDiagnosis(), c.getDiagnosisConfidence(), c.getPlanVersion(), List.copyOf(steps));
        }
    }

    /**
     * Le diagnostic posé en fin d'investigation (SF-176-05).
     *
     * @param confidence {@code FAIBLE}, {@code MOYENNE} ou {@code ELEVEE}
     * @param pending    vrai si « Prêt à planifier » attend le geste de l'utilisateur
     */
    public record Diagnosis(String text, String evidence, String confidence, boolean pending) {
    }

    /**
     * La carte [Passer en guidé] [Rester libre] : pourquoi l'agent la propose, et depuis quand.
     *
     * @param newChantier vrai si le sujet est déjà guidé : accepter ouvre un chantier DE PLUS (F-206)
     */
    public record GuidedProposal(String reason, OffsetDateTime proposedAt, boolean newChantier) {
    }

    /**
     * Le plan structuré (SF-176-03).
     *
     * @param version          la version courante
     * @param validatedVersion la dernière version validée, ou {@code null}
     * @param validatedAt      quand
     * @param awaitingValidation vrai si cette version attend le clic de l'utilisateur
     * @param amendment        vrai si c'est la modification d'un plan déjà validé
     * @param waitingInputs    nombre d'étapes qui attendent une attente encore ouverte
     */
    public record Plan(int version, Integer validatedVersion, OffsetDateTime validatedAt,
                       boolean awaitingValidation, boolean amendment, int waitingInputs, List<Step> steps) {
    }

    /**
     * Une étape telle que l'écran la montre.
     *
     * @param waitsOnStatus l'état de l'attente dont elle dépend ({@code A_FAIRE}, {@code DEMANDE},
     *                      {@code FAIT}, {@code ANNULE}), ou {@code null} si aucune attente ne porte la clé
     * @param changed       vrai si l'étape est nouvelle ou modifiée depuis le plan validé (amendement)
     */
    public record Step(String title, String risk, String riskLabel, String verify, String rollback,
                       String waitsOn, String waitsOnStatus, String status, String evidence, boolean changed) {
    }

    public static SubjectJourneyResponse from(SubjectJourney journey) {
        return from(journey, Map.of());
    }

    public static SubjectJourneyResponse from(SubjectJourney journey, Map<String, String> waitsOn) {
        return from(journey, waitsOn, 0);
    }

    public static SubjectJourneyResponse from(SubjectJourney journey, Map<String, String> waitsOn,
                                              long closedChantiers) {
        JourneyPhase phase = journey.getPhase();
        // F-206 : sujet déjà guidé avec un chantier ouvert ⇒ la proposition est celle d'un chantier de plus.
        boolean alongside = journey.isGuided() && journey.hasOpenFocus();
        GuidedProposal proposal = journey.getGuidedProposedAt() == null || (journey.isGuided() && !alongside)
                ? null
                : new GuidedProposal(journey.getGuidedProposalReason(), journey.getGuidedProposedAt(), alongside);
        return new SubjectJourneyResponse(
                journey.getMode() == null ? JourneyMode.LIBRE.name() : journey.getMode().name(),
                phase == null ? null : phase.name(),
                phase == null ? null : phase.label(),
                journey.getPhaseChangedAt(),
                proposal,
                journey.getGuidedDeclinedAt() != null,
                plan(journey, waitsOn == null ? Map.of() : waitsOn),
                journey.getDiagnosis() == null ? null
                        : new Diagnosis(journey.getDiagnosis(), journey.getDiagnosisEvidence(),
                                journey.getDiagnosisConfidence(),
                                journey.isGuided() && journey.getPhase() == JourneyPhase.INVESTIGATION
                                        && journey.getDiagnosisProposedAt() != null),
                journey.isGuided() && journey.getPhase() != JourneyPhase.CLOS
                        && journey.getCloseProposedAt() != null,
                JourneyGate.isClosed(journey),
                JourneyGate.message(journey),
                journey.getChantierNumber() <= 0 ? null
                        : new Chantier(journey.getChantierNumber(), journey.getChantierTitle(),
                                journey.getChantierOpenedAt()),
                closedChantiers,
                openChantiers(journey));
    }

    private static Plan plan(SubjectJourney journey, Map<String, String> waitsOn) {
        JourneyPlan plan = JourneyPlan.fromJson(journey.getPlanJson());
        if (plan.isEmpty()) {
            return null;
        }
        Integer validated = journey.getValidatedVersion();
        boolean current = validated != null && validated == journey.getPlanVersion();
        boolean amendment = validated != null && !current;
        JourneyPlan validatedPlan = amendment ? JourneyPlan.fromJson(journey.getValidatedPlanJson()) : null;
        List<Step> steps = new ArrayList<>();
        int waiting = 0;
        for (int i = 0; i < plan.steps().size(); i++) {
            JourneyPlan.Step s = plan.steps().get(i);
            String status = s.waitsOn() == null ? null : waitsOn.get(s.waitsOn());
            if (s.waitsOn() != null && s.status() == JourneyPlan.StepStatus.A_FAIRE
                    && ("A_FAIRE".equals(status) || "DEMANDE".equals(status))) {
                waiting++;
            }
            steps.add(new Step(s.title(), s.risk().name(), s.risk().label(), s.verify(), s.rollback(),
                    s.waitsOn(), status, s.status().name(), s.evidence(),
                    amendment && plan.changedSince(validatedPlan, i)));
        }
        boolean awaiting = journey.isGuided() && journey.getPhase() == JourneyPhase.PLAN && !current;
        return new Plan(journey.getPlanVersion(), validated, journey.getPlanValidatedAt(), awaiting, amendment,
                waiting, List.copyOf(steps));
    }
}
