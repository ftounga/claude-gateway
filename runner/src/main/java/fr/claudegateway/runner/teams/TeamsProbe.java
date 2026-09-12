package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>La sonde de santé</b> (F-87 / SF-87-03) — jouée au rattachement.
 *
 * <p>Le produit doit découvrir qu'il ne sait plus lire Teams <b>avant</b> que l'utilisateur ne le
 * découvre. La sonde confronte donc l'hypothèse de l'adaptateur au réel, et c'est elle — et elle
 * seule — qui rend honnête tout le reste du volet : les échantillons sur lesquels l'adaptateur a été
 * écrit sont <b>fabriqués</b>, faute de compte Teams de test. Le jour du premier branchement, c'est
 * ici que l'hypothèse est mise à l'épreuve.</p>
 *
 * <p><b>Elle observe, elle ne navigue pas.</b> Le cadrage disait « ouvrir une conversation
 * connue » ; ouvrir demanderait {@code Page.navigate}, que la liste blanche refuse — nous lisons,
 * nous ne pilotons pas, et déplacer l'onglet de l'utilisateur sous ses yeux serait une intrusion.
 * La sonde écoute donc l'onglet ouvert et le fait défiler d'<b>un</b> geste, ce qui suffit à faire
 * demander quelque chose à la page.</p>
 *
 * <p><b>Trois issues, et une quatrième situation</b> : tout reconnu, partiellement reconnu, rien
 * reconnu — et « rien observé », qui n'est pas un refus (voir {@link TeamsProbeResult#conclusive()}).</p>
 */
public final class TeamsProbe {

    /** Durée maximale de la sonde : au-delà, on conclut avec ce qu'on a vu. */
    public static final long MAX_DURATION_MS = 3_000L;

    /** Un seul geste : la sonde vérifie une forme, elle ne lit pas une conversation. */
    public static final int SCROLL_GESTURES = 1;

    private final TeamsAdapter adapter;

    public TeamsProbe(TeamsAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Joue la sonde sur une liaison déjà ouverte.
     *
     * @param link    la liaison au navigateur
     * @param sleeper l'attente entre les gestes (injectée pour que les tests ne dorment pas)
     */
    public TeamsProbeResult probe(BrowserLink link, BrowserLink.Sleeper sleeper) {
        // Un seul geste, et la vue de l'utilisateur revient d'elle-même où elle était : cette sonde
        // est rejouée à chaque relevé d'état, et une conversation qui remonte toute seule d'un écran
        // toutes les minutes serait insupportable.
        link.nudge(sleeper);
        return judge(link.observer().collect(), link.browser());
    }

    /**
     * Le jugement, isolé de la liaison : c'est la partie qui décide, et elle doit pouvoir être
     * éprouvée sans navigateur.
     */
    public TeamsProbeResult judge(List<ObservedResponse> observed, String browser) {
        int recognized = 0;
        int expected = 0;
        int withBody = 0;
        Set<String> missing = new LinkedHashSet<>();
        Set<String> versions = new LinkedHashSet<>();

        for (ObservedResponse response : observed) {
            if (!response.hasBody()) {
                continue; // un corps indisponible est déjà déclaré comme manque par l'observation
            }
            withBody++;
            TeamsHealth health = adapter.inspect(response.url(), response.body());
            recognized += health.recognizedFields();
            expected += health.expectedFields();
            missing.addAll(health.missingFields());
            versions.addAll(health.observedApiVersions());
        }

        if (withBody == 0) {
            // Rien n'est passé : la page était inactive. On ne crie pas au loup — on dit que la
            // vérification aura lieu à la première lecture.
            return new TeamsProbeResult(TeamsLinkState.LINKED, TeamsHealth.full(0), 0, browser, "");
        }

        TeamsHealth health = TeamsHealth.of(recognized, expected, new ArrayList<>(missing),
                new ArrayList<>(versions),
                "Lu par l'adaptateur " + adapter.version() + " sur " + withBody
                        + (withBody > 1 ? " réponses observées." : " réponse observée."));

        TeamsLinkState state = health.verdict() == TeamsHealthVerdict.NONE
                ? TeamsLinkState.TEAMS_CHANGED
                : TeamsLinkState.LINKED;
        String remedy = state == TeamsLinkState.TEAMS_CHANGED
                ? "Le volet Teams ne peut plus lire ce que le service renvoie. Rien ne sera produit "
                        + "tant que l'adaptateur n'aura pas été mis à jour : un compte rendu à "
                        + "moitié faux serait pire qu'aucun compte rendu."
                : "";
        return new TeamsProbeResult(state, health, withBody, browser, remedy);
    }

    /**
     * Le verdict quand la liaison elle-même a échoué : l'échec porte déjà son remède (la ligne de
     * commande à coller, l'onglet à ouvrir, la session à ouvrir).
     */
    public static TeamsProbeResult notLinked(BrowserLinkException failure) {
        return new TeamsProbeResult(TeamsLinkState.BROWSER_NOT_DETECTED, TeamsHealth.full(0), 0, "",
                failure == null ? "" : failure.getMessage());
    }
}
