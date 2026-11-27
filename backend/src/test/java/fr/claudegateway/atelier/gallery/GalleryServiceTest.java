package fr.claudegateway.atelier.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;
import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** F-278 / SF-278-01 — lecture par le runner, aperçus rangés, dates, écartées, texte au modèle. */
class GalleryServiceTest {

    private final RunnerToolGateway runner = mock(RunnerToolGateway.class);
    private final RunnerAuditService audit = mock(RunnerAuditService.class);
    private final AtelierDepositedFileRepository deposits = mock(AtelierDepositedFileRepository.class);
    private final InMemoryWorkspaceStorage storage = new InMemoryWorkspaceStorage();
    private final GalleryService service = new GalleryService(runner, audit, storage, deposits);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final RunnerTarget target = new RunnerTarget(UUID.randomUUID(), workspaceId, "projet");
    private final long farDeadline = System.currentTimeMillis() + 600_000;

    private static RunnerCallResult bytes(byte[] all, long offset, int length) {
        int from = (int) Math.min(offset, all.length);
        int to = Math.min(all.length, from + length);
        byte[] part = Arrays.copyOfRange(all, from, to);
        return new RunnerCallResult(true, Base64.getEncoder().encodeToString(part), to < all.length, null, 3,
                (long) all.length, null, null, null, false);
    }

    private static RunnerCallResult failure(String code, String message) {
        return new RunnerCallResult(false, null, false, null, 1, null, code, message, null, false);
    }

    private void serve(String path, byte[] content) {
        when(runner.readFileBytes(eq(target), anyString(), eq(path), anyLong(), anyInt())).thenAnswer(
                invocation -> bytes(content, invocation.getArgument(3), invocation.getArgument(4)));
    }

    @BeforeEach
    void setUp() {
        when(deposits.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId)).thenReturn(List.of());
    }

    @Test
    @DisplayName("les images lisibles sont rangées dans l'ordre, avec légende et date ; lues par tranches")
    void nominal() throws Exception {
        byte[] big = GalleryImagesTest.png(2400, 1400, true, false); // > 1 tranche
        serve(".atelier/entrees/a.png", big);
        serve(".atelier/entrees/Screenshot_20261127_093012.png", GalleryImagesTest.png(300, 200, false, false));

        GalleryService.Result result = service.publish(userId, workspaceId, target, "toolu_1", "Captures en attente",
                List.of(new GalleryService.Request(".atelier/entrees/a.png", "Erreur 403 du portail", "hier 18 h"),
                        new GalleryService.Request(".atelier/entrees/Screenshot_20261127_093012.png", null, null)),
                List.of(), farDeadline);

        assertThat(result.error()).isFalse();
        GalleryBlock block = result.block();
        assertThat(block.title()).isEqualTo("Captures en attente");
        assertThat(block.items()).hasSize(2);
        GalleryBlock.Item first = block.items().get(0);
        assertThat(first.name()).isEqualTo("a.png");
        assertThat(first.caption()).isEqualTo("Erreur 403 du portail");
        assertThat(first.date()).isEqualTo("hier 18 h");
        assertThat(first.width()).isLessThanOrEqualTo(GalleryImages.MAX_SIDE);
        assertThat(block.items().get(1).date()).isEqualTo("2026-11-27T09:30:12");
        assertThat(block.skipped()).isEmpty();
        byte[] stored = service.image(userId, workspaceId, first.id()).orElseThrow();
        assertThat(stored.length).isLessThanOrEqualTo(GalleryImages.MAX_BYTES);
        assertThat(result.modelText()).contains("2 images").contains("a.png").doesNotContain("base64");
        verify(audit, org.mockito.Mockito.times(2)).recordCall(eq(userId), eq(target), anyString(), eq("read_file"),
                anyString(), any());
    }

    @Test
    @DisplayName("la date du dépôt connu au même chemin prime sur le nom")
    void depositDate() throws Exception {
        serve(".atelier/entrees/x-20250101.png", GalleryImagesTest.png(50, 50, false, false));
        OffsetDateTime at = OffsetDateTime.of(2026, 11, 26, 14, 5, 0, 0, ZoneOffset.UTC);
        AtelierDepositedFile deposit = mock(AtelierDepositedFile.class);
        when(deposit.getPath()).thenReturn(".atelier/entrees/x-20250101.png");
        when(deposit.getCreatedAt()).thenReturn(at);
        when(deposits.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId)).thenReturn(List.of(deposit));

        GalleryService.Result result = service.publish(userId, workspaceId, target, "toolu_1", "G",
                List.of(new GalleryService.Request("./.atelier/entrees/x-20250101.png", null, null)), List.of(),
                farDeadline);

        assertThat(result.block().items().get(0).date()).isEqualTo(at.toString());
    }

    @Test
    @DisplayName("format non pris en charge, image illisible, fichier trop lourd, lecture en échec : écartés")
    void skipped() throws Exception {
        serve(".atelier/entrees/ok.png", GalleryImagesTest.png(40, 40, false, false));
        serve(".atelier/entrees/faux.png", "pas une image".getBytes());
        when(runner.readFileBytes(eq(target), anyString(), eq("big.png"), anyLong(), anyInt()))
                .thenReturn(new RunnerCallResult(true, "", true, null, 1, 50L * 1024 * 1024, null, null, null, false));
        when(runner.readFileBytes(eq(target), anyString(), eq("absent.png"), anyLong(), anyInt()))
                .thenReturn(failure("not_found", "Fichier introuvable."));

        GalleryService.Result result = service.publish(userId, workspaceId, target, "toolu_1", "G",
                List.of(new GalleryService.Request("photo.webp", null, null),
                        new GalleryService.Request("notes/compte-rendu.md", null, null),
                        new GalleryService.Request(".atelier/entrees/faux.png", null, null),
                        new GalleryService.Request("big.png", null, null),
                        new GalleryService.Request("absent.png", null, null),
                        new GalleryService.Request(".atelier/entrees/ok.png", null, null)),
                List.of(new GalleryBlock.Skipped("secret.png", "lecture refusée")), farDeadline);

        assertThat(result.error()).isFalse();
        assertThat(result.block().items()).extracting(GalleryBlock.Item::name).containsExactly("ok.png");
        assertThat(result.block().skipped()).extracting(GalleryBlock.Skipped::reason).containsExactly(
                "lecture refusée", "format non pris en charge (webp)", "pas une image PNG, JPEG, GIF ou BMP",
                "image illisible", "trop lourde (10 Mo au plus)", "Fichier introuvable.");
        assertThat(result.modelText()).contains("Écartées (6)");
        verify(runner, never()).readFileBytes(any(), anyString(), eq("photo.webp"), anyLong(), anyInt());
    }

    @Test
    @DisplayName("aucune image affichable : erreur, aucun bloc ; poste injoignable : erreur claire")
    void nothingToShow() {
        when(runner.readFileBytes(eq(target), anyString(), eq("a.png"), anyLong(), anyInt()))
                .thenReturn(failure("runner_unavailable", "absent"));
        GalleryService.Result offline = service.publish(userId, workspaceId, target, "t", "G",
                List.of(new GalleryService.Request("a.png", null, null)), List.of(), farDeadline);
        assertThat(offline.error()).isTrue();
        assertThat(offline.block()).isNull();
        assertThat(offline.modelText()).contains("injoignable");

        GalleryService.Result none = service.publish(userId, workspaceId, target, "t", "G",
                List.of(new GalleryService.Request("b.svg", null, null)), List.of(), farDeadline);
        assertThat(none.error()).isTrue();
        assertThat(none.modelText()).contains("Aucune image").contains("b.svg");
    }

    @Test
    @DisplayName("échéance du tour proche : les images ne sont plus lues, elles sont dites non chargées")
    void deadline() {
        GalleryService.Result result = service.publish(userId, workspaceId, target, "t", "G",
                List.of(new GalleryService.Request("a.png", null, null)), List.of(), System.currentTimeMillis());
        assertThat(result.error()).isTrue();
        assertThat(result.modelText()).contains("non chargée");
        verify(runner, never()).readFileBytes(any(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("mode dossier : glob du runner (plus récentes d'abord), images seulement, au plus nombre")
    void folder() {
        when(runner.glob(eq(target), anyString(), any())).thenReturn(new RunnerCallResult(true,
                ".atelier/entrees/c.PNG\n.atelier/entrees/notes.txt\n.atelier/entrees/b.jpg\n.atelier/entrees/a.png",
                false, null, 2, null, null, null, null, false));

        GalleryService.Listing listing = service.listFolder(userId, target, "t", "./.atelier/entrees/", 2);

        assertThat(listing.error()).isNull();
        assertThat(listing.paths()).containsExactly(".atelier/entrees/c.PNG", ".atelier/entrees/b.jpg");
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(runner).glob(eq(target), anyString(), input.capture());
        assertThat(input.getValue().path("pattern").asText()).isEqualTo(".atelier/entrees/**");
        assertThat(input.getValue().path("path").asText()).isEqualTo(".atelier/entrees");
    }

    @Test
    @DisplayName("mode dossier sans image : message qui dit quoi faire")
    void emptyFolder() {
        when(runner.glob(eq(target), anyString(), any())).thenReturn(new RunnerCallResult(true, "Aucun résultat.",
                false, null, 2, null, null, null, null, false));
        GalleryService.Listing listing = service.listFolder(userId, target, "t", "notes", 30);
        assertThat(listing.paths()).isEmpty();
        assertThat(listing.error()).contains("Aucune image").contains("bash");
    }

    @Test
    @DisplayName("l'aperçu d'un autre compte ou d'un autre terminal est introuvable ; un id invalide aussi")
    void isolation() throws Exception {
        serve("a.png", GalleryImagesTest.png(40, 40, false, false));
        String id = service.publish(userId, workspaceId, target, "t", "G",
                List.of(new GalleryService.Request("a.png", null, null)), List.of(), farDeadline)
                .block().items().get(0).id();
        assertThat(service.image(userId, workspaceId, id)).isPresent();
        assertThat(service.image(UUID.randomUUID(), workspaceId, id)).isEmpty();
        assertThat(service.image(userId, UUID.randomUUID(), id)).isEmpty();
        assertThat(service.image(userId, workspaceId, "../../x")).isEmpty();
    }
}
