package fr.claudegateway.jarvis.state.extract;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSpend;
import fr.claudegateway.jarvis.state.JarvisStateVocabulary;

/**
 * <b>Les constats sur l'historique : un appel</b> (F-247 / SF-247-17). Lit un groupe de sorties d'un même tour passé
 * avec <b>la consigne inchangée</b> des constats rédigés ({@link StateNotesGuard#SYSTEM}, {@code notes-v1}) et le
 * modèle rapide, par l'abstraction fournisseur ; ne range que les faits dont la citation est vérifiée mot pour mot
 * (garde déterministe inchangée), à <b>l'heure de leur sortie d'origine</b> (l'upsert n'écrase jamais un fait plus
 * récent ; le filtre des secrets s'applique à l'enregistrement). Le coût va au budget Jarvis, <b>hors</b> plafond
 * mensuel des constats ({@link JarvisStateSpend#accountCatchup}). Chaque sortie lue est <b>marquée</b> par son empreinte
 * ({@code jarvis_state_notes_seen}) : elle n'est jamais relue.
 */
@Component
public class HistoryNotesReader {

    /** Estimation : caractères par jeton (prudente : surestime un peu l'entrée). */
    static final int CHARS_PER_TOKEN = 3;
    /** Estimation : la consigne et l'habillage d'un appel, en jetons. */
    static final int OVERHEAD_TOKENS = 600;
    /** Estimation : la réponse moyenne d'un appel, en jetons (le plafond est {@link StateNotesGuard#MAX_OUTPUT_TOKENS}). */
    static final int EXPECTED_OUTPUT_TOKENS = 400;

    private final AiAgentProvider provider;
    private final JarvisFastBrainService fastBrain;
    private final JarvisStateSpend spend;
    private final JarvisStateRegistry registry;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HistoryNotesReader(AiAgentProvider provider, JarvisFastBrainService fastBrain, JarvisStateSpend spend,
            JarvisStateRegistry registry, JdbcTemplate jdbc, Clock clock) {
        this.provider = provider;
        this.fastBrain = fastBrain;
        this.spend = spend;
        this.registry = registry;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Ce qu'un appel a donné : faits écrits, retenus par la garde, rejetés par la garde, coût. */
    public record Outcome(int written, int kept, int rejected, BigDecimal usd) {
    }

    /**
     * Lit un groupe de sorties d'un tour (une seule requête au modèle) et marque chaque sortie comme lue. Lève si le
     * fournisseur échoue : rien n'est alors marqué (la sortie sera relue au passage suivant).
     */
    public Outcome read(UUID userId, UUID hostId, UUID workspaceId, UUID messageId,
            List<HistoryNotesSelector.Candidate> group) {
        List<StateNotesGuard.Output> outputs = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            outputs.add(new StateNotesGuard.Output(i + 1, group.get(i).command(), group.get(i).body()));
        }
        AgentTurn turn = provider.nextTurn(new AgentTurnRequest(fastBrain.model(), StateNotesGuard.SYSTEM,
                List.of(AgentMessage.userText(StateNotesGuard.message(outputs))), List.of(),
                fastBrain.apiKeyFor(userId), AgentReasoning.none(), AgentContextPolicy.none(), AgentTurnMode.ACT,
                new AgentCallShape(StateNotesGuard.MAX_OUTPUT_TOKENS, false, false, false)));
        BigDecimal usd = spend.accountCatchup(userId, workspaceId, turn, fastBrain.model());
        StateNotesGuard.Verdict verdict = StateNotesGuard.check(turn == null ? null : turn.text(), outputs);
        List<JarvisStateRegistry.Observation> observations = new ArrayList<>();
        for (StateNotesGuard.Fact f : verdict.kept()) {
            HistoryNotesSelector.Candidate from = group.get(f.output() - 1);
            observations.add(new JarvisStateRegistry.Observation(hostId, workspaceId, f.kind(), f.key(),
                    f.attribute(), f.value(), from.observedAt(), JarvisStateVocabulary.SOURCE_NOTE,
                    messageId.toString(), JarvisStateVocabulary.MOYENNE));
        }
        int written = observations.isEmpty() ? 0 : registry.record(userId, observations);
        mark(jdbc, clock, userId, hostId, group.stream().map(HistoryNotesSelector.Candidate::fingerprint).toList());
        return new Outcome(written, verdict.kept().size(), verdict.rejected(), usd);
    }

    /** Les empreintes déjà constatées parmi {@code fingerprints}, pour ce compte et ce client. */
    public Set<String> seen(UUID userId, UUID hostId, Collection<String> fingerprints) {
        Set<String> out = new HashSet<>();
        if (fingerprints.isEmpty()) {
            return out;
        }
        List<String> list = new ArrayList<>(new HashSet<>(fingerprints));
        for (int i = 0; i < list.size(); i += 200) {
            List<String> part = list.subList(i, Math.min(list.size(), i + 200));
            String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(userId);
            args.add(hostId);
            args.addAll(part);
            out.addAll(jdbc.queryForList("SELECT fingerprint FROM jarvis_state_notes_seen WHERE user_id = ? AND "
                    + "host_id = ? AND fingerprint IN (" + marks + ")", String.class, args.toArray()));
        }
        return out;
    }

    /** Marque des sorties comme constatées (idempotent). Ne lève jamais. */
    public static void mark(JdbcTemplate jdbc, Clock clock, UUID userId, UUID hostId, Collection<String> fingerprints) {
        Timestamp now = Timestamp.from(clock.instant());
        for (String fp : new HashSet<>(fingerprints)) {
            try {
                jdbc.update("INSERT INTO jarvis_state_notes_seen (user_id, host_id, fingerprint, created_at) "
                        + "SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM jarvis_state_notes_seen WHERE user_id = ? "
                        + "AND host_id = ? AND fingerprint = ?)", userId, hostId, fp, now, userId, hostId, fp);
            } catch (DuplicateKeyException race) {
                // déjà marquée par un passage concurrent
            } catch (RuntimeException ignored) {
                // une marque manquée coûte au pire une relecture
            }
        }
    }

    /** L'estimation, en dollars, de {@code calls} appels portant {@code chars} caractères de sorties au total. */
    public BigDecimal estimateUsd(long chars, int calls) {
        return spend.priceUsd(fastBrain.model(), chars / CHARS_PER_TOKEN + (long) calls * OVERHEAD_TOKENS,
                (long) calls * EXPECTED_OUTPUT_TOKENS);
    }

    /** Le pire coût, en dollars, d'un appel portant {@code chars} caractères (réponse au plafond). */
    public BigDecimal worstUsd(long chars) {
        return spend.priceUsd(fastBrain.model(), chars / CHARS_PER_TOKEN + OVERHEAD_TOKENS,
                StateNotesGuard.MAX_OUTPUT_TOKENS);
    }

    /** Le poids d'un groupe (sorties et commandes), en caractères. */
    public static long chars(List<HistoryNotesSelector.Candidate> group) {
        return group.stream().mapToLong(c -> c.body().length() + c.command().length()).sum();
    }

    /** Dollars ⇒ euros. */
    public BigDecimal toEur(BigDecimal usd) {
        return spend.toEur(usd);
    }

    /** Le budget Jarvis est-il à l'arrêt dur ? */
    public boolean jarvisHardStop(UUID userId) {
        return spend.jarvisHardStop(userId);
    }
}
