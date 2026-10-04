package fr.claudegateway.governance.map.index;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>Le journal des consultations de la carte</b> (F-174 / SF-174-01).
 *
 * <p>Il compte ce que la gateway donne à l'agent depuis la carte : combien de faits, combien de
 * caractères, de quels fichiers, combien de pièges et d'échéances. Les fouilles de l'agent lui-même
 * ({@code bash}, {@code read_file} sur un fichier de carte) sont déjà dans {@code runner_audit} ; la
 * requête de référence de la mini-spec les y lit.</p>
 *
 * <p><b>Ne lève jamais.</b> Une mesure en panne ne doit pas coûter un tour. Rien du contenu (ni la
 * question, ni les faits) n'est rangé : seulement des compteurs.</p>
 */
@Service
public class HostMapLookupJournal {

    private static final Logger log = LoggerFactory.getLogger(HostMapLookupJournal.class);

    /** Marque d'un piège dans un bloc de faits (SF-174-04). */
    public static final String PITFALL_MARK = "⟨piège";
    /** Marque d'une échéance dans un bloc de faits (SF-174-04). */
    public static final String DEADLINE_MARK = "⟨échéance";

    /** La source d'un fait : {@code [acces.md]} ou {@code [acces.md § Bastions …]}. */
    private static final Pattern SOURCE = Pattern.compile("\\[([^\\]§]+?)(?:\\s*§[^\\]]*)?\\]");

    static final int MAX_SOURCES_CHARS = 1000;

    private final HostMapLookupRepository repository;
    private final Clock clock;

    public HostMapLookupJournal(HostMapLookupRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Range une consultation.
     *
     * @param block le bloc rendu à l'agent, ou {@code null} si rien ne l'a été
     */
    public void record(UUID userId, UUID hostId, UUID workspaceId, String kind, String strategy,
            String block) {
        if (userId == null || hostId == null || kind == null) {
            return;
        }
        try {
            Stats stats = Stats.of(block);
            repository.save(HostMapLookup.builder()
                    .userId(userId)
                    .hostId(hostId)
                    .workspaceId(workspaceId)
                    .kind(kind)
                    .strategy(stats.facts() == 0 ? HostMapLookup.STRATEGY_NONE : strategy)
                    .factsCount(stats.facts())
                    .chars(stats.chars())
                    .pitfallsCount(stats.pitfalls())
                    .deadlinesCount(stats.deadlines())
                    .sources(stats.sources())
                    .createdAt(OffsetDateTime.now(clock))
                    .build());
        } catch (RuntimeException ex) {
            log.debug("Consultation de carte non journalisée ({})", ex.getClass().getSimpleName());
        }
    }

    /** Ce qu'un bloc contient, compté sans rien en garder. */
    record Stats(int facts, int chars, int pitfalls, int deadlines, String sources) {

        static Stats of(String block) {
            if (block == null || block.isBlank()) {
                return new Stats(0, 0, 0, 0, null);
            }
            int facts = 0;
            int pitfalls = 0;
            int deadlines = 0;
            Set<String> sources = new LinkedHashSet<>();
            for (String line : block.split("\n")) {
                if (!line.startsWith("- ")) {
                    continue;
                }
                facts++;
                if (line.contains(PITFALL_MARK)) {
                    pitfalls++;
                }
                if (line.contains(DEADLINE_MARK)) {
                    deadlines++;
                }
                Matcher matcher = SOURCE.matcher(line);
                String source = null;
                while (matcher.find()) {
                    source = matcher.group(1).strip(); // La dernière parenthèse est la source.
                }
                if (source != null && !source.isEmpty()) {
                    sources.add(source);
                }
            }
            String joined = sources.isEmpty() ? null : String.join(",", sources);
            if (joined != null && joined.length() > MAX_SOURCES_CHARS) {
                joined = joined.substring(0, MAX_SOURCES_CHARS);
            }
            return new Stats(facts, block.length(), pitfalls, deadlines, joined);
        }
    }
}
