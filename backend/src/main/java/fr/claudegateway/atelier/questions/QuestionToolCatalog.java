package fr.claudegateway.atelier.questions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.claudegateway.agent.AgentTool;

/**
 * <b>Les outils qui découpent un message en questions et en suivent l'avancement</b> (F-210 / SF-210-01).
 *
 * <p>Outils <b>serveur</b>, sans effet de bord : ils ne lisent ni n'écrivent rien hors de l'état du tour.
 * Ils sont donnés dans tout terminal. Les définitions sont des <b>littéraux stables</b>, aux clés
 * ordonnées : le préfixe mis en cache (F-134) ne bouge pas d'un tour, ni d'un processus, à l'autre.</p>
 */
public final class QuestionToolCatalog {

    /** Annoncer les demandes distinctes du message. */
    public static final String SPLIT = "decouper_demande";

    /** Faire avancer l'état d'une question. */
    public static final String TRACK = "suivre_question";

    /** Les deux noms. */
    public static final Set<String> NAMES = Set.of(SPLIT, TRACK);

    private static final AgentTool SPLIT_DEFINITION = splitDefinition();
    private static final AgentTool TRACK_DEFINITION = trackDefinition();

    private QuestionToolCatalog() {
    }

    /** Vrai si ce nom d'outil est l'un des deux outils de questions. */
    public static boolean isQuestionTool(String tool) {
        return tool != null && NAMES.contains(tool);
    }

    /** Les deux définitions, toujours les mêmes instances. */
    public static List<AgentTool> tools() {
        return List.of(SPLIT_DEFINITION, TRACK_DEFINITION);
    }

    private static AgentTool splitDefinition() {
        Map<String, Object> question = ordered(
                "type", "object",
                "properties", ordered(
                        "id", ordered("type", "integer",
                                "description", "Numéro de la question : 1, 2, … ; croissant d'un appel à l'autre."),
                        "libelle", ordered("type", "string",
                                "description", "La demande, reformulée en quelques mots (80 caractères au plus)."),
                        "depend_de", ordered("type", "integer",
                                "description", "Le numéro de la question dont celle-ci a besoin du résultat, "
                                        + "s'il y en a une.")),
                "required", List.of("id", "libelle"));
        return new AgentTool(SPLIT,
                "Annonce que le message de l'utilisateur (ou son interjection) contient PLUSIEURS demandes "
                        + "DISTINCTES, pour qu'il voie un fil par demande. N'appelle cet outil QUE s'il y a au "
                        + "moins deux demandes distinctes — jamais pour une demande unique, même longue ou en "
                        + "plusieurs étapes. Appelle-le une fois, au début, DANS LA MÊME RÉPONSE que tes "
                        + "premiers outils (jamais seul) ; une interjection qui apporte une nouvelle demande "
                        + "l'ajoute par un nouvel appel, avec des numéros qui continuent. Si la seconde "
                        + "demande remplace la première ou en dépend très fortement, passe fusion avec la "
                        + "raison : une seule réponse sera attendue. L'outil ne fait rien d'autre que "
                        + "l'annoncer ; il ne change rien à ta façon de travailler.",
                ordered("type", "object",
                        "properties", ordered(
                                "questions", ordered("type", "array", "items", question,
                                        "description", "Les demandes distinctes, dans l'ordre du message."),
                                "fusion", ordered("type", "object",
                                        "properties", ordered("raison", ordered("type", "string",
                                                "description", "Pourquoi une seule réponse : la seconde "
                                                        + "remplace la première, ou en dépend très fortement.")),
                                        "required", List.of("raison"),
                                        "description", "À poser seulement si les demandes appellent UNE "
                                                + "réponse commune.")),
                        "required", List.of("questions")));
    }

    private static AgentTool trackDefinition() {
        return new AgentTool(TRACK,
                "Fait avancer une question annoncée par decouper_demande : EN_COURS quand tu passes à elle "
                        + "(tes outils suivants lui sont rattachés à l'écran), TERMINEE quand tu as de quoi y "
                        + "répondre, NON_ABOUTIE si tu ne pourras pas y répondre, EN_ATTENTE si elle attend "
                        + "une autre. Appelle-le DANS LA MÊME RÉPONSE que les outils qui la font avancer, "
                        + "jamais seul. Sans découpage, ne l'appelle pas.",
                ordered("type", "object",
                        "properties", ordered(
                                "id", ordered("type", "integer", "description", "Le numéro de la question."),
                                "etat", ordered("type", "string", "description", "Le nouvel état.",
                                        "enum", List.of("EN_COURS", "EN_ATTENTE", "TERMINEE", "NON_ABOUTIE"))),
                        "required", List.of("id", "etat")));
    }

    /** Une table aux clés dans l'ordre donné : la sérialisation est la même partout. */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
