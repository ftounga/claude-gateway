package fr.claudegateway.jarvis.state;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>La mesure d'état d'une réplique</b> (F-247 / SF-247-01, cadrage §7) : poste éteint ou non, question d'état ou non,
 * et ce que la réponse en a fait ({@code UNKNOWN}, {@code KNOWN_STALE}, {@code KNOWN_FRESH}, {@code NA}).
 *
 * <p>Calculée <b>après</b> la réponse, jamais dans son chemin ; relayée à l'écran par l'événement {@code done}, qui la
 * recopie dans le chronomètre du tour. Aucun contenu gardé. Toute erreur rend une mesure neutre.</p>
 */
@Component
public class JarvisStateTurnMeter {

    /** Le lexique fermé du « je ne sais pas » (texte aplati). */
    static final List<Pattern> UNKNOWN = List.of(
            Pattern.compile("\\bje ne sais pas\\b|\\bje sais pas\\b|\\bje l ignore\\b"),
            Pattern.compile("\\bje n ai (pas|rien|aucune?)\\b"),
            Pattern.compile("\\b(aucune|pas d) (information|info|donnee|idee)s?\\b"),
            Pattern.compile("\\bhors ligne\\b|\\bposte (est )?(eteint|deconnecte|injoignable)\\b"),
            Pattern.compile("\\bje ne (peux|pourrai) pas (le )?(verifier|savoir|consulter)\\b"),
            Pattern.compile("\\bimpossible de (le )?(verifier|savoir|consulter)\\b"));

    /** Mesure neutre (hors question d'état, ou illisible). */
    public static final Measure NONE = new Measure(null, false, "NA", null);

    private final RunnerHostService hosts;
    private final RunnerStatusService status;

    public JarvisStateTurnMeter(RunnerHostService hosts, RunnerStatusService status) {
        this.hosts = hosts;
        this.status = status;
    }

    /** SF-247-10 — le bloc préparé, pour la durée de sa lecture dans la réplique ({@code state_block_ms}). */
    private JarvisStateBlock block;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBlock(JarvisStateBlock block) {
        this.block = block;
    }

    /** La mesure d'état d'une réplique ; jamais d'exception. */
    public Measure measure(UUID userId, Workspace workspace, String said, JarvisFastBrainService.Outcome outcome) {
        try {
            Boolean offline = hostOffline(userId, workspace);
            boolean question = JarvisStateQuestion.isStateQuestion(said);
            Integer blockMs = block == null ? null : block.takeReadMs(userId);
            return new Measure(offline, question, answer(question, Boolean.TRUE.equals(offline), outcome), blockMs);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /**
     * Le poste visé est-il déconnecté ? Le poste du sujet s'il y en a un ; sans sujet, vrai si le compte a des postes et
     * qu'aucun n'est connecté ; {@code null} sans poste (hébergé seul).
     */
    Boolean hostOffline(UUID userId, Workspace workspace) {
        if (workspace != null && workspace.getHostId() != null) {
            return !status.hostStatus(userId, workspace.getHostId()).connected();
        }
        List<RunnerHost> owned = hosts.list(userId).stream().filter(h -> userId.equals(h.getUserId())).toList();
        if (owned.isEmpty()) {
            return null;
        }
        return owned.stream().noneMatch(h -> status.statusOf(userId, h).connected());
    }

    /**
     * La réponse à une question d'état. Délégation d'un poste éteint ⇒ {@code UNKNOWN} (le tour Opus finira en
     * {@code runner_unavailable}) ; réponse rapide du lexique fermé ⇒ {@code UNKNOWN} ; autre réponse ⇒
     * {@code KNOWN_STALE} poste éteint, {@code KNOWN_FRESH} sinon. Hors question d'état ⇒ {@code NA}.
     */
    static String answer(boolean question, boolean offline, JarvisFastBrainService.Outcome outcome) {
        if (!question || outcome == null) {
            return "NA";
        }
        return switch (outcome.kind()) {
            case DELEGATE -> offline ? "UNKNOWN" : "NA";
            case FALLBACK -> "NA";
            case ANSWER -> {
                String flat = JarvisStateQuestion.flatten(outcome.spokenText() == null ? "" : outcome.spokenText());
                if (flat.isBlank() || UNKNOWN.stream().anyMatch(p -> p.matcher(flat).find())) {
                    yield "UNKNOWN";
                }
                yield offline ? "KNOWN_STALE" : "KNOWN_FRESH";
            }
        };
    }

    /** Ce que l'écran recopie dans le chronomètre du tour. */
    public record Measure(Boolean hostOffline, boolean stateQuestion, String stateAnswer, Integer stateBlockMs) {

        /** La même mesure, avec la durée de lecture du bloc tout prêt (SF-247-10). */
        public Measure withBlockMs(Integer ms) {
            return new Measure(hostOffline, stateQuestion, stateAnswer, ms);
        }
    }
}
