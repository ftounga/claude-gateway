package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Le runner est lancé à la racine du <b>poste</b> (F-48 / SF-48-02) ; chaque tour reçoit le
 * <b>projet</b> où il travaille.
 *
 * <p><b>Ce que ces tests protègent a changé le 2026-09-12</b> (F-73 / SF-73-01). Ils affirmaient
 * « le projet A n'atteint jamais le projet B » — une promesse qui ne tenait que sur les outils
 * fichiers : un {@code cd ../projet-b} par {@code bash} n'a jamais été inspecté. Le product owner a
 * retiré le confinement plutôt que de continuer à l'annoncer. Ce qui reste vérifié ici est ce qui
 * est vrai : le projet donne le <b>dossier de départ</b>, et une valeur de projet malformée est
 * refusée <b>avant</b> toute exécution.</p>
 */
class ProjectScopesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path hostRoot;

    private Path projectA;
    private Path projectB;

    @BeforeEach
    void seedTwoSiblingProjects() throws IOException {
        projectA = Files.createDirectories(hostRoot.resolve("projet-a"));
        projectB = Files.createDirectories(hostRoot.resolve("projet-b"));
        Files.writeString(projectA.resolve("a.txt"), "contenu de A");
        Files.writeString(projectB.resolve("secret.txt"), "contenu de B");
    }

    private ProjectScopes scopes() {
        return new ProjectScopes(hostRoot, true, ShellElection.elect(), new Console());
    }

    private static ObjectNode read(String path) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", path);
        return input;
    }

    // ------------------------------------------------------------------ nominal

    @Test
    void unProjetLitSesPropresFichiers() {
        ToolOutcome outcome = scopes().forProject("projet-a")
                .execute("read_file", read("a.txt"), ToolContext.none());

        assertTrue(outcome.ok());
        assertEquals("contenu de A", outcome.content());
    }

    @Test
    void leProjetVideDesigneLaRacineDuPoste() {
        // Un poste peut n'héberger qu'un projet : la racine elle-même est alors le projet.
        ToolOutcome outcome = scopes().forProject("")
                .execute("read_file", read("projet-a/a.txt"), ToolContext.none());

        assertTrue(outcome.ok());
        assertEquals("contenu de A", outcome.content());
    }

    // -------------------------------------------- ce que le projet est, et ce qu'il n'est pas

    @Test
    void unProjetAtteintSonVoisinCarLeConfinementEstRetire() {
        ToolOutcome outcome = scopes().forProject("projet-a")
                .execute("read_file", read("../projet-b/secret.txt"), ToolContext.none());

        assertTrue(outcome.ok(), outcome.errorCode());
        assertEquals("contenu de B", outcome.content());
    }

    @Test
    void unLienSymboliqueVersLeVoisinEstSuivi() throws IOException {
        try {
            Files.createSymbolicLink(projectA.resolve("raccourci"), projectB);
        } catch (UnsupportedOperationException | IOException e) {
            return; // Système sans liens symboliques (Windows sans droits) : rien à vérifier ici.
        }

        ToolOutcome outcome = scopes().forProject("projet-a")
                .execute("read_file", read("raccourci/secret.txt"), ToolContext.none());

        assertTrue(outcome.ok(), outcome.errorCode());
        assertEquals("contenu de B", outcome.content());
    }

    @Test
    void unProjetQuiSortDeLaRacineEstRefuseAvantToutAcces() {
        ProjectScopes scopes = scopes();

        assertEquals("path_outside_root",
                assertThrows(ToolException.class, () -> scopes.forProject("../ailleurs")).code());
        assertEquals("path_outside_root",
                assertThrows(ToolException.class, () -> scopes.forProject("/etc")).code());
        assertEquals("path_outside_root",
                assertThrows(ToolException.class, () -> scopes.forProject("C:\\Windows")).code());
    }

    @Test
    void unProjetInconnuEstIntrouvableEtNeCitePasDeCheminAbsolu() {
        ToolException error =
                assertThrows(ToolException.class, () -> scopes().forProject("projet-fantome"));

        assertEquals("not_found", error.code());
        assertTrue(error.getMessage().contains("projet-fantome"));
        // Anti-fuite (SF-38-04) : l'arborescence de la machine ne remonte jamais à la gateway.
        assertTrue(!error.getMessage().contains(hostRoot.toString()));
    }

    @Test
    void unCheminDeProjetMalformeEstRefuseSansAcces() {
        ProjectScopes scopes = scopes();

        assertEquals("invalid_input",
                assertThrows(ToolException.class, () -> scopes.forProject("x".repeat(5_000))).code());
        assertEquals("invalid_input",
                assertThrows(ToolException.class, () -> scopes.forProject("a\u0000b")).code());
    }

    // ------------------------------------------------------------------- cache

    @Test
    void lesOutilsDUnProjetSontRetenusEtCeuxDeDeuxProjetsSontDistincts() {
        ProjectScopes scopes = scopes();

        assertSame(scopes.forProject("projet-a"), scopes.forProject("./projet-a/"));
        assertNotSame(scopes.forProject("projet-a"), scopes.forProject("projet-b"));
    }

    @Test
    void lesCapacitesDecriventLaMachinePasUnProjet() {
        assertEquals(java.util.List.of("files", "bash"), scopes().capabilities());
        assertEquals(java.util.List.of("files"),
                new ProjectScopes(hostRoot, false, ShellElection.elect(), new Console())
                        .capabilities());
    }
}
