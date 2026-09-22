package fr.claudegateway.presentations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** La logique des présentations (F-129 / SF-129-02) : validation, rangement, isolation. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresentationServiceTest {

    @Mock private PresentationRepository repository;
    @Mock private PresentationStore store;

    private PresentationService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private static byte[] pptx(String marker) {
        byte[] payload = marker.getBytes(StandardCharsets.ISO_8859_1);
        byte[] content = new byte[4 + payload.length];
        content[0] = 0x50;
        content[1] = 0x4B;
        content[2] = 0x03;
        content[3] = 0x04;
        System.arraycopy(payload, 0, content, 4, payload.length);
        return content;
    }

    @BeforeEach
    void setUp() {
        service = new PresentationService(repository, store, new PresentationLimits(1_000L, 100, 5_000L));
        when(repository.save(any(Presentation.class))).thenAnswer(invocation -> {
            Presentation p = invocation.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
    }

    private PresentationPlace place() {
        return new PresentationPlace(userId, PresentationSpace.FORGE, hostId, null);
    }

    @Test
    @DisplayName("création : range le .pptx et crée l'entrée avec l'isolation du lieu")
    void createStoresAndPersists() {
        byte[] bytes = pptx("deck");
        Presentation saved = service.publish(place(), null, "  Deck  ", "Une phrase", bytes);

        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getHostId()).isEqualTo(hostId);
        assertThat(saved.getSpace()).isEqualTo(PresentationSpace.FORGE);
        assertThat(saved.getTitle()).isEqualTo("Deck");
        assertThat(saved.getPptxBytes()).isEqualTo(bytes.length);
        assertThat(saved.getPptxKey()).contains(userId.toString());
        verify(store).putPptx(userId, saved.getId(), bytes);
    }

    @Test
    @DisplayName("remplacement par id : la même entrée, sans doublon, slides remises à refaire")
    void replaceById() {
        UUID id = UUID.randomUUID();
        Presentation existing = Presentation.builder().id(id).userId(userId).space(PresentationSpace.FORGE)
                .hostId(hostId).title("Deck").pptxKey("k").pptxBytes(10).slideCount(9).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(existing));

        Presentation saved = service.publish(place(), id, "Deck v2", null, pptx("v2"));

        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getTitle()).isEqualTo("Deck v2");
        assertThat(saved.getSlideCount()).isNull();
        verify(store).deleteSlides(userId, id);
        verify(store).putPptx(eq(userId), eq(id), any());
    }

    @Test
    @DisplayName("remplacement d'un id d'un autre compte → introuvable (isolation)")
    void replaceForeignIdRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(place(), id, "Deck", null, pptx("x")))
                .isInstanceOf(PresentationNotFoundException.class);
        verify(store, never()).putPptx(any(), any(), any());
    }

    @Test
    @DisplayName("refus : ce n'est pas un .pptx (pas d'en-tête ZIP)")
    void rejectNotPptx() {
        assertThatThrownBy(() -> service.publish(place(), null, "Deck", null,
                "not a zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PresentationRejectedException.class)
                .hasMessageContaining("pptx");
        verify(store, never()).putPptx(any(), any(), any());
    }

    @Test
    @DisplayName("refus : trop volumineux")
    void rejectTooLarge() {
        byte[] big = pptx("x".repeat(2000));
        assertThatThrownBy(() -> service.publish(place(), null, "Deck", null, big))
                .isInstanceOf(PresentationRejectedException.class)
                .hasMessageContaining("taille");
    }

    @Test
    @DisplayName("refus : titre vide")
    void rejectEmptyTitle() {
        assertThatThrownBy(() -> service.publish(place(), null, "   ", null, pptx("x")))
                .isInstanceOf(PresentationRejectedException.class)
                .hasMessageContaining("titre");
    }

    @Test
    @DisplayName("lecture isolée : le .pptx d'un autre compte est introuvable")
    void pptxIsolated() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.pptx(userId, id))
                .isInstanceOf(PresentationNotFoundException.class);
    }
}
