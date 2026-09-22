package fr.claudegateway.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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

import fr.claudegateway.images.ImageProvider.GeneratedImageData;

/** La logique des images générées (F-142 / SF-142-04) : bornes, statut, coût, isolation. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageGenerationServiceTest {

    @Mock private GeneratedImageRepository repository;
    @Mock private GeneratedImageStore store;
    @Mock private ImageProvider provider;

    private final Map<UUID, GeneratedImage> db = new HashMap<>();
    private ImageGenerationService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private ImageGenerationProperties props(int maxAccount) {
        return new ImageGenerationProperties("https://api.openai.com/v1", "sk-secret", "gpt-image-1",
                Duration.ofSeconds(30), 1_000_000L, 1000, 3, maxAccount, new BigDecimal("0.04"));
    }

    @BeforeEach
    void setUp() {
        service = new ImageGenerationService(repository, store, provider, props(500));
        when(repository.save(any(GeneratedImage.class))).thenAnswer(invocation -> {
            GeneratedImage i = invocation.getArgument(0);
            if (i.getId() == null) {
                i.setId(UUID.randomUUID());
            }
            db.put(i.getId(), i);
            return i;
        });
        when(repository.findByIdAndUserId(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            GeneratedImage i = db.get(invocation.getArgument(0));
            return i != null && i.getUserId().equals(invocation.getArgument(1))
                    ? Optional.of(i) : Optional.empty();
        });
        when(repository.countByUserIdAndStatus(eq(userId), eq(GeneratedImageStatus.READY))).thenReturn(0L);
    }

    private ImagePlace place() {
        return new ImagePlace(userId, ImageSpace.FORGE, hostId, null);
    }

    @Test
    @DisplayName("nominal : PENDING puis READY, PNG rangé, coût et taille posés")
    void nominal() {
        when(provider.generate(any(), any()))
                .thenReturn(new GeneratedImageData(new byte[] {1, 2, 3}, "image/png"));

        ImageGenerationService.Generated generated = service.generate(place(), "  une couverture bleue  ",
                ImageSize.LANDSCAPE);

        assertThat(generated.bytes()).containsExactly(1, 2, 3);
        GeneratedImage image = generated.image();
        assertThat(image.getStatus()).isEqualTo(GeneratedImageStatus.READY);
        assertThat(image.getPrompt()).isEqualTo("une couverture bleue"); // strip appliqué
        assertThat(image.getSize()).isEqualTo("1536x1024");
        assertThat(image.getImageBytes()).isEqualTo(3L);
        assertThat(image.getCostEur()).isEqualByComparingTo(new BigDecimal("0.04"));
        assertThat(image.getImageKey()).isEqualTo(GeneratedImageStore.imageKey(userId, image.getId()));
        verify(store).putImage(eq(userId), eq(image.getId()), any());
    }

    @Test
    @DisplayName("prompt vide → rejet, AUCUN appel fournisseur ni rangement")
    void emptyPromptRejected() {
        assertThatThrownBy(() -> service.generate(place(), "   ", ImageSize.SQUARE))
                .isInstanceOf(ImageRejectedException.class);
        verify(provider, never()).generate(any(), any());
        verify(store, never()).putImage(any(), any(), any());
    }

    @Test
    @DisplayName("prompt trop long → rejet")
    void tooLongPromptRejected() {
        service = new ImageGenerationService(repository, store, provider, props(500));
        String longPrompt = "x".repeat(1001);
        assertThatThrownBy(() -> service.generate(place(), longPrompt, ImageSize.SQUARE))
                .isInstanceOf(ImageRejectedException.class);
        verify(provider, never()).generate(any(), any());
    }

    @Test
    @DisplayName("fournisseur non configuré → ImageProviderUnavailableException, aucune trace")
    void notConfigured() {
        service = new ImageGenerationService(repository, store, provider,
                new ImageGenerationProperties("https://api.openai.com/v1", null, null, null, null, null,
                        null, null, null));
        assertThatThrownBy(() -> service.generate(place(), "x", ImageSize.SQUARE))
                .isInstanceOf(ImageProviderUnavailableException.class);
        verify(repository, never()).save(any());
        verify(provider, never()).generate(any(), any());
    }

    @Test
    @DisplayName("quota compte atteint → ImageQuotaExceededException, aucun appel")
    void quotaExceeded() {
        when(repository.countByUserIdAndStatus(eq(userId), eq(GeneratedImageStatus.READY))).thenReturn(500L);
        assertThatThrownBy(() -> service.generate(place(), "x", ImageSize.SQUARE))
                .isInstanceOf(ImageQuotaExceededException.class);
        verify(provider, never()).generate(any(), any());
    }

    @Test
    @DisplayName("échec fournisseur : la ligne devient FAILED avec motif, l'exception est relayée")
    void providerFailureMarksFailed() {
        when(provider.generate(any(), any()))
                .thenThrow(new ImageProviderException("Le fournisseur d'images a répondu une erreur (500)."));

        assertThatThrownBy(() -> service.generate(place(), "x", ImageSize.SQUARE))
                .isInstanceOf(ImageProviderException.class);

        assertThat(db.values()).singleElement()
                .satisfies(i -> {
                    assertThat(i.getStatus()).isEqualTo(GeneratedImageStatus.FAILED);
                    assertThat(i.getError()).contains("erreur");
                });
        verify(store, never()).putImage(any(), any(), any());
    }

    @Test
    @DisplayName("image trop volumineuse → FAILED + rejet")
    void tooLargeImage() {
        service = new ImageGenerationService(repository, store, provider,
                new ImageGenerationProperties("https://api.openai.com/v1", "k", "gpt-image-1", null, 4L, null,
                        null, null, null));
        when(provider.generate(any(), any()))
                .thenReturn(new GeneratedImageData(new byte[] {1, 2, 3, 4, 5}, "image/png"));

        assertThatThrownBy(() -> service.generate(place(), "x", ImageSize.SQUARE))
                .isInstanceOf(ImageRejectedException.class);
        assertThat(db.values()).singleElement()
                .satisfies(i -> assertThat(i.getStatus()).isEqualTo(GeneratedImageStatus.FAILED));
        verify(store, never()).putImage(any(), any(), any());
    }

    @Test
    @DisplayName("get/bytes d'un autre compte → ImageNotFoundException (isolation)")
    void isolationOnRead() {
        when(provider.generate(any(), any()))
                .thenReturn(new GeneratedImageData(new byte[] {9}, "image/png"));
        UUID id = service.generate(place(), "x", ImageSize.SQUARE).image().getId();

        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(other, id)).isInstanceOf(ImageNotFoundException.class);
        assertThatThrownBy(() -> service.bytes(other, id)).isInstanceOf(ImageNotFoundException.class);
    }
}
