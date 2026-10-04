package fr.claudegateway.atelier.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Le plan structuré d'un sujet guidé</b> (F-176 / SF-176-03, cadrage §4) : des étapes, chacune
 * avec l'action, sa <b>classe de risque</b>, comment la <b>vérifier</b>, comment <b>revenir en
 * arrière</b>, et l'attente (F-175) dont elle dépend.
 *
 * <p>Le plan <b>peut être partiel</b> : une inconnue est une étape qui attend un input (« obtenir de
 * Gino X »), reliée à une attente par sa clé. On exécute les étapes sûres, le reste attend.</p>
 *
 * <p><b>Aucun plan mal formé ne fait échouer un tour</b> (même doctrine qu'{@code AtelierPlan}) :
 * tout est normalisé — champs bornés, risque inconnu traité comme une <b>modification réversible</b>
 * (prudence), étapes au-delà de {@link #MAX_STEPS} ignorées.</p>
 */
public record JourneyPlan(List<Step> steps) {

    public static final int MAX_STEPS = 20;
    static final int MAX_TITLE = 300;
    static final int MAX_TEXT = 300;
    static final int MAX_KEY = 200;
    static final int MAX_EVIDENCE = 500;

    public static final JourneyPlan EMPTY = new JourneyPlan(List.of());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** La classe de risque d'une étape (cadrage §4, « La porte, par classe de risque »). */
    public enum Risk {
        /** Lecture : toujours libre. */
        LECTURE("lecture"),
        /** Notes du sujet (STATE.md, PLAN-ACTION.md, notes, carte) : toujours libres. */
        NOTES("notes"),
        /** Modification réversible (édition en branche, fichier local). */
        REVERSIBLE("modification réversible"),
        /** Externe ou irréversible (push, merge, apply, kubectl apply/delete, envoi, prod). */
        EXTERNE("externe / irréversible");

        private final String label;

        Risk(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Lecture tolérante ; un risque inconnu vaut {@link #REVERSIBLE} (prudence : c'est une modification). */
        static Risk parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return REVERSIBLE;
            }
            String v = java.text.Normalizer.normalize(raw.strip().toUpperCase(Locale.ROOT),
                    java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            if (v.startsWith("LECT") || v.equals("READ")) {
                return LECTURE;
            }
            if (v.startsWith("NOTE")) {
                return NOTES;
            }
            if (v.startsWith("EXT") || v.startsWith("IRR") || v.equals("PROD")) {
                return EXTERNE;
            }
            return REVERSIBLE;
        }
    }

    /** Où en est une étape (SF-176-05 la fait avancer). */
    public enum StepStatus {
        A_FAIRE, FAIT, VERIFIE, ECHEC;

        static StepStatus parse(String raw) {
            if (raw == null) {
                return A_FAIRE;
            }
            String v = java.text.Normalizer.normalize(raw.strip().toUpperCase(Locale.ROOT),
                    java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            return switch (v) {
                case "FAIT", "DONE" -> FAIT;
                case "VERIFIE", "VERIFIED", "OK" -> VERIFIE;
                case "ECHEC", "FAILED", "KO" -> ECHEC;
                default -> A_FAIRE;
            };
        }
    }

    /**
     * Une étape.
     *
     * @param title    l'action, à l'infinitif ou l'impératif
     * @param risk     sa classe de risque
     * @param verify   comment on saura qu'elle a marché
     * @param rollback comment revenir en arrière
     * @param waitsOn  la clé de l'attente (F-175) dont elle dépend, ou {@code null}
     * @param status   où elle en est
     * @param evidence la preuve de sa vérification (SF-176-05), ou {@code null}
     */
    public record Step(String title, Risk risk, String verify, String rollback, String waitsOn,
                       StepStatus status, String evidence) {

        /** La même étape sans son avancement : ce que l'utilisateur valide. */
        Step content() {
            return new Step(title, risk, verify, rollback, waitsOn, StepStatus.A_FAIRE, null);
        }
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * Le plan reçu du modèle ({@code steps} : tableau d'objets), normalisé. Un tableau absent ou vide
     * donne {@link #EMPTY}.
     */
    public static JourneyPlan fromToolInput(JsonNode stepsNode) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return EMPTY;
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            if (steps.size() >= MAX_STEPS) {
                break;
            }
            String title = bound(text(node, "title", "action"), MAX_TITLE);
            if (title == null) {
                continue; // une étape sans action n'est pas une étape
            }
            steps.add(new Step(title,
                    Risk.parse(text(node, "risk")),
                    bound(text(node, "verify", "verification"), MAX_TEXT),
                    bound(text(node, "rollback", "retour"), MAX_TEXT),
                    bound(text(node, "waits_on", "waitsOn", "attente"), MAX_KEY),
                    StepStatus.A_FAIRE,
                    null));
        }
        return steps.isEmpty() ? EMPTY : new JourneyPlan(List.copyOf(steps));
    }

    /** Sérialisation pour la persistance. */
    public String toJson() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Step step : steps) {
            ObjectNode node = array.addObject();
            node.put("title", step.title());
            node.put("risk", step.risk().name());
            node.put("verify", step.verify());
            node.put("rollback", step.rollback());
            node.put("waitsOn", step.waitsOn());
            node.put("status", step.status().name());
            node.put("evidence", step.evidence());
        }
        return array.toString();
    }

    /** Relecture depuis la persistance ; un JSON illisible donne {@link #EMPTY} (jamais d'exception). */
    public static JourneyPlan fromJson(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return EMPTY;
            }
            List<Step> steps = new ArrayList<>();
            for (JsonNode node : root) {
                String title = text(node, "title");
                if (title == null) {
                    continue;
                }
                steps.add(new Step(title, Risk.parse(text(node, "risk")), text(node, "verify"),
                        text(node, "rollback"), text(node, "waitsOn"), StepStatus.parse(text(node, "status")),
                        text(node, "evidence")));
            }
            return steps.isEmpty() ? EMPTY : new JourneyPlan(List.copyOf(steps));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * Vrai si le contenu de l'étape {@code index} diffère de celui du plan validé {@code validated} — ou
     * si elle n'y existait pas. C'est ce que l'écran marque dans un amendement.
     */
    public boolean changedSince(JourneyPlan validated, int index) {
        if (validated == null || index >= validated.steps().size()) {
            return true;
        }
        return !Objects.equals(steps.get(index).content(), validated.steps().get(index).content());
    }

    /** Remplace l'avancement d'une étape (SF-176-05). */
    public JourneyPlan withStep(int index, StepStatus status, String evidence) {
        List<Step> copy = new ArrayList<>(steps);
        Step s = copy.get(index);
        copy.set(index, new Step(s.title(), s.risk(), s.verify(), s.rollback(), s.waitsOn(), status,
                bound(evidence, MAX_EVIDENCE)));
        return new JourneyPlan(List.copyOf(copy));
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull()) {
                String s = v.asText("").strip();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    static String bound(String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
