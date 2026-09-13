package fr.claudegateway.runner.teams;

import java.util.Collection;
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
 * <p><b>Deux pannes, deux remèdes</b> (F-89 / SF-89-08). Un zéro a deux causes que l'appelant doit
 * distinguer, parce que la personne qui lit le message doit <b>corriger</b> : Teams n'a rien servi
 * ({@link TeamsGapKind#NOTHING_SERVED} — un geste humain : ouvrir l'écran), ou Teams a servi du contenu
 * que l'adaptateur ne reconnaît pas ({@link TeamsGapKind#NOTHING_CLASSIFIED} — un correctif logiciel :
 * cliquer n'y changera rien). {@link #nothingKind(Collection)} tranche, <b>par nature utile</b> : quinze
 * profils classés ne disent rien des réunions.</p>
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
 * @param unknownPaths       l'inventaire des chemins non classés retenus, du plus fréquent au moins fréquent
 * @param unknownPathsDropped réponses de chemins non classés au-delà du plafond de chemins distincts
 * @param sockets            sockets WebSocket ouvertes sur la famille Microsoft
 * @param socketFrames       trames reçues sur ces sockets (comptées, jamais lues)
 */
public record ObservationDiagnostic(boolean framesObserved, Map<String, Integer> attachedByOrigin,
        Map<String, Integer> responsesByOrigin, Map<String, Integer> classifiedByKind, int ignored,
        int unknownMicrosoft, int unknownElsewhere, List<PathCount> unknownPaths,
        int unknownPathsDropped, int sockets, int socketFrames) {

    /** La commande du relevé réel, citée telle quelle à l'utilisateur. */
    public static final String SURVEY_COMMAND = "java -jar claude-runner.jar --" + TeamsSurveyCommand.FLAG;

    /** Chemins non classés rendus au plus dans le résultat d'un outil de lecture. */
    public static final int TOP_PATHS = 10;

    /** Chemins non classés rendus au plus dans l'inventaire de {@code teams_status} (F-89 / SF-89-08). */
    public static final int INVENTORY_PATHS = 200;

    /** Types MIME retenus au plus par chemin. */
    public static final int MAX_MIME_TYPES = 5;

    public ObservationDiagnostic {
        attachedByOrigin = Map.copyOf(attachedByOrigin == null ? Map.of() : attachedByOrigin);
        responsesByOrigin = Map.copyOf(responsesByOrigin == null ? Map.of() : responsesByOrigin);
        classifiedByKind = Map.copyOf(classifiedByKind == null ? Map.of() : classifiedByKind);
        unknownPaths = List.copyOf(unknownPaths == null ? List.of() : unknownPaths);
    }

    /**
     * Un chemin non classé : hôte ramené à son motif, chemin gabarisé, d'où il a été vu, ce qu'il a servi,
     * et combien de fois. Jamais une requête, un tenant, un identifiant.
     */
    public record PathCount(String host, String path, List<String> origins, List<String> mimeTypes, int count) {

        public PathCount {
            host = host == null ? "" : host;
            path = path == null ? "" : path;
            origins = List.copyOf(origins == null ? List.of() : origins);
            mimeTypes = List.copyOf(mimeTypes == null ? List.of() : mimeTypes);
        }

        /** Hôte et chemin collés, la forme du top 10 depuis SF-89-05. */
        public String motif() {
            return host + path;
        }
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

    /** Les réponses classées dans l'une de ces natures. */
    public int classified(Collection<TeamsPayloadKind> kinds) {
        return kinds == null ? 0 : kinds.stream().mapToInt(kind -> classifiedByKind.getOrDefault(kind.name(), 0)).sum();
    }

    /** Les chemins non classés les plus fréquents, pour le résultat d'un outil. */
    public List<PathCount> topUnknownPaths() {
        return unknownPaths.subList(0, Math.min(TOP_PATHS, unknownPaths.size()));
    }

    /**
     * Vrai quand Teams a répondu par des chemins que l'adaptateur ne reconnaît pas, et que rien de ce
     * qu'il reconnaît n'est passé : le zéro n'est pas « rien d'affiché ».
     */
    public boolean unrecognizedTraffic() {
        return unknownMicrosoft > 0 && classified() == 0;
    }

    /**
     * <b>Quelle panne</b> derrière un zéro, pour un outil qui attend ces natures (F-89 / SF-89-08).
     *
     * <ul>
     *   <li>une réponse d'une de ces natures a été classée → {@link TeamsGapKind#NOTHING_OBSERVED} : servi,
     *       reconnu, rien ne correspond ;</li>
     *   <li>sinon, des réponses Microsoft non reconnues sont arrivées → {@link TeamsGapKind#NOTHING_CLASSIFIED} ;</li>
     *   <li>sinon (rien, ou seulement statiques, télémétrie, hors Microsoft) → {@link TeamsGapKind#NOTHING_SERVED}.</li>
     * </ul>
     */
    public TeamsGapKind nothingKind(Collection<TeamsPayloadKind> useful) {
        if (classified(useful) > 0) {
            return TeamsGapKind.NOTHING_OBSERVED;
        }
        return unknownMicrosoft > 0 ? TeamsGapKind.NOTHING_CLASSIFIED : TeamsGapKind.NOTHING_SERVED;
    }

    /** Le détail d'un manque requalifié : ce qu'on sait, et le remède qui va avec. */
    public String detail(TeamsGapKind kind) {
        if (kind == TeamsGapKind.NOTHING_CLASSIFIED) {
            return unknownMicrosoft + (unknownMicrosoft > 1 ? " réponses Microsoft reçues" : " réponse Microsoft reçue")
                    + " depuis le rattachement, aucune reconnue par l'adaptateur : cliquer ou rouvrir l'écran "
                    + "n'y changera rien";
        }
        if (kind == TeamsGapKind.NOTHING_SERVED) {
            return responses() == 0 ? "aucune réponse réseau depuis le rattachement : ouvrez l'écran voulu dans Teams"
                    : "rien de ce contenu servi depuis le rattachement (" + responses() + " réponse"
                            + (responses() > 1 ? "s" : "") + " d'une autre nature ou sans intérêt) : ouvrez "
                            + "l'écran voulu dans Teams";
        }
        return "";
    }

    /**
     * La phrase qui accompagne un zéro : ce qu'on sait, et le geste. Vide quand des réponses classées
     * existent — le zéro n'est alors pas réinterprété.
     */
    public String sentence() {
        if (unrecognizedTraffic()) {
            return sentence(TeamsGapKind.NOTHING_CLASSIFIED);
        }
        if (classified() == 0) {
            return sentence(TeamsGapKind.NOTHING_SERVED);
        }
        return "";
    }

    /** La phrase d'un manque requalifié (F-89 / SF-89-08). */
    public String sentence(TeamsGapKind kind) {
        if (kind == TeamsGapKind.NOTHING_CLASSIFIED) {
            return "Le contenu est arrivé mais n'a pas été reconnu : Teams a répondu par des chemins que "
                    + "l'adaptateur ne reconnaît pas (" + unknownMicrosoft
                    + (unknownMicrosoft > 1 ? " réponses non classées" : " réponse non classée")
                    + " depuis le rattachement). Cliquer ou rouvrir l'écran dans Teams n'y changera rien : c'est "
                    + "le runner qui doit apprendre ces chemins. Leur inventaire complet est dans teams_status "
                    + "(diagnostic.observation.unknownPaths), sans corps ni requête.";
        }
        if (kind == TeamsGapKind.NOTHING_SERVED) {
            String seen = responses() == 0 && sockets == 0
                    ? "Aucune réponse réseau observée depuis le rattachement (onglet" + (framesObserved
                            ? ", cadres et workers" : "") + ")"
                    : "Teams n'a rien servi d'utile depuis le rattachement (" + responses() + " réponse"
                            + (responses() > 1 ? "s" : "") + ", aucune qui porte ce contenu)";
            return seen + " : Teams affiche peut-être depuis son cache local, ou l'écran n'a pas été ouvert. "
                    + "Ouvrez l'écran voulu dans Teams, puis redemandez.";
        }
        return "";
    }

    /** Le diagnostic, tel qu'il voyage dans le résultat d'un outil de lecture : les 10 chemins premiers. */
    public ObjectNode toJson(ObjectMapper mapper) {
        return toJson(mapper, false);
    }

    /**
     * Le diagnostic ; {@code inventory} ajoute l'<b>inventaire complet</b> des chemins non reconnus (200 au
     * plus) — ce que {@code teams_status} rend pour qu'on apprenne à l'adaptateur les chemins réels.
     */
    public ObjectNode toJson(ObjectMapper mapper, boolean inventory) {
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
        topUnknownPaths().forEach(path -> paths.addObject().put("path", path.motif()).put("count", path.count()));
        if (inventory) {
            ArrayNode all = node.putArray("unknownPaths");
            unknownPaths.stream().limit(INVENTORY_PATHS).forEach(path -> {
                ObjectNode entry = all.addObject();
                entry.put("host", path.host());
                entry.put("path", path.path());
                path.origins().forEach(entry.putArray("origins")::add);
                path.mimeTypes().forEach(entry.putArray("mimeTypes")::add);
                entry.put("count", path.count());
            });
            node.put("unknownPathsNotListed", Math.max(0, unknownPaths.size() - INVENTORY_PATHS));
        }
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
