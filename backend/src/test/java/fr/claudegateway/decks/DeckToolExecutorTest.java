package fr.claudegateway.decks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;

/**
 * F-129 / SF-129-05 — la présentation est construite par la gateway.
 *
 * <p>Ce que ces tests protègent : <b>rien n'est fabriqué sur le poste</b>, les images viennent du
 * projet (lues sous l'isolation du tour), et un chemin venu du modèle ne peut pas sortir du projet.</p>
 */
@ExtendWith(MockitoExtension.class)
class DeckToolExecutorTest {

    @Mock private DeckBuilder builder;
    @Mock private ProjectFileRead reader;
    @Mock private ProjectFileDeposit deposit;

    private DeckToolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final Workspace workspace = new Workspace();

    @BeforeEach
    void setUp() {
        executor = new DeckToolExecutor(builder, reader, deposit, mapper);
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(userId);
    }

    private ObjectNode deck() {
        ObjectNode spec = mapper.createObjectNode();
        spec.put("title", "Cible AWS");
        spec.putArray("slides").addObject().put("type", "bullets").put("title", "Ce qui change");
        ObjectNode input = mapper.createObjectNode();
        input.set("spec", spec);
        return input;
    }

    @Test
    @DisplayName("LE CRITÈRE : la gateway construit le .pptx et le dépose ; le poste ne fabrique rien")
    void buildsAndDeposits() {
        when(builder.build(any())).thenReturn(DeckBuilder.Deck.of("PPTX".getBytes(StandardCharsets.UTF_8)));
        when(deposit.deposit(eq(userId), eq(workspace), anyString(), anyString(), any(),
                eq("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                eq(DeckToolCatalog.BUILD))).thenReturn("cible-aws.pptx");

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1", deck());

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content())
                .contains("cible-aws.pptx")
                .contains("presentation_publish")
                .contains("Rien n'a été installé sur la machine");
    }

    @Test
    @DisplayName("les images viennent DU PROJET : lues sous l'isolation du tour, puis transmises encodées")
    void imagesAreReadFromTheProject() {
        when(reader.read(eq(userId), eq(workspace), anyString(), eq("archi.png"), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.ok("PNG".getBytes(StandardCharsets.UTF_8)));
        when(builder.build(any())).thenReturn(DeckBuilder.Deck.of("PPTX".getBytes(StandardCharsets.UTF_8)));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("deck.pptx");
        ObjectNode input = deck();
        input.putObject("images").put("archi.png", "archi.png");

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-2", input);

        assertThat(outcome.error()).isFalse();
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(builder).build(sent.capture());
        assertThat(sent.getValue().path("images").path("archi.png").asText()).isEqualTo("UE5H");
    }

    @Test
    @DisplayName("ISOLATION : un chemin absolu ou une remontée de dossier est REFUSÉ, sans rien lire")
    void apathOutsideTheProjectIsRefused() {
        ObjectNode absolute = deck();
        absolute.putObject("images").put("x", "/etc/passwd");
        ObjectNode climbing = deck();
        climbing.putObject("images").put("x", "../../secrets.png");

        assertThat(executor.execute(userId, workspace, "c", absolute).content()).contains("absolu refusé");
        assertThat(executor.execute(userId, workspace, "c", climbing).content()).contains("hors du projet");
        verify(reader, never()).read(any(), any(), anyString(), anyString(), anyLong(), anyString());
        verify(builder, never()).build(any());
    }

    @Test
    @DisplayName("image absente du projet : REFUS nommé — un deck avec une image manquante est pire")
    void amissingImageIsRefused() {
        when(reader.read(any(), any(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.error("Fichier illisible sur la machine (archi.png)."));
        ObjectNode input = deck();
        input.putObject("images").put("archi.png", "archi.png");

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-3", input);

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("introuvable ou illisible").contains("render_diagram");
        verify(builder, never()).build(any());
    }

    @Test
    @DisplayName("service indisponible : l'ancienne voie reste possible SI elle est déjà là — jamais l'installer")
    void anunavailableServiceNeverAsksToInstall() {
        when(builder.build(any())).thenThrow(new DeckBuilderUnavailableException("connexion refusée"));

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-4", deck());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content())
                .contains("indisponible")
                .contains("DÉJÀ présent")
                .contains("ne l'installe pas");
    }

    @Test
    @DisplayName("sans description : refus immédiat, rien n'est construit")
    void withoutASpecNothingIsBuilt() {
        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-5",
                mapper.createObjectNode());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("spec est requis");
        verify(builder, never()).build(any());
    }

    private DeckBuilder.Deck deckWithPreview(int slides) {
        java.util.List<byte[]> shots = new java.util.ArrayList<>();
        for (int i = 0; i < slides; i++) {
            shots.add(("PNG" + i).getBytes(StandardCharsets.UTF_8));
        }
        return new DeckBuilder.Deck("PPTX".getBytes(StandardCharsets.UTF_8), shots, "");
    }

    @Test
    @DisplayName("LE CRITÈRE (SF-129-06) : l'aperçu est déposé DANS L'ORDRE, et ses chemins sont rendus")
    void thepreviewIsDepositedInOrder() {
        when(builder.build(any())).thenReturn(deckWithPreview(3));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(3));

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-8", deck());

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(deposit, org.mockito.Mockito.times(4)).deposit(eq(userId), eq(workspace), anyString(),
                names.capture(), any(), types.capture(), eq(DeckToolCatalog.BUILD));
        assertThat(names.getAllValues()).containsExactly("Cible-AWS.pptx",
                "Cible-AWS-slide-01.png", "Cible-AWS-slide-02.png", "Cible-AWS-slide-03.png");
        assertThat(types.getAllValues()).containsExactly(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "image/png", "image/png", "image/png");
        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content())
                .contains("Cible-AWS-slide-01.png")
                .contains("slides")
                .contains("lisible entièrement");
    }

    @Test
    @DisplayName("SF-129-06 : l'aperçu est demandé PAR DÉFAUT, et refusé quand l'agent le dit")
    void thepreviewIsAskedByDefault() {
        when(builder.build(any())).thenReturn(deckWithPreview(1));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(3));

        executor.execute(userId, workspace, "call-9", deck());
        ObjectNode refused = deck();
        refused.put("preview", false);
        executor.execute(userId, workspace, "call-10", refused);

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(builder, org.mockito.Mockito.times(2)).build(sent.capture());
        assertThat(sent.getAllValues().get(0).path("preview").asBoolean()).isTrue();
        assertThat(sent.getAllValues().get(1).path("preview").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("SF-129-06 : un deck trop long n'emporte pas d'aperçu — la borne est tenue ici aussi")
    void alongDeckGetsNoPreview() {
        when(builder.build(any())).thenReturn(DeckBuilder.Deck.of("PPTX".getBytes(StandardCharsets.UTF_8)));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("deck.pptx");
        ObjectNode input = deck();
        ObjectNode spec = (ObjectNode) input.get("spec");
        spec.putArray("slides");
        for (int i = 0; i <= DeckToolExecutor.MAX_PREVIEW_SLIDES; i++) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) spec.get("slides")).addObject()
                    .put("type", "bullets").put("title", "S" + i);
        }

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-11", input);

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(builder).build(sent.capture());
        assertThat(sent.getValue().path("preview").asBoolean()).isFalse();
        assertThat(outcome.content()).contains("Aperçu non produit");
    }

    @Test
    @DisplayName("SF-129-06 : un aperçu en échec ne fait JAMAIS échouer le deck, et la raison est dite")
    void afailedPreviewNeverFailsTheDeck() {
        when(builder.build(any())).thenReturn(new DeckBuilder.Deck(
                "PPTX".getBytes(StandardCharsets.UTF_8), java.util.List.of(), "chromium absent"));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("Cible-AWS.pptx");

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-12", deck());

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content())
                .contains("Cible-AWS.pptx")
                .contains("Aperçu non produit")
                .contains("chromium absent")
                .contains("téléchargeable");
    }

    @Test
    @DisplayName("SF-129-06 : un dépôt d'image en échec donne un aperçu PARTIEL, et le dit")
    void apartialPreviewIsAnnounced() {
        when(builder.build(any())).thenReturn(deckWithPreview(3));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(call -> {
                    String name = call.getArgument(3);
                    return name.endsWith("-slide-03.png") ? null : name;
                });

        DeckToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-13", deck());

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content())
                .contains("Cible-AWS-slide-02.png")
                .doesNotContain("Cible-AWS-slide-03.png")
                .contains("1 image(s) n'ont pas pu être déposées");
    }

    @Test
    @DisplayName("F-129 / SF-129-04 : le thème voyage TEL QUEL dans la description — un seul valideur")
    void thethemeTravelsVerbatim() {
        when(builder.build(any())).thenReturn(DeckBuilder.Deck.of("PPTX".getBytes(StandardCharsets.UTF_8)));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("deck.pptx");
        ObjectNode input = deck();
        ((ObjectNode) input.get("spec")).put("theme", "plain");

        executor.execute(userId, workspace, "call-6", input);

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(builder).build(sent.capture());
        assertThat(sent.getValue().path("theme").asText()).isEqualTo("plain");
    }

    @Test
    @DisplayName("F-129 / SF-129-04 : sans thème, rien n'est inventé côté Java — la charte est le défaut du constructeur")
    void nothemeMeansNothingAddedHere() {
        when(builder.build(any())).thenReturn(DeckBuilder.Deck.of("PPTX".getBytes(StandardCharsets.UTF_8)));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("deck.pptx");

        executor.execute(userId, workspace, "call-7", deck());

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(builder).build(sent.capture());
        assertThat(sent.getValue().has("theme")).isFalse();
    }

    @Test
    @DisplayName("le nom du fichier est dérivé du titre, et NETTOYÉ")
    void thefileNameIsCleaned() {
        // La casse du titre est conservée : c'est le nom que l'utilisateur lira dans son projet.
        assertThat(DeckToolExecutor.fileName(null, "Cible AWS (v2)")).isEqualTo("Cible-AWS-v2.pptx");
        assertThat(DeckToolExecutor.fileName("../../deck.pptx", "x")).isEqualTo("deck.pptx");
        assertThat(DeckToolExecutor.fileName(null, "")).isEqualTo("presentation.pptx");
    }
}
