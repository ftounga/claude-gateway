package fr.claudegateway.jarvis.state.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * F-247 / SF-247-17 — <b>la mesure sur un échantillon réel</b> (hors suite : ne tourne que si
 * {@code -Djarvis.sample=<fichier.jsonl>} est donné). Chaque ligne : {@code h} (client), {@code c} (commande du bloc),
 * {@code o} (sortie rendue au modèle), {@code err}, {@code len}. Rend, par client : sorties lues, sorties couvertes et
 * faits des extracteurs gratuits, et la sélection des constats sur l'historique (retenues, écartées par motif, volume).
 * L'échantillon n'est jamais versionné (sorties réelles) ; seuls les chiffres le sont, dans la mini-spec.
 */
class StateSampleMeasureTest {

    @Test
    void measure() throws Exception {
        String file = System.getProperty("jarvis.sample");
        Assumptions.assumeTrue(file != null && Files.exists(Path.of(file)), "échantillon absent");
        ObjectMapper mapper = new ObjectMapper();
        StateExtractors extractors = new StateExtractors();
        Map<String, Map<String, Long>> stats = new TreeMap<>();
        Map<String, Set<String>> facts = new TreeMap<>();
        Map<String, Set<String>> seen = new TreeMap<>();
        Map<String, List<HistoryNotesSelector.Candidate>> perTurn = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byExtractorForm = new TreeMap<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            JsonNode r;
            try {
                r = mapper.readTree(line);
            } catch (Exception e) {
                continue;
            }
            String h = r.path("h").asText();
            Map<String, Long> s = stats.computeIfAbsent(h, k -> new LinkedHashMap<>());
            if (r.path("err").asBoolean(false) || r.path("len").asInt() > 9000) {
                s.merge("ignorées (erreur ou > 9 000 car.)", 1L, Long::sum);
                continue;
            }
            String c = r.path("c").isNull() ? null : r.path("c").asText();
            String o = r.path("o").asText();
            s.merge("sorties bash", 1L, Long::sum);
            StateExtractors.Result res = extractors.extract(c, o);
            if (!res.facts().isEmpty()) {
                s.merge("couvertes (gratuit)", 1L, Long::sum);
                for (StateExtractor.Extracted e : res.facts()) {
                    facts.computeIfAbsent(h, k -> new HashSet<>()).add(e.key() + "|" + e.attribute());
                    byExtractorForm.computeIfAbsent(h, k -> new TreeMap<>()).merge(e.key().split(":", 2)[0], 1L,
                            Long::sum);
                    if (Boolean.getBoolean("jarvis.sample.verbose")) {
                        System.out.println("  fait " + e.kind() + " " + e.key() + " " + e.attribute() + " = "
                                + e.value());
                    }
                }
            }
            for (HistoryNotesSelector.Verdict v : List.of(HistoryNotesSelector.classify(c, o, res))) {
                s.merge("constats : " + v.reason(), 1L, Long::sum);
                if (v.kept()) {
                    boolean fresh = seen.computeIfAbsent(h, k -> new HashSet<>()).add(v.fingerprint());
                    if (!fresh) {
                        s.merge("constats : doublon exact", 1L, Long::sum);
                    } else {
                        s.merge("constats : car. retenus", (long) v.body().length(), Long::sum);
                        s.merge("constats : sorties retenues uniques", 1L, Long::sum);
                        perTurn.computeIfAbsent(h + "|" + r.path("m").asText(), k -> new java.util.ArrayList<>())
                                .add(new HistoryNotesSelector.Candidate(null, v.command(), v.body(), v.fingerprint(), null));
                    }
                }
            }
        }
        perTurn.forEach((hm, list) -> stats.get(hm.split("\\|")[0]).merge("constats : appels (groupés par tour)",
                (long) HistoryNotesSelector.group(list, HistoryNotesSelector.MAX_GROUP_CHARS).size(), Long::sum));
        perTurn.forEach((hm, list) -> stats.get(hm.split("\\|")[0]).merge("constats : car. de commandes", list.stream()
                .mapToLong(x -> x.command().length()).sum(), Long::sum));
        stats.forEach((h, s) -> {
            System.out.println("=== client " + h + " ===");
            s.forEach((k, v) -> System.out.println("  " + k + " = " + v));
            System.out.println("  faits distincts (clé|attribut) = " + facts.getOrDefault(h, Set.of()).size());
            System.out.println("  faits par préfixe de clé = " + byExtractorForm.getOrDefault(h, Map.of()));
        });
        assertThat(stats).isNotEmpty();
    }
}
