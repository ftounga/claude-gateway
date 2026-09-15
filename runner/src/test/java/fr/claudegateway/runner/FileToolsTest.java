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
        tools = new FileTools(new PathResolver(root));
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
    void litUnFichierHorsDuDossierDuProjet(@TempDir Path outside) throws IOException {
        // F-73 / SF-73-01 : ce test affirmait l'inverse jusqu'au 2026-09-12. Le confinement a été
        // retiré parce qu'il n'existait déjà pas pour bash — le tenir ici seul ne protégeait rien.
        Path voisin = Files.writeString(outside.resolve("voisin.txt"), "contenu du voisin");

        ToolOutcome relatif = tools.execute("read_file",
                input("path", "../" + outside.getFileName() + "/voisin.txt"));
        ToolOutcome absolu = tools.execute("read_file", input("path", voisin.toString()));

        assertTrue(relatif.ok(), relatif.errorCode());
        assertEquals("contenu du voisin", relatif.content());
        assertTrue(absolu.ok(), absolu.errorCode());
        assertEquals("contenu du voisin", absolu.content());
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
    void ecritHorsDuDossierDuProjet(@TempDir Path outside) throws IOException {
        Path cible = Files.writeString(outside.resolve("note.txt"), "avant");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "../" + outside.getFileName() + "/note.txt");
        input.put("content", "après");

        ToolOutcome outcome = tools.execute("write_file", input);

        assertTrue(outcome.ok(), outcome.errorCode());
        assertEquals("après", Files.readString(cible));
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

    // ---------------------------------------------------- read_file_bytes (F-110 / SF-110-03)

    @Test
    void litUnFichierBinaireParTranchesEtRecomposeLesOctetsExacts() throws IOException {
        byte[] original = new byte[FileTools.MAX_BYTES_CHUNK + 1000];
        new java.util.Random(42).nextBytes(original);
        Files.write(root.resolve("cr.pdf"), original);

        ToolOutcome first = tools.execute("read_file_bytes", bytesInput("cr.pdf", 0, FileTools.MAX_BYTES_CHUNK));
        assertTrue(first.ok(), first.errorCode());
        assertEquals(original.length, first.bytes(), "bytes = taille totale du fichier");
        assertTrue(first.truncated(), "il reste des octets");
        byte[] head = java.util.Base64.getDecoder().decode(first.content());
        assertEquals(FileTools.MAX_BYTES_CHUNK, head.length);
        assertTrue(first.content().getBytes(StandardCharsets.UTF_8).length <= FileTools.MAX_CONTENT_BYTES,
                "la tranche encodée tient dans la borne du contrat");

        ToolOutcome second = tools.execute("read_file_bytes",
                bytesInput("cr.pdf", head.length, FileTools.MAX_BYTES_CHUNK));
        assertTrue(second.ok());
        assertFalse(second.truncated());
        byte[] tail = java.util.Base64.getDecoder().decode(second.content());

        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        joined.write(head);
        joined.write(tail);
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, joined.toByteArray());
    }

    @Test
    void readFileBytesRefuseUnFichierAuDelaDeDixMo() throws IOException {
        Files.write(root.resolve("gros.zip"), new byte[(int) FileTools.MAX_BYTES_FILE + 1]);

        ToolOutcome outcome = tools.execute("read_file_bytes", bytesInput("gros.zip", 0, 1024));

        assertEquals("too_large", outcome.errorCode());
    }

    @Test
    void readFileBytesBorneOffsetEtLength() throws IOException {
        Files.write(root.resolve("a.bin"), new byte[] {1, 2, 3});
        Files.createDirectories(root.resolve("dossier"));

        assertEquals("invalid_input", tools.execute("read_file_bytes", bytesInput("a.bin", 4, 10)).errorCode());
        assertEquals("invalid_input", tools.execute("read_file_bytes", bytesInput("a.bin", -1, 10)).errorCode());
        assertEquals("invalid_input", tools.execute("read_file_bytes", bytesInput("a.bin", 0, 0)).errorCode());
        assertEquals("invalid_input", tools.execute("read_file_bytes",
                bytesInput("a.bin", 0, FileTools.MAX_BYTES_CHUNK + 1)).errorCode());
        assertEquals("is_directory", tools.execute("read_file_bytes", bytesInput("dossier", 0, 10)).errorCode());

        ToolOutcome end = tools.execute("read_file_bytes", bytesInput("a.bin", 3, 10));
        assertTrue(end.ok(), "lire à la fin exacte rend une tranche vide");
        assertEquals("", end.content());
        assertEquals(3, end.bytes());

        ToolOutcome defaults = tools.execute("read_file_bytes", input("path", "a.bin"));
        assertTrue(defaults.ok());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {1, 2, 3},
                java.util.Base64.getDecoder().decode(defaults.content()));
    }

    private static ObjectNode bytesInput(String path, long offset, long length) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("path", path);
        node.put("offset", offset);
        node.put("length", length);
        return node;
    }

    // ------------------------------------------------------------- grep (F-121 / SF-121-01)

    @Test
    void grepRegexAuFormatCheminLigneTexte() throws IOException {
        Files.writeString(root.resolve("a.txt"), "alpha\nBeta TODO-42\ngamma");

        ToolOutcome outcome = tools.execute("grep", input("pattern", "TODO-\\d+"));

        assertTrue(outcome.ok(), outcome.errorCode());
        assertEquals("a.txt:2: Beta TODO-42\n", outcome.content());
    }

    @Test
    void grepFiltreParInclude() throws IOException {
        Files.writeString(root.resolve("a.java"), "int x; // match");
        Files.writeString(root.resolve("b.txt"), "aussi match");

        ToolOutcome outcome = tools.execute("grep", grepInput("pattern", "match", "include", "*.java"));

        assertTrue(outcome.ok(), outcome.errorCode());
        assertEquals("a.java:1: int x; // match\n", outcome.content());
    }

    @Test
    void grepRendLesLignesDeContexte() throws IOException {
        Files.writeString(root.resolve("a.txt"), "l1\nl2 HIT\nl3\nl4");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "HIT");
        input.put("context", 1);

        ToolOutcome outcome = tools.execute("grep", input);

        assertTrue(outcome.ok(), outcome.errorCode());
        assertEquals("a.txt-1- l1\na.txt:2: l2 HIT\na.txt-3- l3\n", outcome.content());
    }

    @Test
    void grepModesCountEtFilesWithMatches() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x\nx\ny");

        ToolOutcome count = tools.execute("grep", grepInput("pattern", "x", "output_mode", "count"));
        assertEquals("a.txt:2\n", count.content());

        ToolOutcome files = tools.execute("grep",
                grepInput("pattern", "x", "output_mode", "files_with_matches"));
        assertEquals("a.txt\n", files.content());
    }

    @Test
    void grepIgnoreCase() throws IOException {
        Files.writeString(root.resolve("a.txt"), "Une LIGNE");
        ObjectNode input = MAPPER.createObjectNode();
        input.put("pattern", "ligne");
        input.put("ignore_case", true);

        ToolOutcome outcome = tools.execute("grep", input);

        assertEquals("a.txt:1: Une LIGNE\n", outcome.content());
    }

    @Test
    void grepSansResultat() throws IOException {
        Files.writeString(root.resolve("a.txt"), "rien");
        assertEquals("Aucun résultat.", tools.execute("grep", input("pattern", "zzz")).content());
    }

    @Test
    void grepRefuseUneRegexInvalide() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        ToolOutcome outcome = tools.execute("grep", input("pattern", "[unclosed"));
        assertEquals("invalid_input", outcome.errorCode());
    }

    // ------------------------------------------------------------- glob (F-121 / SF-121-01)

    @Test
    void globRendLesCheminsTriesParDateDecroissante() throws IOException {
        Files.writeString(root.resolve("vieux.java"), "a");
        Files.writeString(root.resolve("neuf.java"), "b");
        Files.writeString(root.resolve("autre.txt"), "c");
        Files.setLastModifiedTime(root.resolve("vieux.java"),
                java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(root.resolve("neuf.java"),
                java.nio.file.attribute.FileTime.fromMillis(2_000_000L));

        ToolOutcome outcome = tools.execute("glob", input("pattern", "*.java"));

        assertTrue(outcome.ok(), outcome.errorCode());
        assertEquals("neuf.java\nvieux.java", outcome.content());
    }

    @Test
    void globCroiseLesDossiers() throws IOException {
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/App.java"), "x");
        Files.writeString(root.resolve("README.md"), "y");

        ToolOutcome outcome = tools.execute("glob", input("pattern", "**/*.java"));

        assertEquals("src/main/App.java", outcome.content());
    }

    private static ObjectNode grepInput(String k1, String v1, String k2, String v2) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(k1, v1);
        node.put(k2, v2);
        return node;
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
