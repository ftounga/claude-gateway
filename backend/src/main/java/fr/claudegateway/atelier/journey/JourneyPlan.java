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
 * (prudence). Seule exception, voulue (F-208) : un plan de plus de {@link #MAX_STEPS} étapes ou une
 * dépendance invalide est <b>refusé avec un message au modèle</b> — plus jamais d'étapes ignorées en
 * silence.</p>
 *
 * <p>F-208 / SF-208-01 : une étape peut déclarer ses <b>dépendances</b> ({@code dependsOn}, rangs
 * d'étapes antérieures) et porter des <b>sous-étapes</b> cochées avec preuve. Ces deux champs ne sont
 * écrits dans le JSON que s'ils ne sont pas vides : un plan d'avant F-208 se relit et se réécrit à
 * l'identique.</p>
 */
public record JourneyPlan(List<Step> steps) {

    public static final int MAX_STEPS = 40;
    /** Sous-étapes d'une étape, au plus (F-208). */
    public static final int MAX_SUB_STEPS = 12;
    static final int MAX_SUB_TITLE = 200;
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

    /** Une sous-étape d'une étape (F-208) : cochée ou non, avec sa preuve. */
    public record SubStep(String title, boolean done, String evidence) {
    }

    /**
     * Une étape.
     *
     * @param title     l'action, à l'infinitif ou l'impératif
     * @param risk      sa classe de risque
     * @param verify    comment on saura qu'elle a marché
     * @param rollback  comment revenir en arrière
     * @param waitsOn   la clé de l'attente (F-175) dont elle dépend, ou {@code null}
     * @param status    où elle en est
     * @param evidence  la preuve de sa vérification (SF-176-05), ou {@code null}
     * @param dependsOn les rangs (à partir de 1) des étapes antérieures dont elle dépend (F-208) ; vide =
     *                  les étapes précédentes
     * @param subSteps  ses sous-étapes (F-208), vide si elle n'est pas détaillée
     */
    public record Step(String title, Risk risk, String verify, String rollback, String waitsOn,
                       StepStatus status, String evidence, List<Integer> dependsOn, List<SubStep> subSteps) {

        public Step {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
        }

        /** Une étape sans dépendances déclarées ni sous-étapes (forme d'avant F-208). */
        public Step(String title, Risk risk, String verify, String rollback, String waitsOn,
                    StepStatus status, String evidence) {
            this(title, risk, verify, rollback, waitsOn, status, evidence, List.of(), List.of());
        }

        /** La même étape sans son avancement : ce que l'utilisateur valide (les dépendances en font partie). */
        Step content() {
            return new Step(title, risk, verify, rollback, waitsOn, StepStatus.A_FAIRE, null, dependsOn, List.of());
        }

        /** Combien de sous-étapes faites. */
        public int subStepsDone() {
            return (int) subSteps.stream().filter(SubStep::done).count();
        }

        /** État dérivé « en cours » (F-208) : à faire, mais au moins une sous-étape faite. */
        public boolean inProgress() {
            return status == StepStatus.A_FAIRE && subStepsDone() > 0;
        }

        /** Faite ou vérifiée. */
        public boolean isDone() {
            return status == StepStatus.FAIT || status == StepStatus.VERIFIE;
        }
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * Le plan reçu du modèle ({@code steps} : tableau d'objets), normalisé. Un tableau absent ou vide
     * donne {@link #EMPTY}.
     *
     * @throws InvalidJourneyException plus de {@link #MAX_STEPS} étapes, ou un {@code depends_on} qui ne
     *                                 cite pas une étape antérieure (F-208 : refus explicite, jamais
     *                                 d'ignorance silencieuse)
     */
    public static JourneyPlan fromToolInput(JsonNode stepsNode) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return EMPTY;
        }
        List<JsonNode> titled = new ArrayList<>();
        for (JsonNode node : stepsNode) {
            if (bound(text(node, "title", "action"), MAX_TITLE) != null) {
                titled.add(node); // une étape sans action n'est pas une étape
            }
        }
        if (titled.size() > MAX_STEPS) {
            throw new InvalidJourneyException("Le plan compte " + titled.size() + " étapes : " + MAX_STEPS
                    + " au plus. Regroupe des étapes voisines (update_sub_steps détaille une étape en "
                    + "sous-étapes) et renvoie le plan complet. Rien n'a été enregistré.");
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode node : titled) {
            int rank = steps.size() + 1;
            steps.add(new Step(bound(text(node, "title", "action"), MAX_TITLE),
                    Risk.parse(text(node, "risk")),
                    bound(text(node, "verify", "verification"), MAX_TEXT),
                    bound(text(node, "rollback", "retour"), MAX_TEXT),
                    bound(text(node, "waits_on", "waitsOn", "attente"), MAX_KEY),
                    StepStatus.A_FAIRE,
                    null,
                    dependsOnFromToolInput(node, rank),
                    List.of()));
        }
        return steps.isEmpty() ? EMPTY : new JourneyPlan(List.copyOf(steps));
    }

    /** Les dépendances déclarées d'une étape, validées : des rangs d'étapes antérieures, triés, uniques. */
    private static List<Integer> dependsOnFromToolInput(JsonNode node, int rank) {
        JsonNode raw = node.get("depends_on");
        if (raw == null) {
            raw = node.get("dependsOn");
        }
        if (raw == null || raw.isNull()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        if (raw.isArray()) {
            raw.forEach(items::add);
        } else {
            items.add(raw);
        }
        java.util.TreeSet<Integer> ranks = new java.util.TreeSet<>();
        for (JsonNode item : items) {
            for (String token : item.asText("").split("[,;\\s]+")) {
                if (token.isBlank()) {
                    continue;
                }
                int dep;
                try {
                    dep = Integer.parseInt(token.replaceAll("[^0-9-]", ""));
                } catch (NumberFormatException e) {
                    throw invalidDependency(rank, token);
                }
                if (dep < 1 || dep >= rank) {
                    throw invalidDependency(rank, String.valueOf(dep));
                }
                ranks.add(dep);
            }
        }
        return List.copyOf(ranks);
    }

    private static InvalidJourneyException invalidDependency(int rank, String cited) {
        String allowed = rank <= 1 ? "la première étape ne dépend d'aucune autre"
                : "ne peut citer que des étapes antérieures (1 à " + (rank - 1) + ")";
        return new InvalidJourneyException("Étape " + rank + " : depends_on cite « " + cited + " » ; " + allowed
                + ". Rien n'a été enregistré.");
    }

    /**
     * Les sous-étapes reçues du modèle (F-208), normalisées : sans titre = ignorée, champs bornés.
     *
     * @throws InvalidJourneyException plus de {@link #MAX_SUB_STEPS} sous-étapes
     */
    public static List<SubStep> subStepsFromToolInput(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SubStep> subSteps = new ArrayList<>();
        for (JsonNode item : node) {
            String title = bound(text(item, "title"), MAX_SUB_TITLE);
            if (title == null) {
                continue;
            }
            subSteps.add(new SubStep(title, item.path("done").asBoolean(false),
                    bound(text(item, "evidence"), MAX_EVIDENCE)));
        }
        if (subSteps.size() > MAX_SUB_STEPS) {
            throw new InvalidJourneyException("Une étape compte " + MAX_SUB_STEPS + " sous-étapes au plus (reçu "
                    + subSteps.size() + ") : regroupe-les. Rien n'a été enregistré.");
        }
        return List.copyOf(subSteps);
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
            // F-208 : écrits seulement s'ils existent — un plan d'avant F-208 reste identique à l'octet.
            if (!step.dependsOn().isEmpty()) {
                ArrayNode deps = node.putArray("dependsOn");
                step.dependsOn().forEach(deps::add);
            }
            if (!step.subSteps().isEmpty()) {
                ArrayNode subs = node.putArray("subSteps");
                for (SubStep sub : step.subSteps()) {
                    ObjectNode subNode = subs.addObject();
                    subNode.put("title", sub.title());
                    subNode.put("done", sub.done());
                    subNode.put("evidence", sub.evidence());
                }
            }
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
                List<Integer> deps = new ArrayList<>();
                for (JsonNode dep : node.path("dependsOn")) {
                    if (dep.canConvertToInt() && dep.asInt() >= 1 && dep.asInt() <= steps.size()) {
                        deps.add(dep.asInt());
                    }
                }
                List<SubStep> subs = new ArrayList<>();
                for (JsonNode sub : node.path("subSteps")) {
                    String subTitle = text(sub, "title");
                    if (subTitle != null) {
                        subs.add(new SubStep(subTitle, sub.path("done").asBoolean(false), text(sub, "evidence")));
                    }
                }
                steps.add(new Step(title, Risk.parse(text(node, "risk")), text(node, "verify"),
                        text(node, "rollback"), text(node, "waitsOn"), StepStatus.parse(text(node, "status")),
                        text(node, "evidence"), deps, subs));
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
                bound(evidence, MAX_EVIDENCE), s.dependsOn(), s.subSteps()));
        return new JourneyPlan(List.copyOf(copy));
    }

    /** Remplace les sous-étapes d'une étape (F-208) ; l'état de l'étape ne change pas. */
    public JourneyPlan withSubSteps(int index, List<SubStep> subSteps) {
        List<Step> copy = new ArrayList<>(steps);
        Step s = copy.get(index);
        copy.set(index, new Step(s.title(), s.risk(), s.verify(), s.rollback(), s.waitsOn(), s.status(),
                s.evidence(), s.dependsOn(), subSteps));
        return new JourneyPlan(List.copyOf(copy));
    }

    /** Remplace les dépendances d'une étape (F-208, report à l'amendement). */
    JourneyPlan withDependsOn(int index, List<Integer> dependsOn) {
        List<Step> copy = new ArrayList<>(steps);
        Step s = copy.get(index);
        copy.set(index, new Step(s.title(), s.risk(), s.verify(), s.rollback(), s.waitsOn(), s.status(),
                s.evidence(), dependsOn, s.subSteps()));
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
