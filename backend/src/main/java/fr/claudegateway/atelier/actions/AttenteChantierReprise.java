package fr.claudegateway.atelier.actions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyChantier;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyService;

/**
 * <b>Le plan prime</b> (F-227 / SF-227-03) — reprise au démarrage : une attente OUVERTE, non fusionnée, qu'attend une
 * étape ({@code waitsOn} = sa clé, quel que soit l'état de l'étape) d'<b>un seul</b> chantier ouvert de son sujet est
 * rangée sous ce chantier, même si la reprise par fenêtre de temps (migration 180) l'avait mise ailleurs.
 *
 * <p>Idempotente (n'écrit que ce qui diffère) ; chaque écriture se fait sous le {@code user_id} + {@code workspace_id}
 * <b>du parcours</b> : rien ne passe d'un compte à l'autre. Ne touche ni {@code updated_at} ni le dernier geste.
 * Bornée à {@value #MAX_JOURNEYS} parcours par démarrage. Coupe-circuit
 * {@code app.atelier.attentes.chantier-reprise.enabled=false}.</p>
 */
@Component
public class AttenteChantierReprise {

    private static final Logger log = LoggerFactory.getLogger(AttenteChantierReprise.class);

    static final int MAX_JOURNEYS = 5000;

    private final SubjectJourneyRepository journeys;
    private final SubjectJourneyService journeyService;
    private final TerminalActionRepository actions;
    private final boolean enabled;

    public AttenteChantierReprise(SubjectJourneyRepository journeys, SubjectJourneyService journeyService,
            TerminalActionRepository actions,
            @Value("${app.atelier.attentes.chantier-reprise.enabled:true}") boolean enabled) {
        this.journeys = journeys;
        this.journeyService = journeyService;
        this.actions = actions;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        try {
            int ranged = run();
            if (ranged > 0) {
                log.info("Attentes rangées sous le chantier qui les attend : {}", ranged);
            }
        } catch (RuntimeException e) {
            log.warn("Reprise des attentes par le plan interrompue : {}", e.getClass().getSimpleName());
        }
    }

    /** La reprise elle-même ; rend le nombre d'attentes rangées. */
    public int run() {
        int ranged = 0;
        int seen = 0;
        for (SubjectJourney row : journeys.findAll()) {
            if (seen++ >= MAX_JOURNEYS) {
                break;
            }
            if (row.getMode() != JourneyMode.GUIDE) {
                continue;
            }
            try {
                ranged += rangeJourney(journeyService.forTurn(row.getUserId(), row.getWorkspaceId()));
            } catch (RuntimeException e) {
                log.debug("Parcours illisible pour la reprise ({})", e.getClass().getSimpleName());
            }
        }
        return ranged;
    }

    /** Range les attentes d'un parcours d'après son plan ; rend le nombre d'attentes rangées. */
    int rangeJourney(SubjectJourney journey) {
        if (journey == null || !journey.isGuided()) {
            return 0;
        }
        Map<String, Set<Integer>> waitedBy = new HashMap<>();
        if (journey.hasOpenFocus() && journey.getChantierNumber() > 0) {
            collect(waitedBy, journey.getPlanJson(), journey.getChantierNumber());
        }
        for (SubjectJourneyChantier other : journey.getOtherOpenChantiers()) {
            collect(waitedBy, other.getPlanJson(), other.getNumber());
        }
        int ranged = 0;
        for (Map.Entry<String, Set<Integer>> e : waitedBy.entrySet()) {
            if (e.getValue().size() != 1) {
                continue; // deux chantiers attendent la même chose : ambigu, rien
            }
            Integer chantier = e.getValue().iterator().next();
            var found = actions.findByUserIdAndWorkspaceIdAndDedupKey(journey.getUserId(), journey.getWorkspaceId(),
                    e.getKey());
            if (found.isEmpty()) {
                continue;
            }
            TerminalAction a = found.get();
            if (!journey.getUserId().equals(a.getUserId()) || !a.isOpen() || a.getMergedIntoId() != null
                    || chantier.equals(a.getChantierNumber())) {
                continue;
            }
            a.setChantierNumber(chantier);
            actions.save(a);
            ranged++;
        }
        return ranged;
    }

    private static void collect(Map<String, Set<Integer>> waitedBy, String planJson, int chantier) {
        List<JourneyPlan.Step> steps;
        try {
            steps = JourneyPlan.fromJson(planJson).steps();
        } catch (RuntimeException e) {
            return;
        }
        for (JourneyPlan.Step step : steps) {
            if (step.waitsOn() != null && !step.waitsOn().isBlank()) {
                waitedBy.computeIfAbsent(TerminalActionService.normalizeKey(step.waitsOn()), k -> new HashSet<>())
                        .add(chantier);
            }
        }
    }
}
