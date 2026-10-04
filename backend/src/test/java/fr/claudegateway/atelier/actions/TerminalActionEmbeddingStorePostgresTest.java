package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Le store vectoriel des attentes sur un <b>vrai PostgreSQL + pgvector</b> (F-175 / SF-175-03) : la
 * migration 143 pose la colonne, la recherche ne voit que les attentes <b>ouvertes</b> du <b>poste</b>
 * <b>du compte</b>, la plus proche d'abord.
 *
 * <p>Ignoré proprement sans démon Docker ({@code disabledWithoutDocker = true}).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class TerminalActionEmbeddingStorePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private TerminalActionRepository repository;
    @Autowired
    private TerminalActionEmbeddingStore store;

    private static float[] vector(float first, float second) {
        float[] v = new float[1536];
        v[0] = first;
        v[1] = second;
        return v;
    }

    private TerminalAction save(UUID userId, UUID hostId, String description, TerminalActionStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return repository.save(TerminalAction.builder().userId(userId).workspaceId(UUID.randomUUID())
                .hostId(hostId).description(description).kind(TerminalActionKind.ACTION).status(status)
                .createdAt(now).updatedAt(now).build());
    }

    @Test
    @DisplayName("recherche : ouvertes du poste du compte seulement, la plus proche d'abord ; rattrapage")
    void searchIsScopedAndOrdered() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        TerminalAction near = save(alice, host, "Demander le compte forge CAPFM", TerminalActionStatus.DEMANDE);
        TerminalAction far = save(alice, host, "Valider le budget", TerminalActionStatus.A_FAIRE);
        TerminalAction closed = save(alice, host, "Compte forge (fait)", TerminalActionStatus.FAIT);
        TerminalAction bobs = save(bob, host, "Compte forge de Bob", TerminalActionStatus.A_FAIRE);
        TerminalAction otherHost = save(alice, UUID.randomUUID(), "Compte forge ailleurs", TerminalActionStatus.A_FAIRE);

        assertThat(store.findOpenUnembedded(alice, host, null, 10))
                .extracting(TerminalActionEmbeddingStore.Unembedded::id)
                .containsExactlyInAnyOrder(near.getId(), far.getId());

        store.store(near.getId(), vector(1f, 0.05f));
        store.store(far.getId(), vector(0f, 1f));
        store.store(closed.getId(), vector(1f, 0f));
        store.store(bobs.getId(), vector(1f, 0f));
        store.store(otherHost.getId(), vector(1f, 0f));

        List<TerminalActionEmbeddingStore.Scored> found = store.searchOpen(alice, host, null, vector(1f, 0f), 5);

        assertThat(found).extracting(TerminalActionEmbeddingStore.Scored::id)
                .containsExactly(near.getId(), far.getId());
        assertThat(found.get(0).distance()).isLessThan(0.05);
        assertThat(store.findOpenUnembedded(alice, host, null, 10)).isEmpty();
    }
}
