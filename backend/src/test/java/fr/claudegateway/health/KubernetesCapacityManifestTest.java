package fr.claudegateway.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * F-118 / SF-118-02 — Garde-fou sur les réglages de tenue en charge de {@code k8s/base/**}.
 *
 * <p>Les valeurs elles-mêmes ont été appliquées par l'orchestrateur/PO (commit {@code 708c1475},
 * puis correctif d'incident {@code cc37318c}). Ce test ne les arbitre pas : il les <b>gèle</b>, et
 * surtout il lit ce que personne ne lisait — le <b>produit</b> de deux fichiers séparés.
 *
 * <p><b>L'incident du 2026-09-16.</b> Le pool Hikari valait 30 dans le configmap, l'HPA montait à 4
 * replicas. Chaque valeur, seule, était défendable. Leur produit — ~120 connexions — a épuisé les
 * slots d'une RDS <b>partagée avec legalcase</b> : pods en crash-loop Liquibase côté claude-gateway,
 * et legalcase tombé avec. Le dépôt n'avait aucun endroit où cette multiplication apparaissait.
 * Désormais si, et elle échoue avant le déploiement.
 *
 * <p>Les autres assertions figent les réglages de F-118 dont la disparition ne se verrait qu'en
 * production : la mémoire JVM (sans {@code MaxRAMPercentage}, ~512 Mo de heap sur une limite de
 * 2 Gi), le plancher de replicas (la charge est de l'<b>attente réseau</b> : un pod sature ses flux
 * SSE sans monter en CPU, donc le seuil CPU ne le fera pas scaler — le plancher à 2, si), et
 * {@code proxy-buffering: off} (sans quoi nginx tamponne et le mot à mot de F-116 se fige pour
 * tout le monde).
 */
class KubernetesCapacityManifestTest {

    /**
     * Budget de connexions base autorisé à claude-gateway sur la RDS <b>partagée</b>. Ne se relève
     * pas sans coordonner avec la capacité RDS (et donc avec legalcase) : c'est la leçon du
     * 2026-09-16, pas une marge de confort.
     */
    private static final int RDS_CONNECTION_BUDGET = 30;

    /** En dessous, la limite mémoire de 2 Gi est gâchée ; au-dessus, plus de marge hors-tas. */
    private static final double MIN_RAM_PERCENTAGE = 50.0;
    private static final double MAX_RAM_PERCENTAGE = 85.0;

    /** ~16 workers {@code @Scheduled} + Tomcat + flux SSE : moins d'un cœur ne tient pas. */
    private static final int MIN_CPU_LIMIT_MILLICORES = 1000;

    private static final Pattern MAX_RAM_PERCENTAGE_OPTION =
            Pattern.compile("-XX:MaxRAMPercentage=(\\d+(?:\\.\\d+)?)");

    private static Map<String, String> configMapData;
    private static Map<String, Object> hpaSpec;
    private static Map<String, Object> deploymentSpec;
    private static Map<String, Object> backendContainer;
    private static Map<String, String> ingressAnnotations;

    @BeforeAll
    static void loadManifests() throws IOException {
        configMapData = stringMap(document("k8s/base/backend/configmap.yaml", "ConfigMap").get("data"),
                "data du ConfigMap backend-config");
        hpaSpec = map(document("k8s/base/backend/hpa.yaml", "HorizontalPodAutoscaler").get("spec"),
                "spec de l'HPA backend");

        Map<String, Object> deployment = document("k8s/base/backend/deployment.yaml", "Deployment");
        deploymentSpec = map(deployment.get("spec"), "spec du Deployment backend");
        backendContainer = backendContainer(deploymentSpec);

        Map<String, Object> ingress = document("k8s/base/ingress/ingress.yaml", "Ingress");
        ingressAnnotations = stringMap(map(ingress.get("metadata"), "metadata de l'Ingress").get("annotations"),
                "annotations de l'Ingress applicatif");
    }

    // ─── Lecture des manifestes ──────────────────────────────────────────────────────────────

    /**
     * Remonte depuis le répertoire de travail (le module {@code backend/} quand Maven joue la
     * suite) jusqu'à la racine du dépôt — même méthode que {@link KubernetesProbeManifestTest}.
     * Une garde qui ne trouve plus sa cible doit échouer bruyamment, jamais passer.
     */
    private static Path locate(String relativePath) {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            Path manifest = candidate.resolve(relativePath);
            if (Files.isRegularFile(manifest)) {
                return manifest;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                relativePath + " introuvable depuis " + Paths.get("").toAbsolutePath()
                        + " : le manifeste a été déplacé ou renommé.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(String relativePath, String kind) throws IOException {
        Path manifest = locate(relativePath);
        try (InputStream in = Files.newInputStream(manifest)) {
            for (Object loaded : new Yaml().loadAll(in)) {
                if (loaded instanceof Map<?, ?> doc && kind.equals(doc.get("kind"))) {
                    return (Map<String, Object>) doc;
                }
            }
        }
        throw new IllegalStateException("Aucun document `" + kind + "` dans " + manifest);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) {
        assertThat(value).as(what).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static Map<String, String> stringMap(Object value, String what) {
        Map<String, Object> raw = map(value, what);
        return raw.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> backendContainer(Map<String, Object> spec) {
        Map<String, Object> podSpec = map(map(spec.get("template"), "template du Deployment").get("spec"),
                "podSpec du Deployment");
        for (Map<String, Object> container : (List<Map<String, Object>>) podSpec.get("containers")) {
            if ("backend".equals(container.get("name"))) {
                return container;
            }
        }
        throw new IllegalStateException("Aucun conteneur `backend` dans k8s/base/backend/deployment.yaml");
    }

    private static int intValue(Map<String, String> data, String key) {
        String raw = data.get(key);
        assertThat(raw).as("clé `%s` du configmap backend", key).isNotNull();
        return Integer.parseInt(raw.trim());
    }

    /** {@code "2000m"} → 2000 ; {@code "2"} → 2000. */
    private static int millicores(String quantity) {
        String trimmed = quantity.trim();
        if (trimmed.endsWith("m")) {
            return Integer.parseInt(trimmed.substring(0, trimmed.length() - 1));
        }
        return (int) Math.round(Double.parseDouble(trimmed) * 1000);
    }

    // ─── L'invariant de capacité base ────────────────────────────────────────────────────────

    @Test
    void le_pool_multiplie_par_le_plafond_de_replicas_tient_dans_le_budget_rds() {
        int pool = intValue(configMapData, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE");
        int maxReplicas = (Integer) hpaSpec.get("maxReplicas");

        assertThat(pool * maxReplicas)
                .as("pool %d × maxReplicas %d connexions sur une RDS PARTAGÉE avec legalcase "
                        + "(budget %d) — c'est l'incident du 2026-09-16 : 30 × 4 = 120, slots épuisés, "
                        + "crash-loop Liquibase ici ET legalcase tombé. Baisser le pool (configmap) ou "
                        + "le plafond de replicas (hpa), ou coordonner la capacité RDS.",
                        pool, maxReplicas, RDS_CONNECTION_BUDGET)
                .isLessThanOrEqualTo(RDS_CONNECTION_BUDGET);
    }

    @Test
    void le_minimum_de_connexions_au_repos_ne_depasse_pas_le_pool() {
        int pool = intValue(configMapData, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE");
        int minimumIdle = intValue(configMapData, "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE");

        assertThat(minimumIdle)
                .as("minimum-idle %d > maximum-pool-size %d : Hikari refuserait de démarrer", minimumIdle, pool)
                .isBetween(0, pool);
    }

    // ─── Mémoire JVM ─────────────────────────────────────────────────────────────────────────

    @Test
    void la_jvm_prend_une_part_explicite_de_la_limite_memoire() {
        String javaToolOptions = configMapData.get("JAVA_TOOL_OPTIONS");
        assertThat(javaToolOptions)
                .as("JAVA_TOOL_OPTIONS : sans -XX:MaxRAMPercentage, la JVM prend ~25 %% de la limite "
                        + "(≈512 Mo sur 2 Gi) et le reste est payé pour rien")
                .isNotNull();

        Matcher matcher = MAX_RAM_PERCENTAGE_OPTION.matcher(javaToolOptions);
        assertThat(matcher.find())
                .as("-XX:MaxRAMPercentage absent de JAVA_TOOL_OPTIONS (`%s`)", javaToolOptions)
                .isTrue();

        assertThat(Double.parseDouble(matcher.group(1)))
                .as("MaxRAMPercentage hors bornes : en dessous de %s %% la mémoire est gâchée, "
                        + "au-dessus de %s %% il ne reste plus de marge hors-tas (métaspace, threads, "
                        + "buffers) et le pod se fait OOMKill", MIN_RAM_PERCENTAGE, MAX_RAM_PERCENTAGE)
                .isBetween(MIN_RAM_PERCENTAGE, MAX_RAM_PERCENTAGE);
    }

    // ─── Mise à l'échelle ────────────────────────────────────────────────────────────────────

    @Test
    void le_backend_ne_tourne_jamais_sur_un_seul_pod() {
        assertThat((Integer) hpaSpec.get("minReplicas"))
                .as("minReplicas : la charge est de l'ATTENTE RÉSEAU — un pod sature ses flux SSE sans "
                        + "monter en CPU, donc le seuil CPU ne déclenchera pas le scale-out. Le plancher "
                        + "à 2 est ce qui tient la charge, et ce qui évite la coupure pendant un rollout.")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void le_plafond_de_replicas_reste_au_dessus_du_plancher() {
        assertThat((Integer) hpaSpec.get("maxReplicas"))
                .as("maxReplicas < minReplicas : HPA invalide")
                .isGreaterThanOrEqualTo((Integer) hpaSpec.get("minReplicas"));
    }

    @Test
    void le_deploiement_part_avec_au_moins_le_plancher_de_l_hpa() {
        assertThat((Integer) deploymentSpec.get("replicas"))
                .as("replicas du Deployment < minReplicas de l'HPA : le cluster oscille à chaque "
                        + "application du manifeste (kustomize écrase, l'HPA rattrape)")
                .isGreaterThanOrEqualTo((Integer) hpaSpec.get("minReplicas"));
    }

    // ─── Capacité du pod ─────────────────────────────────────────────────────────────────────

    @Test
    void le_pod_dispose_d_au_moins_un_coeur_et_d_une_limite_memoire() {
        Map<String, Object> limits = map(map(backendContainer.get("resources"), "resources du conteneur backend")
                .get("limits"), "limits du conteneur backend");

        assertThat(millicores(String.valueOf(limits.get("cpu"))))
                .as("limite CPU : ~16 workers @Scheduled, Tomcat et les flux SSE partagent ce cœur")
                .isGreaterThanOrEqualTo(MIN_CPU_LIMIT_MILLICORES);

        assertThat(String.valueOf(limits.get("memory")))
                .as("limite mémoire absente : MaxRAMPercentage n'aurait plus de référence")
                .isNotBlank()
                .isNotEqualTo("null");
    }

    // ─── Fluidité SSE ────────────────────────────────────────────────────────────────────────

    @Test
    void l_ingress_ne_tamponne_pas_les_flux() {
        assertThat(ingressAnnotations.get("nginx.ingress.kubernetes.io/proxy-buffering"))
                .as("proxy-buffering : à `on` (le défaut nginx), le mot à mot de F-116 arrive par "
                        + "blocs ou pas du tout, pour TOUS les utilisateurs")
                .isEqualTo("off");
    }
}
