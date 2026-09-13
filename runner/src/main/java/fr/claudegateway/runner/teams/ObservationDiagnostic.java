package fr.claudegateway.runner.teams;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Ce que la liaison a vu, chiffré</b> (F-89 / SF-89-05) — l'instantané qui distingue « relié mais
 * aveugle » de « rien à lire ».
 *
 * <p>Constat de production : {@code teams_status} disait « relié », et tous les outils de lecture
 * rendaient zéro avec « rien d'observé depuis le rattachement ». Rien ne permettait de savoir si Teams
 * n'avait rien demandé au réseau (cache local), avait répondu depuis un worker que l'onglet ne voyait
 * pas, ou avait répondu par des chemins que l'adaptateur ne classe pas. Cet instantané le dit.</p>
 *
 * <p><b>Ce qu'il ne porte jamais</b> : un corps, un en-tête, une chaîne de requête, un nom de tenant,
 * un identifiant. Les chemins sont ramenés à leur motif d'hôte et à leur gabarit, comme dans le relevé
 * réel ({@link SurveyPaths}).</p>
 *
 * @param framesObserved     vrai si l'écoute des cadres et workers est active sur cette liaison
 * @param attachedByOrigin   cibles attachées retenues, par origine
 * @param responsesByOrigin  réponses observées, par origine
 * @param classifiedByKind   réponses classées, par nature (hors {@code IGNORED} et {@code UNKNOWN})
 * @param ignored            réponses reconnues et volontairement ignorées
 * @param unknownMicrosoft   réponses non classées sur un hôte de la famille Microsoft
 * @param unknownElsewhere   réponses non classées hors famille Microsoft (jamais détaillées)
 * @param topUnknownPaths    les chemins non classés les plus fréquents (10 au plus), du plus fréquent
 * @param unknownPathsDropped réponses de chemins non classés au-delà du plafond de chemins distincts
 * @param sockets            sockets WebSocket ouvertes sur la famille Microsoft
 * @param socketFrames       trames reçues sur ces sockets (comptées, jamais lues)
 */
public record ObservationDiagnostic(boolean framesObserved, Map<String, Integer> attachedByOrigin,
        Map<String, Integer> responsesByOrigin, Map<String, Integer> classifiedByKind, int ignored,
        int unknownMicrosoft, int unknownElsewhere, List<PathCount> topUnknownPaths,
        int unknownPathsDropped, int sockets, int socketFrames) {

    /** La commande du relevé réel, citée telle quelle à l'utilisateur. */
    public static final String SURVEY_COMMAND = "java -jar claude-runner.jar --" + TeamsSurveyCommand.FLAG;

    /** Chemins non classés rendus au plus. */
    public static final int TOP_PATHS = 10;

    public ObservationDiagnostic {
        attachedByOrigin = Map.copyOf(attachedByOrigin == null ? Map.of() : attachedByOrigin);
        responsesByOrigin = Map.copyOf(responsesByOrigin == null ? Map.of() : responsesByOrigin);
        classifiedByKind = Map.copyOf(classifiedByKind == null ? Map.of() : classifiedByKind);
        topUnknownPaths = List.copyOf(topUnknownPaths == null ? List.of() : topUnknownPaths);
    }

    /** Un chemin gabarisé et le nombre de réponses vues par lui. */
    public record PathCount(String path, int count) {
    }

    /** Aucun diagnostic : la liaison n'existe pas. */
    public static ObservationDiagnostic none() {
        return new ObservationDiagnostic(false, Map.of(), Map.of(), Map.of(), 0, 0, 0, List.of(), 0, 0, 0);
    }

    /** Toutes les réponses observées, toutes origines confondues. */
    public int responses() {
        return responsesByOrigin.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Les réponses classées dans une nature que l'adaptateur sait nommer. */
    public int classified() {
        return classifiedByKind.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Vrai quand Teams a répondu par des chemins que l'adaptateur ne reconnaît pas, et que rien de ce
     * qu'il reconnaît n'est passé : le zéro n'est pas « rien d'affiché ».
     */
    public boolean unrecognizedTraffic() {
        return unknownMicrosoft > 0 && classified() == 0;
    }

    /**
     * La phrase qui accompagne un zéro : ce qu'on sait, et le geste. Vide quand des réponses classées
     * existent — le zéro n'est alors pas réinterprété.
     */
    public String sentence() {
        if (responses() == 0 && sockets == 0) {
            return "Aucune réponse réseau observée depuis le rattachement (onglet" + (framesObserved
                    ? ", cadres et workers" : "") + ") : Teams affiche peut-être depuis son cache local.";
        }
        if (unrecognizedTraffic()) {
            return "Teams a répondu par des chemins que l'adaptateur ne reconnaît pas (" + unknownMicrosoft
                    + (unknownMicrosoft > 1 ? " réponses non classées" : " réponse non classée")
                    + " depuis le rattachement). Lancez le relevé sur ce poste : " + SURVEY_COMMAND
                    + " — il note ces chemins, sans corps ni requête.";
        }
        return "";
    }

    /** Le diagnostic, tel qu'il voyage dans le résultat d'un outil. */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("framesObserved", framesObserved);
        node.put("responses", responses());
        putCounts(node.putObject("attachedByOrigin"), attachedByOrigin);
        putCounts(node.putObject("responsesByOrigin"), responsesByOrigin);
        putCounts(node.putObject("classifiedByKind"), classifiedByKind);
        node.put("classified", classified());
        node.put("ignored", ignored);
        node.put("unknownMicrosoft", unknownMicrosoft);
        node.put("unknownElsewhere", unknownElsewhere);
        ArrayNode paths = node.putArray("topUnknownPaths");
        topUnknownPaths.forEach(path -> paths.addObject().put("path", path.path()).put("count", path.count()));
        if (unknownPathsDropped > 0) {
            node.put("unknownPathsDropped", unknownPathsDropped);
        }
        node.put("sockets", sockets);
        node.put("socketFrames", socketFrames);
        String sentence = sentence();
        if (!sentence.isEmpty()) {
            node.put("sentence", sentence);
        }
        return node;
    }

    private static void putCounts(ObjectNode target, Map<String, Integer> counts) {
        new LinkedHashMap<>(counts).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> target.put(entry.getKey(), entry.getValue()));
    }
}
