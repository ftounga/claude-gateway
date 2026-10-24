package fr.claudegateway.atelier.questions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Les questions d'un tour</b> (F-210 / SF-210-01) : ce que l'agent a annoncé par
 * {@code decouper_demande}, l'état de chaque question, et les appels d'outils qui lui sont rattachés.
 *
 * <p>Un objet par tour, tenu par la boucle (un seul fil) : aucune synchronisation. Toute entrée invalide
 * rend un résultat d'outil en erreur, jamais une exception — le découpage n'a pas le droit de tuer le tour
 * qu'il rend lisible.</p>
 */
public final class TurnQuestions {

    /** Questions au plus par tour. */
    public static final int MAX_QUESTIONS = 8;

    /** Longueur maximale d'un libellé. */
    public static final int MAX_LABEL = 80;

    /** Longueur maximale de la raison d'une fusion. */
    public static final int MAX_REASON = 200;

    /** Clé du relevé ({@code terminal_json}) qui porte l'instantané. */
    public static final String REPORT_FIELD = "questions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** L'état d'une question. */
    public enum State {
        EN_COURS, EN_ATTENTE, TERMINEE, NON_ABOUTIE;

        boolean open() {
            return this == EN_COURS || this == EN_ATTENTE;
        }
    }

    /** Le résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
        static Outcome ok(String content) {
            return new Outcome(content, false);
        }

        static Outcome error(String content) {
            return new Outcome(content, true);
        }
    }

    /** Une question telle que l'écran la voit (SSE {@code questions}, {@code terminal_json.questions}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuestionView(int id, String libelle, Integer dependsOn, String state, List<String> toolUseIds) {
    }

    /** La fusion annoncée : une seule réponse attendue. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fusion(String raison) {
    }

    /** L'instantané complet des questions du tour. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(List<QuestionView> questions, Fusion fusion,
            @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<QuestionAnswers.Answer> answers) {

        /** Sans réponses (SF-210-01). */
        public Snapshot(List<QuestionView> questions, Fusion fusion) {
            this(questions, fusion, null);
        }

        /** Le même instantané, avec les réponses par question (F-210 / SF-210-02) ; vide = aucune. */
        public Snapshot withAnswers(List<QuestionAnswers.Answer> list) {
            return list == null || list.isEmpty() ? this : new Snapshot(questions, fusion, List.copyOf(list));
        }
    }

    private static final class Question {
        final int id;
        final String libelle;
        final Integer dependsOn;
        State state;
        final List<String> toolUseIds = new ArrayList<>();

        Question(int id, String libelle, Integer dependsOn, State state) {
            this.id = id;
            this.libelle = libelle;
            this.dependsOn = dependsOn;
            this.state = state;
        }
    }

    private final Map<Integer, Question> questions = new LinkedHashMap<>();
    /** Les questions passées EN_COURS, dans l'ordre : la dernière encore en cours est la courante. */
    private final List<Integer> runningOrder = new ArrayList<>();
    private Fusion fusion;

    /** Vrai si l'agent a découpé ce tour. */
    public boolean isSplit() {
        return !questions.isEmpty();
    }

    /** La question courante, ou {@code null}. */
    public Integer current() {
        for (int i = runningOrder.size() - 1; i >= 0; i--) {
            Question question = questions.get(runningOrder.get(i));
            if (question != null && question.state == State.EN_COURS) {
                return question.id;
            }
        }
        return null;
    }

    /** Exécute un appel d'outil de questions. */
    public Outcome apply(String tool, JsonNode input) {
        if (QuestionToolCatalog.SPLIT.equals(tool)) {
            return split(input);
        }
        if (QuestionToolCatalog.TRACK.equals(tool)) {
            return track(input);
        }
        return Outcome.error("Outil de questions inconnu : " + tool);
    }

    /** {@code decouper_demande}. */
    Outcome split(JsonNode input) {
        JsonNode list = input == null ? null : input.get("questions");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return Outcome.error("Aucune question lisible : passe questions = [{id, libelle}, …].");
        }
        JsonNode fusionNode = input.get("fusion");
        String reason = fusionNode == null || fusionNode.isNull() ? null : text(fusionNode, "raison");
        boolean fused = fusionNode != null && fusionNode.isObject();
        if (!isSplit() && list.size() < 2 && !fused) {
            return Outcome.error("Une seule demande : n'appelle decouper_demande que si le message contient au "
                    + "moins deux demandes distinctes. Continue normalement.");
        }
        if (questions.size() + list.size() > MAX_QUESTIONS) {
            return Outcome.error("Trop de questions pour un tour (" + MAX_QUESTIONS + " au plus) : regroupe-les.");
        }
        int lastId = questions.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Question> added = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (JsonNode item : list) {
            JsonNode idNode = item == null ? null : item.get("id");
            if (idNode == null || !idNode.canConvertToInt() || idNode.asInt() <= 0) {
                return Outcome.error("Chaque question a un id entier positif (1, 2, …).");
            }
            int id = idNode.asInt();
            if (id <= lastId) {
                return Outcome.error("L'id " + id + " est déjà pris ou n'est pas croissant : numérote à partir de "
                        + (lastId + 1) + ".");
            }
            String libelle = text(item, "libelle");
            if (libelle == null) {
                return Outcome.error("La question " + id + " n'a pas de libelle.");
            }
            Integer dependsOn = null;
            JsonNode dependsNode = item.get("depend_de");
            if (dependsNode != null && dependsNode.canConvertToInt() && !dependsNode.isNull()) {
                int target = dependsNode.asInt();
                boolean known = questions.containsKey(target)
                        || added.stream().anyMatch(question -> question.id == target);
                if (known && target != id) {
                    dependsOn = target;
                } else {
                    notes.add("dépendance de la question " + id + " ignorée (question " + target + " inconnue)");
                }
            }
            added.add(new Question(id, truncate(libelle, MAX_LABEL), dependsOn, State.EN_ATTENTE));
            lastId = id;
        }
        for (Question question : added) {
            questions.put(question.id, question);
        }
        if (fused) {
            fusion = new Fusion(reason == null ? "" : truncate(reason, MAX_REASON));
        }
        if (current() == null) {
            for (Question question : added) {
                if (question.dependsOn == null) {
                    start(question);
                    break;
                }
            }
        }
        StringBuilder content = new StringBuilder(fused
                ? "Découpage annoncé, réponses FUSIONNÉES : l'utilisateur voit les demandes et une seule réponse."
                : "Découpage annoncé : l'utilisateur voit un fil par question.");
        Integer running = current();
        if (running != null) {
            content.append(" Question ").append(running).append(" en cours");
        }
        content.append(". Fais avancer chaque question avec suivre_question, dans la même réponse que ses "
                + "outils.");
        if (!notes.isEmpty()) {
            content.append(" Note : ").append(String.join(" ; ", notes)).append('.');
        }
        return Outcome.ok(content.toString());
    }

    /** {@code suivre_question}. */
    Outcome track(JsonNode input) {
        if (!isSplit()) {
            return Outcome.error("Aucune question annoncée dans ce tour : suivre_question ne sert qu'après "
                    + "decouper_demande.");
        }
        JsonNode idNode = input == null ? null : input.get("id");
        Question question = idNode == null || !idNode.canConvertToInt() ? null : questions.get(idNode.asInt());
        if (question == null) {
            return Outcome.error("Question inconnue : les numéros annoncés sont " + questions.keySet() + ".");
        }
        State state;
        try {
            String raw = text(input, "etat");
            state = raw == null ? null : State.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            state = null;
        }
        if (state == null) {
            return Outcome.error("État inconnu : EN_COURS, EN_ATTENTE, TERMINEE ou NON_ABOUTIE.");
        }
        if (state == State.EN_COURS) {
            start(question);
        } else {
            question.state = state;
        }
        return Outcome.ok("Question " + question.id + " : " + state.name() + ".");
    }

    private void start(Question question) {
        question.state = State.EN_COURS;
        runningOrder.remove(Integer.valueOf(question.id));
        runningOrder.add(question.id);
    }

    /**
     * Rattache un appel d'outil visible à la question courante.
     *
     * @return le numéro de la question, ou {@code null} sans question courante
     */
    public Integer attach(String toolUseId) {
        Integer id = current();
        if (id != null && toolUseId != null) {
            questions.get(id).toolUseIds.add(toolUseId);
        }
        return id;
    }

    /**
     * Clôt le tour : une question restée ouverte est {@code TERMINEE} si le tour a abouti (la réponse
     * finale couvre toutes les demandes), {@code NON_ABOUTIE} sinon. Un état posé par l'agent est gardé.
     */
    public void close(boolean completed) {
        for (Question question : questions.values()) {
            if (question.state.open()) {
                question.state = completed ? State.TERMINEE : State.NON_ABOUTIE;
            }
        }
    }

    /**
     * Les questions annoncées restées sans bloc de réponse alors que d'autres en ont un (F-210 /
     * SF-210-02) : elles ne disparaissent pas, elles sont {@code NON_ABOUTIE}.
     */
    public void markUnanswered(List<Integer> ids) {
        for (Integer id : ids) {
            Question question = questions.get(id);
            if (question != null) {
                question.state = State.NON_ABOUTIE;
            }
        }
    }

    /** L'instantané, ou {@code null} sans découpage. */
    public Snapshot snapshot() {
        if (!isSplit()) {
            return null;
        }
        List<QuestionView> views = new ArrayList<>();
        for (Question question : questions.values()) {
            views.add(new QuestionView(question.id, question.libelle, question.dependsOn, question.state.name(),
                    List.copyOf(question.toolUseIds)));
        }
        return new Snapshot(List.copyOf(views), fusion);
    }

    /**
     * Range l'instantané dans le relevé du message. Sans découpage, le relevé traverse inchangé ; sans
     * relevé, un document minimal est créé ; un relevé illisible est laissé tel quel.
     */
    public static String attachTo(String reportJson, Snapshot snapshot) {
        if (snapshot == null) {
            return reportJson;
        }
        ObjectNode report;
        if (reportJson == null || reportJson.isBlank()) {
            report = MAPPER.createObjectNode();
        } else {
            try {
                JsonNode parsed = MAPPER.readTree(reportJson);
                if (!(parsed instanceof ObjectNode object)) {
                    return reportJson;
                }
                report = object;
            } catch (JsonProcessingException ex) {
                return reportJson;
            }
        }
        report.set(REPORT_FIELD, MAPPER.valueToTree(new Snapshot(snapshot.questions(), snapshot.fusion())));
        if (snapshot.answers() != null && !snapshot.answers().isEmpty()) {
            // F-210 / SF-210-02 : les réponses par question, à part — `terminal_json.answers`.
            report.set(QuestionAnswers.REPORT_FIELD, MAPPER.valueToTree(snapshot.answers()));
        }
        try {
            return MAPPER.writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            return reportJson;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").strip();
        return text.isEmpty() ? null : text;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1).strip() + "…";
    }
}
