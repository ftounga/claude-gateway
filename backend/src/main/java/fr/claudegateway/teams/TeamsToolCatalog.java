package fr.claudegateway.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>Le catalogue d'outils Teams donné à l'agent — et la garde qui décide s'il l'est</b>
 * (F-89 / SF-89-01, cadrage §5.4).
 *
 * <h2>La garde est au niveau de l'outil, pas de l'écran</h2>
 *
 * <p>{@code buildTools(workspace)} décide déjà quels outils l'agent reçoit : c'est là que
 * {@code bash} est donné ou non selon la cible d'exécution. Les outils {@code teams_*} suivent
 * exactement le même chemin, sous <b>deux</b> conditions cumulatives : le workspace est un
 * <b>terminal Teams</b>, et l'utilisateur a le <b>droit Teams</b>.</p>
 *
 * <p><b>Sans l'une ou l'autre, les outils ne sont pas donnés — et c'est tout.</b> La nuance est le
 * motif même de cette conception : <i>l'agent ne refuse pas, il n'a pas la capacité</i>. Il ne dira
 * jamais « je pourrais mais vous n'avez pas payé » ; interrogé sur Teams, il dira qu'il ne sait pas
 * le lire. On ne met pas l'utilisateur devant une porte fermée à chaque phrase.</p>
 *
 * <h2>Un seul endroit</h2>
 *
 * <p>Le catalogue et sa garde vivent <b>ici</b>, et nulle part ailleurs. F-88 y ajoute ses outils de
 * lecture ({@code teams_read_conversation}, {@code teams_mentions}, {@code teams_search}…), F-89 /
 * SF-89-02 ses outils de présentation : aucun des deux n'a à réécrire la garde, et aucun ne peut
 * l'oublier. Un catalogue éparpillé serait un catalogue dont une entrée échapperait un jour au
 * droit.</p>
 *
 * <h2>Ce que le catalogue contient aujourd'hui</h2>
 *
 * <p>{@code teams_status} — l'outil que le runner sait exécuter depuis F-87 / SF-87-03, et que
 * l'agent n'avait jamais reçu : l'indicateur de la barre le consommait, l'agent non. Il est le bon
 * premier outil du catalogue parce qu'il est celui par lequel l'agent apprend qu'il <b>peut</b>
 * lire Teams, et, quand il ne le peut pas, <b>quoi dire</b> à l'utilisateur (lancer le navigateur
 * avec son port de débogage) : le résultat porte la phrase toute faite.</p>
 */
@Component
public class TeamsToolCatalog {

    /** L'état de la liaison (F-87 / SF-87-03). */
    public static final String STATUS = "teams_status";
    /** Retrouver une conversation par personne, groupe ou sujet (F-88 / SF-88-01). */
    public static final String FIND_CONVERSATIONS = "teams_find_conversations";
    /** Lire une conversation sur une fenêtre de temps (F-88 / SF-88-01). */
    public static final String READ_CONVERSATION = "teams_read_conversation";
    /** Là où l'on m'a mentionné, par le flux d'activité (F-88 / SF-88-02). */
    public static final String MENTIONS = "teams_mentions";
    /** Rechercher dans le contenu, par l'index de Teams (F-88 / SF-88-02). */
    public static final String SEARCH = "teams_search";
    /** Retrouver une réunion (F-88 / SF-88-02). */
    public static final String FIND_MEETINGS = "teams_find_meetings";
    /** La transcription d'une réunion enregistrée (F-88 / SF-88-02). */
    public static final String MEETING_TRANSCRIPT = "teams_meeting_transcript";
    /** L'enregistrement d'une réunion : où il est, et ce qu'on n'en fait pas (F-88 / SF-88-02). */
    public static final String MEETING_RECORDING = "teams_meeting_recording";

    /**
     * <b>Le catalogue, dans l'ordre où il est donné à l'agent</b> — et la seule liste qui fasse foi
     * côté gateway. Sa contrepartie côté runner ({@code TeamsTools.CATALOG}) porte exactement les
     * mêmes noms ; les deux sont verrouillées par un test de chaque côté, parce que deux dépôts de
     * la même vérité finissent par diverger quand personne ne les compare.
     */
    public static final List<String> CATALOG = List.of(STATUS, FIND_CONVERSATIONS,
            READ_CONVERSATION, MENTIONS, SEARCH, FIND_MEETINGS, MEETING_TRANSCRIPT,
            MEETING_RECORDING);

    /**
     * Préfixe commun à tous les outils du volet. Sert à une seule chose, mais elle compte : un test
     * peut vérifier qu'<b>aucun</b> outil commençant par {@code teams_} n'est donné là où le droit
     * n'est pas ouvert, sans avoir à énumérer un catalogue qui grandira.
     */
    public static final String PREFIX = "teams_";

    private final TeamsAccessService teamsAccess;

    public TeamsToolCatalog(TeamsAccessService teamsAccess) {
        this.teamsAccess = teamsAccess;
    }

    /**
     * Catalogue <b>vide</b>, sur le modèle d'{@code AtelierCheckpointRunner.none()} : aucun outil
     * Teams n'est jamais donné. C'est ce que reçoivent les formes d'{@code AtelierChatService}
     * conservées pour les appelants et les tests qui ne se soucient pas du volet Teams — leur
     * comportement est alors celui d'avant F-89, à l'identique.
     */
    public static TeamsToolCatalog none() {
        return new TeamsToolCatalog(null);
    }

    /**
     * Les outils Teams à donner à l'agent pour ce tour, ou <b>la liste vide</b>.
     *
     * @param userId    propriétaire du terminal (isolation : celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour
     * @return le catalogue si le workspace est un terminal Teams <b>et</b> que le droit est ouvert ;
     *         la liste vide dans tous les autres cas
     */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        if (teamsAccess == null || workspace == null || !workspace.isTeamsTerminal()
                || !teamsAccess.hasAccess(userId)) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new AgentTool(STATUS,
                "Dit où en est la liaison Teams de cette machine : reliée, navigateur non détecté, "
                        + "ou Teams a changé de forme. Appelle-le AVANT toute lecture de Teams — et "
                        + "quand il dit que la liaison n'est pas établie, répète à l'utilisateur la "
                        + "phrase et le remède qu'il te rend, mot pour mot : ils contiennent la "
                        + "commande exacte à lancer.",
                Map.of("type", "object", "properties", Map.of())));
        tools.addAll(readingTools());
        return List.copyOf(tools);
    }

    /**
     * <b>Les sept outils de lecture</b> (F-88 / SF-88-03). Aucun n'est un bouton : l'agent les
     * compose. « Qu'est-ce qu'on attend de moi ? » appellera les mentions, puis la recherche sur les
     * variantes du nom, puis la lecture des fils récemment actifs — et il conclura ; une autre
     * question appellera autre chose.
     *
     * <p><b>Les descriptions portent la doctrine du volet</b>, parce que c'est le seul endroit où
     * l'agent la lit : le plafond y est <b>annoncé et dit négociable</b> (D4), les <b>trois
     * gisements</b> d'un engagement y sont nommés — et surtout, chaque outil rappelle que son
     * résultat porte <b>ce qu'il n'a pas pu lire</b>, et qu'il faut le <b>répéter</b>. Un compte
     * rendu qui tait un trou est un compte rendu faux, et c'est la règle qui prime sur toutes les
     * autres dans ce volet.</p>
     */
    private List<AgentTool> readingTools() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> number = Map.of("type", "integer");
        List<AgentTool> tools = new ArrayList<>();

        tools.add(new AgentTool(FIND_CONVERSATIONS,
                "Retrouve une conversation Teams par personne, par groupe ou par sujet, classée de "
                        + "la plus récemment active à la plus ancienne. Un tête-à-tête n'a pas de "
                        + "sujet : cherche alors par le NOM de la personne. C'est par là qu'on "
                        + "commence quand on ne sait pas encore quel fil lire.",
                Map.of("type", "object",
                        "properties", Map.of("query", text, "limit", number))));

        tools.add(new AgentTool(READ_CONVERSATION,
                "Lit les messages d'une conversation sur une PÉRIODE. Sans conversation_id, lit le "
                        + "fil actuellement affiché dans Teams. La période par défaut est de 7 jours "
                        + "et 500 messages — ce plafond est NÉGOCIABLE : passe from (« 2026-09-01 », "
                        + "« 21d », « 3 semaines ») et max_messages (jusqu'à 2000) pour remonter "
                        + "plus loin. Le résultat porte TOUJOURS la fenêtre RÉELLEMENT lue "
                        + "(window.actualFrom / actualTo) et la liste des manques (gaps) : cite la "
                        + "phrase « text » telle quelle, et ne présente jamais une lecture "
                        + "incomplète comme complète. C'est le seul outil qui trouve les "
                        + "engagements qu'on a pris soi-même (« je te l'envoie demain ») : ils ne "
                        + "contiennent ni mention ni nom, aucune recherche ne les trouve.",
                Map.of("type", "object",
                        "properties", Map.of("conversation_id", text, "from", text, "to", text,
                                "max_messages", number))));

        tools.add(new AgentTool(MENTIONS,
                "Là où l'on vous a MENTIONNÉ explicitement (@vous), lu dans le flux d'activité que "
                        + "Teams calcule déjà : c'est exact et peu coûteux, commence par là. "
                        + "Attention : cela ne couvre QUE les mentions explicites — ce qu'on vous "
                        + "demande sans vous mentionner n'y est pas.",
                Map.of("type", "object",
                        "properties", Map.of("from", text, "to", text, "max_mentions", number))));

        tools.add(new AgentTool(SEARCH,
                "Cherche dans le CONTENU des messages, en posant la question à l'index de Teams. "
                        + "Sert à trouver ce qui vous concerne sans vous mentionner — « Francky "
                        + "s'occupe du MFA » : essaie plusieurs variantes du nom (prénom, nom, "
                        + "initiales). Si le résultat dit que la question n'a PAS pu être posée, "
                        + "ce n'est pas « il n'y a rien » : répète à l'utilisateur ce qu'il peut "
                        + "faire.",
                Map.of("type", "object",
                        "properties", Map.of("query", text, "from", text, "to", text,
                                "max_results", number),
                        "required", List.of("query"))));

        tools.add(new AgentTool(FIND_MEETINGS,
                "Retrouve une réunion par date, par sujet ou par participant. Le résultat dit si "
                        + "elle a été ENREGISTRÉE et si une TRANSCRIPTION est annoncée : ce sont "
                        + "les deux champs qui décident si tu peux aller plus loin.",
                Map.of("type", "object",
                        "properties", Map.of("query", text, "from", text, "to", text,
                                "limit", number))));

        tools.add(new AgentTool(MEETING_TRANSCRIPT,
                "La transcription d'une réunion enregistrée : les répliques horodatées, avec leur "
                        + "locuteur. Trouve d'abord la réunion avec " + FIND_MEETINGS + ". Si rien "
                        + "ne revient, dis-le : une réunion non enregistrée n'a pas de "
                        + "transcription, et il ne faut surtout pas en inventer le contenu.",
                Map.of("type", "object",
                        "properties", Map.of("meeting_id", text),
                        "required", List.of("meeting_id"))));

        tools.add(new AgentTool(MEETING_RECORDING,
                "Dit si une réunion a un enregistrement et où il se trouve. Il ne le TÉLÉCHARGE "
                        + "PAS : ne laisse jamais croire à l'utilisateur qu'un fichier a été "
                        + "récupéré — le résultat explique pourquoi, répète-le.",
                Map.of("type", "object",
                        "properties", Map.of("meeting_id", text),
                        "required", List.of("meeting_id"))));

        return tools;
    }
}
