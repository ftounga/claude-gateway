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
        if (journey.getMode() == mode && journey.getCreatedAt() != null) {
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
