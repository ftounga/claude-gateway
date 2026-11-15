package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.diagrams.DiagramRenderer.Format;

/**
 * F-142 / SF-142-06 — l'appel au service de rendu, éprouvé sur un vrai serveur HTTP local.
 *
 * <p>Ce que ces tests protègent : les bornes sont appliquées <b>avant</b> tout appel, et les deux
 * familles d'échec restent <b>distinctes</b> — un diagramme invalide n'est pas un service en panne.</p>
 */
class HttpDiagramRendererTest {

    private HttpServer server;
    private DiagramProperties properties;
    private HttpDiagramRenderer renderer;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new DiagramProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        renderer = new HttpDiagramRenderer(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void serve(int status, String contentType, byte[] body) {
        server.createContext("/render", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @Test
    @DisplayName("nominal : l'image revient, et le code du diagramme est bien ce qui a été envoyé")
    void rendersAnImage() {
        serve(200, "image/png", "PNG-BYTES".getBytes(StandardCharsets.UTF_8));

        DiagramRenderer.Rendered rendered = renderer.render("flowchart TD\n A-->B", Format.PNG, 1200);

        assertThat(rendered.format()).isEqualTo(Format.PNG);
        assertThat(new String(rendered.bytes(), StandardCharsets.UTF_8)).isEqualTo("PNG-BYTES");
        assertThat(lastBody.get()).contains("flowchart TD").contains("\"width\":1200").contains("\"format\":\"png\"");
    }

    @Test
    @DisplayName("diagramme invalide (422) : la RAISON du moteur remonte, en DiagramRejectedException")
    void aninvalidDiagramCarriesTheReason() {
        serve(422, "application/json", "{\"error\":\"Parse error on line 2\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> renderer.render("flowchart TD\n A--", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("Parse error on line 2");
    }

    @Test
    @DisplayName("service en panne (500) : DiagramRendererUnavailableException — pas la faute du diagramme")
    void abrokenServiceIsNotTheDiagramsFault() {
        serve(500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> renderer.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRendererUnavailableException.class);
    }

    @Test
    @DisplayName("service injoignable : dit indisponible, sans faire attendre indéfiniment")
    void anunreachableServiceIsSaid() {
        properties.setBaseUrl("http://127.0.0.1:1");

        assertThatThrownBy(() -> renderer.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRendererUnavailableException.class);
    }

    @Test
    @DisplayName("BORNES appliquées AVANT tout appel : code vide, code trop long, image trop lourde")
    void boundsAreAppliedBeforeCalling() {
        properties.setMaxCodeChars(10);

        assertThatThrownBy(() -> renderer.render("   ", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("Aucun code");
        assertThatThrownBy(() -> renderer.render("flowchart TD A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("trop long");
        // Le serveur n'a jamais été démarré : ces refus n'ont donc appelé personne.

        properties.setMaxCodeChars(20_000);
        properties.setMaxImageBytes(4);
        serve(200, "image/png", "PNG-BYTES".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> renderer.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("trop lourde");
    }

    @Test
    @DisplayName("sans service configuré, l'outil n'est pas disponible — on ne promet rien")
    void withoutConfigurationNothingIsPromised() {
        DiagramProperties empty = new DiagramProperties();
        HttpDiagramRenderer off = new HttpDiagramRenderer(empty, new ObjectMapper());

        assertThat(off.isAvailable()).isFalse();
        assertThatThrownBy(() -> off.render("flowchart TD\n A-->B", Format.PNG, null))
                .isInstanceOf(DiagramRendererUnavailableException.class);
    }

    @Test
    @DisplayName("SF-142-18 : engine=cloud est posté, et le SVG revient annoncé en Format.SVG")
    void cloudPostsEngineAndReturnsSvg() {
        serve(200, "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a").put("type", "aws.s3").put("label", "Bucket");

        DiagramRenderer.Rendered rendered = renderer.renderCloud(spec);

        assertThat(rendered.format()).isEqualTo(Format.SVG);
        assertThat(new String(rendered.bytes(), StandardCharsets.UTF_8)).isEqualTo("<svg/>");
        assertThat(lastBody.get()).contains("\"engine\":\"cloud\"").contains("aws.s3");
    }

    @Test
    @DisplayName("SF-238-06 : défaut elk (schéma cadré) ; retour arrière graphviz ⇒ aucun champ « layout » (rendu d'avant)")
    void cloudDefaultsToElkAndGraphvizIsTheRollback() {
        assertThat(new DiagramProperties().isCadre()).isTrue();
        serve(200, "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        properties.setCloudLayout("graphviz");
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a").put("type", "aws.s3").put("label", "Bucket");

        renderer.renderCloud(spec);

        assertThat(properties.isCadre()).isFalse();
        assertThat(lastBody.get()).doesNotContain("\"layout\"");
    }

    @Test
    @DisplayName("SF-238-01 : APP_DIAGRAM_CLOUD_LAYOUT=elk ⇒ layout=elk est posté au moteur cloud")
    void cloudPostsElkLayoutWhenConfigured() {
        serve(200, "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        properties.setCloudLayout(" ELK ");
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a").put("type", "aws.s3").put("label", "Bucket");

        renderer.renderCloud(spec);

        assertThat(lastBody.get()).contains("\"engine\":\"cloud\"").contains("\"layout\":\"elk\"");
    }

    @Test
    @DisplayName("SF-238-01 : une valeur inconnue retombe sur graphviz — un réglage mal écrit ne casse rien")
    void unknownLayoutFallsBackToGraphviz() {
        DiagramProperties p = new DiagramProperties();
        p.setCloudLayout("dot");
        assertThat(p.getCloudLayout()).isEqualTo("graphviz");
        p.setCloudLayout(null);
        assertThat(p.isCadre()).isFalse();
    }

    // --- F-142 / SF-142-13 — le schéma réouvrable dans draw.io ---

    private static String base64(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("SF-142-13 : engine=drawio est posté, et les DEUX artefacts sont décodés")
    void editableReturnsBothArtefacts() {
        serve(200, "application/json", ("{\"drawio\":\"" + base64("<mxfile/>") + "\",\"png\":\""
                + base64("PNG") + "\",\"previewError\":\"\",\"width\":800,\"height\":400}")
                .getBytes(StandardCharsets.UTF_8));
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a").put("label", "A");

        DiagramRenderer.Editable editable = renderer.renderEditable(spec);

        assertThat(new String(editable.drawio(), StandardCharsets.UTF_8)).isEqualTo("<mxfile/>");
        assertThat(editable.hasPreview()).isTrue();
        assertThat(new String(editable.png(), StandardCharsets.UTF_8)).isEqualTo("PNG");
        assertThat(lastBody.get()).contains("\"engine\":\"drawio\"").contains("\"nodes\"");
    }

    @Test
    @DisplayName("SF-238-05 : à elk, le .drawio est demandé cadré (layout=elk) ; le graphe en plus est ignoré")
    void editablePostsElkLayoutWhenConfigured() {
        serve(200, "application/json", ("{\"drawio\":\"" + base64("<mxfile/>") + "\",\"png\":\"\","
                + "\"previewError\":\"\",\"width\":800,\"height\":400,\"graph\":{\"version\":1}}")
                .getBytes(StandardCharsets.UTF_8));
        properties.setCloudLayout("elk");
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a").put("label", "A");

        DiagramRenderer.Editable editable = renderer.renderEditable(spec);

        assertThat(new String(editable.drawio(), StandardCharsets.UTF_8)).isEqualTo("<mxfile/>");
        assertThat(lastBody.get()).contains("\"engine\":\"drawio\"").contains("\"layout\":\"elk\"");
    }

    @Test
    @DisplayName("SF-142-13 : un aperçu manquant n'empêche pas le .drawio de partir — et c'est DIT")
    void aMissingPreviewDoesNotLoseTheFile() {
        serve(200, "application/json", ("{\"drawio\":\"" + base64("<mxfile/>")
                + "\",\"png\":\"\",\"previewError\":\"chromium absent\"}")
                .getBytes(StandardCharsets.UTF_8));
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a");

        DiagramRenderer.Editable editable = renderer.renderEditable(spec);

        assertThat(editable.drawio()).isNotEmpty();
        assertThat(editable.hasPreview()).isFalse();
        assertThat(editable.previewError()).isEqualTo("chromium absent");
    }

    @Test
    @DisplayName("SF-142-13 : sans fichier draw.io, le service est DIT indisponible — jamais de vide déposé")
    void anEmptyEditableIsAnUnavailability() {
        serve(200, "application/json", "{\"drawio\":\"\",\"png\":\"\"}".getBytes(StandardCharsets.UTF_8));
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a");

        assertThatThrownBy(() -> renderer.renderEditable(spec))
                .isInstanceOf(DiagramRendererUnavailableException.class)
                .hasMessageContaining("aucun fichier draw.io");
    }

    @Test
    @DisplayName("SF-142-13 : un 422 porte la raison du moteur ; une description vide est refusée sans appel")
    void editableRefusalsAreNamed() {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode empty = mapper.createObjectNode();
        assertThatThrownBy(() -> renderer.renderEditable(empty))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("au moins un nœud");
        assertThatThrownBy(() -> renderer.renderEditable(null))
                .isInstanceOf(DiagramRejectedException.class);
        // Le serveur n'a jamais démarré : ces refus n'ont appelé personne.

        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a");
        serve(422, "application/json",
                "{\"error\":\"Lien pendant n°1 : « zz » ne désigne aucun nœud déclaré.\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> renderer.renderEditable(spec))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("Lien pendant")
                .hasMessageContaining("zz");
    }

    @Test
    @DisplayName("SF-142-13 : un fichier au-dessus de la borne est refusé, un aperçu au-dessus est seulement perdu")
    void editableBoundsAreApplied() {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a");
        properties.setMaxImageBytes(5);
        serve(200, "application/json", ("{\"drawio\":\"" + base64("<mxfile-beaucoup-trop-long/>")
                + "\",\"png\":\"\"}").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> renderer.renderEditable(spec))
                .isInstanceOf(DiagramRejectedException.class)
                .hasMessageContaining("Fichier draw.io trop lourd");
    }

    @Test
    @DisplayName("SF-142-13 : un service muet reste une indisponibilité, pas une description fautive")
    void aSilentServiceIsAnUnavailabilityForTheEditableToo() {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode spec = mapper.createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a");
        // Le serveur n'est jamais démarré : la connexion est refusée.

        assertThatThrownBy(() -> renderer.renderEditable(spec))
                .isInstanceOf(DiagramRendererUnavailableException.class);
    }

    // --- F-238 / SF-238-07 — le graphe conservé ---

    private static com.fasterxml.jackson.databind.node.ObjectNode s3Spec() {
        com.fasterxml.jackson.databind.node.ObjectNode spec = new ObjectMapper().createObjectNode();
        spec.putArray("nodes").addObject().put("id", "a").put("type", "aws.s3").put("label", "Bucket");
        return spec;
    }

    @Test
    @DisplayName("SF-238-07 : cloud cadré ⇒ graph:true posté ; {svg, graph} décodé, le SVG suit le chemin d'avant")
    void cloudCadreReturnsTheGraph() {
        serve(200, "application/json; charset=utf-8", ("{\"svg\":\"" + base64("<svg/>")
                + "\",\"graph\":{\"version\":1,\"nodes\":[{\"id\":\"a\"}]}}").getBytes(StandardCharsets.UTF_8));
        properties.setCloudLayout("elk");

        DiagramRenderer.Rendered rendered = renderer.renderCloud(s3Spec());

        assertThat(new String(rendered.bytes(), StandardCharsets.UTF_8)).isEqualTo("<svg/>");
        assertThat(rendered.format()).isEqualTo(Format.SVG);
        assertThat(rendered.graph().path("nodes").get(0).path("id").asText()).isEqualTo("a");
        assertThat(lastBody.get()).contains("\"layout\":\"elk\"").contains("\"graph\":true");
    }

    @Test
    @DisplayName("SF-238-07 : renderer ancien (SVG brut malgré graph:true) ⇒ rendu inchangé, graphe absent")
    void anOlderRendererStillWorks() {
        serve(200, "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        properties.setCloudLayout("elk");

        DiagramRenderer.Rendered rendered = renderer.renderCloud(s3Spec());

        assertThat(new String(rendered.bytes(), StandardCharsets.UTF_8)).isEqualTo("<svg/>");
        assertThat(rendered.graph()).isNull();
    }

    @Test
    @DisplayName("SF-238-07 : graphviz (retour arrière) ⇒ ni layout ni graph posté")
    void graphvizAsksNoGraph() {
        serve(200, "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8));
        properties.setCloudLayout("graphviz");

        assertThat(renderer.renderCloud(s3Spec()).graph()).isNull();
        assertThat(lastBody.get()).doesNotContain("\"graph\"");
    }

    @Test
    @DisplayName("SF-238-07 : drawio ⇒ le graphe de la réponse est lu")
    void editableCarriesTheGraph() {
        serve(200, "application/json", ("{\"drawio\":\"" + base64("<mxfile/>") + "\",\"png\":\"\","
                + "\"graph\":{\"version\":1,\"nodes\":[]}}").getBytes(StandardCharsets.UTF_8));

        assertThat(renderer.renderEditable(s3Spec()).graph().path("version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("SF-238-07 : computeGraph cloud ⇒ même corps qu'un rendu cadré (elk + graph:true), graphe rendu")
    void computeGraphCloud() {
        serve(200, "application/json", ("{\"svg\":\"" + base64("<svg/>")
                + "\",\"graph\":{\"version\":1,\"nodes\":[]}}").getBytes(StandardCharsets.UTF_8));
        properties.setCloudLayout("graphviz"); // même en retour arrière, le graphe se calcule cadré

        assertThat(renderer.computeGraph("cloud", s3Spec())).get()
                .satisfies(g -> assertThat(g.path("version").asInt()).isEqualTo(1));
        assertThat(lastBody.get()).contains("\"engine\":\"cloud\"").contains("\"layout\":\"elk\"")
                .contains("\"graph\":true");
    }

    @Test
    @DisplayName("SF-238-07 : computeGraph drawio ⇒ moteur drawio cadré ; mermaid ⇒ vide sans appel ; refus nommé")
    void computeGraphDrawioMermaidAndRefusal() {
        serve(422, "application/json", "{\"error\":\"AZ hors VPC\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(renderer.computeGraph("mermaid", s3Spec())).isEmpty();
        assertThat(lastBody.get()).isEmpty();
        assertThatThrownBy(() -> renderer.computeGraph("drawio", s3Spec()))
                .isInstanceOf(DiagramRejectedException.class).hasMessageContaining("AZ hors VPC");
        assertThat(lastBody.get()).contains("\"engine\":\"drawio\"").contains("\"layout\":\"elk\"");
    }
}
