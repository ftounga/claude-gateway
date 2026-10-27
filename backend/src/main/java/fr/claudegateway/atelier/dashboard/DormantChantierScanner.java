package fr.claudegateway.atelier.dashboard;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyChantier;
import fr.claudegateway.atelier.journey.SubjectJourneyChantierRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;

/**
 * <b>Qui a des chantiers dormants ?</b> (F-220 / SF-220-05) — la matière du rappel quotidien : les comptes qui ont
 * au moins un chantier ouvert sans aucun geste depuis {@code app.client-board.dormant-days} jours, et non reporté.
 *
 * <p>Même règle d'ancienneté que le tableau du client (SF-220-02) : chantier au focus = parcours <b>et</b> dernier
 * message du sujet ; autre chantier ouvert = sa ligne. Lecture transverse (tâche de fond), mais chaque compte est
 * relu sous son propre {@code user_id} (messages, reports) et le résultat n'est qu'un ensemble de comptes : aucune
 * donnée d'un compte ne part vers un autre.</p>
 */
@Service
public class DormantChantierScanner {

    private final SubjectJourneyRepository journeys;
    private final SubjectJourneyChantierRepository chantiers;
    private final ChantierSnoozeRepository snoozes;
    private final AtelierMessageRepository messages;
    private final int dormantAfterDays;

    public DormantChantierScanner(SubjectJourneyRepository journeys, SubjectJourneyChantierRepository chantiers,
            ChantierSnoozeRepository snoozes, AtelierMessageRepository messages,
            @Value("${app.client-board.dormant-days:2}") int dormantAfterDays) {
        this.journeys = journeys;
        this.chantiers = chantiers;
        this.snoozes = snoozes;
        this.messages = messages;
        this.dormantAfterDays = Math.max(1, dormantAfterDays);
    }

    /** Un chantier candidat : son sujet, son numéro, et s'il est au focus (à recouper avec les messages). */
    private record Candidate(UUID workspaceId, int number, boolean focus) {
    }

    /** Les comptes qui ont au moins un chantier ouvert dormant et non reporté, à l'instant {@code now}. */
    @Transactional(readOnly = true)
    public Set<UUID> usersWithDormant(OffsetDateTime now) {
        OffsetDateTime threshold = now.minusDays(dormantAfterDays);
        Map<UUID, List<Candidate>> byUser = new LinkedHashMap<>();
        for (SubjectJourney j : journeys.findByModeAndUpdatedAtBefore(JourneyMode.GUIDE, threshold)) {
            if (j.getUserId() != null && j.hasOpenFocus() && j.getChantierNumber() > 0
                    && (j.getChantierOpenedAt() == null || j.getChantierOpenedAt().isBefore(threshold))) {
                byUser.computeIfAbsent(j.getUserId(), k -> new ArrayList<>())
                        .add(new Candidate(j.getWorkspaceId(), j.getChantierNumber(), true));
            }
        }
        Map<String, Optional<SubjectJourney>> journeyOf = new HashMap<>();
        for (SubjectJourneyChantier c : chantiers.findByStatusAndUpdatedAtBefore(SubjectJourneyChantier.OPEN,
                threshold)) {
            if (c.getUserId() == null) {
                continue;
            }
            Optional<SubjectJourney> journey = journeyOf.computeIfAbsent(c.getUserId() + "/" + c.getWorkspaceId(),
                    k -> journeys.findByUserIdAndWorkspaceId(c.getUserId(), c.getWorkspaceId()));
            boolean otherOpen = journey.map(j -> j.isGuided() && j.getChantierNumber() != c.getNumber())
                    .orElse(false);
            if (otherOpen) {
                byUser.computeIfAbsent(c.getUserId(), k -> new ArrayList<>())
                        .add(new Candidate(c.getWorkspaceId(), c.getNumber(), false));
            }
        }
        Set<UUID> users = new LinkedHashSet<>();
        for (Map.Entry<UUID, List<Candidate>> entry : byUser.entrySet()) {
            if (anyDormant(entry.getKey(), entry.getValue(), now, threshold)) {
                users.add(entry.getKey());
            }
        }
        return users;
    }

    private boolean anyDormant(UUID userId, List<Candidate> candidates, OffsetDateTime now, OffsetDateTime threshold) {
        Set<String> snoozed = new HashSet<>();
        for (ChantierSnooze s : snoozes.findByUserIdAndSnoozedUntilAfter(userId, now)) {
            snoozed.add(s.getWorkspaceId() + "#" + s.getChantierNumber());
        }
        Set<UUID> focusWorkspaces = new HashSet<>();
        for (Candidate c : candidates) {
            if (c.focus() && !snoozed.contains(c.workspaceId() + "#" + c.number())) {
                focusWorkspaces.add(c.workspaceId());
            }
        }
        Map<UUID, OffsetDateTime> lastMessage = new HashMap<>();
        if (!focusWorkspaces.isEmpty()) {
            for (Object[] row : messages.lastActivityByWorkspace(focusWorkspaces, userId)) {
                if (row != null && row.length == 2 && row[0] instanceof UUID id && row[1] instanceof OffsetDateTime at) {
                    lastMessage.put(id, at);
                }
            }
        }
        for (Candidate c : candidates) {
            if (snoozed.contains(c.workspaceId() + "#" + c.number())) {
                continue;
            }
            OffsetDateTime last = c.focus() ? lastMessage.get(c.workspaceId()) : null;
            if (last == null || last.isBefore(threshold)) {
                return true;
            }
        }
        return false;
    }
}
