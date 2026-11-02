package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentTool;

/**
 * F-238 / SF-238-06 — la consigne de l'outil de diagrammes : modifiée UNE fois (zones AWS, schéma cadré), puis
 * byte-stable. Elle vit dans le préfixe mis en cache (F-134) : un octet qui bouge d'un tour à l'autre annule le
 * cache ; un octet qui bouge d'une version à l'autre doit être VOULU — d'où l'empreinte épinglée.
 */
class DiagramToolCatalogTest {

    /** Empreinte du GUIDE après SF-238-06. La changer = une décision (et un coût de cache), jamais un effet de bord. */
    private static final String GUIDE_SHA256 = "10100db6559b9cf8a1832fc16b397f48abbaa1090d0dd6172e02431b8cce07b4";
    /** Empreinte de la description du champ « spec » après SF-238-06. */
    private static final String SPEC_SHA256 = "10170c990323039f4a4d0fd78a47e9b680316b0e7b31bec6451d43fb407a3688";

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("SF-238-06 : le GUIDE est byte-stable (empreinte épinglée)")
    void guideIsByteStable() throws Exception {
        assertThat(sha256(DiagramToolCatalog.GUIDE)).isEqualTo(GUIDE_SHA256);
    }

    @Test
    @DisplayName("SF-238-06 : la description de « spec » est byte-stable, et c'est bien elle que porte l'outil")
    void specDescriptionIsByteStable() throws Exception {
        assertThat(sha256(DiagramToolCatalog.SPEC_DESCRIPTION)).isEqualTo(SPEC_SHA256);
        AgentTool tool = DiagramToolCatalog.definition();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) properties.get("spec");
        assertThat(spec.get("description")).isEqualTo(DiagramToolCatalog.SPEC_DESCRIPTION);
    }

    @Test
    @DisplayName("SF-238-06 : deux définitions successives sont identiques (rien de volatil dans l'outil)")
    void definitionIsStableFromOneCallToTheNext() {
        AgentTool a = DiagramToolCatalog.definition();
        AgentTool b = DiagramToolCatalog.definition();
        assertThat(a.description()).isEqualTo(b.description());
        assertThat(a.inputSchema()).isEqualTo(b.inputSchema());
    }

    @Test
    @DisplayName("SF-238-06 : la consigne enseigne les zones AWS cadrées (kind, imbrication réelle, AZ alignées, TB, bidirectionnel)")
    void guideTeachesAwsZones() {
        assertThat(DiagramToolCatalog.GUIDE)
                .contains("« kind »")
                .contains("subnet_public")
                .contains("Région › VPC › Availability Zone › sous-réseau")
                .contains("REFUSÉE avec la raison")
                .contains("MÊMES types")
                .contains("direction TB")
                .contains("bidirectional:true")
                .contains("N'UTILISE JAMAIS UNE ICÔNE APPROCHANTE");
        assertThat(DiagramToolCatalog.GUIDE).doesNotContain("cadres frères");
        assertThat(DiagramToolCatalog.SPEC_DESCRIPTION).contains("kind").contains("bidirectional")
                .doesNotContain("cadres frères");
    }

    @Test
    @DisplayName("SF-238-06 : rien de volatil dans la consigne (ni date, ni heure, ni identifiant)")
    void guideHoldsNothingVolatile() {
        assertThat(DiagramToolCatalog.GUIDE).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}")
                .doesNotContainPattern("\\d{2}:\\d{2}")
                .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
    }
}
