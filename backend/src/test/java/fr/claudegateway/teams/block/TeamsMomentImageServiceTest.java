package fr.claudegateway.teams.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;

/**
 * <b>Les images des moments</b> (F-89 / SF-89-02) : là où elles vivent, et ce qui les protège.
 *
 * <p>Le point dur n'est pas le dépôt : c'est que l'<b>isolation soit dans la clé</b>. Un identifiant
 * reçu du client n'est qu'un dernier segment ; il ne peut ni remonter d'un dossier, ni désigner
 * l'image d'un autre compte — et « inconnue » et « à quelqu'un d'autre » restent indiscernables.</p>
 */
class TeamsMomentImageServiceTest {

    private InMemoryWorkspaceStorage storage;
    private TeamsMomentImageService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID terminal = UUID.randomUUID();

    private static final byte[] PIXEL = "png-bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        storage = new InMemoryWorkspaceStorage();
        service = new TeamsMomentImageService(storage);
    }

    @Test
    @DisplayName("une image déposée se relit avec son type")
    void aStoredImageComesBackWithItsType() {
        String id = service.store(alice, terminal, "image/png", PIXEL);

        assertThat(service.find(alice, terminal, id)).hasValueSatisfying(image -> {
            assertThat(image.contentType()).isEqualTo("image/png");
            assertThat(image.content()).isEqualTo(PIXEL);
        });
    }

    @Test
    @DisplayName("la clé porte le propriétaire ET le terminal")
    void theKeyCarriesTheOwnerAndTheTerminal() {
        String id = service.store(alice, terminal, "image/png", PIXEL);

        assertThat(storage.listKeys("teams-moments/" + alice + "/" + terminal + "/"))
                .anySatisfy(key -> assertThat(key).endsWith(id + ".png"));
    }

    @Test
    @DisplayName("L'IMAGE D'UN AUTRE COMPTE EST INTROUVABLE, et ne se distingue pas d'une inconnue")
    void anotherAccountsImageIsUnreachable() {
        String id = service.store(alice, terminal, "image/png", PIXEL);

        assertThat(service.find(bob, terminal, id)).isEmpty();
        assertThat(service.find(bob, terminal, "jamais-deposee")).isEmpty();
        assertThat(service.exists(bob, terminal, id)).isFalse();
    }

    @Test
    @DisplayName("l'image d'un AUTRE terminal du même compte est introuvable elle aussi")
    void anotherTerminalsImageIsUnreachable() {
        String id = service.store(alice, terminal, "image/png", PIXEL);

        assertThat(service.find(alice, UUID.randomUUID(), id)).isEmpty();
    }

    @Test
    @DisplayName("un identifiant qui essaie d'être un chemin n'en est pas un")
    void anIdentifierIsNeverAPath() {
        service.store(alice, terminal, "image/png", PIXEL);

        assertThat(service.find(alice, terminal, "../../autre/compte/x")).isEmpty();
        assertThat(service.find(alice, terminal, "..")).isEmpty();
        assertThat(service.find(alice, terminal, "a/b")).isEmpty();
        assertThat(service.find(alice, terminal, "")).isEmpty();
        assertThat(service.find(alice, terminal, null)).isEmpty();
    }

    @Test
    @DisplayName("un type d'image hors de la liste close est refusé au dépôt")
    void anUnknownTypeIsRefused() {
        assertThatThrownBy(() -> service.store(alice, terminal, "image/gif", PIXEL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.store(alice, terminal, "application/pdf", PIXEL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D2 — elles s'effacent avec le compte rendu, et rien d'autre ne part")
    void theyAreErasedWithTheReport() {
        service.store(alice, terminal, "image/png", PIXEL);
        service.store(alice, terminal, "image/jpeg", PIXEL);
        UUID otherTerminal = UUID.randomUUID();
        service.store(alice, otherTerminal, "image/webp", PIXEL);

        service.deleteAll(alice, terminal);

        assertThat(service.list(alice, terminal)).isEmpty();
        assertThat(service.list(alice, otherTerminal)).hasSize(1);
    }
}
