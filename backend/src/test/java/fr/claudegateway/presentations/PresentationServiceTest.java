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

    // ------------------------------------------------------------ SF-129-03 : le rendu par slides

    @Test
    @DisplayName("attacher des slides : efface l'ancien rendu, range dans l'ordre, pose slide_count")
    void attachSlidesStoresAndCounts() {
        UUID id = UUID.randomUUID();
        Presentation existing = Presentation.builder().id(id).userId(userId).space(PresentationSpace.FORGE)
                .hostId(hostId).title("Deck").pptxKey("k").pptxBytes(10).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(existing));

        byte[] a = "img1".getBytes(StandardCharsets.UTF_8);
        byte[] b = "img2".getBytes(StandardCharsets.UTF_8);
        byte[] c = "img3".getBytes(StandardCharsets.UTF_8);
        Presentation saved = service.attachSlides(userId, id, java.util.List.of(a, b, c));

        assertThat(saved.getSlideCount()).isEqualTo(3);
        verify(store).deleteSlides(userId, id);
        verify(store).putSlide(userId, id, 1, a, "image/png");
        verify(store).putSlide(userId, id, 2, b, "image/png");
        verify(store).putSlide(userId, id, 3, c, "image/png");
    }

    @Test
    @DisplayName("attacher : au-delà de max-slides → refus, rien n'est rangé")
    void attachTooManySlides() {
        UUID id = UUID.randomUUID();
        Presentation existing = Presentation.builder().id(id).userId(userId).space(PresentationSpace.FORGE)
                .title("Deck").pptxKey("k").pptxBytes(10).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(existing));
        java.util.List<byte[]> many = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(("s" + i).getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> service.attachSlides(userId, id, many))
                .isInstanceOf(PresentationRejectedException.class).hasMessageContaining("slides");
        verify(store, never()).putSlide(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    @DisplayName("attacher les slides d'un autre compte → introuvable (isolation)")
    void attachSlidesForeignIdRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attachSlides(userId, id,
                java.util.List.of("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(PresentationNotFoundException.class);
    }

    @Test
    @DisplayName("lire une slide hors bornes ou non rendue → introuvable")
    void slideOutOfBounds() {
        UUID id = UUID.randomUUID();
        Presentation rendered = Presentation.builder().id(id).userId(userId).space(PresentationSpace.FORGE)
                .title("Deck").pptxKey("k").pptxBytes(10).slideCount(3).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(rendered));
        assertThatThrownBy(() -> service.slide(userId, id, 0))
                .isInstanceOf(PresentationNotFoundException.class);
        assertThatThrownBy(() -> service.slide(userId, id, 4))
                .isInstanceOf(PresentationNotFoundException.class);

        Presentation noRender = Presentation.builder().id(id).userId(userId).space(PresentationSpace.FORGE)
                .title("Deck").pptxKey("k").pptxBytes(10).build();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(noRender));
        assertThatThrownBy(() -> service.slide(userId, id, 1))
                .isInstanceOf(PresentationNotFoundException.class);
    }
}
