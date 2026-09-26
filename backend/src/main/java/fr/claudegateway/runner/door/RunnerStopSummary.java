package fr.claudegateway.runner.door;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import fr.claudegateway.atelier.AtelierToolTrace;

/**
 * <b>Ce qu'on rend quand le poste tombe en plein tour</b> (F-161 / SF-161-02).
 *
 * <p><b>Pourquoi ce texte n'est pas écrit par le modèle.</b> Quand le runner décroche au milieu
 * d'un tour, l'agent reçoit l'erreur, raisonne sur tout le contexte, et écrit « Non concluant ». On
 * paie un appel complet pour apprendre ce que la gateway savait déjà. Ici, le même service est rendu
 * <b>pour zéro jeton</b> — parce que tout ce qu'il y avait à dire est déjà dans la trace.</p>
 *
 * <p><b>Et on ne rend pas qu'un constat d'échec</b> : sur la session mesurée, ces messages avaient
 * de la valeur (« la branche est créée localement »). Les étapes déjà accomplies sont donc nommées —
 * c'est l'essentiel du contenu qu'on aurait payé.</p>
 */
public final class RunnerStopSummary {

    /**
     * Le début, <b>stable</b>, du message : c'est lui qui permet de reconnaître cette issue sans
     * ajouter un champ à traîner dans toutes les formes du résultat — même idiome que
     * l'interruption utilisateur.
     */
    public static final String PREFIX = "Le poste a cessé de répondre pendant ce tour.";

    /** Au-delà, la liste cesse d'informer et devient un mur. */
    private static final int MAX_NAMED = 6;

    private RunnerStopSummary() {
    }

    /** Le message rendu à l'utilisateur, à partir de ce que le tour a réellement fait. */
    public static String of(AtelierToolTrace trace, String hostName) {
        // Le préfixe reste INTACT : c'est lui qui identifie l'issue. Le nom du poste vient après,
        // jamais dedans — l'insérer au milieu casserait la reconnaissance, ce qu'un test a montré.
        StringBuilder text = new StringBuilder(PREFIX);
        if (hostName != null && !hostName.isBlank()) {
            text.append(" Il s'agit du poste « ").append(hostName).append(" ».");
        }
        text.append(" Je m'arrête là plutôt que de continuer à l'aveugle — rien de plus n'a été dépensé.");

        List<String> done = succeeded(trace);
        if (done.isEmpty()) {
            text.append("\n\nAucune étape n'avait encore abouti.");
        } else {
            text.append("\n\nCe qui a été fait avant la coupure :");
            for (String name : done) {
                text.append("\n- ").append(name);
            }
        }
        lastFailure(trace).ifPresent(failure ->
                text.append("\n\nDernier échec : ").append(failure));
        text.append("\n\nRelance le runner du poste, puis redemande — le travail ci-dessus est conservé.");
        return text.toString();
    }

    /** Reconnaît cette issue à son début stable. */
    public static boolean isStopped(String reply) {
        return reply != null && reply.startsWith(PREFIX);
    }

    /**
     * Les outils qui ont <b>abouti</b>, dans l'ordre, sans doublon. On nomme l'outil, jamais son
     * contenu : un résultat de commande peut porter des secrets, et ce message est persisté.
     */
    private static List<String> succeeded(AtelierToolTrace trace) {
        Set<String> names = new LinkedHashSet<>();
        for (AtelierToolTrace.Step step : steps(trace)) {
            for (AtelierToolTrace.Call call : calls(step)) {
                if (!call.error() && call.name() != null) {
                    names.add(call.name());
                }
            }
        }
        List<String> kept = new ArrayList<>(names);
        if (kept.size() <= MAX_NAMED) {
            return kept;
        }
        List<String> trimmed = new ArrayList<>(kept.subList(0, MAX_NAMED));
        trimmed.add("et " + (kept.size() - MAX_NAMED) + " autre(s)");
        return trimmed;
    }

    /** Le dernier appel en échec — celui qui a très probablement constaté la coupure. */
    private static java.util.Optional<String> lastFailure(AtelierToolTrace trace) {
        String last = null;
        for (AtelierToolTrace.Step step : steps(trace)) {
            for (AtelierToolTrace.Call call : calls(step)) {
                if (call.error() && call.name() != null) {
                    last = call.name();
                }
            }
        }
        return java.util.Optional.ofNullable(last);
    }

    private static List<AtelierToolTrace.Step> steps(AtelierToolTrace trace) {
        return trace == null || trace.steps() == null ? List.of() : trace.steps();
    }

    private static List<AtelierToolTrace.Call> calls(AtelierToolTrace.Step step) {
        return step == null || step.calls() == null ? List.of() : step.calls();
    }
}
