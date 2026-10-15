package fr.claudegateway.atelier.routine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRequest;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineView;
import fr.claudegateway.atelier.routine.RoutineExceptions.InvalidRoutineException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineConflictException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineNotFoundException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineUnsupportedException;

/**
 * <b>Déclarer une routine</b> (F-183 / SF-183-01, D1) : créer, relire, modifier, mettre en pause,
 * supprimer — et la proposition de l'agent, validée par l'utilisateur.
 *
 * <p><b>Isolation.</b> Chaque geste commence par {@code requireOwned(userId, workspaceId)} (404
 * indiscernable pour un sujet d'autrui), puis relit la routine par {@code id + user_id +
 * workspace_id}.</p>
 *
 * <p><b>Le créneau passé ne part pas.</b> À la création, au changement de rythme et à l'activation,
 * {@code last_slot_at} est posé sur le créneau dû : la routine partira au <b>prochain</b> créneau.</p>
 */
@Service
public class RoutineService {

    /** Routines au plus par sujet : au-delà, c'est un planning, pas une routine. */
    public static final int MAX_PER_SUBJECT = 10;
    public static final int MAX_NAME = 80;
    public static final int MAX_INSTRUCTION = 4000;
    public static final BigDecimal DEFAULT_MAX_RUN_USD = new BigDecimal("5.00");
    public static final BigDecimal MIN_MAX_RUN_USD = new BigDecimal("0.10");
    public static final BigDecimal MAX_MAX_RUN_USD = new BigDecimal("50.00");
    public static final String DEFAULT_TIME_ZONE = "Europe/Paris";
    public static final BigDecimal DEFAULT_MONTHLY_BUDGET_USD = new BigDecimal("100.00");
    public static final BigDecimal MIN_MONTHLY_BUDGET_USD = new BigDecimal("1.00");
    public static final BigDecimal MAX_MONTHLY_BUDGET_USD = new BigDecimal("2000.00");

    private final WorkspaceService workspaceService;
    private final SubjectRoutineRepository routines;
    private final Clock clock;
    /** Le journal des exécutions (SF-183-02) ; absent des formes historiques / tests unitaires. */
    private RoutineRunService runs;

    public RoutineService(WorkspaceService workspaceService, SubjectRoutineRepository routines, Clock clock) {
        this.workspaceService = workspaceService;
        this.routines = routines;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRunService(RoutineRunService runs) {
        this.runs = runs;
    }

    /** Les routines du sujet, dans l'ordre de leur déclaration. */
    @Transactional(readOnly = true)
    public List<RoutineView> list(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return routines.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId).stream()
                .map(routine -> view(routine, now))
                .toList();
    }

    /** Déclare une routine depuis l'écran du sujet. */
    @Transactional
    public RoutineView create(UUID userId, UUID workspaceId, RoutineRequest request) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        requireSubjectOnHost(workspace);
        requireRoom(userId, workspaceId);
        SubjectRoutine routine = SubjectRoutine.builder()
                .userId(userId).workspaceId(workspaceId).origin(RoutineOrigin.USER).build();
        apply(routine, request, true);
        routine.setProposed(false);
        return save(routine, true);
    }

    /**
     * Modifie une routine : un champ absent garde sa valeur. Un changement de rythme, d'heure ou de
     * fuseau, ou une réactivation, repose le créneau consommé sur le créneau dû.
     */
    @Transactional
    public RoutineView update(UUID userId, UUID workspaceId, UUID routineId, RoutineRequest request) {
        workspaceService.requireOwned(userId, workspaceId);
        SubjectRoutine routine = require(userId, workspaceId, routineId);
        String before = scheduleKey(routine);
        boolean wasScheduled = routine.isScheduled();
        apply(routine, request, false);
        boolean reslot = !before.equals(scheduleKey(routine)) || (!wasScheduled && routine.isScheduled());
        return save(routine, reslot);
    }

    /** Supprime une routine — et refuse ainsi une proposition de l'agent. */
    @Transactional
    public void delete(UUID userId, UUID workspaceId, UUID routineId) {
        workspaceService.requireOwned(userId, workspaceId);
        routines.delete(require(userId, workspaceId, routineId));
    }

    /** Valide une proposition de l'agent : elle devient une routine active. */
    @Transactional
    public RoutineView accept(UUID userId, UUID workspaceId, UUID routineId) {
        workspaceService.requireOwned(userId, workspaceId);
        SubjectRoutine routine = require(userId, workspaceId, routineId);
        if (!routine.isProposed()) {
            throw new RoutineConflictException("routine_conflict", "Cette routine n'est pas une proposition.");
        }
        routine.setProposed(false);
        routine.setActive(true);
        return save(routine, true);
    }

    /**
     * La proposition de l'agent (outil {@code routine_proposer}) : inactive tant que l'utilisateur ne
     * l'a pas validée — l'agent ne programme jamais rien seul.
     */
    @Transactional
    public RoutineView propose(UUID userId, Workspace workspace, RoutineRequest request) {
        if (workspace == null || !userId.equals(workspace.getUserId())) {
            throw new RoutineNotFoundException();
        }
        requireSubjectOnHost(workspace);
        requireRoom(userId, workspace.getId());
        SubjectRoutine routine = SubjectRoutine.builder()
                .userId(userId).workspaceId(workspace.getId()).origin(RoutineOrigin.AGENT).build();
        apply(routine, request, true);
        routine.setProposed(true);
        routine.setActive(false);
        return save(routine, true);
    }

    /** L'historique d'une routine (SF-183-02) : ses 20 dernières exécutions. */
    @Transactional(readOnly = true)
    public List<RoutineDtos.RoutineRunView> history(UUID userId, UUID workspaceId, UUID routineId) {
        workspaceService.requireOwned(userId, workspaceId);
        require(userId, workspaceId, routineId);
        return runs == null ? List.of() : runs.history(userId, workspaceId, routineId);
    }

    /** La routine de ce propriétaire et de ce sujet, ou 404. */
    public SubjectRoutine require(UUID userId, UUID workspaceId, UUID routineId) {
        return routines.findByIdAndUserIdAndWorkspaceId(routineId, userId, workspaceId)
                .orElseThrow(RoutineNotFoundException::new);
    }

    /**
     * Une routine tourne dans le terminal d'un <b>sujet sur poste</b> : ni bac à sable hébergé (hors
     * périmètre « exécution hébergée sans runner »), ni terminal du poste (hors périmètre « routines
     * au poste » — le poste aiguille, il ne travaille pas).
     */
    public static boolean isRoutineCapable(Workspace workspace) {
        return workspace != null && workspace.isRunnerTarget() && workspace.getHostId() != null
                && !workspace.isHostTerminal();
    }

    private static void requireSubjectOnHost(Workspace workspace) {
        if (!isRoutineCapable(workspace)) {
            throw new RoutineUnsupportedException(
                    "Une routine tourne sur le poste d'un sujet : ce terminal ne peut pas en porter.");
        }
    }

    private void requireRoom(UUID userId, UUID workspaceId) {
        if (routines.countByUserIdAndWorkspaceId(userId, workspaceId) >= MAX_PER_SUBJECT) {
            throw new RoutineConflictException("routine_limit",
                    "Ce sujet a déjà " + MAX_PER_SUBJECT + " routines : supprimez-en une d'abord.");
        }
    }

    private RoutineView save(SubjectRoutine routine, boolean reslot) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (reslot) {
            routine.setLastSlotAt(RoutineSlots.dueSlot(routine, now).orElse(null));
        }
        if (routine.getCreatedAt() == null) {
            routine.setCreatedAt(now);
        }
        routine.setUpdatedAt(now);
        return view(routines.save(routine), now);
    }

    /** La vue d'une routine : dernière exécution et dépense du mois quand le journal est branché. */
    private RoutineView view(SubjectRoutine routine, OffsetDateTime now) {
        if (runs == null || routine.getId() == null) {
            return RoutineView.of(routine, now);
        }
        return RoutineView.of(routine, now, runs.last(routine.getUserId(), routine.getId()),
                runs.monthSpent(routine, now));
    }

    private static String scheduleKey(SubjectRoutine routine) {
        return routine.getCadence() + "|" + routine.getDayOfWeek() + "|" + routine.getRunTime() + "|"
                + routine.getTimeZone();
    }

    /**
     * Applique la requête à la routine et valide le résultat. {@code creating} : les champs absents
     * prennent leur défaut ; sinon ils gardent leur valeur.
     */
    static void apply(SubjectRoutine routine, RoutineRequest request, boolean creating) {
        if (request == null) {
            throw new InvalidRoutineException("Requête vide.");
        }
        if (request.name() != null || creating) {
            routine.setName(text(request.name(), "Le nom", MAX_NAME));
        }
        if (request.instruction() != null || creating) {
            routine.setInstruction(text(request.instruction(), "La consigne", MAX_INSTRUCTION));
        }
        if (request.cadence() != null || creating) {
            routine.setCadence(cadence(request.cadence()));
        }
        if (request.dayOfWeek() != null) {
            routine.setDayOfWeek(request.dayOfWeek());
        }
        if (routine.getCadence() == RoutineCadence.WEEKLY) {
            Integer day = routine.getDayOfWeek();
            if (day == null || day < 1 || day > 7) {
                throw new InvalidRoutineException("Une routine hebdomadaire a un jour : 1 (lundi) à 7 (dimanche).");
            }
        } else {
            routine.setDayOfWeek(null);
        }
        if (request.runTime() != null || creating) {
            LocalTime time = RoutineSlots.parseTime(request.runTime())
                    .orElseThrow(() -> new InvalidRoutineException("L'heure s'écrit HH:mm (ex. 08:00)."));
            routine.setRunTime(String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute()));
        }
        if (request.timeZone() != null || creating) {
            String raw = request.timeZone() == null || request.timeZone().isBlank()
                    ? DEFAULT_TIME_ZONE : request.timeZone().strip();
            ZoneId zone = RoutineSlots.parseZone(raw)
                    .orElseThrow(() -> new InvalidRoutineException("Fuseau horaire inconnu : " + raw + "."));
            routine.setTimeZone(zone.getId());
        }
        if (request.maxRunUsd() != null || creating) {
            BigDecimal amount = request.maxRunUsd() == null ? DEFAULT_MAX_RUN_USD : request.maxRunUsd();
            if (amount.compareTo(MIN_MAX_RUN_USD) < 0 || amount.compareTo(MAX_MAX_RUN_USD) > 0) {
                throw new InvalidRoutineException("Le budget par exécution va de 0,10 $ à 50,00 $.");
            }
            routine.setMaxRunUsd(amount.setScale(2, RoundingMode.HALF_UP));
        }
        if (request.monthlyBudgetUsd() != null || creating) {
            BigDecimal monthly = request.monthlyBudgetUsd() == null
                    ? DEFAULT_MONTHLY_BUDGET_USD : request.monthlyBudgetUsd();
            if (monthly.compareTo(MIN_MONTHLY_BUDGET_USD) < 0 || monthly.compareTo(MAX_MONTHLY_BUDGET_USD) > 0) {
                throw new InvalidRoutineException("Le budget mensuel va de 1,00 $ à 2 000,00 $.");
            }
            routine.setMonthlyBudgetUsd(monthly.setScale(2, RoundingMode.HALF_UP));
        }
        if (routine.getMonthlyBudgetUsd() != null && routine.getMaxRunUsd() != null
                && routine.getMonthlyBudgetUsd().compareTo(routine.getMaxRunUsd()) < 0) {
            throw new InvalidRoutineException("Le budget mensuel ne peut pas être inférieur au budget par exécution.");
        }
        if (request.active() != null) {
            routine.setActive(request.active());
        } else if (creating) {
            routine.setActive(true);
        }
    }

    private static String text(String value, String label, int max) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            throw new InvalidRoutineException(label + " est obligatoire.");
        }
        if (stripped.length() > max) {
            throw new InvalidRoutineException(label + " dépasse " + max + " caractères.");
        }
        return stripped;
    }

    private static RoutineCadence cadence(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRoutineException("Le rythme est obligatoire : DAILY, WEEKDAYS ou WEEKLY.");
        }
        try {
            return RoutineCadence.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRoutineException("Rythme inconnu : " + value.strip()
                    + " (DAILY, WEEKDAYS ou WEEKLY).");
        }
    }
}
