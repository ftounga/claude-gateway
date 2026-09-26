package fr.claudegateway.office;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;

/**
 * F-129 / SF-129-07 — le document et le classeur sont construits par la gateway.
 *
 * <p>Ce que ces tests protègent : <b>rien n'est fabriqué sur le poste</b>, les images viennent du
 * projet (lues sous l'isolation du tour), et un chemin venu du modèle ne peut pas sortir du
 * projet.</p>
 */
@ExtendWith(MockitoExtension.class)
class OfficeToolExecutorTest {

    @Mock private OfficeBuilder builder;
    @Mock private ProjectFileRead reader;
    @Mock private ProjectFileDeposit deposit;

    private OfficeToolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final Workspace workspace = new Workspace();

    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @BeforeEach
    void setUp() {
        executor = new OfficeToolExecutor(builder, reader, deposit, mapper);
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(userId);
    }

    private ObjectNode document() {
        ObjectNode spec = mapper.createObjectNode();
        spec.put("title", "Compte rendu");
        spec.putArray("blocks").addObject().put("type", "text").put("text", "Bonjour");
        ObjectNode input = mapper.createObjectNode();
        input.set("spec", spec);
        return input;
    }

    private ObjectNode spreadsheet() {
        ObjectNode spec = mapper.createObjectNode();
        spec.put("title", "Coûts 2026");
        spec.putArray("sheets").addObject().put("name", "Coûts");
        ObjectNode input = mapper.createObjectNode();
        input.set("spec", spec);
        return input;
    }

    @Test
    @DisplayName("LE CRITÈRE : la gateway construit le .docx et le dépose ; le poste ne fabrique rien")
    void buildsAndDepositsTheDocument() {
        when(builder.build(eq(OfficeFormat.DOCX), any()))
                .thenReturn("DOCX".getBytes(StandardCharsets.UTF_8));
        when(deposit.deposit(eq(userId), eq(workspace), anyString(), eq("Compte-rendu.docx"), any(),
                eq(DOCX_TYPE), eq(OfficeToolCatalog.BUILD_DOCUMENT))).thenReturn("Compte-rendu.docx");

        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1",
                OfficeToolCatalog.BUILD_DOCUMENT, document());

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content())
                .contains("Compte-rendu.docx")
                .contains("Rien n'a été installé sur la machine");
    }

    @Test
    @DisplayName("LE CRITÈRE : le .xlsx suit le MÊME chemin, avec son type et son extension")
    void buildsAndDepositsTheSpreadsheet() {
        when(builder.build(eq(OfficeFormat.XLSX), any()))
                .thenReturn("XLSX".getBytes(StandardCharsets.UTF_8));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(3));

        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-2",
                OfficeToolCatalog.BUILD_SPREADSHEET, spreadsheet());

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(deposit).deposit(eq(userId), eq(workspace), anyString(), names.capture(), any(),
                types.capture(), eq(OfficeToolCatalog.BUILD_SPREADSHEET));
        assertThat(names.getValue()).isEqualTo("Coûts-2026.xlsx");
        assertThat(types.getValue()).isEqualTo(XLSX_TYPE);
        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("1 feuilles");
    }

    @Test
    @DisplayName("les images viennent DU PROJET : lues sous l'isolation du tour, puis transmises encodées")
    void imagesAreReadFromTheProject() {
        when(reader.read(eq(userId), eq(workspace), anyString(), eq("archi.png"), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.ok("PNG".getBytes(StandardCharsets.UTF_8)));
        when(builder.build(any(), any())).thenReturn("DOCX".getBytes(StandardCharsets.UTF_8));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("doc.docx");
        ObjectNode input = document();
        input.putObject("images").put("archi.png", "archi.png");

        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-3",
                OfficeToolCatalog.BUILD_DOCUMENT, input);

        assertThat(outcome.error()).isFalse();
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(builder).build(eq(OfficeFormat.DOCX), sent.capture());
        assertThat(sent.getValue().path("images").path("archi.png").asText()).isEqualTo("UE5H");
    }

    @Test
    @DisplayName("ISOLATION : un chemin absolu ou une remontée de dossier est REFUSÉ, sans rien lire")
    void apathOutsideTheProjectIsRefused() {
        ObjectNode absolute = document();
        absolute.putObject("images").put("x", "/etc/passwd");
        ObjectNode climbing = document();
        climbing.putObject("images").put("x", "../../secrets.png");

        assertThat(executor.execute(userId, workspace, "c", OfficeToolCatalog.BUILD_DOCUMENT, absolute)
                .content()).contains("absolu refusé");
        assertThat(executor.execute(userId, workspace, "c", OfficeToolCatalog.BUILD_DOCUMENT, climbing)
                .content()).contains("hors du projet");
        verify(reader, never()).read(any(), any(), anyString(), anyString(), anyLong(), anyString());
        verify(builder, never()).build(any(), any());
    }

    @Test
    @DisplayName("image absente du projet : REFUS nommé — rien n'est construit")
    void amissingImageIsRefused() {
        when(reader.read(any(), any(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.error("Fichier illisible sur la machine (archi.png)."));
        ObjectNode input = document();
        input.putObject("images").put("archi.png", "archi.png");

        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-4",
                OfficeToolCatalog.BUILD_DOCUMENT, input);

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("introuvable ou illisible").contains("render_diagram");
        verify(builder, never()).build(any(), any());
    }

    @Test
    @DisplayName("service indisponible : l'ancienne voie reste possible SI elle est déjà là — jamais l'installer")
    void anunavailableServiceNeverAsksToInstall() {
        when(builder.build(any(), any()))
                .thenThrow(new OfficeBuilderUnavailableException("connexion refusée"));

        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-5",
                OfficeToolCatalog.BUILD_DOCUMENT, document());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content())
                .contains("indisponible")
                .contains("DÉJÀ présents")
                .contains("ne les installe pas");
    }

    @Test
    @DisplayName("description à corriger : la raison du service est REPRISE, rien n'est déposé")
    void arejectedSpecIsExplained() {
        when(builder.build(any(), any()))
                .thenThrow(new OfficeRejectedException("Bloc 2 : type inconnu « video »."));

        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-6",
                OfficeToolCatalog.BUILD_DOCUMENT, document());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("type inconnu").contains("Corrige la description");
        verify(deposit, never()).deposit(any(), any(), anyString(), anyString(), any(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("sans description, ou avec le mauvais champ : refus immédiat, rien n'est construit")
    void withoutASpecNothingIsBuilt() {
        assertThat(executor.execute(userId, workspace, "call-7", OfficeToolCatalog.BUILD_DOCUMENT,
                mapper.createObjectNode()).content()).contains("blocks");
        // Un classeur décrit avec des « blocks » n'est pas un classeur : le champ attendu est dit.
        assertThat(executor.execute(userId, workspace, "call-8", OfficeToolCatalog.BUILD_SPREADSHEET,
                document()).content()).contains("sheets");
        verify(builder, never()).build(any(), any());
    }

    @Test
    @DisplayName("les bornes de la description sont tenues ICI AUSSI, avant tout appel")
    void theboundsAreHeldHereToo() {
        ObjectNode input = document();
        ArrayNode blocks = ((ObjectNode) input.get("spec")).putArray("blocks");
        for (int i = 0; i <= OfficeToolExecutor.MAX_BLOCKS; i++) {
            blocks.addObject().put("type", "text").put("text", "x");
        }
        ObjectNode sheets = spreadsheet();
        ArrayNode list = ((ObjectNode) sheets.get("spec")).putArray("sheets");
        for (int i = 0; i <= OfficeToolExecutor.MAX_SHEETS; i++) {
            list.addObject().put("name", "S" + i);
        }
        ObjectNode images = document();
        ObjectNode many = images.putObject("images");
        for (int i = 0; i <= OfficeToolExecutor.MAX_IMAGES; i++) {
            many.put("i" + i, "i" + i + ".png");
        }

        assertThat(executor.execute(userId, workspace, "c", OfficeToolCatalog.BUILD_DOCUMENT, input)
                .content()).contains("trop grande");
        assertThat(executor.execute(userId, workspace, "c", OfficeToolCatalog.BUILD_SPREADSHEET, sheets)
                .content()).contains("trop grande");
        assertThat(executor.execute(userId, workspace, "c", OfficeToolCatalog.BUILD_DOCUMENT, images)
                .content()).contains("Trop d'images");
        verify(builder, never()).build(any(), any());
    }

    @Test
    @DisplayName("un dépôt en échec est DIT : on ne laisse pas croire que le fichier est là")
    void afailedDepositIsAnnounced() {
        when(builder.build(any(), any())).thenReturn("DOCX".getBytes(StandardCharsets.UTF_8));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(null);

        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-9",
                OfficeToolCatalog.BUILD_DOCUMENT, document());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("dépôt dans le projet a échoué");
    }

    @Test
    @DisplayName("un nom d'outil inconnu ne construit rien")
    void anunknownToolBuildsNothing() {
        OfficeToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-10",
                "build_pdf", document());

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("Outil inconnu");
        verify(builder, never()).build(any(), any());
    }

    @Test
    @DisplayName("le thème voyage TEL QUEL dans la description — un seul valideur, côté service")
    void thethemeTravelsVerbatim() {
        when(builder.build(any(), any())).thenReturn("DOCX".getBytes(StandardCharsets.UTF_8));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("doc.docx");
        ObjectNode input = document();
        ((ObjectNode) input.get("spec")).put("theme", "plain");

        executor.execute(userId, workspace, "call-11", OfficeToolCatalog.BUILD_DOCUMENT, input);

        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(builder).build(eq(OfficeFormat.DOCX), sent.capture());
        assertThat(sent.getValue().path("theme").asText()).isEqualTo("plain");
    }

    @Test
    @DisplayName("le titre du journal vient de la description, et ne reste jamais vide")
    void theauditTargetIsReadable() {
        assertThat(OfficeToolExecutor.auditTarget(document())).isEqualTo("Compte rendu");
        assertThat(OfficeToolExecutor.auditTarget(mapper.createObjectNode())).isEqualTo("document");
        assertThat(OfficeToolExecutor.auditTarget(null)).isNull();
    }
}
