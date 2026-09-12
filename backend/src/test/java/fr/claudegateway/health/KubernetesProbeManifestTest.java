package fr.claudegateway.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * F-77 — Garde-fou sur {@code k8s/base/backend/deployment.yaml}.
 *
 * <p>Le 2026-09-12, les trois sondes du backend interrogeaient l'agrégat
 * {@code /api/actuator/health} — qui inclut l'indicateur de courrier — avec le délai d'attente par
 * défaut de Kubernetes, une seconde. Le relais SMTP a mis 70 s à répondre ; les trois sondes ont
 * échoué ; quatre pods sont entrés en redémarrage permanent. Le correctif a été appliqué à la main
 * sur le cluster, puis porté dans ce manifeste.
 *
 * <p>Ce test relit le manifeste et échoue si quelqu'un le ramène vers l'agrégat ou lui retire son
 * délai d'attente. C'est le seul endroit du dépôt où une régression de ce fichier peut être
 * attrapée avant qu'elle ne coupe le service : rien d'autre ne le lit avant le déploiement.
 */
class KubernetesProbeManifestTest {

    private static final String LIVENESS_PATH = "/api/actuator/health/liveness";
    private static final String READINESS_PATH = "/api/actuator/health/readiness";
    private static final String AGGREGATE_PATH = "/api/actuator/health";
    private static final List<String> PROBES = List.of("startupProbe", "readinessProbe", "livenessProbe");

    private static Map<String, Object> backendContainer;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadBackendContainer() throws IOException {
        Path manifest = locateManifest();
        try (InputStream in = Files.newInputStream(manifest)) {
            for (Object document : new Yaml().loadAll(in)) {
                if (!(document instanceof Map)) {
                    continue;
                }
                Map<String, Object> doc = (Map<String, Object>) document;
                if (!"Deployment".equals(doc.get("kind"))) {
                    continue;
                }
                Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
                Map<String, Object> template = (Map<String, Object>) spec.get("template");
                Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
                List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
                for (Map<String, Object> container : containers) {
                    if ("backend".equals(container.get("name"))) {
                        backendContainer = container;
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Aucun conteneur `backend` dans " + manifest + " : le manifeste a été déplacé ou renommé.");
    }

    /**
     * Remonte depuis le répertoire de travail (le module {@code backend/} quand Maven joue la
     * suite) jusqu'à la racine du dépôt. Chercher au lieu de coder le chemin en dur évite que ce
     * test devienne silencieusement inopérant si la suite est lancée d'ailleurs — un garde-fou qui
     * ne trouve plus sa cible doit échouer bruyamment, jamais passer.
     */
    private static Path locateManifest() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            Path manifest = candidate.resolve("k8s/base/backend/deployment.yaml");
            if (Files.isRegularFile(manifest)) {
                return manifest;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "k8s/base/backend/deployment.yaml introuvable depuis " + Paths.get("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> probe(String name) {
        Map<String, Object> found = (Map<String, Object>) backendContainer.get(name);
        assertThat(found).as("sonde `%s` du conteneur backend", name).isNotNull();
        return found;
    }

    @SuppressWarnings("unchecked")
    private static String path(String probeName) {
        return (String) ((Map<String, Object>) probe(probeName).get("httpGet")).get("path");
    }

    @Test
    void la_sonde_de_vivacite_lit_le_groupe_liveness() {
        assertThat(path("livenessProbe")).isEqualTo(LIVENESS_PATH);
    }

    @Test
    void la_sonde_de_disponibilite_lit_le_groupe_readiness() {
        assertThat(path("readinessProbe")).isEqualTo(READINESS_PATH);
    }

    @Test
    void la_sonde_de_demarrage_lit_le_groupe_liveness() {
        // Son échec TUE le conteneur : elle pose la même question que la vivacité, pas celle de la
        // disponibilité (arbitrage A1 de SF-77-01).
        assertThat(path("startupProbe")).isEqualTo(LIVENESS_PATH);
    }

    @Test
    void aucune_sonde_ne_lit_l_agregat_ou_le_courrier_serait_present() {
        assertThat(List.of(path("startupProbe"), path("readinessProbe"), path("livenessProbe")))
                .as("l'agrégat inclut MailHealthIndicator : c'est la panne du 2026-09-12")
                .doesNotContain(AGGREGATE_PATH);
    }

    @Test
    void les_trois_sondes_attendent_cinq_secondes() {
        for (String name : PROBES) {
            assertThat(probe(name).get("timeoutSeconds"))
                    .as("timeoutSeconds de `%s` (absent => 1 s, le défaut qui a causé la panne)", name)
                    .isEqualTo(5);
        }
    }

    @Test
    void le_delai_d_attente_reste_inferieur_a_la_periode_de_chaque_sonde() {
        // Sinon deux exécutions d'une même sonde se chevauchent, et le diagnostic devient illisible.
        for (String name : PROBES) {
            Map<String, Object> probe = probe(name);
            assertThat((Integer) probe.get("timeoutSeconds"))
                    .as("timeoutSeconds < periodSeconds pour `%s`", name)
                    .isLessThan((Integer) probe.get("periodSeconds"));
        }
    }
}
