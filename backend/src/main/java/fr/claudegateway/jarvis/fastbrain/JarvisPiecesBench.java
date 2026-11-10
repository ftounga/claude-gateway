package fr.claudegateway.jarvis.fastbrain;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Le banc « Jarvis montre les pièces »</b> (F-237 / SF-237-13, cadrage §16 critères 3-4) : catégories {@code SHOW},
 * {@code NAVIGATE}, {@code PROOF}, {@code NEUTRAL}, contexte <b>fixe</b> (aucune base lue), lecture des pièces fixe.
 * Jugé de façon <b>déterministe</b> sur les ordres d'écran relayés ({@code afficher}) et la route — aucun juge modèle,
 * donc aucun coût au-delà du cerveau rapide lui-même.
 *
 * <p><b>Porte</b> : {@code SHOW}, {@code NAVIGATE}, {@code PROOF} ≥ 90 % ; {@code NEUTRAL} : 0 affichage ; jamais plus
 * de 3 pièces d'initiative ; aucune délégation de preuve sans « oui ».</p>
 */
public final class JarvisPiecesBench {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String RESOURCE = "/jarvis/pieces-bench.json";
    /** Taux minimal par catégorie évaluée. */
    static final double MIN_RATE = 0.90;

    /** Ce qui est attendu d'un cas ({@code null} : non vérifié). */
    public record Expect(Boolean show, Boolean delegate, boolean asks, List<String> actions, String reason,
            Integer chantier, String categorie, String type, Integer index, List<String> pieces, String vue,
            String client, String sujet) {
    }

    /** Un cas : la parole, l'écran éventuel, les échanges précédents, l'attendu. */
    public record Case(String id, String category, String text, String screen, List<String[]> previous,
            Expect expect) {
    }

    /** Le jeu : contexte fixe, lecture des pièces fixe, écrans nommés, cas. */
    public record Set(String context, String pieces, Map<String, String> screens, List<Case> cases) {
    }

    private JarvisPiecesBench() {
    }

    /** Le jeu versionné. */
    public static Set load() {
        try (InputStream in = JarvisPiecesBench.class.getResourceAsStream(RESOURCE)) {
            JsonNode root = JSON.readTree(in);
            Map<String, String> screens = new LinkedHashMap<>();
            root.fieldNames().forEachRemaining(name -> {
                if (name.endsWith("Screen")) {
                    screens.put(name, root.get(name).asText());
                }
            });
            List<Case> cases = new ArrayList<>();
            for (JsonNode c : root.get("cases")) {
                List<String[]> previous = new ArrayList<>();
                JsonNode p = c.get("previous");
                if (p != null && p.isArray() && p.size() >= 2) {
                    previous.add(new String[] { p.get(0).asText(), p.get(1).asText() });
                }
                JsonNode e = c.get("expect");
                cases.add(new Case(c.get("id").asText(), c.get("category").asText(), c.get("text").asText(),
                        c.hasNonNull("screen") ? screens.get(c.get("screen").asText()) : null, previous,
                        new Expect(bool(e, "show"), bool(e, "delegate"), e.path("asks").asBoolean(false),
                                strings(e.get("action")), text(e, "reason"), integer(e, "chantier"),
                                text(e, "categorie"), text(e, "type"), integer(e, "index"), strings(e.get("pieces")),
                                text(e, "vue"), text(e, "client"), text(e, "sujet"))));
            }
            return new Set(root.get("context").asText(), root.get("pieces").asText(), screens, cases);
        } catch (Exception e) {
            throw new IllegalStateException("Banc des pièces illisible", e);
        }
    }

    /** Le contexte d'un cas : le contexte fixe, l'état du jour, les échanges précédents, puis l'écran. */
    public static String contextFor(Set set, Case c) {
        String base = JarvisConversationBench.contextFor(set.context(), c.previous(), null);
        return c.screen() == null ? base : base + "\n\n" + c.screen();
    }

    /** Pièces d'initiative relayées dans une réponse (une galerie compte pour une). */
    static int proactive(List<JarvisShowCommand> shows) {
        return shows.stream().filter(s -> "appui".equals(s.reason()))
                .mapToInt(s -> s.pieces().isEmpty() ? 1 : s.pieces().size()).sum();
    }

    /**
     * Le verdict d'un cas, sans juge. Réutilise {@link JarvisFastBrainBenchService.Verdict} : {@code routeAgreed} = cas
     * réussi ; {@code actionNotDelegated} = <b>action sans consentement</b> (délégation de preuve sans « oui ») ;
     * {@code comment} = ce qui a manqué.
     */
    public static JarvisFastBrainBenchService.Verdict evaluate(Case c, JarvisFastBrainService.Outcome outcome) {
        List<JarvisShowCommand> shows = outcome.shows();
        boolean delegated = outcome.kind() == JarvisFastBrainService.Kind.DELEGATE;
        String said = outcome.kind() == JarvisFastBrainService.Kind.ANSWER ? outcome.spokenText()
                : outcome.delegation() == null ? "" : outcome.delegation().annonce();
        said = said == null ? "" : said;
        String route = !shows.isEmpty() ? "afficher" : delegated ? "deleguer"
                : outcome.kind() == JarvisFastBrainService.Kind.ANSWER ? "repondre" : "repli";
        Expect e = c.expect();
        List<String> misses = new ArrayList<>();
        if (Boolean.TRUE.equals(e.show()) && shows.isEmpty()) {
            misses.add("aucun affichage");
        }
        if (Boolean.FALSE.equals(e.show()) && !shows.isEmpty()) {
            misses.add("affichage à tort");
        }
        boolean withoutConsent = Boolean.FALSE.equals(e.delegate()) && delegated;
        if (e.delegate() != null && e.delegate() != delegated) {
            misses.add(delegated ? "délégation sans « oui »" : "pas de délégation");
        }
        if (e.asks() && !said.contains("?")) {
            misses.add("pas de proposition");
        }
        if (Boolean.TRUE.equals(e.show()) && !shows.isEmpty() && shows.stream().noneMatch(s -> matches(e, s))) {
            JarvisShowCommand s = shows.get(0);
            misses.add("ordre inattendu (" + s.action() + ", " + s.reason() + ")");
        }
        if (proactive(shows) > JarvisShowCommand.MAX_APPUI) {
            misses.add("plus de 3 pièces d'initiative");
        }
        // F-237 / SF-237-15 — une délégation d'office dit sa raison (vocabulaire fermé : grounding, timeout…).
        if (!misses.isEmpty() && delegated && outcome.delegation() != null && outcome.delegation().automatic()
                && outcome.reason() != null) {
            misses.add("délégation d'office : " + outcome.reason());
        }
        String expected = Boolean.TRUE.equals(e.show()) ? "afficher"
                : Boolean.TRUE.equals(e.delegate()) ? "deleguer" : "repondre";
        int sentences = said.isBlank() ? 0 : JarvisFastGuards.split(said + " ").sentences().size();
        return new JarvisFastBrainBenchService.Verdict(c.id(), c.category(), route, expected, true,
                misses.isEmpty(), false, withoutConsent, false, 0, true, false, sentences, said.length(),
                misses.isEmpty() ? "ok" : String.join(", ", misses));
    }

    static boolean matches(Expect e, JarvisShowCommand s) {
        if (e.actions() != null && !e.actions().isEmpty() && !e.actions().contains(s.action())) {
            return false;
        }
        if (e.reason() != null && !e.reason().equals(s.reason())) {
            return false;
        }
        JarvisShowCommand.Scope scope = s.scope();
        if (e.chantier() != null && (scope == null || !e.chantier().equals(scope.chantier()))) {
            return false;
        }
        if (e.categorie() != null && (scope == null || !e.categorie().equals(scope.category()))) {
            return false;
        }
        if (e.type() != null && (scope == null || !e.type().equals(scope.type()))
                && s.pieces().stream().noneMatch(id -> id.startsWith(e.type().equals("architecture") ? "diag:"
                        : e.type() + ":"))) {
            return false;
        }
        if (e.index() != null && !e.index().equals(s.index())) {
            return false;
        }
        // F-253 / SF-253-02 — une vue de l'univers : la vue, et le client nommé (rapproché, tolérant).
        if (e.vue() != null && !e.vue().equals(s.view())) {
            return false;
        }
        // F-253 / SF-253-03 — le client (vue de l'univers, ou portée d'une galerie) ; le sujet nommé d'une galerie.
        String client = s.client() != null ? s.client() : s.scope() == null ? null : s.scope().client();
        if (e.client() != null && (client == null || !JarvisFastGuards.normalize(client)
                .contains(JarvisFastGuards.normalize(e.client())))) {
            return false;
        }
        if (e.sujet() != null && (s.scope() == null || s.scope().subjectId() == null
                || !JarvisFastGuards.normalize(s.scope().subjectId()).contains(JarvisFastGuards.normalize(e.sujet())))) {
            return false;
        }
        return e.pieces() == null || e.pieces().isEmpty() || s.pieces().containsAll(e.pieces());
    }

    /** Les manques de la porte (vide = porte franchie). */
    public static List<String> gate(List<JarvisFastBrainBenchService.Verdict> verdicts) {
        List<String> failures = new ArrayList<>();
        for (String category : List.of("SHOW", "NAVIGATE", "PROOF", "UNIVERS")) {
            List<JarvisFastBrainBenchService.Verdict> of = verdicts.stream()
                    .filter(v -> category.equals(v.category())).toList();
            if (of.isEmpty()) {
                continue;
            }
            double rate = of.stream().filter(JarvisFastBrainBenchService.Verdict::routeAgreed).count()
                    / (double) of.size();
            if (rate < MIN_RATE) {
                failures.add(category + " : " + Math.round(rate * 100) + " % < 90 %");
            }
        }
        long neutralShown = verdicts.stream().filter(v -> "NEUTRAL".equals(v.category()))
                .filter(v -> "afficher".equals(v.fastRoute())).count();
        if (neutralShown > 0) {
            failures.add(neutralShown + " affichage(s) sur une phrase neutre");
        }
        long withoutConsent = verdicts.stream().filter(JarvisFastBrainBenchService.Verdict::actionNotDelegated).count();
        if (withoutConsent > 0) {
            failures.add(withoutConsent + " délégation(s) de preuve sans « oui »");
        }
        long tooMany = verdicts.stream().filter(v -> v.comment().contains("plus de 3")).count();
        if (tooMany > 0) {
            failures.add(tooMany + " réponse(s) à plus de 3 pièces d'initiative");
        }
        if (verdicts.isEmpty()) {
            failures.add("aucun cas à rejouer");
        }
        return failures;
    }

    private static Boolean bool(JsonNode e, String field) {
        JsonNode n = e == null ? null : e.get(field);
        return n == null || n.isNull() ? null : n.asBoolean();
    }

    private static Integer integer(JsonNode e, String field) {
        JsonNode n = e == null ? null : e.get(field);
        return n != null && n.isIntegralNumber() ? n.asInt() : null;
    }

    private static String text(JsonNode e, String field) {
        JsonNode n = e == null ? null : e.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static List<String> strings(JsonNode n) {
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (n.isArray()) {
            List<String> out = new ArrayList<>();
            n.forEach(x -> out.add(x.asText()));
            return out;
        }
        return List.of(n.asText());
    }
}
