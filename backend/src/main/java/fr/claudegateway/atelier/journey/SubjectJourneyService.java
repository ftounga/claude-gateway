package fr.claudegateway.atelier.journey;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceService;

/**
 * <b>Le parcours du sujet d'un terminal</b> (F-176) : lire son mode et sa phase, changer de mode.
 *
 * <p><b>Isolation.</b> Chaque méthode publique appelle {@code requireOwned} <b>en premier</b> — un
 * terminal d'autrui rend 404 avant que quoi que ce soit d'autre ne soit lu. Les lectures portent
 * ensuite {@code user_id} <i>et</i> {@code workspace_id}.</p>
 *
 * <p><b>Pas de ligne = Libre</b> (décision Q4) : la lecture n'écrit jamais ; seule une décision
 * (changer de mode…) crée la ligne.</p>
 */
@Service
public class SubjectJourneyService {

    private static final Logger log = LoggerFactory.getLogger(SubjectJourneyService.class);

    private final SubjectJourneyRepository repository;
    private final SubjectJourneyEventRepository events;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    public SubjectJourneyService(SubjectJourneyRepository repository,
                                 SubjectJourneyEventRepository events,
                                 WorkspaceService workspaceService,
                                 Clock clock) {
        this.repository = repository;
        this.events = events;
        this.workspaceService = workspaceService;
        this.clock = clock;
    }

    /** Le parcours du terminal ; Libre s'il n'a jamais été décidé. 404 sur un terminal d'autrui. */
    @Transactional(readOnly = true)
    public SubjectJourney get(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        return withOpenChantiers(find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId)));
    }

    /**
     * Le parcours tel que le tour le lit — <b>sans</b> repasser par {@code requireOwned} : l'appelant
     * (la boucle de l'agent) a déjà le terminal possédé du tour. Libre si rien n'est décidé.
     */
    @Transactional(readOnly = true)
    public SubjectJourney forTurn(UUID userId, UUID workspaceId) {
        return withOpenChantiers(find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId)));
    }

    /**
     * <b>Change le mode du sujet</b> (SF-176-01) — le geste du menu du terminal.
     *
     * <p>Passer en Guidé ouvre le parcours en <b>Investigation</b> si le sujet n'a jamais été guidé, ou
     * s'il avait été clos ; sinon il reprend la phase où il était (repasser par Libre ne perd rien).
     * Repasser en Libre garde la phase et le plan — la porte cesse simplement de s'appliquer (Q4).</p>
     *
     * @throws InvalidJourneyException mode absent ou inconnu
     */
    @Transactional
    public SubjectJourney setMode(UUID userId, UUID workspaceId, String rawMode) {
        return setMode(userId, workspaceId, rawMode, null);
    }

    /**
     * Même geste, avec le <b>titre</b> du chantier qui s'ouvre (SF-176-11) — ignoré si le passage en
     * Guidé reprend un chantier en cours.
     */
    @Transactional
    public SubjectJourney setMode(UUID userId, UUID workspaceId, String rawMode, String title) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        JourneyMode mode = JourneyMode.parse(rawMode);
        if (mode == null) {
            throw new InvalidJourneyException("Le mode est « LIBRE » ou « GUIDE ».");
        }
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
        boolean reopen = mode == JourneyMode.GUIDE && journey.getPhase() == JourneyPhase.CLOS;
        if (journey.getMode() == mode && journey.getCreatedAt() != null && !reopen) {
            return journey; // rien ne change : pas d'écriture, pas d'événement
        }
        SubjectJourney saved = applyMode(journey, mode, OffsetDateTime.now(clock), title);
        record(saved, SubjectJourneyEvent.MODE_CHANGED, "USER");
        return saved;
    }

    /**
     * <b>[Passer en guidé]</b> (SF-176-02) : la proposition de l'agent est acceptée — le sujet passe en
     * Guidé (Investigation) et la carte disparaît. Sans proposition en attente : même effet que le menu.
     */
    @Transactional
    public SubjectJourney acceptGuidedProposal(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
        // SF-176-11 : la raison de la proposition titre le chantier qui s'ouvre.
        String title = journey.getGuidedProposalReason();
        OffsetDateTime now = OffsetDateTime.now(clock);
        SubjectJourney saved;
        if (journey.isGuided() && journey.hasOpenFocus()) {
            // F-206 : déjà guidé — la proposition ouvre un chantier DE PLUS, sans clore les autres.
            journey.setGuidedProposedAt(null);
            journey.setGuidedProposalReason(null);
            openChantier(journey, title, now);
            saved = save(journey, now);
        } else {
            saved = applyMode(journey, JourneyMode.GUIDE, now, title);
        }
        record(saved, SubjectJourneyEvent.GUIDED_ACCEPTED, null);
        return withOpenChantiers(saved);
    }

    /**
     * <b>[Rester libre]</b> (SF-176-02) : la proposition est écartée, et l'agent ne la refera plus sur ce
     * sujet. Le mode ne change pas.
     */
    @Transactional
    public SubjectJourney declineGuidedProposal(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setGuidedProposedAt(null);
        journey.setGuidedProposalReason(null);
        if (!journey.isGuided()) {
            // F-206 : écarter un chantier DE PLUS (sujet déjà guidé) ne ferme pas la porte aux suivants.
            journey.setGuidedDeclinedAt(now);
        }
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.GUIDED_DECLINED, null);
        return saved;
    }

    /** Ce que l'agent apprend en proposant le mode guidé (SF-176-02). */
    public enum ProposalOutcome {
        /** La carte est posée. */
        PROPOSED,
        /** La même proposition attend déjà le choix de l'utilisateur. */
        ALREADY_PROPOSED,
        /** Le sujet est déjà guidé. */
        ALREADY_GUIDED,
        /** L'utilisateur a choisi de rester libre sur ce sujet. */
        DECLINED,
        /** Sujet déjà guidé : la carte propose un chantier DE PLUS, sans clore les autres (F-206). */
        PROPOSED_NEW_CHANTIER
    }

    /**
     * <b>L'agent propose le mode guidé</b> (SF-176-02). Le terminal est celui <b>du tour</b>, déjà
     * possédé ; la lecture filtre quand même {@code user_id}.
     *
     * @throws InvalidJourneyException raison vide
     */
    @Transactional
    public ProposalOutcome proposeGuided(UUID userId, UUID workspaceId, String reason) {
        String cleanReason = reason == null ? "" : reason.strip();
        if (cleanReason.isEmpty()) {
            throw new InvalidJourneyException("reason est requise : pourquoi ce sujet est un chantier.");
        }
        if (cleanReason.length() > MAX_REASON) {
            cleanReason = cleanReason.substring(0, MAX_REASON);
        }
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
        if (journey.isGuided()) {
            if (!journey.hasOpenFocus()) {
                return ProposalOutcome.ALREADY_GUIDED;
            }
            // F-206 : un chantier distinct sur un sujet guidé — proposé, jamais ouvert sans le geste.
            if (journey.getGuidedProposedAt() != null) {
                return ProposalOutcome.ALREADY_PROPOSED;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            journey.setGuidedProposedAt(now);
            journey.setGuidedProposalReason(cleanReason);
            SubjectJourney saved = save(journey, now);
            record(saved, SubjectJourneyEvent.GUIDED_PROPOSED, "chantier de plus");
            return ProposalOutcome.PROPOSED_NEW_CHANTIER;
        }
        if (journey.getGuidedDeclinedAt() != null) {
            return ProposalOutcome.DECLINED;
        }
        if (journey.getGuidedProposedAt() != null) {
            return ProposalOutcome.ALREADY_PROPOSED;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setGuidedProposedAt(now);
        journey.setGuidedProposalReason(cleanReason);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.GUIDED_PROPOSED, null);
        return ProposalOutcome.PROPOSED;
    }

    /**
     * Journalise un refus de la porte (SF-176-04) : la classe et l'outil, jamais la commande.
     * Best-effort.
     */
    @Transactional
    public void recordGateBlocked(SubjectJourney journey, JourneyPlan.Risk risk, String tool) {
        if (journey == null) {
            return;
        }
        record(journey, SubjectJourneyEvent.GATE_BLOCKED, (risk == null ? "?" : risk.name()) + " · " + tool);
    }

    /** Ce que l'agent apprend en posant son plan (SF-176-03). */
    public enum PlanOutcome {
        /** Le plan est posé, il attend la validation de l'utilisateur. */
        SET,
        /** Un plan déjà validé a été modifié : c'est un amendement, à revalider. */
        AMENDMENT,
        /** Le sujet est en Libre : le plan structuré est celui du mode guidé. */
        NOT_GUIDED,
        /** Le sujet est clos. */
        CLOSED,
        /** Aucune étape lisible. */
        EMPTY,
        /** Le sujet est en Investigation : diagnostic d'abord, confirmé par l'utilisateur (SF-176-05). */
        NEEDS_DIAGNOSIS
    }

    // ------------------------------------------------------------------ SF-176-05 : les transitions

    /** Ce que l'agent apprend en posant son diagnostic. */
    public enum DiagnosisOutcome { PROPOSED, NOT_INVESTIGATING, NOT_GUIDED }

    /**
     * <b>L'agent pose son diagnostic</b> (SF-176-05) : ce qu'il a compris, ses preuves, son niveau de
     * confiance. L'utilisateur voit « Prêt à planifier » [Planifier] [Continuer l'investigation].
     *
     * @throws InvalidJourneyException diagnostic ou preuves vides
     */
    @Transactional
    public DiagnosisOutcome submitDiagnosis(UUID userId, UUID workspaceId, String diagnosis, String evidence,
            String confidence) {
        return submitDiagnosis(userId, workspaceId, null, diagnosis, evidence, confidence);
    }

    /** Même geste, sur le chantier ouvert {@code chantier} ({@code null} = le focus) — F-206. */
    @Transactional
    public DiagnosisOutcome submitDiagnosis(UUID userId, UUID workspaceId, Integer chantier, String diagnosis,
            String evidence, String confidence) {
        SubjectJourney journey = target(userId, workspaceId, chantier);
        if (!journey.isGuided()) {
            return DiagnosisOutcome.NOT_GUIDED;
        }
        if (journey.getPhase() != JourneyPhase.INVESTIGATION) {
            return DiagnosisOutcome.NOT_INVESTIGATING;
        }
        String cleanDiagnosis = JourneyPlan.bound(diagnosis, MAX_DIAGNOSIS);
        String cleanEvidence = JourneyPlan.bound(evidence, MAX_DIAGNOSIS);
        if (cleanDiagnosis == null || cleanEvidence == null) {
            throw new InvalidJourneyException("Le diagnostic et ses preuves sont requis : ce que tu as compris, "
                    + "et ce qui le montre.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setDiagnosis(cleanDiagnosis);
        journey.setDiagnosisEvidence(cleanEvidence);
        journey.setDiagnosisConfidence(confidenceOf(confidence));
        journey.setDiagnosisProposedAt(now);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.DIAGNOSIS_PROPOSED, saved.getDiagnosisConfidence());
        return DiagnosisOutcome.PROPOSED;
    }

    /** [Planifier] : le diagnostic est accepté, le sujet passe en Plan (SF-176-05). */
    @Transactional
    public SubjectJourney confirmDiagnosis(UUID userId, UUID workspaceId) {
        return confirmDiagnosis(userId, workspaceId, null);
    }

    /** [Planifier] sur le chantier ouvert {@code chantier} ({@code null} = le focus) — F-206. Rend le focus. */
    @Transactional
    public SubjectJourney confirmDiagnosis(UUID userId, UUID workspaceId, Integer chantier) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId, chantier);
        if (journey.getPhase() != JourneyPhase.INVESTIGATION || journey.getDiagnosisProposedAt() == null) {
            throw new InvalidJourneyException("Aucun diagnostic n'attend de confirmation.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setDiagnosisProposedAt(null);
        moveTo(journey, JourneyPhase.PLAN, now);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.DIAGNOSIS_CONFIRMED, saved.getDiagnosisConfidence());
        return focusAfter(saved, userId, workspaceId);
    }

    /** [Continuer l'investigation] : le diagnostic reste lisible, le sujet reste en Investigation. */
    @Transactional
    public SubjectJourney dismissDiagnosis(UUID userId, UUID workspaceId) {
        return dismissDiagnosis(userId, workspaceId, null);
    }

    /** [Continuer l'investigation] sur le chantier {@code chantier} ({@code null} = le focus) — F-206. */
    @Transactional
    public SubjectJourney dismissDiagnosis(UUID userId, UUID workspaceId, Integer chantier) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId, chantier);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setDiagnosisProposedAt(null);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.DIAGNOSIS_DISMISSED, null);
        return focusAfter(saved, userId, workspaceId);
    }

    /** Ce que l'agent apprend en faisant avancer une étape. */
    public enum StepOutcome { UPDATED, TO_VERIFICATION, ALL_VERIFIED, FAILED, NOT_EXECUTING, NO_SUCH_STEP,
        NOT_GUIDED, BLOCKED, UNBLOCKED, ACTOR_SET }

    /** Le résultat d'une étape avancée : l'issue et le plan à jour. */
    public record StepChange(StepOutcome outcome, JourneyPlan plan) {
    }

    /**
     * <b>L'agent fait avancer une étape</b> (SF-176-05) : {@code FAIT} après l'avoir exécutée,
     * {@code VERIFIE} ou {@code ECHEC} après l'avoir vérifiée comme le plan le dit — preuves à l'appui.
     *
     * <p>Quand plus aucune étape n'est « à faire », le sujet passe en <b>Vérification</b> (les étapes qui
     * attendent un input le gardent en Exécution). Quand toutes sont vérifiées, la clôture est
     * <b>proposée</b> à l'utilisateur.</p>
     *
     * @param stepNumber le rang de l'étape, à partir de 1
     * @throws InvalidJourneyException état inconnu, ou vérification sans preuve
     */
    @Transactional
    public StepChange updateStep(UUID userId, UUID workspaceId, int stepNumber, String rawStatus,
            String evidence) {
        return updateStep(userId, workspaceId, null, stepNumber, rawStatus, evidence);
    }

    /** Même geste, sur le chantier ouvert {@code chantier} ({@code null} = le focus) — F-206. */
    @Transactional
    public StepChange updateStep(UUID userId, UUID workspaceId, Integer chantier, int stepNumber, String rawStatus,
            String evidence) {
        return updateStep(userId, workspaceId, chantier, stepNumber, rawStatus, evidence, null);
    }

    /**
     * Même geste, avec <b>qui exécute l'étape</b> (F-219 / SF-219-06). {@code rawActor} seul (sans état)
     * requalifie l'étape : permis dès qu'un plan existe, ce n'est <b>pas un amendement</b> (plan courant et plan
     * validé réécrits ensemble), l'état et la phase ne bougent pas.
     *
     * @param rawActor {@code OUTIL}, {@code MOI}, {@code TIERS}, ou vide (inchangé)
     * @throws InvalidJourneyException acteur inconnu, ou ni état ni acteur
     */
    @Transactional
    public StepChange updateStep(UUID userId, UUID workspaceId, Integer chantier, int stepNumber, String rawStatus,
            String evidence, String rawActor) {
        SubjectJourney journey = target(userId, workspaceId, chantier);
        JourneyPlan plan = JourneyPlan.fromJson(journey.getPlanJson());
        if (!journey.isGuided()) {
            return new StepChange(StepOutcome.NOT_GUIDED, plan);
        }
        JourneyPlan.Actor actor = JourneyPlan.Actor.parse(rawActor);
        boolean statusGiven = rawStatus != null && !rawStatus.isBlank();
        if (!statusGiven && actor == null) {
            throw new InvalidJourneyException("status ou actor est requis.");
        }
        boolean validatedCurrent = journey.getValidatedVersion() != null
                && journey.getValidatedVersion() == journey.getPlanVersion();
        if (!statusGiven) {
            return setActor(journey, plan, stepNumber, actor, validatedCurrent);
        }
        if (!validatedCurrent || (journey.getPhase() != JourneyPhase.EXECUTION
                && journey.getPhase() != JourneyPhase.VERIFICATION)) {
            return new StepChange(StepOutcome.NOT_EXECUTING, plan);
        }
        if (stepNumber < 1 || stepNumber > plan.steps().size()) {
            return new StepChange(StepOutcome.NO_SUCH_STEP, plan);
        }
        if (actor != null) {
            plan = plan.withActor(stepNumber - 1, actor);
        }
        JourneyPlan.StepStatus status = parseStepStatus(rawStatus);
        String cleanEvidence = JourneyPlan.bound(evidence, JourneyPlan.MAX_EVIDENCE);
        if (status == JourneyPlan.StepStatus.BLOQUE && cleanEvidence == null) {
            throw new InvalidJourneyException("Un blocage se justifie : evidence est requise (la raison — ce qui "
                    + "manque ou ce qui a échoué sans remède).");
        }
        if ((status == JourneyPlan.StepStatus.VERIFIE || status == JourneyPlan.StepStatus.ECHEC)
                && cleanEvidence == null) {
            throw new InvalidJourneyException("Une vérification se prouve : evidence est requise (ce que tu as "
                    + "observé, la commande et son résultat).");
        }
        JourneyPlan updated = plan.withStep(stepNumber - 1, status, cleanEvidence);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setPlanJson(updated.toJson());
        // Le plan validé suit l'avancement : l'avancement n'est pas un amendement.
        journey.setValidatedPlanJson(updated.toJson());
        StepOutcome outcome = StepOutcome.UPDATED;
        if (status == JourneyPlan.StepStatus.ECHEC) {
            outcome = StepOutcome.FAILED;
        } else if (status == JourneyPlan.StepStatus.BLOQUE) {
            outcome = StepOutcome.BLOCKED; // F-219 : la phase ne bouge pas, l'étape attend qu'on la débloque
        } else if (status == JourneyPlan.StepStatus.A_FAIRE) {
            outcome = StepOutcome.UNBLOCKED;
        } else if (updated.steps().stream().allMatch(s -> s.status() == JourneyPlan.StepStatus.VERIFIE)) {
            if (journey.getPhase() != JourneyPhase.VERIFICATION) {
                moveTo(journey, JourneyPhase.VERIFICATION, now);
            }
            journey.setCloseProposedAt(now);
            outcome = StepOutcome.ALL_VERIFIED;
        } else if (journey.getPhase() == JourneyPhase.EXECUTION && updated.steps().stream()
                .noneMatch(s -> s.status() == JourneyPlan.StepStatus.A_FAIRE
                        || s.status() == JourneyPlan.StepStatus.BLOQUE)) {
            moveTo(journey, JourneyPhase.VERIFICATION, now);
            outcome = StepOutcome.TO_VERIFICATION;
        }
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.STEP_UPDATED, stepNumber + " · " + status.name());
        if (outcome == StepOutcome.ALL_VERIFIED) {
            record(saved, SubjectJourneyEvent.CLOSE_PROPOSED, null);
        }
        return new StepChange(outcome, updated);
    }

    /** SF-219-06 : requalifier qui exécute une étape — ni amendement, ni changement d'état ou de phase. */
    private StepChange setActor(SubjectJourney journey, JourneyPlan plan, int stepNumber, JourneyPlan.Actor actor,
            boolean validatedCurrent) {
        if (plan.isEmpty() || journey.getPhase() == null || journey.getPhase() == JourneyPhase.CLOS) {
            return new StepChange(StepOutcome.NOT_EXECUTING, plan);
        }
        if (stepNumber < 1 || stepNumber > plan.steps().size()) {
            return new StepChange(StepOutcome.NO_SUCH_STEP, plan);
        }
        JourneyPlan updated = plan.withActor(stepNumber - 1, actor);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setPlanJson(updated.toJson());
        if (validatedCurrent) {
            journey.setValidatedPlanJson(updated.toJson()); // une qualification n'est pas un amendement
        }
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.STEP_ACTOR_SET, stepNumber + " · " + actor.name());
        return new StepChange(StepOutcome.ACTOR_SET, updated);
    }

    /**
     * <b>L'agent détaille une étape en sous-étapes</b> (F-208 / SF-208-01) : la liste reçue
     * <b>remplace</b> la précédente (idempotent). Mêmes gardes qu'une étape avancée ; l'avancement n'est
     * pas un amendement — plan courant et plan validé sont réécrits ensemble.
     *
     * @param stepNumber le rang de l'étape, à partir de 1
     */
    @Transactional
    public StepChange updateSubSteps(UUID userId, UUID workspaceId, Integer chantier, int stepNumber,
            java.util.List<JourneyPlan.SubStep> subSteps) {
        SubjectJourney journey = target(userId, workspaceId, chantier);
        JourneyPlan plan = JourneyPlan.fromJson(journey.getPlanJson());
        if (!journey.isGuided()) {
            return new StepChange(StepOutcome.NOT_GUIDED, plan);
        }
        boolean validatedCurrent = journey.getValidatedVersion() != null
                && journey.getValidatedVersion() == journey.getPlanVersion();
        if (!validatedCurrent || (journey.getPhase() != JourneyPhase.EXECUTION
                && journey.getPhase() != JourneyPhase.VERIFICATION)) {
            return new StepChange(StepOutcome.NOT_EXECUTING, plan);
        }
        if (stepNumber < 1 || stepNumber > plan.steps().size()) {
            return new StepChange(StepOutcome.NO_SUCH_STEP, plan);
        }
        JourneyPlan updated = plan.withSubSteps(stepNumber - 1, subSteps);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setPlanJson(updated.toJson());
        journey.setValidatedPlanJson(updated.toJson());
        SubjectJourney saved = save(journey, now);
        JourneyPlan.Step step = updated.steps().get(stepNumber - 1);
        record(saved, SubjectJourneyEvent.SUB_STEPS_UPDATED,
                stepNumber + " · " + step.subStepsDone() + "/" + step.subSteps().size());
        return new StepChange(StepOutcome.UPDATED, updated);
    }

    /**
     * <b>Retour en Investigation</b> (SF-176-05) : une découverte contredit le diagnostic, ou une
     * vérification a échoué. Le plan (et sa dernière version validée) est gardé ; la porte se referme.
     *
     * @throws InvalidJourneyException raison vide
     */
    @Transactional
    public boolean reopenInvestigation(UUID userId, UUID workspaceId, String reason) {
        return reopenInvestigation(userId, workspaceId, null, reason);
    }

    /** Même geste, sur le chantier ouvert {@code chantier} ({@code null} = le focus) — F-206. */
    @Transactional
    public boolean reopenInvestigation(UUID userId, UUID workspaceId, Integer chantier, String reason) {
        String cleanReason = JourneyPlan.bound(reason, MAX_REASON);
        if (cleanReason == null) {
            throw new InvalidJourneyException("reason est requise : ce qui contredit le diagnostic.");
        }
        SubjectJourney journey = target(userId, workspaceId, chantier);
        if (!journey.isGuided() || journey.getPhase() == null || journey.getPhase() == JourneyPhase.CLOS) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setDiagnosisProposedAt(null);
        journey.setCloseProposedAt(null);
        moveTo(journey, JourneyPhase.INVESTIGATION, now);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.REOPENED, cleanReason);
        return true;
    }

    /**
     * [Clore le sujet] (SF-176-05) : le chantier guidé est clos <b>et le terminal revient en Libre</b>
     * (SF-176-07, D1) — la porte s'ouvre, le plan clos reste consultable.
     */
    @Transactional
    public SubjectJourney close(UUID userId, UUID workspaceId) {
        return close(userId, workspaceId, null);
    }

    /**
     * [Clore] le chantier ouvert {@code chantier} ({@code null} = le focus) — F-206. Clore un chantier
     * qui n'est pas le focus ne change rien au focus. Clore le focus alors que d'autres chantiers restent
     * ouverts met le plus récent d'entre eux au focus, et le sujet <b>reste guidé</b> ; clore le dernier
     * ramène le terminal en Libre (SF-176-07). Rend le focus.
     */
    @Transactional
    public SubjectJourney close(UUID userId, UUID workspaceId, Integer chantier) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId, chantier);
        if (journey.getPhase() == JourneyPhase.CLOS) {
            throw new InvalidJourneyException("Ce sujet n'est pas en mode guidé.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setCloseProposedAt(null);
        journey.setDiagnosisProposedAt(null);
        moveTo(journey, JourneyPhase.CLOS, now);
        record(journey, SubjectJourneyEvent.CLOSED, null); // journalisé en Guidé (mesure SF-176-06)
        archive(journey, now); // SF-176-11 : le chantier clos reste consultable
        if (journey.getParkedRow() != null) {
            return focusAfter(journey, userId, workspaceId); // F-206 : le focus n'a pas bougé
        }
        java.util.List<SubjectJourneyChantier> stillOpen = openRows(journey);
        if (!stillOpen.isEmpty()) {
            // F-206 : d'autres chantiers restent ouverts — le plus récent prend le focus, on reste guidé.
            loadFocus(journey, stillOpen.get(stillOpen.size() - 1));
            return withOpenChantiers(save(journey, now));
        }
        journey.setMode(JourneyMode.LIBRE);
        // SF-176-11 : un chantier clos rouvre la question — l'agent pourra proposer le suivant.
        journey.setGuidedDeclinedAt(null);
        return save(journey, now);
    }

    /** [Pas encore] : la proposition de clôture est écartée (SF-176-05). */
    @Transactional
    public SubjectJourney dismissClose(UUID userId, UUID workspaceId) {
        return dismissClose(userId, workspaceId, null);
    }

    /** [Pas encore] sur le chantier {@code chantier} ({@code null} = le focus) — F-206. Rend le focus. */
    @Transactional
    public SubjectJourney dismissClose(UUID userId, UUID workspaceId, Integer chantier) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId, chantier);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setCloseProposedAt(null);
        return focusAfter(save(journey, now), userId, workspaceId);
    }

    private SubjectJourney requireGuided(UUID userId, UUID workspaceId, Integer chantier) {
        SubjectJourney journey = target(userId, workspaceId, chantier);
        if (journey.getCreatedAt() == null || !journey.isGuided() || journey.getPhase() == null) {
            throw new InvalidJourneyException("Ce sujet n'est pas en mode guidé.");
        }
        return journey;
    }

    static final int MAX_DIAGNOSIS = 2_000;

    /** FAIBLE, MOYENNE ou ELEVEE ; une confiance illisible vaut MOYENNE. */
    static String confidenceOf(String raw) {
        if (raw == null) {
            return "MOYENNE";
        }
        String v = java.text.Normalizer.normalize(raw.strip().toUpperCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        if (v.startsWith("FAIB") || v.equals("LOW")) {
            return "FAIBLE";
        }
        if (v.startsWith("ELEV") || v.equals("HIGH") || v.startsWith("HAUT")) {
            return "ELEVEE";
        }
        return "MOYENNE";
    }

    private static JourneyPlan.StepStatus parseStepStatus(String raw) {
        String v = raw == null ? "" : java.text.Normalizer.normalize(raw.strip().toUpperCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return switch (v) {
            case "FAIT", "DONE" -> JourneyPlan.StepStatus.FAIT;
            case "VERIFIE", "VERIFIED", "OK" -> JourneyPlan.StepStatus.VERIFIE;
            case "ECHEC", "FAILED", "KO" -> JourneyPlan.StepStatus.ECHEC;
            case "BLOQUE", "BLOCKED" -> JourneyPlan.StepStatus.BLOQUE;
            case "A_FAIRE", "TODO" -> JourneyPlan.StepStatus.A_FAIRE;
            default -> throw new InvalidJourneyException("status est FAIT, VERIFIE, ECHEC, BLOQUE ou A_FAIRE.");
        };
    }

    /** Le résultat d'un plan posé : l'issue et la version courante. */
    public record PlanChange(PlanOutcome outcome, int version) {
    }

    /**
     * <b>L'agent pose (ou amende) le plan structuré du sujet</b> (SF-176-03). Le terminal est celui du
     * tour. Le sujet passe (ou revient) en phase <b>Plan</b> : rien de ce qui modifie ne passera la
     * porte avant que l'utilisateur ait validé cette version (Q2, Q3).
     *
     * <p>Une étape inchangée garde son avancement (fait, vérifié) : amender n'efface pas le travail
     * accompli.</p>
     */
    @Transactional
    public PlanChange setPlan(UUID userId, UUID workspaceId, JourneyPlan plan) {
        return setPlan(userId, workspaceId, null, plan);
    }

    /** Même geste, sur le chantier ouvert {@code chantier} ({@code null} = le focus) — F-206. */
    @Transactional
    public PlanChange setPlan(UUID userId, UUID workspaceId, Integer chantier, JourneyPlan plan) {
        SubjectJourney journey = target(userId, workspaceId, chantier);
        if (!journey.isGuided()) {
            return new PlanChange(PlanOutcome.NOT_GUIDED, journey.getPlanVersion());
        }
        if (journey.getPhase() == JourneyPhase.CLOS) {
            return new PlanChange(PlanOutcome.CLOSED, journey.getPlanVersion());
        }
        // SF-176-05 : on sort de l'Investigation par un diagnostic que l'utilisateur confirme.
        if (journey.getPhase() == JourneyPhase.INVESTIGATION) {
            return new PlanChange(PlanOutcome.NEEDS_DIAGNOSIS, journey.getPlanVersion());
        }
        if (plan == null || plan.isEmpty()) {
            return new PlanChange(PlanOutcome.EMPTY, journey.getPlanVersion());
        }
        JourneyPlan previous = JourneyPlan.fromJson(journey.getPlanJson());
        JourneyPlan carried = carryProgress(plan, previous);
        boolean amendment = journey.getValidatedVersion() != null;
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setPlanJson(carried.toJson());
        journey.setPlanVersion(journey.getPlanVersion() + 1);
        moveTo(journey, JourneyPhase.PLAN, now);
        SubjectJourney saved = save(journey, now);
        recordVersion(saved, carried, now);
        record(saved, amendment ? SubjectJourneyEvent.PLAN_AMENDED : SubjectJourneyEvent.PLAN_SET,
                "v" + saved.getPlanVersion() + " · " + carried.steps().size() + " étapes");
        return new PlanChange(amendment ? PlanOutcome.AMENDMENT : PlanOutcome.SET, saved.getPlanVersion());
    }

    /**
     * <b>[Valider le plan]</b> (SF-176-03, décision Q3) : un clic valide le plan entier. La version
     * validée doit être celle que l'utilisateur a sous les yeux — un plan amendé entre-temps se revalide.
     * Le sujet passe en <b>Exécution</b>.
     *
     * @param version la version vue à l'écran ; {@code null} = la version courante
     * @throws InvalidJourneyException sujet non guidé, pas en phase Plan, sans plan, ou version dépassée
     */
    @Transactional
    public SubjectJourney validatePlan(UUID userId, UUID workspaceId, Integer version) {
        return validatePlan(userId, workspaceId, null, version);
    }

    /** [Valider le plan] du chantier ouvert {@code chantier} ({@code null} = le focus) — F-206. Rend le focus. */
    @Transactional
    public SubjectJourney validatePlan(UUID userId, UUID workspaceId, Integer chantier, Integer version) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = target(userId, workspaceId, chantier);
        if (journey.getCreatedAt() == null) {
            throw new InvalidJourneyException("Ce sujet n'a pas de plan à valider.");
        }
        if (!journey.isGuided() || journey.getPhase() != JourneyPhase.PLAN) {
            throw new InvalidJourneyException("Il n'y a pas de plan en attente de validation.");
        }
        if (JourneyPlan.fromJson(journey.getPlanJson()).isEmpty()) {
            throw new InvalidJourneyException("Ce sujet n'a pas de plan à valider.");
        }
        if (version != null && version != journey.getPlanVersion()) {
            throw new InvalidJourneyException("Le plan a changé depuis (version " + journey.getPlanVersion()
                    + ") : relisez-le avant de le valider.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean amendment = journey.getValidatedVersion() != null;
        journey.setValidatedVersion(journey.getPlanVersion());
        journey.setValidatedPlanJson(journey.getPlanJson());
        journey.setPlanValidatedAt(now);
        moveTo(journey, JourneyPhase.EXECUTION, now);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.PLAN_VALIDATED,
                "v" + saved.getPlanVersion() + (amendment ? " · amendement" : ""));
        return focusAfter(saved, userId, workspaceId);
    }

    /** Les attentes F-175 dont dépendent les étapes ; absent = la clé seule est montrée. */
    private fr.claudegateway.atelier.actions.TerminalActionService terminalActions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTerminalActions(fr.claudegateway.atelier.actions.TerminalActionService terminalActions) {
        this.terminalActions = terminalActions;
    }

    /**
     * <b>L'état des attentes dont dépendent les étapes</b> (SF-176-03) : clé → {@code A_FAIRE},
     * {@code DEMANDE}, {@code FAIT}, {@code ANNULE}. Une clé sans attente n'y figure pas. Best-effort.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, String> waitsOn(UUID userId, fr.claudegateway.atelier.Workspace workspace,
            SubjectJourney journey) {
        java.util.Map<String, String> statuses = new java.util.LinkedHashMap<>();
        waitInfos(userId, workspace, journey).forEach((key, info) -> statuses.put(key, info.status()));
        return statuses;
    }

    /**
     * <b>Les attentes dont dépendent les étapes, détaillées</b> (F-220 / SF-220-01) : clé → état, qui doit agir,
     * à qui, depuis quand. Une clé sans attente n'y figure pas. Best-effort ; l'appelant a le terminal possédé.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, WaitInfo> waitInfos(UUID userId, fr.claudegateway.atelier.Workspace workspace,
            SubjectJourney journey) {
        java.util.Map<String, WaitInfo> infos = new java.util.LinkedHashMap<>();
        if (terminalActions == null || journey == null || workspace == null) {
            return infos;
        }
        // F-208 : les attentes de TOUS les chantiers ouverts — le tableau détaille chacun d'eux.
        java.util.List<JourneyPlan.Step> steps = new java.util.ArrayList<>(
                JourneyPlan.fromJson(journey.getPlanJson()).steps());
        for (SubjectJourneyChantier other : journey.getOtherOpenChantiers()) {
            steps.addAll(JourneyPlan.fromJson(other.getPlanJson()).steps());
        }
        for (JourneyPlan.Step step : steps) {
            if (step.waitsOn() == null || infos.containsKey(step.waitsOn())) {
                continue;
            }
            try {
                terminalActions.actionOfKey(userId, workspace, step.waitsOn())
                        .ifPresent(action -> infos.put(step.waitsOn(), waitInfo(action)));
            } catch (RuntimeException ex) {
                log.debug("Attente d'une étape illisible (best-effort) : {}", ex.getMessage());
            }
        }
        return infos;
    }

    private static WaitInfo waitInfo(fr.claudegateway.atelier.actions.TerminalAction action) {
        String who = action.getRequestedTo() != null && !action.getRequestedTo().isBlank()
                ? action.getRequestedTo() : action.getPerson();
        return new WaitInfo(action.getStatus() == null ? null : action.getStatus().name(),
                action.getQui() == null ? null : action.getQui().name(),
                who == null || who.isBlank() ? null : who.strip(),
                action.getRequestedAt() != null ? action.getRequestedAt() : action.getCreatedAt(),
                action.getId());
    }

    /** Même lecture détaillée, depuis la route : {@code requireOwned} d'abord. */
    @Transactional(readOnly = true)
    public java.util.Map<String, WaitInfo> waitInfos(UUID userId, UUID workspaceId, SubjectJourney journey) {
        return waitInfos(userId, workspaceService.requireOwned(userId, workspaceId), journey);
    }

    /** Même lecture, depuis la route : {@code requireOwned} d'abord. */
    @Transactional(readOnly = true)
    public java.util.Map<String, String> waitsOn(UUID userId, UUID workspaceId, SubjectJourney journey) {
        return waitsOn(userId, workspaceService.requireOwned(userId, workspaceId), journey);
    }

    /**
     * Une étape au contenu identique (même rang) garde l'avancement qu'elle avait. F-208 : à rang et titre
     * égaux, les dépendances et les sous-étapes de l'ancienne étape sont reportées si le nouveau plan n'en
     * donne pas — amender n'efface ni la structure ni le travail détaillé.
     */
    static JourneyPlan carryProgress(JourneyPlan next, JourneyPlan previous) {
        JourneyPlan result = next;
        for (int i = 0; i < next.steps().size() && i < previous.steps().size(); i++) {
            JourneyPlan.Step before = previous.steps().get(i);
            JourneyPlan.Step now = result.steps().get(i);
            boolean sameTitle = java.util.Objects.equals(before.title(), now.title());
            if (sameTitle && now.dependsOn().isEmpty() && !before.dependsOn().isEmpty()) {
                result = result.withDependsOn(i, before.dependsOn());
            }
            if (sameTitle && now.subSteps().isEmpty() && !before.subSteps().isEmpty()) {
                result = result.withSubSteps(i, before.subSteps());
            }
            if (sameTitle && now.actor() == null && before.actor() != null) {
                result = result.withActor(i, before.actor()); // SF-219-06 : renvoyer le plan n'efface pas l'acteur
            }
            // SF-219-06 : l'acteur n'entre pas dans la comparaison — le préciser garde l'avancement.
            if (java.util.Objects.equals(result.steps().get(i).work(), before.work())
                    && before.status() != JourneyPlan.StepStatus.A_FAIRE) {
                result = result.withStep(i, before.status(), before.evidence());
            }
        }
        return result;
    }

    /** Raison d'une proposition : une phrase. */
    static final int MAX_REASON = 300;

    /**
     * Applique un mode : passer en Guidé <b>ouvre un chantier</b> si le sujet n'a jamais été guidé ou si
     * le précédent est clos (SF-176-11) ; tout choix explicite de mode efface la proposition en attente.
     */
    private SubjectJourney applyMode(SubjectJourney journey, JourneyMode mode, OffsetDateTime now, String title) {
        journey.setMode(mode);
        journey.setGuidedProposedAt(null);
        journey.setGuidedProposalReason(null);
        if (mode == JourneyMode.GUIDE
                && (journey.getPhase() == null || journey.getPhase() == JourneyPhase.CLOS)) {
            openChantier(journey, title, now);
        }
        return save(journey, now);
    }

    // ------------------------------------------------------------------ SF-176-11 : les chantiers

    /** Titre d'un chantier : une phrase. */
    static final int MAX_TITLE = 200;

    /** Les chantiers clos du sujet ; absent = archivage désactivé (formes historiques, tests). */
    private SubjectJourneyChantierRepository chantiers;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChantiers(SubjectJourneyChantierRepository chantiers) {
        this.chantiers = chantiers;
    }

    /**
     * <b>Ouvre un nouveau chantier</b> (SF-176-11, D9) : numéro suivant, titre, Investigation, plan et
     * diagnostic <b>remis à zéro</b> — un nouveau chantier n'hérite jamais du plan de l'ancien. Un chantier
     * clos avant l'archivage (reprise) est archivé ici, avant d'être effacé.
     */
    void openChantier(SubjectJourney journey, String title, OffsetDateTime now) {
        if (journey.getPhase() == JourneyPhase.CLOS) {
            archive(journey, journey.getPhaseChangedAt() == null ? now : journey.getPhaseChangedAt());
        } else if (journey.hasOpenFocus()) {
            mirror(journey, now); // F-206 : le chantier courant reste OUVERT, intact, à côté du nouveau
        }
        int highest = chantiers == null || journey.getCreatedAt() == null ? 0
                : chantiers.maxNumber(journey.getUserId(), journey.getWorkspaceId());
        int number = Math.max(journey.getChantierNumber(), highest) + 1;
        String cleanTitle = JourneyPlan.bound(title, MAX_TITLE);
        journey.setChantierNumber(number);
        journey.setChantierTitle(cleanTitle == null ? "Chantier " + number : cleanTitle.replace('\n', ' '));
        journey.setChantierOpenedAt(now);
        journey.setPlanJson(null);
        journey.setPlanVersion(0);
        journey.setValidatedPlanJson(null);
        journey.setValidatedVersion(null);
        journey.setPlanValidatedAt(null);
        journey.setDiagnosis(null);
        journey.setDiagnosisEvidence(null);
        journey.setDiagnosisConfidence(null);
        journey.setDiagnosisProposedAt(null);
        journey.setCloseProposedAt(null);
        moveTo(journey, JourneyPhase.INVESTIGATION, now);
    }

    /**
     * Archive le chantier courant (titre, dates, diagnostic, plan validé final). Idempotent : un numéro
     * déjà archivé ne l'est pas deux fois.
     */
    void archive(SubjectJourney journey, OffsetDateTime closedAt) {
        if (chantiers == null) {
            return;
        }
        int number = Math.max(1, journey.getChantierNumber());
        if (journey.getChantierNumber() == 0) {
            journey.setChantierNumber(1); // parcours antérieur aux chantiers : c'était le premier
        }
        SubjectJourneyChantier row = journey.getParkedRow() != null ? journey.getParkedRow()
                : chantiers.findFirstByUserIdAndWorkspaceIdAndNumber(journey.getUserId(), journey.getWorkspaceId(),
                        number).orElse(null);
        if (row != null && !row.isOpen()) {
            return; // déjà archivé
        }
        if (row == null) {
            row = SubjectJourneyChantier.builder()
                    .userId(journey.getUserId())
                    .workspaceId(journey.getWorkspaceId())
                    .number(number)
                    .build();
        }
        boolean validated = journey.getValidatedPlanJson() != null;
        writeBack(journey, row, closedAt);
        row.setStatus(SubjectJourneyChantier.CLOSED);
        row.setTitle(journey.getChantierTitle() == null ? "Chantier " + number : journey.getChantierTitle());
        row.setOpenedAt(journey.getChantierOpenedAt() == null ? journey.getCreatedAt() : journey.getChantierOpenedAt());
        row.setClosedAt(closedAt);
        row.setPlanJson(validated ? journey.getValidatedPlanJson() : journey.getPlanJson());
        row.setPlanVersion(validated ? journey.getValidatedVersion() : journey.getPlanVersion());
        chantiers.save(row);
    }

    /** Les chantiers clos du sujet, le plus récent d'abord. 404 sur un terminal d'autrui. */
    @Transactional(readOnly = true)
    public java.util.List<SubjectJourneyChantier> closedChantiers(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        return chantiers == null ? java.util.List.of()
                : chantiers.findByUserIdAndWorkspaceIdAndStatusOrderByNumberDesc(userId, workspaceId,
                        SubjectJourneyChantier.CLOSED);
    }

    /** Combien de chantiers clos (l'en-tête dit « Chantiers : N — voir »). Lecture déjà possédée. */
    @Transactional(readOnly = true)
    public long closedChantierCount(UUID userId, UUID workspaceId) {
        return chantiers == null ? 0
                : chantiers.countByUserIdAndWorkspaceIdAndStatus(userId, workspaceId, SubjectJourneyChantier.CLOSED);
    }

    // ------------------------------------------------------------------ F-208 : l'historique des versions

    /** L'historique des versions du plan ; absent = pas d'historique (formes historiques, tests). */
    private SubjectJourneyPlanVersionRepository planVersions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlanVersions(SubjectJourneyPlanVersionRepository planVersions) {
        this.planVersions = planVersions;
    }

    /** Une version du plan, avec ce qui a changé depuis la précédente. */
    public record PlanVersionView(SubjectJourneyPlanVersion row, JourneyPlan plan, JourneyPlan.Changes changes) {
    }

    /**
     * Écrit la version posée dans l'historique (F-208 / SF-208-02). <b>Best-effort</b> : un historique qui
     * casse ne casse pas le plan qu'il raconte.
     */
    private void recordVersion(SubjectJourney journey, JourneyPlan plan, OffsetDateTime now) {
        if (planVersions == null) {
            return;
        }
        try {
            planVersions.save(SubjectJourneyPlanVersion.builder()
                    .userId(journey.getUserId())
                    .workspaceId(journey.getWorkspaceId())
                    .chantierNumber(Math.max(1, journey.getChantierNumber()))
                    .version(journey.getPlanVersion())
                    .planJson(plan.toJson())
                    .createdAt(now)
                    .build());
        } catch (RuntimeException ex) {
            log.debug("Historique du plan ignoré (best-effort) : {}", ex.getMessage());
        }
    }

    /**
     * Les versions du plan du chantier {@code chantier}, de la plus ancienne à la plus récente, chacune avec
     * ce qui a changé depuis la précédente (F-208 / SF-208-02). 404 sur un terminal d'autrui. Vide pour un
     * chantier sans historique (plans posés avant F-208).
     */
    @Transactional(readOnly = true)
    public java.util.List<PlanVersionView> planVersions(UUID userId, UUID workspaceId, int chantier) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        if (planVersions == null) {
            return java.util.List.of();
        }
        java.util.List<PlanVersionView> views = new java.util.ArrayList<>();
        JourneyPlan previous = null;
        for (SubjectJourneyPlanVersion row : planVersions
                .findByUserIdAndWorkspaceIdAndChantierNumberOrderByVersionAscCreatedAtAsc(userId, workspaceId,
                        chantier)) {
            JourneyPlan plan = JourneyPlan.fromJson(row.getPlanJson());
            views.add(new PlanVersionView(row, plan, JourneyPlan.changes(previous, plan)));
            previous = plan;
        }
        return java.util.List.copyOf(views);
    }

    // ------------------------------------------------------------------ F-206 : plusieurs chantiers ouverts

    /**
     * <b>Ouvre un chantier de plus</b>, à côté des autres (F-206) — le geste de l'utilisateur. Le sujet
     * doit déjà être guidé avec un chantier ouvert (le premier s'ouvre depuis le menu). Le nouveau
     * chantier prend le focus ; les autres restent ouverts, intacts.
     *
     * @throws InvalidJourneyException sujet non guidé
     */
    @Transactional
    public SubjectJourney openAlongside(UUID userId, UUID workspaceId, String title) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = find(userId, workspaceId).orElse(null);
        if (journey == null || !journey.isGuided() || !journey.hasOpenFocus()) {
            throw new InvalidJourneyException("Passez d'abord le sujet en mode guidé : le premier chantier "
                    + "s'ouvre depuis le menu.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        openChantier(journey, title, now);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.CHANTIER_OPENED, "chantier " + saved.getChantierNumber());
        return withOpenChantiers(saved);
    }

    /**
     * <b>Bascule</b> le focus sur le chantier ouvert {@code chantier} (F-206) : son état est rechargé tel
     * quel ; le chantier quitté reste ouvert, intact. Sans effet sur le focus courant.
     *
     * @throws InvalidJourneyException le chantier n'est pas ouvert
     */
    @Transactional
    public SubjectJourney focus(UUID userId, UUID workspaceId, int chantier) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = find(userId, workspaceId).orElse(null);
        if (journey != null && journey.hasOpenFocus() && journey.getChantierNumber() == chantier) {
            return withOpenChantiers(journey);
        }
        SubjectJourneyChantier row = journey == null ? null : openRow(journey, chantier);
        if (row == null) {
            throw notOpen(chantier);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (journey.hasOpenFocus()) {
            mirror(journey, now); // le chantier quitté reste ouvert, à jour
        }
        loadFocus(journey, row);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.CHANTIER_FOCUSED, "chantier " + chantier);
        return withOpenChantiers(saved);
    }

    /**
     * Le parcours, avec la liste de ses <b>autres</b> chantiers ouverts (F-206) — ce que la porte, la note
     * du tour, le Cap et l'écran lisent. Lecture déjà possédée.
     */
    public SubjectJourney withOpenChantiers(SubjectJourney journey) {
        if (journey == null || journey.getParkedRow() != null) {
            return journey;
        }
        journey.setOtherOpenChantiers(journey.getCreatedAt() == null ? java.util.List.of()
                : java.util.List.copyOf(openRows(journey)));
        return journey;
    }

    /** Les chantiers ouverts autres que le focus, par numéro croissant. */
    private java.util.List<SubjectJourneyChantier> openRows(SubjectJourney journey) {
        if (chantiers == null) {
            return java.util.List.of();
        }
        java.util.List<SubjectJourneyChantier> rows = new java.util.ArrayList<>();
        for (SubjectJourneyChantier row : chantiers.findByUserIdAndWorkspaceIdAndStatusOrderByNumberAsc(
                journey.getUserId(), journey.getWorkspaceId(), SubjectJourneyChantier.OPEN)) {
            if (row.getNumber() != journey.getChantierNumber()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private SubjectJourneyChantier openRow(SubjectJourney journey, int number) {
        if (chantiers == null) {
            return null;
        }
        return chantiers.findFirstByUserIdAndWorkspaceIdAndNumber(journey.getUserId(), journey.getWorkspaceId(), number)
                .filter(SubjectJourneyChantier::isOpen)
                .orElse(null);
    }

    private static InvalidJourneyException notOpen(int number) {
        return new InvalidJourneyException("Le chantier " + number + " n'est pas ouvert.");
    }

    /**
     * Le chantier visé par un geste (F-206) : le focus ({@code chantier} absent ou égal au focus), ou une
     * <b>vue de travail</b> d'un autre chantier ouvert — même mode que le sujet, enregistrée dans sa propre
     * ligne, jamais comme parcours.
     *
     * @throws InvalidJourneyException le chantier n'est pas ouvert
     */
    SubjectJourney target(UUID userId, UUID workspaceId, Integer chantier) {
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
        if (chantier == null || chantier == journey.getChantierNumber()) {
            return journey;
        }
        SubjectJourneyChantier row = journey.getCreatedAt() == null ? null : openRow(journey, chantier);
        if (row == null) {
            throw notOpen(chantier);
        }
        SubjectJourney view = SubjectJourney.builder()
                .workspaceId(journey.getWorkspaceId())
                .userId(journey.getUserId())
                .mode(journey.getMode())
                .createdAt(journey.getCreatedAt())
                .updatedAt(journey.getUpdatedAt())
                .build();
        copyInto(row, view);
        view.setParkedRow(row);
        return view;
    }

    /** Le focus à rendre après un geste : lui-même, ou relu si le geste visait un autre chantier. */
    private SubjectJourney focusAfter(SubjectJourney target, UUID userId, UUID workspaceId) {
        SubjectJourney focus = target.getParkedRow() == null ? target
                : find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
        return withOpenChantiers(focus);
    }

    /** Met un chantier ouvert au focus : le parcours en devient la copie de travail. */
    private static void loadFocus(SubjectJourney journey, SubjectJourneyChantier row) {
        copyInto(row, journey);
    }

    private static void copyInto(SubjectJourneyChantier row, SubjectJourney journey) {
        journey.setChantierNumber(row.getNumber());
        journey.setChantierTitle(row.getTitle());
        journey.setChantierOpenedAt(row.getOpenedAt());
        journey.setPhase(row.getPhase());
        journey.setPhaseChangedAt(row.getPhaseChangedAt());
        journey.setPlanJson(row.getPlanJson());
        journey.setPlanVersion(row.getPlanVersion() == null ? 0 : row.getPlanVersion());
        journey.setValidatedPlanJson(row.getValidatedPlanJson());
        journey.setValidatedVersion(row.getValidatedVersion());
        journey.setPlanValidatedAt(row.getPlanValidatedAt());
        journey.setDiagnosis(row.getDiagnosis());
        journey.setDiagnosisEvidence(row.getDiagnosisEvidence());
        journey.setDiagnosisConfidence(row.getDiagnosisConfidence());
        journey.setDiagnosisProposedAt(row.getDiagnosisProposedAt());
        journey.setCloseProposedAt(row.getCloseProposedAt());
    }

    /** Recopie l'état d'un chantier (focus ou vue) dans sa ligne ; le statut n'y est jamais touché. */
    private static void writeBack(SubjectJourney journey, SubjectJourneyChantier row, OffsetDateTime now) {
        row.setTitle(journey.getChantierTitle());
        row.setOpenedAt(journey.getChantierOpenedAt());
        row.setPhase(journey.getPhase());
        row.setPhaseChangedAt(journey.getPhaseChangedAt());
        row.setPlanJson(journey.getPlanJson());
        row.setPlanVersion(journey.getPlanVersion());
        row.setValidatedPlanJson(journey.getValidatedPlanJson());
        row.setValidatedVersion(journey.getValidatedVersion());
        row.setPlanValidatedAt(journey.getPlanValidatedAt());
        row.setDiagnosis(journey.getDiagnosis());
        row.setDiagnosisEvidence(journey.getDiagnosisEvidence());
        row.setDiagnosisConfidence(journey.getDiagnosisConfidence());
        row.setDiagnosisProposedAt(journey.getDiagnosisProposedAt());
        row.setCloseProposedAt(journey.getCloseProposedAt());
        row.setUpdatedAt(now);
    }

    /**
     * Recopie le chantier en focus, ouvert, dans sa ligne {@code OPEN} (F-206) — créée à la première
     * écriture (reprise des parcours d'avant F-206). Une archive close n'est jamais rouverte ici.
     */
    void mirror(SubjectJourney journey, OffsetDateTime now) {
        if (chantiers == null || journey.getParkedRow() != null || journey.getChantierNumber() <= 0
                || !journey.hasOpenFocus()) {
            return;
        }
        SubjectJourneyChantier row = chantiers.findFirstByUserIdAndWorkspaceIdAndNumber(journey.getUserId(),
                journey.getWorkspaceId(), journey.getChantierNumber()).orElse(null);
        if (row != null && !row.isOpen()) {
            return;
        }
        if (row == null) {
            row = SubjectJourneyChantier.builder()
                    .userId(journey.getUserId())
                    .workspaceId(journey.getWorkspaceId())
                    .number(journey.getChantierNumber())
                    .status(SubjectJourneyChantier.OPEN)
                    .build();
        }
        writeBack(journey, row, now);
        chantiers.save(row);
    }


    // ------------------------------------------------------------------ interne

    Optional<SubjectJourney> find(UUID userId, UUID workspaceId) {
        return repository.findByUserIdAndWorkspaceId(userId, workspaceId);
    }

    /** Un parcours non encore écrit : Libre, sans phase. {@code createdAt == null} le distingue. */
    static SubjectJourney blank(UUID userId, UUID workspaceId) {
        return SubjectJourney.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .mode(JourneyMode.LIBRE)
                .planVersion(0)
                .build();
    }

    void moveTo(SubjectJourney journey, JourneyPhase phase, OffsetDateTime now) {
        journey.setPhase(phase);
        journey.setPhaseChangedAt(now);
    }

    SubjectJourney save(SubjectJourney journey, OffsetDateTime now) {
        if (journey.getParkedRow() != null) {
            // F-206 : la vue d'un chantier qui n'est pas le focus s'enregistre dans SA ligne, jamais
            // comme parcours (même clé que le focus : ce serait l'écraser).
            writeBack(journey, journey.getParkedRow(), now);
            chantiers.save(journey.getParkedRow());
            return journey;
        }
        if (journey.getCreatedAt() == null) {
            journey.setCreatedAt(now);
        }
        journey.setUpdatedAt(now);
        SubjectJourney saved = repository.save(journey);
        mirror(saved, now);
        return saved;
    }

    /**
     * Journalise un geste du parcours (mesure SF-176-06). <b>Best-effort</b> : un journal qui casse ne
     * doit jamais casser le geste qu'il raconte.
     */
    void record(SubjectJourney journey, String type, String detail) {
        try {
            events.save(SubjectJourneyEvent.builder()
                    .userId(journey.getUserId())
                    .workspaceId(journey.getWorkspaceId())
                    .type(type)
                    .mode(journey.getMode() == null ? null : journey.getMode().name())
                    .phase(journey.getPhase() == null ? null : journey.getPhase().name())
                    .detail(detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail))
                    .createdAt(OffsetDateTime.now(clock))
                    .build());
        } catch (RuntimeException ex) {
            log.debug("Journal du parcours ignoré (best-effort) : {}", ex.getMessage());
        }
    }
}
