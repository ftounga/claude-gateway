package fr.claudegateway.runner.update;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerUpdateFrameEvent;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostNotFoundException;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.runner.host.RunnerUpdateAdvisor;
import fr.claudegateway.runner.host.dto.RunnerUpdateView;
import fr.claudegateway.runner.relay.RunnerRelayBroadcaster;
import fr.claudegateway.runner.update.RunnerUpdateJournalEntry.State;
import fr.claudegateway.user.UserRole;

/**
 * <b>La mise à jour du runner d'un clic</b> (F-111 / SF-111-04), côté gateway : autoriser, vérifier
 * qu'elle a un sens, journaliser, remettre la commande au runner, puis suivre ce qu'il en dit.
 *
 * <p><b>Qui peut déclencher</b> : le propriétaire du poste, ou un ADMIN (qui a tous les droits). Le poste
 * d'autrui est indiscernable d'un poste inconnu pour tous les autres (404, règle F-48).</p>
 *
 * <p><b>La gateway ne fait rien installer.</b> Elle dit au runner quelle version prendre ; c'est lui qui
 * la télécharge, vérifie sa signature avec sa clé embarquée et décide (SF-111-03).</p>
 */
@Service
public class RunnerUpdateService {

    private static final Logger log = LoggerFactory.getLogger(RunnerUpdateService.class);

    private final RunnerHostRepository hosts;
    private final RunnerUpdateAdvisor advisor;
    private final RunnerUpdateArtifacts artifacts;
    private final RunnerUpdateJournalRepository journal;
    private final RunnerCallDispatcher dispatcher;
    private final RunnerRelayBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public RunnerUpdateService(RunnerHostRepository hosts, RunnerUpdateAdvisor advisor,
            RunnerUpdateArtifacts artifacts, RunnerUpdateJournalRepository journal, RunnerCallDispatcher dispatcher,
            RunnerRelayBroadcaster broadcaster, ObjectMapper objectMapper) {
        this.hosts = hosts;
        this.advisor = advisor;
        this.artifacts = artifacts;
        this.journal = journal;
        this.dispatcher = dispatcher;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    /**
     * Demande la mise à jour du runner d'un poste.
     *
     * @param caller l'utilisateur authentifié (propriétaire ou ADMIN)
     * @param force  vrai pour « Forcer » : relance une mise à jour active sans attendre le calme
     * @return l'état de la mise à jour
     * @throws RunnerHostNotFoundException           poste inconnu, ou d'autrui pour un non-ADMIN
     * @throws RunnerUpdateNotPossibleException      rien à mettre à jour d'un clic (motif)
     * @throws RunnerUpdateInProgressException       une mise à jour est déjà en cours (sans force)
     * @throws RunnerUpdateUndeliveredException      le runner n'a pas pu être joint
     */
    @Transactional(noRollbackFor = RunnerUpdateUndeliveredException.class)
    public RunnerUpdateProgress request(AuthenticatedUser caller, UUID hostId, boolean force) {
        RunnerHost host = authorized(caller, hostId);
        OffsetDateTime now = OffsetDateTime.now();
        Optional<RunnerUpdateJournalEntry> active = journal.findFirstByHostIdOrderByRequestedAtDesc(hostId)
                .filter(entry -> RunnerUpdateProgress.of(entry, now).active());

        RunnerUpdateJournalEntry entry;
        if (active.isPresent()) {
            if (!force) {
                throw new RunnerUpdateInProgressException(
                        "Une mise à jour vers " + active.get().getToVersion() + " est déjà en cours sur ce poste.");
            }
            entry = active.get();
            entry.setForced(true);
            entry.setUpdatedAt(now);
        } else {
            RunnerUpdateView view = advisor.advise(host, false);
            requirePossible(view, host);
            entry = journal.save(RunnerUpdateJournalEntry.builder()
                    .userId(host.getUserId())
                    .hostId(hostId)
                    .requestedBy(caller.id())
                    .fromVersion(host.getRunnerVersion())
                    .toVersion(view.servedId())
                    .forced(force)
                    .state(State.REQUESTED)
                    .requestedAt(now)
                    .updatedAt(now)
                    .build());
        }

        String frame = updateFrame(entry, force);
        boolean delivered = dispatcher.sendControl(hostId, frame) || broadcaster.broadcastControl(hostId, frame);
        if (!delivered) {
            entry.moveTo(State.FAILED, "Le runner de ce poste n'a pas pu être joint.", now);
            journal.save(entry);
            throw new RunnerUpdateUndeliveredException("Le runner de ce poste n'est pas joignable : connectez-le, "
                    + "puis relancez la mise à jour.");
        }
        log.info("Mise à jour du runner demandée (poste={}, demandeur={}, de={}, vers={}, forcée={})", hostId,
                caller.id(), entry.getFromVersion(), entry.getToVersion(), force);
        return RunnerUpdateProgress.of(entry, now);
    }

    /** Les 20 dernières mises à jour d'un poste (propriétaire ou ADMIN). */
    @Transactional(readOnly = true)
    public List<RunnerUpdateProgress> journal(AuthenticatedUser caller, UUID hostId) {
        authorized(caller, hostId);
        OffsetDateTime now = OffsetDateTime.now();
        List<RunnerUpdateProgress> entries = new ArrayList<>();
        journal.findTop20ByHostIdOrderByRequestedAtDesc(hostId)
                .forEach(entry -> entries.add(RunnerUpdateProgress.of(entry, now)));
        return entries;
    }

    /**
     * Ce que le runner dit de la mise à jour ({@code update_status}), ou son retour ({@code ready}).
     * Le poste est celui de la <b>session</b> ; un {@code updateId} d'un autre poste ne trouve rien.
     */
    @EventListener
    @Transactional
    public void onFrame(RunnerUpdateFrameEvent event) {
        if (event.hostId() == null) {
            return;
        }
        if ("update_status".equals(event.type())) {
            onStatus(event.hostId(), event.frame());
        } else if ("ready".equals(event.type()) && event.declaration() != null) {
            if (!onRollbackReport(event.hostId(), event.frame().path("lastUpdate"))) {
                onReady(event.hostId(), event.declaration().version());
            }
        }
    }

    /**
     * Le rapport d'un <b>retour arrière</b> du lanceur (F-111 / SF-111-05) : la dernière mise à jour du poste
     * visant cette version passe en {@code ROLLED_BACK} avec le motif — même si elle avait été crue réussie
     * (la nouvelle version s'était connectée, puis a planté trois fois).
     *
     * @return vrai si le rapport a été appliqué
     */
    private boolean onRollbackReport(UUID hostId, JsonNode report) {
        if (report == null || !report.isObject() || !"rolled_back".equals(report.path("result").asText())
                || !report.path("to").isTextual()) {
            return false;
        }
        String to = report.path("to").asText();
        String from = report.path("from").asText("");
        String reason = report.path("reason").asText("");
        OffsetDateTime now = OffsetDateTime.now();
        return journal.findFirstByHostIdOrderByRequestedAtDesc(hostId)
                .filter(entry -> entry.getToVersion().equals(to))
                .filter(entry -> !entry.getState().terminal() || entry.getState() == State.SUCCEEDED)
                .map(entry -> {
                    // Le motif seul : « de → vers » est déjà sur la ligne, l'écran écrit « retour à 1.4 ».
                    entry.moveTo(State.ROLLED_BACK, reason.isBlank() ? "échec de la nouvelle version" : reason, now);
                    journal.save(entry);
                    log.info("Mise à jour du runner annulée par le lanceur (poste={}, vers={}, retour à {})", hostId,
                            to, from);
                    return true;
                })
                .orElse(false);
    }

    private void onStatus(UUID hostId, JsonNode frame) {
        UUID updateId = uuid(frame.path("updateId").asText(null));
        if (updateId == null) {
            return;
        }
        journal.findByIdAndHostId(updateId, hostId)
                .filter(entry -> !entry.getState().terminal())
                .ifPresent(entry -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    switch (frame.path("state").asText("")) {
                        case "downloading" -> entry.moveTo(State.DOWNLOADING, null, now);
                        case "waiting" -> entry.moveTo(State.WAITING, busy(frame), now);
                        case "restarting" -> entry.moveTo(State.RESTARTING, null, now);
                        case "failed" -> {
                            entry.moveTo(State.FAILED, failure(frame), now);
                            log.info("Mise à jour du runner refusée par le runner (poste={}, vers={}, motif={})",
                                    hostId, entry.getToVersion(), frame.path("reason").asText("?"));
                        }
                        default -> {
                            // État inconnu d'un runner plus récent : ignoré, jamais une erreur.
                        }
                    }
                    journal.save(entry);
                });
    }

    private void onReady(UUID hostId, String declaredVersion) {
        OffsetDateTime now = OffsetDateTime.now();
        journal.findFirstByHostIdOrderByRequestedAtDesc(hostId)
                .filter(entry -> !entry.getState().terminal())
                .ifPresent(entry -> {
                    if (entry.getToVersion().equals(declaredVersion)) {
                        entry.moveTo(State.SUCCEEDED, null, now);
                        log.info("Mise à jour du runner réussie (poste={}, de={}, vers={})", hostId,
                                entry.getFromVersion(), entry.getToVersion());
                    } else if (entry.getState() == State.RESTARTING || entry.getState() == State.REQUESTED
                            || entry.getState() == State.DOWNLOADING || entry.getState() == State.WAITING) {
                        entry.moveTo(State.FAILED, "Le runner s'est reconnecté en "
                                + (declaredVersion == null ? "version inconnue" : declaredVersion)
                                + " avant d'avoir basculé.", now);
                    }
                    journal.save(entry);
                });
    }

    private RunnerHost authorized(AuthenticatedUser caller, UUID hostId) {
        boolean admin = caller != null && caller.role() == UserRole.ADMIN;
        return hosts.findById(hostId)
                .filter(host -> admin || (caller != null && host.getUserId().equals(caller.id())))
                .orElseThrow(() -> new RunnerHostNotFoundException("Poste introuvable."));
    }

    private void requirePossible(RunnerUpdateView view, RunnerHost host) {
        String status = view.status();
        if (RunnerUpdateView.Status.UP_TO_DATE.name().equals(status)) {
            throw new RunnerUpdateNotPossibleException("up_to_date", "Le runner de ce poste est déjà à jour.");
        }
        if (RunnerUpdateView.Status.MANUAL_LAST_TIME.name().equals(status)) {
            throw new RunnerUpdateNotPossibleException("no_launcher", "Ce runner a été installé avant la mise à "
                    + "jour automatique : mettez-le à jour à la main une dernière fois.");
        }
        if (RunnerUpdateView.Status.MANUAL_JAVA.name().equals(status)) {
            throw new RunnerUpdateNotPossibleException("java", "La nouvelle version demande Java "
                    + view.requiredJava() + " : mise à jour manuelle requise.");
        }
        if (!RunnerUpdateView.Status.AVAILABLE.name().equals(status)) {
            throw new RunnerUpdateNotPossibleException("unknown", "Aucune version à installer n'est connue.");
        }
        if (!artifacts.signedUpdateAvailable()) {
            throw new RunnerUpdateNotPossibleException("not_signed", "Cette gateway ne sert pas de version signée "
                    + "du runner : aucune mise à jour d'un clic n'est possible.");
        }
        if (host.getRunnerContract() == null || host.getRunnerContract() < RunnerUpdateAdvisor.UPDATE_CONTRACT) {
            throw new RunnerUpdateNotPossibleException("contract", "Ce runner ne sait pas encore recevoir la "
                    + "commande de mise à jour : mettez-le à jour à la main une dernière fois.");
        }
    }

    private String updateFrame(RunnerUpdateJournalEntry entry, boolean force) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("type", "update");
        frame.put("updateId", entry.getId().toString());
        frame.put("version", entry.getToVersion());
        artifacts.sha256(entry.getToVersion()).ifPresent(sha -> frame.put("sha256", sha));
        frame.put("force", force);
        return frame.toString();
    }

    private static String busy(JsonNode frame) {
        List<String> labels = new ArrayList<>();
        frame.path("busy").forEach(node -> {
            if (node.isTextual() && node.asText().length() <= 40) {
                labels.add(node.asText());
            }
        });
        return labels.isEmpty() ? null : String.join(", ", labels);
    }

    private static String failure(JsonNode frame) {
        String message = frame.path("message").asText("");
        String reason = frame.path("reason").asText("");
        if (!message.isBlank()) {
            return message;
        }
        return reason.isBlank() ? "Mise à jour refusée par le runner." : reason;
    }

    private static UUID uuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
