package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Quelles URL portent quoi</b> (F-87 / SF-87-01) — le premier des trois savoirs de l'adaptateur.
 *
 * <p>Classe volontairement <b>non publique</b> : hors de ce paquet, personne n'a le droit de
 * reconnaître une adresse Microsoft. Un test d'architecture le vérifie sur tout le runner.</p>
 *
 * <p>La reconnaissance porte sur le <b>chemin</b>, jamais sur la requête : les paramètres portent
 * des identifiants de session et parfois des jetons, et rien ici ne doit inciter à les lire.</p>
 */
final class TeamsUrls {

    /** Hôtes des services de conversation observés depuis le client web. */
    private static final List<String> CHAT_HOST_MARKERS =
            List.of("teams.microsoft.com", "teams.live.com", "skype.com", "teams.cloud.microsoft");

    private TeamsUrls() {
    }

    /** Ce que porte cette adresse. Ne lit jamais le corps ; ne suit jamais le lien. */
    static TeamsPayloadKind classify(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return TeamsPayloadKind.UNKNOWN;
        }
        String url = rawUrl.toLowerCase(Locale.ROOT);
        String path = pathOf(url);

        if (isIgnorable(url, path)) {
            return TeamsPayloadKind.IGNORED;
        }
        if (!isChatHost(url)) {
            return TeamsPayloadKind.UNKNOWN;
        }
        // L'ordre compte : « …/conversations/{id}/messages » est plus précis que « …/conversations ».
        if (path.contains("/conversations/") && path.endsWith("/messages")) {
            return TeamsPayloadKind.CONVERSATION_MESSAGES;
        }
        if (path.endsWith("/conversations") || path.contains("/users/me/conversations")) {
            return TeamsPayloadKind.CONVERSATION_LIST;
        }
        if (path.contains("/activityfeed") || path.contains("/activity/feed")) {
            return TeamsPayloadKind.ACTIVITY_FEED;
        }
        if (path.contains("/search/") || path.endsWith("/search")) {
            return TeamsPayloadKind.SEARCH_RESULTS;
        }
        if (path.contains("/transcripts")) {
            return TeamsPayloadKind.MEETING_TRANSCRIPT;
        }
        if (path.contains("/meetings/") || path.endsWith("/meetings")
                || path.contains("/calling/meetings")) {
            return TeamsPayloadKind.MEETING_DETAILS;
        }
        if (path.contains("/users/") && (path.contains("/profile") || path.endsWith("/properties"))) {
            return TeamsPayloadKind.PROFILE;
        }
        return TeamsPayloadKind.UNKNOWN;
    }

    /**
     * Versions d'interface lisibles dans l'adresse (« v1 », « v2 », « beta »). Elles alimentent le
     * refus de la sonde de santé : « Teams a changé, version observée : beta ».
     */
    static List<String> apiVersions(String rawUrl) {
        List<String> versions = new ArrayList<>();
        if (rawUrl == null) {
            return versions;
        }
        for (String segment : pathOf(rawUrl.toLowerCase(Locale.ROOT)).split("/")) {
            if (segment.equals("beta") || segment.matches("v[0-9]+(\\.[0-9]+)?")) {
                if (!versions.contains(segment)) {
                    versions.add(segment);
                }
            }
        }
        return versions;
    }

    private static boolean isChatHost(String url) {
        return CHAT_HOST_MARKERS.stream().anyMatch(url::contains);
    }

    /**
     * Reconnue et sans intérêt. Les distinguer d'{@code UNKNOWN} est ce qui empêche la sonde de
     * santé de crier au loup : une page web charge des centaines de ressources qui ne sont pas des
     * réponses de service.
     */
    private static boolean isIgnorable(String url, String path) {
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".png")
                || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".svg")
                || path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".map")
                || path.endsWith(".ico") || path.endsWith(".gif")) {
            return true;
        }
        return url.contains("/telemetry") || url.contains("browser.pipe.aria.microsoft.com")
                || url.contains("/presence") || url.contains("/beacon")
                || url.contains("/loggingservice") || url.contains("/poll");
    }

    private static String pathOf(String url) {
        int scheme = url.indexOf("//");
        int start = scheme < 0 ? 0 : url.indexOf('/', scheme + 2);
        if (start < 0) {
            return "";
        }
        int end = url.length();
        int query = url.indexOf('?', start);
        if (query >= 0) {
            end = query;
        }
        int fragment = url.indexOf('#', start);
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        return url.substring(start, end);
    }
}
