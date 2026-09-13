package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** F-111 / SF-111-01 — la version réelle du runner. */
class RunnerBuildTest {

    @Test
    void laConstructionCouranteEstFiltreeParMaven() {
        RunnerBuild build = RunnerBuild.current();

        // La ressource est filtrée à la construction : plus aucun « 0.0.1 », ni placeholder brut.
        assertNotEquals("0.0.1", build.version());
        assertNotEquals(RunnerBuild.UNKNOWN_VERSION, build.version(),
                "runner-build.properties doit être filtré (version du pom)");
        assertTrue(build.stamp() != null && build.stamp().matches("\\d{12}"), build.id());
        assertTrue(build.id().startsWith(build.version() + "-" + build.stamp()), build.id());
    }

    @Test
    void leContratEtLeJavaMinimalEcritsDansLeJarSontCeuxDuCode() throws Exception {
        // La gateway lit ces deux valeurs dans le jar qu'elle sert : elles ne doivent pas dériver.
        java.util.Properties properties = new java.util.Properties();
        try (var in = RunnerBuild.class.getResourceAsStream(RunnerBuild.RESOURCE)) {
            properties.load(in);
        }
        assertEquals(String.valueOf(RunnerBuild.CONTRACT), properties.getProperty("contract"));
        assertEquals("21", properties.getProperty("java"));
    }

    @Test
    void lIdentifiantReunitNumeroDateEtCommit() {
        assertEquals("1.0.0-202609131412-f30b4c0",
                RunnerBuild.of("1.0.0", "202609131412", "f30b4c0").id());
        assertEquals("1.0.0-202609131412", RunnerBuild.of("1.0.0", "202609131412", null).id());
        assertEquals("1.0.0", RunnerBuild.of("1.0.0", null, "f30b4c0").id());
    }

    @Test
    void uneRessourceAbsenteOuNonFiltreeRendZeroSansInventer() {
        assertEquals("0.0.0", RunnerBuild.read(null).id());
        RunnerBuild raw = RunnerBuild.read(new ByteArrayInputStream(
                "version=${project.version}\nstamp=${runner.build.stamp}\ncommit=local\n"
                        .getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("0.0.0", raw.version());
        assertNull(raw.stamp());
        assertEquals("local", raw.commit());
    }

    @Test
    void lIdentifiantSeRelitALIdentique() {
        RunnerBuild build = RunnerBuild.of("2.3.4", "202701011200", "abc1234");
        assertEquals(build, RunnerBuild.parseId(build.id()).orElseThrow());
        assertTrue(RunnerBuild.parseId("../../etc").isEmpty(), "un chemin n'est pas une version");
        assertTrue(RunnerBuild.parseId("1.0.0-pas-une-date").isEmpty());
        assertTrue(RunnerBuild.parseId("").isEmpty());
    }

    @Test
    void lOrdreSuitLeNumeroPuisLaDate() {
        RunnerBuild ancien = RunnerBuild.of("1.0.0", "202609131412", "aaa");
        RunnerBuild recent = RunnerBuild.of("1.0.0", "202609141000", "bbb");
        RunnerBuild majeur = RunnerBuild.of("1.1.0", "202601010000", "ccc");

        assertTrue(ancien.compareTo(recent) < 0);
        assertTrue(recent.compareTo(majeur) < 0, "le numéro l'emporte sur la date");
        assertEquals(0, RunnerBuild.of("1.0.0", null, null).compareTo(ancien),
                "sans date des deux côtés, le numéro seul décide");
    }
}
