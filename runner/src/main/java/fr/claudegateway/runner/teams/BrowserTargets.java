package fr.claudegateway.runner.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Les onglets du navigateur relié, et <b>lequel est Teams</b> (F-87 / SF-87-02).
 *
 * <p>On se rattache à <b>un onglet</b>, celui de Teams, et à aucun autre : les autres onglets de
 * l'utilisateur ne nous regardent pas, et ouvrir une socket sur sa banque en ligne pour « voir »
 * serait exactement ce qu'il faut ne jamais faire.</p>
 */
public final class BrowserTargets {

    /** Ce qui fait qu'une adresse est celle de Teams. */
    private static final List<String> TEAMS_HOSTS =
            List.of("teams.microsoft.com", "teams.live.com", "teams.cloud.microsoft");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrowserTargets() {
    }

    /** Un onglet du navigateur, tel que le protocole de découverte le décrit. */
    public record Target(String id, String type, String url, String title,
            String webSocketDebuggerUrl) {

        public Target {
            id = id == null ? "" : id;
            type = type == null ? "" : type;
            url = ObservedResponse.withoutQuery(url);
            title = title == null ? "" : title;
            webSocketDebuggerUrl = webSocketDebuggerUrl == null ? "" : webSocketDebuggerUrl;
        }

        boolean isPage() {
            return "page".equals(type);
        }
    }

    /** Analyse la réponse de découverte. Une réponse illisible rend une liste vide, jamais null. */
    public static List<Target> parse(String json) {
        List<Target> targets = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return targets;
        }
        try {
            JsonNode array = MAPPER.readTree(json);
            if (!array.isArray()) {
                return targets;
            }
            for (JsonNode node : array) {
                targets.add(new Target(node.path("id").asText(""), node.path("type").asText(""),
                        node.path("url").asText(""), node.path("title").asText(""),
                        node.path("webSocketDebuggerUrl").asText("")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return targets;
    }

    /** L'onglet Teams, s'il y en a un. Le premier trouvé : un utilisateur n'en ouvre pas deux. */
    public static Optional<Target> teamsTab(List<Target> targets) {
        return targets.stream()
                .filter(Target::isPage)
                .filter(target -> matches(target.url(), TEAMS_HOSTS))
                .filter(target -> !target.webSocketDebuggerUrl().isEmpty())
                .findFirst();
    }

    /**
     * Vrai si le navigateur est arrêté sur une identification Microsoft. Le distinguer de « pas
     * d'onglet Teams » change le remède : ici il faut se connecter, là il faut ouvrir un onglet.
     */
    public static boolean looksLikeSignIn(List<Target> targets) {
        // Une seule source de vérité pour les hôtes d'identification : MicrosoftDomains (F-108).
        return targets.stream().filter(Target::isPage)
                .anyMatch(target -> MicrosoftDomains.isSignIn(target.url()));
    }

    /** Le nom du navigateur, tel qu'il se déclare : « Chrome/140.0.0.0 ». Pour le diagnostic. */
    public static String browserName(String versionJson) {
        if (versionJson == null || versionJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(versionJson);
            String browser = node.path("Browser").asText("");
            return browser.isBlank() ? node.path("product").asText("") : browser;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean matches(String url, List<String> hosts) {
        String lower = url.toLowerCase(Locale.ROOT);
        return hosts.stream().anyMatch(lower::contains);
    }
}
