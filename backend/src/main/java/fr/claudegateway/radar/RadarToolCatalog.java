package fr.claudegateway.radar;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>Les outils Radar donnés à un agent — et la garde qui décide s'ils le sont</b> (F-104 / SF-104-01,
 * cadrage §9).
 *
 * <h2>La garde</h2>
 *
 * <p>Même doctrine que {@code TeamsToolCatalog} : la garde est au niveau de l'outil. Les outils ne sont
 * donnés que dans le <b>terminal Teams d'un poste</b>, à un utilisateur qui a le <b>droit Vigie</b>, et
 * pour un poste <b>activé dans la Vigie</b>. Sans l'une de ces conditions : aucun outil, en silence —
 * l'agent ne refuse pas, il n'a pas la capacité.</p>
 *
 * <h2>La parole de l'utilisateur est la preuve</h2>
 *
 * <p>Les descriptions portent la doctrine du Radar, parce que c'est le seul endroit où le modèle la lit :
 * n'écrire que ce que l'utilisateur a dit, ne rien inventer, montrer ce qu'on a compris avant d'écrire,
 * dire que c'est annulable. La preuve elle-même n'est jamais un paramètre : l'exécuteur la tient de la
 * boucle ({@link RadarNote}).</p>
 */
@Component
public class RadarToolCatalog {

    /** Préfixe commun : un test vérifie qu'aucun outil {@code radar_*} n'échappe à la garde. */
    public static final String PREFIX = "radar_";

    public static final String FIND_SUBJECT = "radar_find_subject";
    public static final String UPDATE_SUBJECT = "radar_update_subject";
    public static final String CLOSE_SUBJECT = "radar_close_subject";
    public static final String ADD_ENGAGEMENT = "radar_add_engagement";
    public static final String MARK_ENGAGEMENT = "radar_mark_engagement";
    public static final String MERGE_SUBJECTS = "radar_merge_subjects";

    /** Les six outils, dans l'ordre où ils sont donnés. */
    public static final List<String> CATALOG = List.of(FIND_SUBJECT, UPDATE_SUBJECT, CLOSE_SUBJECT,
            ADD_ENGAGEMENT, MARK_ENGAGEMENT, MERGE_SUBJECTS);

    /** Les outils qui <b>écrivent</b> le registre : tous sauf la recherche. */
    public static final List<String> WRITE = List.of(UPDATE_SUBJECT, CLOSE_SUBJECT, ADD_ENGAGEMENT,
            MARK_ENGAGEMENT, MERGE_SUBJECTS);

    /**
     * <b>Ce que l'agent du terminal Teams doit savoir du Radar</b> (F-104 / SF-104-03) — ajouté à la consigne
     * système seulement quand la garde est ouverte.
     *
     * <p>Deux règles, et elles ne sont pas de confort. <b>Registre d'abord</b> : c'est la promesse du cadrage
     * (« où en est le MFA ? » trouve sa réponse sans relire Teams). <b>La preuve est la parole de
     * l'utilisateur</b> : dans ce terminal, l'agent lit aussi Teams ; écrire une lecture de Teams sous la
     * preuve du message de l'utilisateur lui attribuerait des mots qu'il n'a pas dits.</p>
     */
    public static final String TERMINAL_NOTICE = "--- Radar du client ---\n"
            + "Ce client est suivi par le Radar : un registre de ses sujets, de leurs engagements et des relances, "
            + "tenu à jour chaque soir. Pour « où en est tel sujet ? », « qu'est-ce que j'attends de telle "
            + "personne ? » ou « quelles relances sont dues ? », cherche D'ABORD dans le registre avec "
            + FIND_SUBJECT + " : sa réponse est sourcée et ne relit pas Teams. Ne relis Teams que si le registre "
            + "ne sait pas ou si l'utilisateur le demande, et dis alors d'où vient ta réponse.\n"
            + "Les écritures radar_* ont pour preuve LE MESSAGE DE L'UTILISATEUR : n'écris que ce qu'il te dit "
            + "lui-même (« le sujet LDAP est clos », « Julie m'a répondu »), JAMAIS ce que tu as lu dans Teams ni "
            + "ce que tu déduis — une question n'est pas une nouvelle. Avant d'écrire, dis en une phrase ce que tu "
            + "as compris (« Je note : … ») ; après, rappelle que c'est annulable depuis la chronologie du sujet. "
            + "Rien n'est jamais écrit dans Teams.";

    private final TeamsAccessService teamsAccess;
    private final HostSpaceService spaces;

    @Autowired
    public RadarToolCatalog(TeamsAccessService teamsAccess, HostSpaceService spaces) {
        this.teamsAccess = teamsAccess;
        this.spaces = spaces;
    }

    /** Catalogue <b>vide</b> : aucun outil Radar n'est jamais donné (formes historiques, tests). */
    public static RadarToolCatalog none() {
        return new RadarToolCatalog(null, null);
    }

    /** Vrai si ce nom d'outil est un outil Radar. */
    public static boolean isRadarTool(String tool) {
        return tool != null && tool.startsWith(PREFIX);
    }

    /** Vrai si cet outil Radar écrit le registre. */
    public static boolean isWrite(String tool) {
        return tool != null && WRITE.contains(tool);
    }

    /**
     * Vrai si les outils Radar sont ouverts pour ce tour.
     *
     * @param userId    propriétaire du terminal (celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        if (teamsAccess == null || spaces == null || userId == null || workspace == null
                || !workspace.isTeamsTerminal() || workspace.getHostId() == null) {
            return false;
        }
        if (!teamsAccess.hasAccess(userId)) {
            return false;
        }
        try {
            return spaces.isActive(userId, workspace.getHostId(), ClientSpace.VIGIE);
        } catch (RuntimeException e) {
            // Poste introuvable ou d'autrui : fermé, sans rien dire de plus.
            return false;
        }
    }

    /** Les outils Radar à donner à l'agent pour ce tour, ou <b>la liste vide</b>. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        return isOpenFor(userId, workspace) ? definitions() : List.of();
    }

    /**
     * <b>La cible lisible d'un appel Radar</b> (F-104 / SF-104-03) : ce que le terminal affiche sur l'étape et
     * ce que la transcription garde. Jamais un identifiant technique, jamais le contenu d'un message.
     */
    public static String stepTarget(String tool, com.fasterxml.jackson.databind.JsonNode input) {
        String query = text(input, "query");
        return switch (tool == null ? "" : tool) {
            case FIND_SUBJECT -> query.isEmpty() ? "Radar · sujets ouverts" : "Radar · recherche « " + query + " »";
            case UPDATE_SUBJECT -> {
                String created = text(input, "new_subject_name");
                if (!created.isEmpty() && text(input, "subject_id").isEmpty()) {
                    yield "Radar · nouveau sujet « " + created + " »";
                }
                List<String> fields = new java.util.ArrayList<>();
                if (!text(input, "name").isEmpty()) {
                    fields.add("nom");
                }
                if (!text(input, "state").isEmpty()) {
                    fields.add("état");
                }
                if (input != null && input.has("next_step")) {
                    fields.add("prochaine étape");
                }
                if (input != null && input.has("due_date")) {
                    fields.add("échéance");
                }
                yield fields.isEmpty() ? "Radar · mise à jour d'un sujet" : "Radar · sujet : " + String.join(", ", fields);
            }
            case CLOSE_SUBJECT -> "Radar · clôture d'un sujet";
            case ADD_ENGAGEMENT -> {
                String description = text(input, "description");
                yield description.isEmpty() ? "Radar · engagement ajouté"
                        : "Radar · engagement « " + (description.length() > 80 ? description.substring(0, 80) + "…" : description) + " »";
            }
            case MARK_ENGAGEMENT -> "Radar · engagement " + switch (text(input, "status").toUpperCase(java.util.Locale.ROOT)) {
                case "DONE" -> "tenu";
                case "ABANDON" -> "abandonné";
                case "POSTPONE" -> "reporté";
                case "REOPEN" -> "rouvert";
                case "NOT_MINE" -> "pas le mien";
                default -> "marqué";
            };
            case MERGE_SUBJECTS -> "Radar · fusion de deux sujets";
            default -> "Radar";
        };
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.path(field).asText("").strip();
    }

    /**
     * <b>Les définitions</b>, sans garde. Réservé à un appelant qui a déjà vérifié le droit, la possession
     * et l'activation du poste (l'écran <i>Donner la nouvelle</i>, SF-104-02).
     */
    public static List<AgentTool> definitions() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> bool = Map.of("type", "boolean");
        Map<String, Object> date = Map.of("type", "string",
                "description", "Date AAAA-MM-JJ ; chaîne vide pour effacer.");
        return List.of(
                new AgentTool(FIND_SUBJECT,
                        "Cherche dans le REGISTRE du Radar de ce client les sujets suivis : par un mot du nom "
                                + "ou d'un alias (« MFA », « double auth »), ou, sans « query », les sujets "
                                + "ouverts. Rend pour chacun son identifiant, son état, sa prochaine étape, son "
                                + "échéance, ses alias, son résumé et ses engagements en cours (avec leur "
                                + "identifiant). C'est la réponse à « où en est tel sujet ? » : cherche ici "
                                + "AVANT de relire Teams. Appelle-le aussi AVANT toute écriture, pour écrire "
                                + "sur le bon sujet et le bon engagement — un sujet déjà suivi peut porter un "
                                + "autre nom.",
                        Map.of("type", "object",
                                "properties", Map.of("query", text, "include_closed", bool))),
                new AgentTool(UPDATE_SUBJECT,
                        "Écrit dans le registre ce que l'UTILISATEUR vient de dire d'un sujet : son état, sa "
                                + "prochaine étape, son échéance, son nom. N'écris QUE ce qu'il a dit, jamais "
                                + "ce que tu déduis ; sa parole est la preuve, elle est rangée d'office dans "
                                + "la chronologie. Donne « subject_id » (trouvé par " + FIND_SUBJECT + ") ; "
                                + "pour un sujet qui n'existe pas encore, donne « new_subject_name » à la "
                                + "place. Ce que l'utilisateur dit est souverain : aucune synchro ne le "
                                + "réécrira. Dis-lui en une phrase ce que tu as compris (« Je note : … ») ; "
                                + "tout est annulable depuis la chronologie.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "subject_id", text,
                                        "new_subject_name", text,
                                        "name", Map.of("type", "string",
                                                "description", "Nouveau nom ; l'ancien devient un alias."),
                                        "state", Map.of("type", "string",
                                                "enum", List.of("NEW", "ADVANCING", "WAITING", "BLOCKED"),
                                                "description", "NEW nouveau, ADVANCING avance, WAITING en "
                                                        + "attente, BLOCKED bloqué. Pour clore : "
                                                        + CLOSE_SUBJECT + "."),
                                        "next_step", Map.of("type", "string",
                                                "description", "Prochaine étape ; chaîne vide pour effacer."),
                                        "due_date", date))),
                new AgentTool(CLOSE_SUBJECT,
                        "Clôt un sujet PARCE QUE L'UTILISATEUR LE DIT (« le sujet LDAP peut être considéré "
                                + "comme clos ») : clos immédiatement, sans autre confirmation. Ne l'appelle "
                                + "jamais de ta propre initiative. Le résultat liste les engagements encore "
                                + "ouverts du sujet : pose alors la question à l'utilisateur (« 1 engagement "
                                + "encore ouvert : le fermer aussi ? ») au lieu de les fermer toi-même.",
                        Map.of("type", "object",
                                "properties", Map.of("subject_id", text),
                                "required", List.of("subject_id"))),
                new AgentTool(ADD_ENGAGEMENT,
                        "Ajoute un engagement que l'UTILISATEUR vient de dire : ME_TO_OTHER (je dois quelque "
                                + "chose, « to_person » facultatif), OTHER_TO_ME (on me doit quelque chose, "
                                + "« from_person » requis), INTRODUCTION (je dois mettre « to_person » et "
                                + "« other_person » en relation). Les personnes se désignent par leur NOM tel "
                                + "que l'utilisateur l'a dit. Vérifie avec " + FIND_SUBJECT + " qu'il n'existe "
                                + "pas déjà ; s'il existe, marque-le plutôt avec " + MARK_ENGAGEMENT + ".",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "subject_id", text,
                                        "direction", Map.of("type", "string",
                                                "enum", List.of("ME_TO_OTHER", "OTHER_TO_ME", "INTRODUCTION")),
                                        "description", text,
                                        "from_person", text,
                                        "to_person", text,
                                        "other_person", text,
                                        "due_date", date),
                                "required", List.of("subject_id", "direction", "description"))),
                new AgentTool(MARK_ENGAGEMENT,
                        "Marque un engagement existant d'après ce que l'UTILISATEUR vient de dire : DONE "
                                + "(tenu), ABANDON (abandonné), POSTPONE (reporté, « due_date » requise), "
                                + "REOPEN (rouvert), NOT_MINE (ce n'est pas le sien). L'identifiant vient de "
                                + FIND_SUBJECT + ".",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "commitment_id", text,
                                        "status", Map.of("type", "string",
                                                "enum", List.of("DONE", "ABANDON", "POSTPONE", "REOPEN", "NOT_MINE")),
                                        "due_date", date),
                                "required", List.of("commitment_id", "status"))),
                new AgentTool(MERGE_SUBJECTS,
                        "Fusionne deux sujets que l'UTILISATEUR dit être le même : « source_subject_id » est "
                                + "absorbé par « into_subject_id », son nom devient un alias, ses preuves et "
                                + "ses engagements suivent. Annulable depuis la chronologie.",
                        Map.of("type", "object",
                                "properties", Map.of("source_subject_id", text, "into_subject_id", text),
                                "required", List.of("source_subject_id", "into_subject_id"))));
    }
}
