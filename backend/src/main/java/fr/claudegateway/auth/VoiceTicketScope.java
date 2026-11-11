package fr.claudegateway.auth;

import java.util.List;

import org.springframework.util.AntPathMatcher;

/**
 * <b>La liste fermée de la portée {@code jarvis-voice}</b> (F-257 / SF-257-02, préoccupation transversale
 * Auth/Principal). Le ticket de session vocale, émis par la passerelle à l'écran puis présenté par le service
 * {@code jarvis-voice}, n'ouvre QUE les routes dont la boucle vocale a besoin : vérifier le ticket, le cerveau rapide et
 * son verdict, la voix, la session d'écoute, la mesure et le journal d'écoute. Tout le reste répond 403
 * {@code jarvis_voice_scope} — y compris l'émission d'un nouveau ticket.
 *
 * <p>Chemins relatifs au context-path ({@code /api} retiré). Un {@code *} vaut un seul segment ; jamais de {@code **}.
 * Pur, testé seul.</p>
 */
public final class VoiceTicketScope {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Une route ouverte : la méthode HTTP et le motif du chemin. */
    public record Route(String method, String pattern) {
    }

    /** Les routes de la portée. */
    public static final List<Route> ROUTES = List.of(
            new Route("GET", "/jarvis/voice-session/me"),
            new Route("POST", "/jarvis/converse"),
            new Route("POST", "/jarvis/converse/verdict"),
            new Route("POST", "/jarvis/speech"),
            new Route("POST", "/jarvis/stt-session"),
            new Route("POST", "/jarvis/stt-session/*/close"),
            new Route("POST", "/jarvis/timings"),
            new Route("POST", "/jarvis/listen-trace"));

    private VoiceTicketScope() {
    }

    /** Vrai si la portée ouvre {@code method} {@code path}. */
    public static boolean allows(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String clean = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        return ROUTES.stream().anyMatch(r -> r.method().equalsIgnoreCase(method) && MATCHER.match(r.pattern(), clean));
    }
}
