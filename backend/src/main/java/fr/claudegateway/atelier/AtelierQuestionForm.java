package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Le lot de <b>questions structurées</b> d'un appel {@code demander} (F-164 / SF-164-01), une fois
 * <b>validé</b> et prêt à être relayé à l'écran.
 *
 * <p>C'est le contrat que le modèle doit respecter : 1 à 4 questions, chacune avec un intitulé, un
 * texte, un mode (choix simple ou multiple) et 1 à 8 options ; au plus une option recommandée par
 * question (elle servira de défaut à SF-164-03). L'option libre « autre / tape ta réponse » est
 * <b>implicite</b> : jamais déclarée par le modèle, toujours acceptée à la réponse.</p>
 *
 * <p>La validation <b>échoue bruyamment</b> ({@link AtelierQuestionRejectedException}) : un lot mal
 * formé rend au modèle une phrase qui dit quoi corriger, jamais un tour figé.</p>
 */
public record AtelierQuestionForm(List<Question> questions) {

    /** Nombre minimal et maximal de questions dans un lot (parité Claude Code : au plus 4). */
    public static final int MIN_QUESTIONS = 1;
    public static final int MAX_QUESTIONS = 4;

    /** Nombre minimal et maximal d'options par question. */
    public static final int MIN_OPTIONS = 1;
    public static final int MAX_OPTIONS = 8;

    private static final int MAX_QUESTION_CHARS = 2000;
    private static final int MAX_HEADER_CHARS = 120;
    private static final int MAX_LABEL_CHARS = 200;
    private static final int MAX_DESCRIPTION_CHARS = 500;

    /**
     * Une question du lot.
     *
     * @param header      court intitulé (onglet) ; jamais vide (dérivé du texte si absent)
     * @param question    le texte de la question posée à l'utilisateur
     * @param multiSelect vrai si plusieurs options peuvent être cochées (choix multiple)
     * @param options     les options proposées (1 à 8) ; l'option libre « autre » n'y figure pas
     */
    public record Question(String header, String question, boolean multiSelect, List<Option> options) {
    }

    /**
     * Une option proposée pour une question.
     *
     * @param label       le libellé affiché et renvoyé à la sélection ; jamais vide
     * @param description précision facultative (peut être vide)
     * @param recommended vrai pour l'option recommandée ; au plus une par question (défaut de SF-164-03)
     */
    public record Option(String label, String description, boolean recommended) {
    }

    /**
     * Valide l'input de l'outil {@code demander} et en construit le lot.
     *
     * @throws AtelierQuestionRejectedException si le lot est absent, vide, trop grand, ou mal formé
     */
    public static AtelierQuestionForm from(JsonNode input) {
        JsonNode questionsNode = input == null ? null : input.get("questions");
        if (questionsNode == null || !questionsNode.isArray() || questionsNode.isEmpty()) {
            throw new AtelierQuestionRejectedException(
                    "Fournis un tableau « questions » d'au moins une question pour utiliser « demander ».");
        }
        if (questionsNode.size() > MAX_QUESTIONS) {
            throw new AtelierQuestionRejectedException(
                    "Au plus " + MAX_QUESTIONS + " questions par appel : regroupe ou pose-les en plusieurs fois.");
        }
        List<Question> questions = new ArrayList<>(questionsNode.size());
        for (int i = 0; i < questionsNode.size(); i++) {
            questions.add(readQuestion(questionsNode.get(i), i + 1));
        }
        return new AtelierQuestionForm(List.copyOf(questions));
    }

    private static Question readQuestion(JsonNode node, int position) {
        if (node == null || !node.isObject()) {
            throw new AtelierQuestionRejectedException(
                    "La question " + position + " doit être un objet { question, options }.");
        }
        String question = text(node, "question");
        if (question.isEmpty()) {
            throw new AtelierQuestionRejectedException(
                    "La question " + position + " n'a pas de texte « question ».");
        }
        question = bound(question, MAX_QUESTION_CHARS);
        String header = text(node, "header");
        header = header.isEmpty() ? deriveHeader(question) : bound(header, MAX_HEADER_CHARS);
        boolean multiSelect = node.path("multiSelect").asBoolean(false);

        JsonNode optionsNode = node.get("options");
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.isEmpty()) {
            throw new AtelierQuestionRejectedException(
                    "La question " + position + " doit proposer au moins une option "
                            + "(l'option libre « autre » est ajoutée automatiquement).");
        }
        if (optionsNode.size() > MAX_OPTIONS) {
            throw new AtelierQuestionRejectedException(
                    "La question " + position + " propose trop d'options (maximum " + MAX_OPTIONS + ").");
        }
        List<Option> options = new ArrayList<>(optionsNode.size());
        int recommended = 0;
        for (int i = 0; i < optionsNode.size(); i++) {
            Option option = readOption(optionsNode.get(i), position, i + 1);
            if (option.recommended()) {
                recommended++;
            }
            options.add(option);
        }
        if (recommended > 1) {
            throw new AtelierQuestionRejectedException(
                    "La question " + position + " ne peut recommander qu'une seule option.");
        }
        return new Question(header, question, multiSelect, List.copyOf(options));
    }

    private static Option readOption(JsonNode node, int question, int position) {
        if (node == null || !node.isObject()) {
            throw new AtelierQuestionRejectedException(
                    "L'option " + position + " de la question " + question
                            + " doit être un objet { label, description }.");
        }
        String label = text(node, "label");
        if (label.isEmpty()) {
            throw new AtelierQuestionRejectedException(
                    "L'option " + position + " de la question " + question + " n'a pas de « label ».");
        }
        String description = bound(text(node, "description"), MAX_DESCRIPTION_CHARS);
        boolean recommended = node.path("recommended").asBoolean(false);
        return new Option(bound(label, MAX_LABEL_CHARS), description, recommended);
    }

    /** Texte d'un champ, borné et sans espaces de bord ; {@code ""} si absent ou non textuel. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static String bound(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Intitulé court dérivé du texte de la question quand le modèle n'en fournit pas. */
    private static String deriveHeader(String question) {
        return question.length() <= MAX_HEADER_CHARS ? question : question.substring(0, MAX_HEADER_CHARS);
    }
}
