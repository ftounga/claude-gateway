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
        if (isFileHost(url)) {
            // F-100 / SF-100-03 — reconnaissance PAR MOTIF : Microsoft range transcriptions et
            // enregistrements dans SharePoint / OneDrive, sous le nom du tenant (<tenant>.sharepoint.com,
            // <tenant>-my.sharepoint.com). Le motif est le même pour tous les clients : rien à configurer,
            // rien à demander. Seules les transcriptions sont lues ici — la vidéo n'est pas nécessaire.
            return path.contains("/transcripts") ? TeamsPayloadKind.MEETING_TRANSCRIPT : TeamsPayloadKind.UNKNOWN;
        }
        // F-89 / SF-89-15 — la LISTE du calendrier servie par Microsoft Graph (résilience, relevé
        // catalogue CAGIP 2026-09-16). Reconnue par son hôte ET sa fin de chemin EXACTE :
        // « /v1.0/me/events » est une liste de réunions (enveloppe value[]), mais
        // « /v1.0/me/events/{id}/instances » ne l'est pas (id/iCalUId/start/end sans sujet) et reste
        // UNKNOWN. graph.microsoft.com n'est pas un hôte de conversation : ce cas est traité à part.
        if ("graph.microsoft.com".equals(MicrosoftDomains.hostOf(url)) && path.endsWith("/me/events")) {
            return TeamsPayloadKind.CALENDAR_EVENT;
        }
        if (!isChatHost(url)) {
            return TeamsPayloadKind.UNKNOWN;
        }
        for (Rule rule : CHAT_RULES) {
            if (rule.matches(path)) {
                return rule.kind();
            }
        }
        return TeamsPayloadKind.UNKNOWN;
    }

    /**
     * Une règle de classement sur un hôte de conversation (F-89 / SF-89-08) : le chemin, en minuscules et
     * sans requête, <b>contient</b> chacun des fragments et, si elle est donnée, <b>se termine</b> par la fin.
     *
     * @param kind     la nature rendue
     * @param contains fragments tous requis (liste vide : aucun)
     * @param endsWith fin de chemin requise, ou {@code ""} : aucune
     */
    record Rule(TeamsPayloadKind kind, List<String> contains, String endsWith) {

        static Rule containing(TeamsPayloadKind kind, String... fragments) {
            return new Rule(kind, List.of(fragments), "");
        }

        static Rule ending(TeamsPayloadKind kind, String end) {
            return new Rule(kind, List.of(), end);
        }

        static Rule containingAndEnding(TeamsPayloadKind kind, String fragment, String end) {
            return new Rule(kind, List.of(fragment), end);
        }

        boolean matches(String path) {
            return contains.stream().allMatch(path::contains) && (endsWith.isEmpty() || path.endsWith(endsWith));
        }
    }

    /**
     * <b>Les règles des hôtes de conversation, dans l'ordre</b> — la première qui correspond gagne.
     *
     * <p>Ajouter un chemin relevé sur un poste réel, c'est <b>une ligne ici</b> et <b>une ligne</b> dans la
     * table de {@code TeamsUrlsTest}. L'ordre compte : « …/conversations/{id}/messages » est plus précis que
     * « …/conversations », et doit donc venir avant. Ne jamais ajouter une règle devinée : seulement un
     * chemin vu dans un inventaire ({@code teams_status}) ou un relevé.</p>
     */
    static final List<Rule> CHAT_RULES = List.of(
            Rule.containingAndEnding(TeamsPayloadKind.CONVERSATION_MESSAGES, "/conversations/", "/messages"),
            Rule.ending(TeamsPayloadKind.CONVERSATION_LIST, "/conversations"),
            Rule.containing(TeamsPayloadKind.CONVERSATION_LIST, "/users/me/conversations"),
            Rule.containing(TeamsPayloadKind.ACTIVITY_FEED, "/activityfeed"),
            Rule.containing(TeamsPayloadKind.ACTIVITY_FEED, "/activity/feed"),
            Rule.containing(TeamsPayloadKind.SEARCH_RESULTS, "/search/"),
            Rule.ending(TeamsPayloadKind.SEARCH_RESULTS, "/search"),
            Rule.containing(TeamsPayloadKind.MEETING_TRANSCRIPT, "/transcripts"),
            // F-89 / SF-89-05 — relevé réel du 2026-09-13 : deux chemins de l'étape « réunion ».
            Rule.containing(TeamsPayloadKind.MEETING_COLLAB_OBJECT, "/collab/readcollabobject"),
            Rule.containing(TeamsPayloadKind.CALENDAR_EVENT, "/calendars/events"),
            Rule.containing(TeamsPayloadKind.CALENDAR_EVENT, "/me/events"),
            // F-89 / SF-89-15 — la LISTE du calendrier (relevé catalogue CAGIP 2026-09-16) :
            // « …/me/calendars/default/calendarView », enveloppe value[] d'items CALENDAR_EVENT.
            Rule.ending(TeamsPayloadKind.CALENDAR_EVENT, "/calendarview"),
            Rule.containing(TeamsPayloadKind.MEETING_DETAILS, "/meetings/"),
            Rule.ending(TeamsPayloadKind.MEETING_DETAILS, "/meetings"),
            Rule.containing(TeamsPayloadKind.MEETING_DETAILS, "/calling/meetings"),
            Rule.containing(TeamsPayloadKind.PROFILE, "/users/", "/profile"),
            Rule.containingAndEnding(TeamsPayloadKind.PROFILE, "/users/", "/properties"));

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

    /** Un hôte SharePoint ou OneDrive d'entreprise, quel que soit le tenant (motif, jamais un nom). */
    private static boolean isFileHost(String url) {
        String host = MicrosoftDomains.hostOf(url);
        return host.endsWith(".sharepoint.com") && host.length() > ".sharepoint.com".length();
    }

    private static boolean isChatHost(String url) {
        return CHAT_HOST_MARKERS.stream().anyMatch(url::contains);
    }

    /**
     * <b>Une famille reconnue et sans intérêt</b> (F-89 / SF-89-10). Reconnue soit par l'<b>hôte</b>
     * (une plateforme entière de bruit : télémétrie, éditeur assisté, config), soit par le
     * <b>chemin</b> (un point de terminaison de bruit — auth, présence, abonnement, réglage — sur un
     * hôte par ailleurs utile). Jamais le corps, jamais la requête.
     *
     * @param contains  fragments tous requis dans l'URL entière (hôte + chemin, minuscules)
     * @param pathEnds  fin de chemin requise, ou {@code ""} : aucune
     * @param excludes  fragment qui, s'il est présent dans l'URL, <b>empêche</b> la correspondance
     *                  (garde-fou : « …/updates » est du bruit, mais pas « …/conversations/updates »)
     */
    record IgnoreRule(List<String> contains, String pathEnds, String excludes) {

        static IgnoreRule url(String... fragments) {
            return new IgnoreRule(List.of(fragments), "", "");
        }

        static IgnoreRule pathEnding(String end) {
            return new IgnoreRule(List.of(), end, "");
        }

        static IgnoreRule urlExcept(String fragment, String pathEnd, String excludes) {
            return new IgnoreRule(List.of(fragment), pathEnd, excludes);
        }

        boolean matches(String url, String path) {
            return (excludes.isEmpty() || !url.contains(excludes))
                    && contains.stream().allMatch(url::contains)
                    && (pathEnds.isEmpty() || path.endsWith(pathEnds));
        }
    }

    /**
     * <b>Les familles de bruit, calées sur le relevé réel</b> (F-89 / SF-89-10, relevé
     * {@code docs/features/F-100/releves/releve-teams-2026-09-15-…}). Ajouter une famille, c'est
     * <b>une ligne ici</b> et <b>une ligne</b> dans {@code TeamsUrlsTest} (un exemple du relevé).
     *
     * <p><b>Prudence</b> : on n'ignore que ce qui est manifestement config, télémétrie, présence,
     * auth, abonnement ou coquille d'app. En cas de doute, on laisse {@code UNKNOWN} — un
     * {@code IGNORED} de trop masquerait une future capacité. C'est pourquoi {@code /users/{id}} nu et
     * {@code /cookiev2} ne sont pas ici, et pourquoi l'ignore des « …/updates » exclut
     * « …/conversations/updates ».</p>
     */
    static final List<IgnoreRule> IGNORE_RULES = List.of(
            // Télémétrie et journalisation.
            IgnoreRule.url("browser.pipe.aria.microsoft.com"),
            IgnoreRule.url("events.data.microsoft.com"),
            IgnoreRule.url("/telemetry"),
            IgnoreRule.url("/beacon"),
            IgnoreRule.url("/loggingservice"),
            IgnoreRule.url("/poll"),
            // Présence, abonnements, enregistrement, endpoints.
            IgnoreRule.url("/presence"),
            IgnoreRule.url("/pubsub/"),
            IgnoreRule.pathEnding("/me/endpoints"),
            IgnoreRule.url("/registrar/"),
            IgnoreRule.url("trouter.teams.microsoft.com"),
            // Auth (jetons, tokens) — la requête porte les jetons, on ne la lit pas ; on nomme l'hôte.
            IgnoreRule.url("/skypetokenauth"),
            IgnoreRule.url("/aadtokenauth"),
            IgnoreRule.url("/api/authsvc/"),
            IgnoreRule.url("/trap/tokens"),
            // Config, éditeur assisté, coquille de l'application.
            IgnoreRule.url("config.teams.microsoft.com"),
            IgnoreRule.url("augloop.office.com"),
            IgnoreRule.url("editor.svc.cloud.microsoft"),
            IgnoreRule.pathEnding("/manifest.json"),
            IgnoreRule.pathEnding("/v2"),
            // Surfaces de la suite Office, base de connaissances, admin.
            IgnoreRule.url("webshell.suite.office.com"),
            IgnoreRule.url("loki.delve.office.com"),
            IgnoreRule.url("/userknowledgebase/"),
            IgnoreRule.url("admin.microsoft.com"),
            // Réglages et listes de découverte — pas du contenu de conversation ni de réunion.
            IgnoreRule.url("/batcheddefinitions"),
            IgnoreRule.url("/usersettings"),
            IgnoreRule.url("/settings/meetingconfiguration"),
            IgnoreRule.url("/engagementsurfaces"),
            IgnoreRule.pathEnding("/usage"),
            IgnoreRule.url("/discover"),
            IgnoreRule.url("/pinnedchannels"),
            IgnoreRule.urlExcept("/teams/users/", "/updates", "/conversations"),
            // Infra du lecteur SharePoint (relevé étape « transcription ») — jamais le contenu.
            IgnoreRule.url("spcomponentregistry.ashx"),
            IgnoreRule.url("spwebworkerproxy.ashx"));

    /**
     * Reconnue et sans intérêt. Les distinguer d'{@code UNKNOWN} est ce qui empêche la sonde de
     * santé de crier au loup : une page web charge des centaines de ressources qui ne sont pas des
     * réponses de service. Les ressources statiques sont reconnues par leur extension ; le reste par
     * la table {@link #IGNORE_RULES}, calée sur le relevé réel.
     */
    private static boolean isIgnorable(String url, String path) {
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".png")
                || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".svg")
                || path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".map")
                || path.endsWith(".ico") || path.endsWith(".gif")) {
            return true;
        }
        return IGNORE_RULES.stream().anyMatch(rule -> rule.matches(url, path));
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
