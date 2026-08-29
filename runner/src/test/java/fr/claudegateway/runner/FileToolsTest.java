package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Outils fichiers du runner (F-38 / SF-38-04) : formats de sortie identiques au mode hébergé, bornes
 * de taille du contrat de messages, codes d'erreur de la liste close, et confinement à la racine.
 */
class FileToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private FileTools tools;

    @BeforeEach
    void setUp() {
        tools = new FileTools(new PathGuard(root));
    }

    // ---------------------------------------------------------------- read_file

    @Test
    void litUnFichierDeLaRacine() throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}");

        ToolOutcome outcome = tools.execute("read_file", input("path", "src/App.java"));

        assertTrue(outcome.ok());
        assertEquals("class App {}", outcome.content());
        assertEquals(12, outcome.bytes());
        assertFalse(outcome.truncated());
    }

    @Test
    void refuseUnFichierInexistant() {
        ToolOutcome outcome = tools.execute("read_file", input("path", "absent.txt"));

        assertFalse(outcome.ok());
        assertEquals("not_found", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("absent.txt"));
        assertFalse(outcome.errorMessage().contains(root.toString()));
    }

    @Test
    void refuseDeLireUnDossier() throws IOException {
        Files.createDirectories(root.resolve("src"));

        ToolOutcome outcome = tools.execute("read_file", input("path", "src"));

        assertEquals("is_directory", outcome.errorCode());
    }

    @Test
    void refuseUnFichierAuDelaDuPlafondDeLecture() throws IOException {
        Path big = root.resolve("gros.bin");
        Files.write(big, new byte[(int) FileTools.MAX_READ_BYTES + 1]);

        ToolOutcome outcome = tools.execute("read_file", input("path", "gros.bin"));

        assertEquals("too_large", outcome.errorCode());
    }

    @Test
    void tronqueUnContenuAuDelaDeLaBorneDuContrat() throws IOException {
        Files.writeString(root.resolve("long.txt"), "é".repeat(FileTools.MAX_CONTENT_BYTES));

        ToolOutcome outcome = tools.execute("read_file", input("path", "long.txt"));

        assertTrue(outcome.ok());
        assertTrue(outcome.truncated());
        assertTrue(outcome.content().getBytes(StandardCharsets.UTF_8).length <= FileTools.MAX_CONTENT_BYTES);
        // Coupe sur une frontière de caractère : aucun caractère de remplacement.
        assertFalse(outcome.content().contains("�"));
    }

    @Test
    void refuseUnCheminHorsRacineALaLecture() {
        ToolOutcome outcome = tools.execute("read_file", input("path", "../secret.txt"));

        assertEquals("path_outside_root", outcome.errorCode());
    }

    // --------------------------------------------------------------- write_file

    @Test
    void ecritUnFichierEtCreeLesDossiersParents() throws IOException {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "src/main/App.java");
        input.put("content", "class App {}");

        ToolOutcome outcome = tools.execute("write_file", input);

        assertTrue(outcome.ok());
        assertEquals("class App {}", Files.readString(root.resolve("src/main/App.java")));
        assertEquals(12, outcome.bytes());
    }

    @Test
    void remplaceLeContenuExistant() throws IOException {
        Files.writeString(root.resolve("a.txt"), "ancien contenu très long");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "a.txt");
        input.put("content", "neuf");

        tools.execute("write_file", input);

        assertEquals("neuf", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void refuseUneEcritureHorsRacineSansToucherAuFichierCible(@TempDir Path outside) throws IOException {
        Path victim = Files.writeString(outside.resolve("secret.txt"), "intact");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "../" + outside.getFileName() + "/secret.txt");
        input.put("content", "compromis");

        ToolOutcome outcome = tools.execute("write_file", input);

        assertEquals("path_outside_root", outcome.errorCode());
        assertEquals("intact", Files.readString(victim));
    }

    @Test
    void refuseUnContenuAuDelaDeLaBorne() {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "gros.txt");
        input.put("content", "a".repeat(FileTools.MAX_CONTENT_BYTES + 1));

        ToolOutcome outcome = tools.execute("write_file", input);

        assertEquals("invalid_input", outcome.errorCode());
        assertFalse(Files.exists(root.resolve("gros.txt")));
    }

    @Test
    void refuseDEcrireSurUnDossier() throws IOException {
        Files.createDirectories(root.resolve("src"));
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "src");
        input.put("content", "x");

        assertEquals("is_directory", tools.execute("write_file", input).errorCode());
    }

    @Test
    void refuseUneEcritureSansContenu() {
        ToolOutcome outcome = tools.execute("write_file", input("path", "a.txt"));

        assertEquals("invalid_input", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("content"));
    }

    // --------------------------------------------------------------- list_files

    @Test
    void listeLesFichiersReguliersEnCheminsRelatifsTries() throws IOException {
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/App.java"), "x");
        Files.writeString(root.resolve("README.md"), "y");

        ToolOutcome outcome = tools.execute("list_files", null);

        assertTrue(outcome.ok());
        assertEquals("README.md\nsrc/main/App.java", outcome.content());
    }

    @Test
    void neListePasLesLiensSymboliques(@TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "mot de passe");
        Files.writeString(root.resolve("a.txt"), "x");
        try {
            Files.createSymbolicLink(root.resolve("lien.txt"), outside.resolve("secret.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Liens symboliques non supportés sur cette plateforme");
        }

        ToolOutcome outcome = tools.execute("list_files", null);

        assertEquals("a.txt", outcome.content());
    }

    // ------------------------------------------------------------- search_files

    @Test
    void rechercheAuFormatCheminLigneTexte() throws IOException {
        Files.writeString(root.resolve("a.txt"), "premiere ligne\nTODO corriger\ntroisieme");

        ToolOutcome outcome = tools.execute("search_files", input("query", "todo"));

        assertTrue(outcome.ok());
        assertEquals("a.txt:2: TODO corriger\n", outcome.content());
    }

    @Test
    void rechercheSansResultat() throws IOException {
        Files.writeString(root.resolve("a.txt"), "rien ici");

        assertEquals("Aucun résultat.", tools.execute("search_files", input("query", "zzz")).content());
    }

    @Test
    void rechercheTronqueeAvecLeSuffixeAttendu() throws IOException {
        Files.writeString(root.resolve("a.txt"), ("motif ligne\n").repeat(2_000));

        ToolOutcome outcome = tools.execute("search_files", input("query", "motif"));

        assertTrue(outcome.truncated());
        assertTrue(outcome.content().endsWith("… (résultats tronqués)"));
    }

    @Test
    void rechercheIgnoreLesFichiersBinaires() throws IOException {
        Files.write(root.resolve("bin.dat"), new byte[] {'m', 'o', 't', 0, 'i', 'f'});
        Files.writeString(root.resolve("a.txt"), "mot");

        ToolOutcome outcome = tools.execute("search_files", input("query", "mot"));

        assertEquals("a.txt:1: mot\n", outcome.content());
    }

    @Test
    void refuseUneRechercheVide() {
        assertEquals("invalid_input", tools.execute("search_files", input("query", "   ")).errorCode());
        assertEquals("invalid_input", tools.execute("search_files", null).errorCode());
    }

    // ------------------------------------------------------------------- divers

    @Test
    void refuseUnOutilNonSupporte() {
        ToolOutcome outcome = tools.execute("bash", input("command", "rm -rf /"));

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
    }

    private static ObjectNode input(String field, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(field, value);
        return node;
    }
}
