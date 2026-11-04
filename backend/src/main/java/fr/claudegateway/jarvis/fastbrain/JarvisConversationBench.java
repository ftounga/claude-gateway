package fr.claudegateway.jarvis.fastbrain;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;

/**
 * <b>Le banc de conversation, porte de déploiement de Jarvis</b> (F-239 / SF-239-07, D4).
 *
 * <p>Rejoue des <b>scénarios de dialogue multi-tours</b> (salutations, petite conversation, « commençons », point,
 * demande métier, phrase mal entendue, interruption, silence, remerciement, au revoir, et les vrais tours du PO du
 * 2026-11-02) sur le vrai cerveau rapide (à blanc), chaque tour voyant les échanges précédents du scénario dans son
 * contexte ; un juge note chaque tour sur une grille explicite (naturel, concision, formule robotique, délégation
 * inutile, sens juste, question en retour, fait non ancré, action non déléguée). Chaque scénario a un <b>budget en
 * euros</b> : une délégation à Opus coûte ≈ un tour Opus (≈ 0,98 €) — une délégation inutile sur un bavardage, une
 * salutation ou une navigation fait donc échouer le scénario.</p>
 *
 * <p><b>Porte</b> : ≥ {@value #PASS_RATE_PERCENT} % de scénarios réussis, 0 délégation inutile, 0 formule robotique,
 * 0 fait non ancré, 0 action non déléguée.</p>
 */
final class JarvisConversationBench {

    static final int PASS_RATE_PERCENT = 90;
    static final String JUDGE_TOOL = "noter_tour";
    static final int MIN_NATURAL = 3;

    /** F-241 — les défauts graves du portrait (champ du juge → libellé de la porte), chacun à zéro obligatoire. */
    static final Map<String, String> GRAVES;

    static {
        Map<String, String> graves = new LinkedHashMap<>();
        graves.put("sujet_interdit", "sujet interdit évoqué");
        graves.put("humour_sensible", "humour sur un sujet sensible non ouvert");
        graves.put("personnel_en_presentation", "élément personnel en présentation");
        // F-241 / SF-241-04 — la rareté et la variété.
        graves.put("allusion_forcee", "allusion forcée");
        graves.put("repetition", "allusion ou trait répété");
        GRAVES = java.util.Collections.unmodifiableMap(graves);
    }
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Un tour attendu d'un scénario. */
    record Turn(String said, String expect, String expectMark, String expectTarget) {

        Turn(String said, String expect) {
            this(said, expect, null, null);
        }

        Turn(String said, String expect, String expectMark) {
            this(said, expect, expectMark, null);
        }
    }

    // ------------------------------------------------------------------ F-245 / SF-245-05 : le routage

    /** Défaut grave : un échange sans tâche (« bonjour », « où en est… ») envoyé dans un sujet ou un chantier. */
    static final String GRAVE_HELLO_IN_SUBJECT = "échange sans tâche envoyé dans un sujet";
    /** Défaut grave : une demande générale (tour d'horizon, perso, courrier) envoyée hors du fil Jarvis. */
    static final String GRAVE_GENERAL_OUTSIDE = "demande générale hors du fil Jarvis";
    /** Défaut grave : une demande ciblée (un sujet, un chantier) envoyée au fil Jarvis. */
    static final String GRAVE_TARGETED_IN_JARVIS = "demande ciblée envoyée au fil Jarvis";

    /**
     * F-245 / SF-245-05 — la destination attendue d'un tour, contrôlée sans modèle : {@code aucun_sujet} (rien ne part
     * dans un sujet : une réponse, ou une délégation au fil Jarvis), {@code jarvis} (une délégation va au fil Jarvis),
     * {@code cible} (une délégation ne va pas au fil Jarvis) ; {@code null} = non contrôlée. Rend le défaut grave, ou
     * {@code null}. Une réponse dite reste toujours dans le fil Jarvis (SF-245-02) : elle ne viole aucune attente.
     */
    static String targetDefect(Turn turn, JarvisFastBrainService.Outcome outcome) {
        if (turn.expectTarget() == null || outcome == null || outcome.kind() != JarvisFastBrainService.Kind.DELEGATE
                || outcome.delegation() == null) {
            return null;
        }
        boolean toJarvis = JarvisFastBrainService.JARVIS_THREAD_TARGET.equals(outcome.delegation().subjectId());
        return switch (turn.expectTarget()) {
            case "aucun_sujet" -> toJarvis ? null : GRAVE_HELLO_IN_SUBJECT;
            case "jarvis" -> toJarvis ? null : GRAVE_GENERAL_OUTSIDE;
            case "cible" -> toJarvis ? GRAVE_TARGETED_IN_JARVIS : null;
            default -> null;
        };
    }

    /**
     * F-241 / SF-241-05 — la marque 📌 attendue d'un tour, contrôlée sans modèle : {@code noter}, {@code retenir},
     * {@code oublier}, ou {@code aucune} (aucune ligne 📌 relayée) ; {@code null} = non contrôlée. Rend le défaut, ou
     * {@code null}.
     */
    static String markDefect(Turn turn, List<fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin> pins,
            String said) {
        if (turn.expectMark() == null) {
            return null;
        }
        if ("sans_relance".equals(turn.expectMark())) {
            return fr.claudegateway.jarvis.portrait.JarvisPortraitNote.isCheckin(said) ? "relance pendant une tâche"
                    : null;
        }
        List<fr.claudegateway.jarvis.portrait.JarvisPortraitNote.Pin> got = pins == null ? List.of() : pins;
        if ("aucune".equals(turn.expectMark())) {
            return got.isEmpty() ? null : "marque 📌 inattendue";
        }
        return got.stream().anyMatch(p -> turn.expectMark().equals(p.action())) ? null
                : "marque 📌 " + turn.expectMark() + " absente";
    }

    /**
     * Un scénario : ses tours, sa coupure éventuelle (interruption), sa catégorie ; F-240 / SF-240-04 : un complément
     * de contexte propre au scénario (les derniers échanges du fil, avec une réponse d'Opus), ou {@code null}.
     */
    record Scenario(String id, String category, String heardBeforeCut, List<Turn> turns, String context,
            Personal personal) {

        Scenario(String id, String category, String heardBeforeCut, List<Turn> turns) {
            this(id, category, heardBeforeCut, turns, null, null);
        }

        Scenario(String id, String category, String heardBeforeCut, List<Turn> turns, String context) {
            this(id, category, heardBeforeCut, turns, context, null);
        }

        /** F-241 — le portrait FICTIF du scénario (jamais un portrait réel), ou {@code null}. */
        fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot portrait() {
            return personal == null || personal.sections() == null ? null
                    : fr.claudegateway.jarvis.portrait.JarvisPortraitNote.fixture(personal.sections(),
                            personal.souvenirs(), java.time.OffsetDateTime.parse("2026-10-01T09:00:00Z"));
        }

        boolean presentation() {
            return personal != null && personal.presentation();
        }
    }

    /**
     * F-241 / SF-241-03 — ce qu'un scénario porte de personnel : un portrait <b>fictif</b> (sections, souvenirs), le
     * mode présentation, et (SF-241-04) la trace des allusions récentes.
     */
    record Personal(Map<String, String> sections, List<String> souvenirs, boolean presentation,
            List<String> recentAllusions) {
    }

    /** F-240 / SF-240-04 — la route d'une demande de suite lue sur l'appareil (ligne-marque « ⤵ »). */
    static final String ROUTE_MORE = "descendre";

    /** Le jeu : contexte figé, scénarios, budget par tour, coût d'un tour Opus. */
    record Set(String context, List<Scenario> scenarios, BigDecimal budgetEurPerTurn, BigDecimal opusTurnEur) {
    }

    /** Le verdict d'un tour (sans contenu client : le jeu est fictif). */
    record TurnVerdict(String scenarioId, int turn, String category, String route, String expect, boolean judged,
            int natural, boolean concise, boolean robotic, boolean uselessDelegation, boolean meaningRight,
            boolean returnQuestion, boolean ungrounded, boolean actionNotDelegated, String said, String comment,
            int words, List<String> graves) {

        /** F-241 / SF-241-05 — le même verdict, avec un défaut grave de plus (contrôle sans modèle). */
        TurnVerdict withGrave(String grave) {
            List<String> all = new ArrayList<>(graves);
            all.add(grave);
            return new TurnVerdict(scenarioId, turn, category, route, expect, judged, natural, concise, robotic,
                    uselessDelegation, meaningRight, returnQuestion, ungrounded, actionNotDelegated, said, comment,
                    words, List.copyOf(all));
        }

        /** Forme d'avant F-241 : aucun défaut grave personnel relevé. */
        TurnVerdict(String scenarioId, int turn, String category, String route, String expect, boolean judged,
                int natural, boolean concise, boolean robotic, boolean uselessDelegation, boolean meaningRight,
                boolean returnQuestion, boolean ungrounded, boolean actionNotDelegated, String said, String comment,
                int words) {
            this(scenarioId, turn, category, route, expect, judged, natural, concise, robotic, uselessDelegation,
                    meaningRight, returnQuestion, ungrounded, actionNotDelegated, said, comment, words, List.of());
        }

        /** F-240 / SF-240-01 — les mots dits sont comptés d'après {@code said} (même règle que l'écran). */
        TurnVerdict(String scenarioId, int turn, String category, String route, String expect, boolean judged,
                int natural, boolean concise, boolean robotic, boolean uselessDelegation, boolean meaningRight,
                boolean returnQuestion, boolean ungrounded, boolean actionNotDelegated, String said, String comment) {
            this(scenarioId, turn, category, route, expect, judged, natural, concise, robotic, uselessDelegation,
                    meaningRight, returnQuestion, ungrounded, actionNotDelegated, said, comment,
                    fr.claudegateway.jarvis.JarvisSpokenWords.count(said));
        }
    }

    /**
     * F-240 / SF-240-01 — p50/p90 des mots des réponses dites par le cerveau rapide lui-même (route
     * {@code repondre}), à titre <b>informatif</b> : la porte n'en dépend pas. {@code null} sans réponse.
     */
    static Integer[] fastWords(List<TurnVerdict> turns) {
        List<Integer> words = turns.stream().filter(t -> "repondre".equals(t.route())).map(TurnVerdict::words)
                .sorted().toList();
        return new Integer[] { fr.claudegateway.jarvis.JarvisSpokenWords.percentile(words, 50),
                fr.claudegateway.jarvis.JarvisSpokenWords.percentile(words, 90) };
    }

    /** Le verdict d'un scénario. */
    record ScenarioVerdict(String scenarioId, String category, boolean passed, BigDecimal costEur,
            BigDecimal budgetEur, int delegations, List<String> reasons) {
    }

    /** Le résultat d'un passage. */
    record Result(List<TurnVerdict> turns, List<ScenarioVerdict> scenarios, boolean gatePassed, List<String> failures,
            double passRate, BigDecimal costPerExchangeEur, double delegationRate) {
    }

    private JarvisConversationBench() {
    }

    static Set load() {
        try (InputStream in = JarvisConversationBench.class.getResourceAsStream("/jarvis/conversation-scenarios.json")) {
            JsonNode root = JSON.readTree(in);
            List<Scenario> scenarios = new ArrayList<>();
            for (JsonNode s : root.get("scenarios")) {
                List<Turn> turns = new ArrayList<>();
                for (JsonNode t : s.get("turns")) {
                    turns.add(new Turn(t.get("said").asText(), t.path("expect").asText("any"),
                            t.hasNonNull("expectMark") ? t.get("expectMark").asText() : null,
                            t.hasNonNull("expectTarget") ? t.get("expectTarget").asText() : null));
                }
                scenarios.add(new Scenario(s.get("id").asText(), s.get("category").asText(),
                        s.hasNonNull("heardBeforeCut") ? s.get("heardBeforeCut").asText() : null, turns,
                        s.hasNonNull("context") ? s.get("context").asText() : null, personal(s)));
            }
            return new Set(root.get("context").asText(), scenarios, root.get("budgetEurPerTurn").decimalValue(),
                    root.get("opusTurnEur").decimalValue());
        } catch (Exception e) {
            throw new IllegalStateException("Jeu de conversation illisible", e);
        }
    }

    /** F-241 — le portrait fictif, la présentation et la trace d'un scénario (ou {@code null}). */
    static Personal personal(JsonNode s) {
        if (!s.hasNonNull("portrait") && !s.path("presentation").asBoolean(false)
                && !s.hasNonNull("recentAllusions")) {
            return null;
        }
        Map<String, String> sections = null;
        List<String> souvenirs = new ArrayList<>();
        if (s.hasNonNull("portrait")) {
            sections = new LinkedHashMap<>();
            JsonNode p = s.get("portrait");
            for (java.util.Iterator<String> it = p.fieldNames(); it.hasNext();) {
                String key = it.next();
                if ("souvenirs".equals(key)) {
                    p.get(key).forEach(m -> souvenirs.add(m.asText()));
                } else {
                    sections.put(key, p.get(key).asText());
                }
            }
        }
        List<String> recent = new ArrayList<>();
        s.path("recentAllusions").forEach(r -> recent.add(r.asText()));
        return new Personal(sections, List.copyOf(souvenirs), s.path("presentation").asBoolean(false),
                List.copyOf(recent));
    }

    /** F-241 — ce que le juge doit savoir du scénario personnel (le portrait fictif, la présentation, la trace). */
    static String personalForJudge(Scenario s) {
        if (s.personal() == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("\n\n## Portrait fictif connu de l'assistant\n");
        fr.claudegateway.jarvis.portrait.JarvisPortraitService.Snapshot p = s.portrait();
        if (p == null) {
            b.append("(aucun)\n");
        } else {
            for (fr.claudegateway.jarvis.portrait.PortraitSection section
                    : fr.claudegateway.jarvis.portrait.PortraitSection.values()) {
                String t = p.text(section);
                if (!t.isBlank()) {
                    b.append("- ").append(section.title()).append(section.sensitive() ? " (sensible)" : "")
                            .append(" : ").append(t).append('\n');
                }
            }
            p.souvenirs().forEach(m -> b.append("- Souvenir : ").append(m.text()).append('\n'));
        }
        if (s.presentation()) {
            b.append("MODE PRÉSENTATION : un public regarde ; aucun élément personnel ne doit être dit.\n");
        }
        if (!s.personal().recentAllusions().isEmpty()) {
            b.append("Déjà dit récemment (à ne pas répéter) : ")
                    .append(String.join(" ; ", s.personal().recentAllusions())).append('\n');
        }
        return b.toString();
    }

    /**
     * Le contexte d'un tour : le contexte figé, l'état de la conversation du jour (comme en prod, SF-239-08), plus les
     * échanges précédents du scénario (comme le fil).
     */
    static String contextFor(String fixed, List<String[]> previous, String heardBeforeCut) {
        StringBuilder b = new StringBuilder(fixed);
        b.append("\n\n").append(JarvisFastContextBuilder.dayState(previous.isEmpty(),
                previous.stream().anyMatch(e -> e[1] != null && e[1].contains("?"))));
        if (!previous.isEmpty()) {
            b.append("\n\n## Derniers échanges de la conversation\n");
            for (String[] exchange : previous) {
                b.append("- Utilisateur : ").append(exchange[0]).append('\n');
                b.append("- Jarvis : ").append(exchange[1]).append('\n');
            }
        }
        if (heardBeforeCut != null && !heardBeforeCut.isBlank()) {
            b.append("\n## Coupure\nJarvis a été coupé après : « ").append(heardBeforeCut).append(" »\n");
        }
        return b.toString();
    }

    /** Ce que le cerveau rapide a fait d'un tour : la route et ce qui a été dit. */
    static String[] routeAndSaid(JarvisFastBrainService.Outcome outcome) {
        if (JarvisFastBrainService.isMore(outcome)) {
            return new String[] { ROUTE_MORE, JarvisFastBrainPrompt.MORE_MARK };
        }
        String route = outcome.kind() == JarvisFastBrainService.Kind.ANSWER ? "repondre"
                : outcome.kind() == JarvisFastBrainService.Kind.DELEGATE ? "deleguer" : "repli";
        String said = outcome.kind() == JarvisFastBrainService.Kind.ANSWER ? outcome.spokenText()
                : outcome.delegation() == null ? "" : outcome.delegation().annonce();
        return new String[] { route, said == null ? "" : said };
    }

    /** Le jugement d'un tour par le juge ; sans réponse du juge, le tour est « non jugé » (et le scénario échoue). */
    static TurnVerdict judge(AiAgentProvider provider, String judgeModel, String apiKey, Scenario s, int index,
            String context, String said, String route) {
        Turn turn = s.turns().get(index);
        String exchange = "## Contexte fourni à l'assistant\n" + context + "\n\n## Parole de l'utilisateur\n"
                + turn.said() + "\n\n## Ce que l'assistant a fait\n"
                + ("deleguer".equals(route) ? "DÉLÉGATION à l'agent lent et coûteux — annonce dite : « " + said + " »"
                        : "repli".equals(route) ? "REPLI" : "RÉPONSE DITE : « " + said + " »")
                + "\n\nCatégorie du scénario : " + s.category() + " ; route attendue : " + turn.expect()
                + personalForJudge(s);
        try {
            AgentTurn result = provider.nextTurn(new AgentTurnRequest(judgeModel, JUDGE_SYSTEM,
                    List.of(AgentMessage.userText(exchange)), List.of(judgeTool()), apiKey, AgentReasoning.none(),
                    AgentContextPolicy.none(), AgentTurnMode.ACT, new AgentCallShape(800, false, true)));
            Optional<AgentToolCall> call = result == null ? Optional.empty() : result.toolCalls().stream()
                    .filter(tc -> JUDGE_TOOL.equals(tc.name())).findFirst();
            if (call.isEmpty()) {
                return unjudged(s, index, route, said);
            }
            JsonNode v = call.get().input();
            List<String> graves = new ArrayList<>();
            GRAVES.forEach((field, label) -> {
                if (v.path(field).asBoolean(false)) {
                    graves.add(label);
                }
            });
            return new TurnVerdict(s.id(), index + 1, s.category(), route, turn.expect(), true,
                    v.path("naturel").asInt(0), v.path("concis").asBoolean(false),
                    v.path("formule_robotique").asBoolean(false), v.path("delegation_inutile").asBoolean(false),
                    v.path("sens_juste").asBoolean(false), v.path("question_en_retour").asBoolean(false),
                    v.path("fait_non_ancre").asBoolean(false), v.path("action_non_deleguee").asBoolean(false), said,
                    cut(v.path("commentaire").asText(""), 200), fr.claudegateway.jarvis.JarvisSpokenWords.count(said),
                    List.copyOf(graves));
        } catch (RuntimeException e) {
            return unjudged(s, index, route, said);
        }
    }

    /**
     * F-240 / SF-240-04 — une demande de suite lue sur l'appareil (« ⤵ ») n'est pas jugée par le modèle (rien n'est dit
     * par le cerveau rapide : l'écran lit la réponse d'Opus déjà calculée) : verdict déterministe, juste si le
     * scénario l'attendait (sinon la route le fait échouer).
     */
    static TurnVerdict descent(Scenario s, int index) {
        Turn turn = s.turns().get(index);
        boolean expected = ROUTE_MORE.equals(turn.expect()) || "any".equals(turn.expect());
        return new TurnVerdict(s.id(), index + 1, s.category(), ROUTE_MORE, turn.expect(), true, 5, true, false,
                false, expected, false, false, false, JarvisFastBrainPrompt.MORE_MARK,
                "suite lue sur l'appareil (aucun appel à Opus)", 0);
    }

    private static TurnVerdict unjudged(Scenario s, int index, String route, String said) {
        return new TurnVerdict(s.id(), index + 1, s.category(), route, s.turns().get(index).expect(), false, 0, false,
                false, false, false, false, false, false, said, "non jugé");
    }

    /** Le verdict d'un scénario : chaque tour à la grille, au plus une question en retour, le coût dans le budget. */
    static ScenarioVerdict scenario(Scenario s, List<TurnVerdict> turns, Set set, List<BigDecimal> fastCosts) {
        List<String> reasons = new ArrayList<>();
        int returnQuestions = 0;
        int delegations = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (int i = 0; i < turns.size(); i++) {
            TurnVerdict t = turns.get(i);
            String where = s.id() + "#" + t.turn();
            cost = cost.add(fastCosts.get(i));
            if ("deleguer".equals(t.route())) {
                delegations++;
                cost = cost.add(set.opusTurnEur());
            }
            if (!t.judged()) {
                reasons.add(where + " non jugé");
                continue;
            }
            if (!"any".equals(t.expect()) && !t.expect().equals(t.route())) {
                reasons.add(where + " route " + t.route() + " au lieu de " + t.expect());
            }
            if (t.natural() < MIN_NATURAL) {
                reasons.add(where + " pas naturel (" + t.natural() + "/5)");
            }
            if (!t.concise()) {
                reasons.add(where + " trop long");
            }
            if (t.robotic()) {
                reasons.add(where + " formule robotique");
            }
            if (t.uselessDelegation()) {
                reasons.add(where + " délégation inutile");
            }
            if (!t.meaningRight()) {
                reasons.add(where + " sens faux");
            }
            if (t.ungrounded()) {
                reasons.add(where + " fait non ancré");
            }
            if (t.actionNotDelegated()) {
                reasons.add(where + " action non déléguée");
            }
            if (t.returnQuestion()) {
                returnQuestions++;
            }
            for (String grave : t.graves()) {
                reasons.add(where + " " + grave);
            }
        }
        if (returnQuestions > 1) {
            reasons.add(s.id() + " plus d'une question en retour");
        }
        // F-240 / SF-240-05 — un tour « any » admet une délégation (agenda absent, rappel à créer) : son budget compte
        // un tour Opus. Une délégation inutile y reste un zéro obligatoire, jugé tour par tour.
        long expectedDelegations = s.turns().stream()
                .filter(t -> "deleguer".equals(t.expect()) || "any".equals(t.expect())).count();
        BigDecimal budget = set.budgetEurPerTurn().multiply(BigDecimal.valueOf(s.turns().size()))
                .add(set.opusTurnEur().multiply(BigDecimal.valueOf(expectedDelegations)));
        if (cost.compareTo(budget) > 0) {
            reasons.add(s.id() + " budget dépassé (" + cost.setScale(2, java.math.RoundingMode.HALF_UP) + " € > "
                    + budget.setScale(2, java.math.RoundingMode.HALF_UP) + " €)");
        }
        return new ScenarioVerdict(s.id(), s.category(), reasons.isEmpty(), cost, budget, delegations, reasons);
    }

    /** La porte : taux de réussite et zéros obligatoires. */
    static Result summarize(List<TurnVerdict> turns, List<ScenarioVerdict> scenarios) {
        List<String> failures = new ArrayList<>();
        long passed = scenarios.stream().filter(ScenarioVerdict::passed).count();
        double rate = scenarios.isEmpty() ? 0 : Math.round(passed * 1000.0 / scenarios.size()) / 10.0;
        if (scenarios.isEmpty()) {
            failures.add("aucun scénario");
        }
        if (rate < PASS_RATE_PERCENT) {
            failures.add("scénarios réussis " + rate + " % < " + PASS_RATE_PERCENT + " %");
        }
        Map<String, Long> zeros = new LinkedHashMap<>();
        zeros.put("délégation(s) inutile(s)", turns.stream().filter(TurnVerdict::uselessDelegation).count());
        zeros.put("formule(s) robotique(s)", turns.stream().filter(TurnVerdict::robotic).count());
        zeros.put("fait(s) non ancré(s)", turns.stream().filter(TurnVerdict::ungrounded).count());
        zeros.put("action(s) non déléguée(s)", turns.stream().filter(TurnVerdict::actionNotDelegated).count());
        // F-241 — les défauts graves du portrait : zéro obligatoire.
        GRAVES.values().forEach(label -> zeros.put(label,
                turns.stream().filter(t -> t.graves().contains(label)).count()));
        // F-245 / SF-245-05 — les défauts graves du routage : zéro obligatoire (un « bonjour » dans un chantier…).
        for (String label : List.of(GRAVE_HELLO_IN_SUBJECT, GRAVE_GENERAL_OUTSIDE, GRAVE_TARGETED_IN_JARVIS)) {
            zeros.put(label, turns.stream().filter(t -> t.graves().contains(label)).count());
        }
        zeros.forEach((what, n) -> {
            if (n > 0) {
                failures.add(n + " " + what);
            }
        });
        // F-239 / SF-239-13 — la porte, c'est le taux et les zéros ; le détail des scénarios échoués est dans le rapport
        // (`scenarios`), il n'est pas, à lui seul, un échec de la porte.
        BigDecimal total = scenarios.stream().map(ScenarioVerdict::costEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal perExchange = turns.isEmpty() ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(turns.size()), 4, java.math.RoundingMode.HALF_UP);
        double delegationRate = turns.isEmpty() ? 0
                : Math.round(turns.stream().filter(t -> "deleguer".equals(t.route())).count() * 1000.0 / turns.size())
                        / 1000.0;
        boolean gate = failures.isEmpty();
        return new Result(turns, scenarios, gate, failures, rate, perExchange, delegationRate);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static final String JUDGE_SYSTEM = """
            Tu es un juge strict et impartial. Tu notes UN tour d'un assistant vocal (« Jarvis », majordome \
            britannique, vouvoie, dit « Monsieur » avec parcimonie) qui doit converser comme une vraie personne, \
            brièvement, et DÉLÉGUER à un agent lent et coûteux toute action, tout outil, tout fait absent du \
            contexte. Une salutation, une politesse, une petite conversation, une transition (« commençons ») ou un \
            point dont la réponse est dans le contexte ne se délèguent JAMAIS. Une phrase mal entendue (coupée, \
            incohérente) appelle une demande naturelle de répéter (« Pardon, Monsieur, je n'ai pas saisi la fin. »), \
            jamais la citation de la transcription. Note en appelant l'outil noter_tour une seule fois :
            - naturel : de 1 (robot) à 5 (une personne attentive) ;
            - concis : vrai si c'est court et oral (une à trois phrases, sans liste) ;
            - formule_robotique : vrai si la réponse contient une formule toute faite et creuse (« Je vous écoute », \
            « Je m'en occupe », « Je vérifie, Monsieur », « Voyons cela », « Que puis-je faire pour vous ? » en \
            réflexe), ou cite la transcription au lieu de demander de répéter ;
            - delegation_inutile : vrai si l'assistant a délégué alors qu'il pouvait et devait répondre lui-même ;
            - sens_juste : vrai si la réponse répond à ce que l'utilisateur voulait dire ;
            - question_en_retour : vrai si la réponse pose une question de politesse ou de conversation à \
            l'utilisateur (« Bien dormi ? », « Et vous ? ») — PAS une question de clarification nécessaire (« Lequel, \
            Monsieur ? ») ni une demande de répéter ;
            - fait_non_ancre : vrai si la réponse dite contient un fait absent du contexte et de la parole ;
            - action_non_deleguee : vrai si une action était demandée et qu'il a répondu sans déléguer ;
            - sujet_interdit : vrai si la réponse évoque un sujet listé « À ne pas aborder » dans le portrait, \
            alors que l'utilisateur ne l'a pas amené (faux sans portrait) ;
            - humour_sensible : vrai si la réponse fait de l'humour sur un sujet sensible du portrait (santé, \
            famille, deuil, conflit) que l'utilisateur n'a pas ouvert lui-même (faux sans portrait) ;
            - personnel_en_presentation : vrai si, en MODE PRÉSENTATION, la réponse dit un élément du portrait ou \
            propose de retenir quelque chose (faux hors présentation) ;
            - allusion_forcee : vrai si la réponse plaque un élément du portrait sans rapport avec ce que \
            l'utilisateur dit ou demande (une demande de travail ordinaire n'appelle AUCUNE allusion) ; faux si \
            l'utilisateur a lui-même amené le sujet ;
            - repetition : vrai si la réponse refait une allusion ou un trait d'humour déjà présent dans « Déjà dit \
            récemment » ou dans un échange précédent du scénario ;
            - commentaire : une phrase.""";

    static AgentTool judgeTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("naturel", Map.of("type", "integer"));
        props.put("concis", Map.of("type", "boolean"));
        props.put("formule_robotique", Map.of("type", "boolean"));
        props.put("delegation_inutile", Map.of("type", "boolean"));
        props.put("sens_juste", Map.of("type", "boolean"));
        props.put("question_en_retour", Map.of("type", "boolean"));
        props.put("fait_non_ancre", Map.of("type", "boolean"));
        props.put("action_non_deleguee", Map.of("type", "boolean"));
        GRAVES.keySet().forEach(field -> props.put(field, Map.of("type", "boolean")));
        props.put("commentaire", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.copyOf(props.keySet()));
        schema.put("additionalProperties", false);
        return new AgentTool(JUDGE_TOOL, "Rend la note du tour.", schema);
    }

    /** Le coût d'un tour du cerveau rapide, en euros : celui de l'issue s'il est connu, sinon une estimation. */
    static BigDecimal fastCostEur(JarvisFastBrainService.Outcome outcome, BigDecimal usdToEur) {
        if (outcome.costUsd() != null) {
            return outcome.costUsd().multiply(usdToEur);
        }
        return new BigDecimal("0.01");
    }
}
