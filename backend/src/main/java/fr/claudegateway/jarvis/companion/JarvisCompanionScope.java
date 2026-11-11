package fr.claudegateway.jarvis.companion;

import java.util.List;
import java.util.Locale;

import org.springframework.util.AntPathMatcher;

/**
 * <b>La liste fermée de la portée {@code jarvis-compagnon}</b> (F-232 / SF-232-05, préoccupation transversale
 * Auth/Principal). Une session émise au compagnon Android n'ouvre QUE ces routes — celles que l'écran {@code /jarvis}
 * appelle réellement (relevé du 2026-11-04, mini-spec SF-232-05 §Endpoints de la portée). Tout le reste répond 403
 * {@code jarvis_companion_scope} : administration, terminal hors Jarvis, réglages du compte, et les <b>réglages</b> de
 * Jarvis qui engagent hors de la conversation (relier ou délier une boîte, un agenda, un compagnon ; effacer le
 * portrait ; changer le budget).
 *
 * <p>Chemins relatifs au context-path ({@code /api} retiré). Un {@code *} vaut un seul segment ; jamais de {@code **} :
 * une route future d'un préfixe n'est jamais ouverte d'avance. Pur, testé seul.</p>
 */
public final class JarvisCompanionScope {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Une route ouverte : la méthode HTTP et le motif du chemin. */
    public record Route(String method, String pattern) {
    }

    /** Les routes de la portée, groupées comme l'écran les appelle. */
    public static final List<Route> ROUTES = List.of(
            // Le fil et le tour (la délégation à Opus ; le « oui » des actions passe ici, gardé par SF-232-06).
            new Route("GET", "/workspaces"),
            new Route("GET", "/workspaces/*/chat"),
            new Route("POST", "/workspaces/*/chat/stream"),
            new Route("POST", "/workspaces/*/chat/interrupt"),
            new Route("POST", "/workspaces/*/chat/confirm"),
            // L'écoute et la voix.
            new Route("POST", "/atelier/transcription"),
            new Route("GET", "/jarvis/voice"),
            new Route("POST", "/jarvis/speech"),
            // F-250 / SF-233-16 — le coupe-circuit du visage (un booléen) : sans lui, le compagnon montrait le cœur.
            new Route("GET", "/jarvis/face"),
            new Route("GET", "/jarvis/clips/*"),
            new Route("POST", "/jarvis/stt-session"),
            new Route("POST", "/jarvis/stt-session/*/close"),
            new Route("POST", "/jarvis/utterance"),
            // Le cerveau rapide.
            new Route("POST", "/jarvis/converse"),
            new Route("POST", "/jarvis/converse/warm"),
            // F-257 / SF-257-03 — le ticket du lien vocal en flux (l'écran /jarvis ouvert dans le compagnon).
            new Route("POST", "/jarvis/voice-session"),
            // F-248 / SF-248-06 — le plein écran d'une pancarte (lecture, une ligne sous le compte).
            new Route("GET", "/jarvis/cards/*"),
            // F-248 / SF-248-08 — les pancartes des clés citées par Opus (lecture par index, après le premier mot).
            new Route("GET", "/jarvis/cards"),
            // La scène, l'activité, la mesure.
            new Route("GET", "/jarvis/universe"),
            new Route("GET", "/jarvis/activity"),
            new Route("GET", "/jarvis/watches"),
            new Route("GET", "/jarvis/thread/today"),
            new Route("POST", "/jarvis/usage"),
            new Route("POST", "/jarvis/timings"),
            // F-233 / SF-233-15 — le journal de l'écoute (transitions et erreurs, sans contenu).
            new Route("POST", "/jarvis/listen-trace"),
            new Route("POST", "/jarvis/captures"),
            new Route("PUT", "/jarvis/captures/latest/correction"),
            // Préférences d'écran (mode de conversation, mot d'éveil) ; coût et « je la lance ? » du budget.
            new Route("GET", "/jarvis/preferences"),
            new Route("PUT", "/jarvis/preferences"),
            new Route("GET", "/jarvis/costs"),
            new Route("POST", "/jarvis/budget/confirm"),
            // Les alertes de veille.
            new Route("GET", "/jarvis/alerts/*"),
            new Route("POST", "/jarvis/alerts/*/open"),
            // Lecture : courrier, agenda (et l'état des volets, en lecture seule).
            new Route("GET", "/jarvis/mail/brief"),
            new Route("GET", "/jarvis/mail/items/*"),
            new Route("GET", "/jarvis/mail/accounts"),
            new Route("GET", "/jarvis/calendar/events"),
            new Route("GET", "/jarvis/calendar/feeds"),
            new Route("GET", "/jarvis/calendar/grants"),
            new Route("GET", "/jarvis/companions"),
            // Le portrait : lu, « retiens… » / « oublie ça » à la voix, l'entretien.
            new Route("GET", "/jarvis/portrait"),
            new Route("POST", "/jarvis/portrait/souvenirs"),
            new Route("DELETE", "/jarvis/portrait/souvenirs/*"),
            new Route("GET", "/jarvis/portrait/interview"),
            new Route("POST", "/jarvis/portrait/interview/offered"),
            new Route("POST", "/jarvis/portrait/interview/draft"),
            // Les pièces et leur contenu (lecture ; le retour sur une vidéo, interne).
            new Route("GET", "/jarvis/pieces"),
            new Route("GET", "/jarvis/pieces/*/thumb"),
            new Route("GET", "/jarvis/pieces/*/text"),
            new Route("GET", "/jarvis/pieces/*/image"),
            new Route("GET", "/workspaces/*/deposits/*/content"),
            new Route("GET", "/workspaces/*/proofs/*"),
            new Route("GET", "/generated-images/*/image"),
            new Route("GET", "/presentations/*/slides/*"),
            new Route("GET", "/videos/*"),
            new Route("POST", "/videos/*/comments"),
            new Route("GET", "/pages/*"),
            // Le compagnon lui-même : sa version (routes publiques ; l'écran y joint son jeton).
            new Route("GET", "/runner/download/android"),
            new Route("GET", "/runner/download/android/version"));

    private JarvisCompanionScope() {
    }

    /** Vrai si la route est dans la liste fermée. Méthode insensible à la casse ; chemin exact (motifs à un segment). */
    public static boolean allows(String method, String path) {
        if (method == null || path == null || path.isEmpty()) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        String p = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (p.contains("..") || p.contains("//")) {
            return false;
        }
        for (Route route : ROUTES) {
            if (route.method().equals(m) && MATCHER.match(route.pattern(), p)) {
                return true;
            }
        }
        return false;
    }
}
