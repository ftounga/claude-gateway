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
        return find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
    }

    /**
     * Le parcours tel que le tour le lit — <b>sans</b> repasser par {@code requireOwned} : l'appelant
     * (la boucle de l'agent) a déjà le terminal possédé du tour. Libre si rien n'est décidé.
     */
    @Transactional(readOnly = true)
    public SubjectJourney forTurn(UUID userId, UUID workspaceId) {
        return find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
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
        SubjectJourney saved = applyMode(journey, mode, OffsetDateTime.now(clock));
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
        SubjectJourney saved = applyMode(journey, JourneyMode.GUIDE, OffsetDateTime.now(clock));
        record(saved, SubjectJourneyEvent.GUIDED_ACCEPTED, null);
        return saved;
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
        journey.setGuidedDeclinedAt(now);
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
        DECLINED
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
            return ProposalOutcome.ALREADY_GUIDED;
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
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
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
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId);
        if (journey.getPhase() != JourneyPhase.INVESTIGATION || journey.getDiagnosisProposedAt() == null) {
            throw new InvalidJourneyException("Aucun diagnostic n'attend de confirmation.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setDiagnosisProposedAt(null);
        moveTo(journey, JourneyPhase.PLAN, now);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.DIAGNOSIS_CONFIRMED, saved.getDiagnosisConfidence());
        return saved;
    }

    /** [Continuer l'investigation] : le diagnostic reste lisible, le sujet reste en Investigation. */
    @Transactional
    public SubjectJourney dismissDiagnosis(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setDiagnosisProposedAt(null);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.DIAGNOSIS_DISMISSED, null);
        return saved;
    }

    /** Ce que l'agent apprend en faisant avancer une étape. */
    public enum StepOutcome { UPDATED, TO_VERIFICATION, ALL_VERIFIED, FAILED, NOT_EXECUTING, NO_SUCH_STEP,
        NOT_GUIDED }

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
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
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
        JourneyPlan.StepStatus status = parseStepStatus(rawStatus);
        String cleanEvidence = JourneyPlan.bound(evidence, JourneyPlan.MAX_EVIDENCE);
        if (status != JourneyPlan.StepStatus.FAIT && cleanEvidence == null) {
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
        } else if (updated.steps().stream().allMatch(s -> s.status() == JourneyPlan.StepStatus.VERIFIE)) {
            if (journey.getPhase() != JourneyPhase.VERIFICATION) {
                moveTo(journey, JourneyPhase.VERIFICATION, now);
            }
            journey.setCloseProposedAt(now);
            outcome = StepOutcome.ALL_VERIFIED;
        } else if (journey.getPhase() == JourneyPhase.EXECUTION && updated.steps().stream()
                .noneMatch(s -> s.status() == JourneyPlan.StepStatus.A_FAIRE)) {
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

    /**
     * <b>Retour en Investigation</b> (SF-176-05) : une découverte contredit le diagnostic, ou une
     * vérification a échoué. Le plan (et sa dernière version validée) est gardé ; la porte se referme.
     *
     * @throws InvalidJourneyException raison vide
     */
    @Transactional
    public boolean reopenInvestigation(UUID userId, UUID workspaceId, String reason) {
        String cleanReason = JourneyPlan.bound(reason, MAX_REASON);
        if (cleanReason == null) {
            throw new InvalidJourneyException("reason est requise : ce qui contredit le diagnostic.");
        }
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
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

    /** [Clore le sujet] (SF-176-05) : le sujet guidé est clos ; la porte reste fermée. */
    @Transactional
    public SubjectJourney close(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setCloseProposedAt(null);
        journey.setDiagnosisProposedAt(null);
        moveTo(journey, JourneyPhase.CLOS, now);
        SubjectJourney saved = save(journey, now);
        record(saved, SubjectJourneyEvent.CLOSED, null);
        return saved;
    }

    /** [Pas encore] : la proposition de clôture est écartée (SF-176-05). */
    @Transactional
    public SubjectJourney dismissClose(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = requireGuided(userId, workspaceId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        journey.setCloseProposedAt(null);
        return save(journey, now);
    }

    private SubjectJourney requireGuided(UUID userId, UUID workspaceId) {
        SubjectJourney journey = find(userId, workspaceId)
                .orElseThrow(() -> new InvalidJourneyException("Ce sujet n'est pas en mode guidé."));
        if (!journey.isGuided() || journey.getPhase() == null) {
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
            default -> throw new InvalidJourneyException("status est FAIT, VERIFIE ou ECHEC.");
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
        SubjectJourney journey = find(userId, workspaceId).orElseGet(() -> blank(userId, workspaceId));
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
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        SubjectJourney journey = find(userId, workspaceId)
                .orElseThrow(() -> new InvalidJourneyException("Ce sujet n'a pas de plan à valider."));
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
        return saved;
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
        if (terminalActions == null || journey == null || workspace == null) {
            return statuses;
        }
        for (JourneyPlan.Step step : JourneyPlan.fromJson(journey.getPlanJson()).steps()) {
            if (step.waitsOn() == null || statuses.containsKey(step.waitsOn())) {
                continue;
            }
            try {
                terminalActions.statusOfKey(userId, workspace, step.waitsOn())
                        .ifPresent(status -> statuses.put(step.waitsOn(), status.name()));
            } catch (RuntimeException ex) {
                log.debug("Attente d'une étape illisible (best-effort) : {}", ex.getMessage());
            }
        }
        return statuses;
    }

    /** Même lecture, depuis la route : {@code requireOwned} d'abord. */
    @Transactional(readOnly = true)
    public java.util.Map<String, String> waitsOn(UUID userId, UUID workspaceId, SubjectJourney journey) {
        return waitsOn(userId, workspaceService.requireOwned(userId, workspaceId), journey);
    }

    /** Une étape au contenu identique (même rang) garde l'avancement qu'elle avait. */
    static JourneyPlan carryProgress(JourneyPlan next, JourneyPlan previous) {
        JourneyPlan result = next;
        for (int i = 0; i < next.steps().size() && i < previous.steps().size(); i++) {
            JourneyPlan.Step before = previous.steps().get(i);
            if (!next.changedSince(previous, i) && before.status() != JourneyPlan.StepStatus.A_FAIRE) {
                result = result.withStep(i, before.status(), before.evidence());
            }
        }
        return result;
    }

    /** Raison d'une proposition : une phrase. */
    static final int MAX_REASON = 300;

    /**
     * Applique un mode : passer en Guidé ouvre l'Investigation si le sujet n'a jamais été guidé ou était
     * clos ; tout choix explicite de mode efface la proposition en attente.
     */
    private SubjectJourney applyMode(SubjectJourney journey, JourneyMode mode, OffsetDateTime now) {
        journey.setMode(mode);
        journey.setGuidedProposedAt(null);
        journey.setGuidedProposalReason(null);
        if (mode == JourneyMode.GUIDE
                && (journey.getPhase() == null || journey.getPhase() == JourneyPhase.CLOS)) {
            moveTo(journey, JourneyPhase.INVESTIGATION, now);
        }
        return save(journey, now);
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
        if (journey.getCreatedAt() == null) {
            journey.setCreatedAt(now);
        }
        journey.setUpdatedAt(now);
        return repository.save(journey);
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
