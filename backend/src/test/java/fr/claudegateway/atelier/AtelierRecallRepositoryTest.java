package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * La requête de rappel (F-162 / SF-162-01) au contact d'une vraie base H2 — donc portable H2 +
 * PostgreSQL (pas de {@code to_tsvector}). On y fige les deux propriétés non négociables :
 *
 * <ul>
 *   <li><b>Isolation</b> {@code user_id} <b>ET</b> {@code workspace_id} : un utilisateur ne rappelle
 *       jamais les messages d'un autre, ni ceux d'un autre workspace ;</li>
 *   <li><b>Sur tout le fil</b> : {@code searchByContent} retrouve un message <b>antérieur</b> à une
 *       frontière de rejeu, que la lecture bornée post-frontière exclut — c'est ce qui rend la
 *       compaction et le « Nouveau départ » sûrs.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class AtelierRecallRepositoryTest {

    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JdbcTemplate jdbc;

    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private UUID aliceWs1;
    private UUID aliceWs2;

    private static final PageRequest FIVE = PageRequest.of(0, 5);

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        workspaceRepository.deleteAll();
        aliceWs1 = workspace(aliceId, "alice-projet-1");
        aliceWs2 = workspace(aliceId, "alice-projet-2");
    }

    private UUID workspace(UUID userId, String name) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(name)
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build()).getId();
    }

    /**
     * {@code created_at} porte {@code @CreationTimestamp} (posé à l'insertion) : pour maîtriser l'ordre
     * chronologique et la frontière, on le force en base après la sauvegarde.
     */
    private void message(UUID workspaceId, UUID userId, String role, String content, OffsetDateTime when) {
        AtelierMessage saved = messageRepository.save(AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role(role).content(content).build());
        jdbc.update("update atelier_messages set created_at = ? where id = ?",
                java.sql.Timestamp.from(when.toInstant()), saved.getId());
    }

    @Test
    @DisplayName("isolation : ni un autre workspace, ni un autre utilisateur ne remontent")
    void isolatedByUserAndWorkspace() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-12T10:00:00Z");
        message(aliceWs1, aliceId, "USER", "le secret du VPC est dans le coffre", now);
        // Même mot-clé, mais dans l'AUTRE workspace d'Alice : hors périmètre du fil courant.
        message(aliceWs2, aliceId, "USER", "un autre secret, autre projet", now);
        // Même mot-clé et MÊME workspace, mais un AUTRE utilisateur : le filtre user_id doit l'exclure.
        message(aliceWs1, bobId, "USER", "le secret de Bob, à ne jamais rappeler", now);

        var hits = messageRepository.searchByContent(aliceWs1, aliceId, "%secret%", FIVE);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getContent()).contains("VPC");
    }

    @Test
    @DisplayName("insensible à la casse")
    void caseInsensitive() {
        message(aliceWs1, aliceId, "ASSISTANT", "Décision : on part sur TERRAFORM.",
                OffsetDateTime.parse("2026-09-12T10:00:00Z"));

        assertThat(messageRepository.searchByContent(aliceWs1, aliceId, "%terraform%", FIVE)).hasSize(1);
    }

    @Test
    @DisplayName("sur tout le fil : un message d'avant la frontière est rappelé (compaction-indépendant)")
    void spansTheWholeThreadBeyondTheReplayFrontier() {
        OffsetDateTime old = OffsetDateTime.parse("2026-01-01T09:00:00Z");
        OffsetDateTime recent = OffsetDateTime.parse("2026-09-12T10:00:00Z");
        OffsetDateTime frontier = OffsetDateTime.parse("2026-06-01T00:00:00Z");
        message(aliceWs1, aliceId, "USER", "mot de passe temporaire hunter2 (ancien tour)", old);
        message(aliceWs1, aliceId, "USER", "on reparle de hunter2 (tour récent)", recent);

        // La lecture bornée par la frontière (rejeu / après un « Nouveau départ ») exclut l'ancien.
        var afterFrontier = messageRepository
                .findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        aliceWs1, aliceId, frontier);
        assertThat(afterFrontier).hasSize(1);

        // recall, lui, retrouve les DEUX — y compris l'ancien, d'avant la frontière.
        var hits = messageRepository.searchByContent(aliceWs1, aliceId, "%hunter2%", FIVE);
        assertThat(hits).hasSize(2);
        assertThat(hits).anyMatch(m -> m.getCreatedAt().isEqual(old));
    }

    @Test
    @DisplayName("bornage : au plus N extraits, du plus récent au plus ancien")
    void boundedAndOrderedByRecency() {
        for (int i = 0; i < 8; i++) {
            message(aliceWs1, aliceId, "USER", "note budget n°" + i,
                    OffsetDateTime.parse("2026-09-12T10:00:00Z").plusMinutes(i));
        }

        var hits = messageRepository.searchByContent(aliceWs1, aliceId, "%budget%", PageRequest.of(0, 5));

        assertThat(hits).hasSize(5);
        assertThat(hits.get(0).getContent()).contains("n°7"); // le plus récent d'abord
    }

    @Test
    @DisplayName("SF-162-06 : relecture par ids isolée user + workspace (défense en profondeur du sémantique)")
    void findByIdsIsolatedByUserAndWorkspace() {
        AtelierMessage mine = messageRepository.save(AtelierMessage.builder()
                .workspaceId(aliceWs1).userId(aliceId).role("USER").content("à moi, ws1").build());
        AtelierMessage otherWs = messageRepository.save(AtelierMessage.builder()
                .workspaceId(aliceWs2).userId(aliceId).role("USER").content("à moi, mais autre ws").build());
        AtelierMessage otherUser = messageRepository.save(AtelierMessage.builder()
                .workspaceId(aliceWs1).userId(bobId).role("USER").content("à Bob, même ws").build());

        // On demande les TROIS ids, mais scopé (aliceWs1, alice) : seul le sien remonte.
        var hits = messageRepository.findByWorkspaceIdAndUserIdAndIdIn(aliceWs1, aliceId,
                java.util.List.of(mine.getId(), otherWs.getId(), otherUser.getId()));

        assertThat(hits).extracting(AtelierMessage::getId).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("comptage de tour isolé user + workspace")
    void turnCountIsIsolated() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-09-12T10:00:00Z");
        OffsetDateTime t2 = t1.plusMinutes(5);
        message(aliceWs1, aliceId, "USER", "premier tour", t1);
        message(aliceWs1, aliceId, "ASSISTANT", "réponse 1", t1.plusSeconds(1));
        message(aliceWs1, aliceId, "USER", "deuxième tour", t2);
        // Bruit d'un autre utilisateur dans le même workspace : ne doit pas gonfler le compte.
        message(aliceWs1, bobId, "USER", "intrus", t1);

        long turns = messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThanEqual(
                aliceWs1, aliceId, "USER", t2);

        assertThat(turns).isEqualTo(2);
    }

    @Test
    @DisplayName("F-178 / SF-178-01 : portée poste — les fils de l'ensemble, jamais un autre user ni un fil hors ensemble")
    void hostScopeIsIsolatedByUserAndWorkspaceSet() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-30T10:00:00Z");
        UUID aliceOtherHostWs = workspace(aliceId, "alice-autre-poste");
        message(aliceWs1, aliceId, "USER", "jeton Atlantis dans le coffre", now);
        message(aliceWs2, aliceId, "ASSISTANT", "Atlantis : rotation tous les 90 jours", now.plusMinutes(1));
        // Même user, mais un fil HORS de l'ensemble (un autre poste) : exclu.
        message(aliceOtherHostWs, aliceId, "USER", "Atlantis sur l'autre poste", now);
        // Même ensemble de fils, mais un AUTRE utilisateur : exclu par user_id.
        message(aliceWs1, bobId, "USER", "Atlantis de Bob", now);

        var hits = messageRepository.searchByContentInWorkspaces(
                java.util.List.of(aliceWs1, aliceWs2), aliceId, "%atlantis%", FIVE);

        assertThat(hits).extracting(AtelierMessage::getContent)
                .containsExactly("Atlantis : rotation tous les 90 jours", "jeton Atlantis dans le coffre");

        AtelierMessage bob = messageRepository.save(AtelierMessage.builder()
                .workspaceId(aliceWs1).userId(bobId).role("USER").content("Bob").build());
        AtelierMessage outside = messageRepository.save(AtelierMessage.builder()
                .workspaceId(aliceOtherHostWs).userId(aliceId).role("USER").content("hors poste").build());
        AtelierMessage mine = messageRepository.save(AtelierMessage.builder()
                .workspaceId(aliceWs2).userId(aliceId).role("USER").content("à moi").build());
        assertThat(messageRepository.findByUserIdAndWorkspaceIdInAndIdIn(aliceId,
                java.util.List.of(aliceWs1, aliceWs2), java.util.List.of(bob.getId(), outside.getId(), mine.getId())))
                .extracting(AtelierMessage::getId).containsExactly(mine.getId());
    }
}
