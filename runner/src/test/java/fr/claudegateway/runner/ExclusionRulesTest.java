package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Filtre d'exclusion du runner (F-38 / SF-38-10, décision D10) : liste par défaut non désactivable,
 * {@code .runnerignore} avec repli {@code .gitignore}, motifs résolus relativement à la racine.
 */
class ExclusionRulesTest {

    @TempDir
    Path root;

    // ------------------------------------------------- liste par défaut non désactivable

    @Test
    void excluteLaListeParDefautSansFichierDeRegles() {
        ExclusionRules rules = ExclusionRules.load(root, null);

        assertEquals("(aucun)", rules.source());
        assertEquals(0, rules.userRuleCount());
        assertTrue(rules.isExcludedFile(".env"));
        assertTrue(rules.isExcludedFile("cert.pem"));
        assertTrue(rules.isExcludedFile("infra/tls/serveur.pem"));
        assertTrue(rules.isExcludedFile("id_rsa"));
        assertTrue(rules.isExcludedFile(".ssh/id_rsa.pub"));
        assertTrue(rules.isExcludedFile(".aws/credentials"));
        assertTrue(rules.isExcludedFile(".kube/config"));
        assertTrue(rules.isExcludedFile(".ssh/config"));
    }

    @Test
    void excluteLaDenyListAToutesLesProfondeurs() {
        ExclusionRules rules = ExclusionRules.defaultsOnly();

        assertTrue(rules.isExcludedFile("apps/api/.env"));
        assertTrue(rules.isExcludedFile("projet/.kube/config"));
        assertTrue(rules.isExcludedFile("home/.aws/credentials"));
        assertTrue(rules.isExcludedDirectory("projet/.ssh"));
    }

    @Test
    void uneNegationNeReactiveJamaisLaListeParDefaut() throws IOException {
        Files.writeString(root.resolve(".runnerignore"), "!.env\n!*.pem\n!.ssh/\n");

        ExclusionRules rules = ExclusionRules.load(root, null);

        assertTrue(rules.isExcludedFile(".env"));
        assertTrue(rules.isExcludedFile("cle.pem"));
        assertTrue(rules.isExcludedFile(".ssh/id_rsa"));
    }

    @Test
    void neExcluteNiClaudeMdNiLesSkills() {
        ExclusionRules rules = ExclusionRules.defaultsOnly();

        assertFalse(rules.isExcludedFile("CLAUDE.md"));
        assertFalse(rules.isExcludedFile(".claude/skills/revue.md"));
        assertFalse(rules.isExcludedDirectory(".claude"));
        assertFalse(rules.isExcludedDirectory(".claude/skills"));
        assertFalse(rules.isExcludedFile("skills/deploiement.md"));
        assertFalse(rules.isExcludedFile("docs/README.md"));
        assertFalse(rules.isExcludedFile("environment.ts"));
    }

    // ------------------------------------------------- source des règles utilisateur

    @Test
    void utiliseRunnerignoreEtIgnoreGitignoreQuandLesDeuxExistent() throws IOException {
        Files.writeString(root.resolve(".runnerignore"), "build/\n");
        Files.writeString(root.resolve(".gitignore"), "src/\n");

        ExclusionRules rules = ExclusionRules.load(root, null);

        assertEquals(".runnerignore", rules.source());
        assertTrue(rules.isExcludedFile("build/app.js"));
        assertFalse(rules.isExcludedFile("src/App.java"));
    }

    @Test
    void seRabatSurGitignoreQuandRunnerignoreEstAbsent() throws IOException {
        Files.writeString(root.resolve(".gitignore"), "target/\n*.log\n");

        ExclusionRules rules = ExclusionRules.load(root, null);

        assertEquals(".gitignore", rules.source());
        assertTrue(rules.isExcludedFile("target/classes/App.class"));
        assertTrue(rules.isExcludedFile("logs/app.log"));
        assertFalse(rules.isExcludedFile("src/App.java"));
    }

    // ------------------------------------------------- syntaxe des motifs

    @Test
    void appliqueLesMotifsGitignoreCourants() {
        ExclusionRules rules = ExclusionRules.of(List.of(
                "# commentaire",
                "",
                "*.log",
                "!garder.log",
                "/build/",
                "node_modules/",
                "docs/**/prive.md",
                "temp?.txt"));

        assertTrue(rules.isExcludedFile("app.log"));
        assertTrue(rules.isExcludedFile("var/app.log"));
        assertFalse(rules.isExcludedFile("garder.log"), "la dernière règle qui matche l'emporte");
        assertTrue(rules.isExcludedFile("build/app.js"));
        assertFalse(rules.isExcludedFile("src/build/app.js"), "motif ancré à la racine");
        assertTrue(rules.isExcludedFile("front/node_modules/pkg/index.js"), "motif de dossier à toute profondeur");
        assertTrue(rules.isExcludedFile("docs/a/b/prive.md"));
        assertTrue(rules.isExcludedFile("docs/prive.md"));
        assertTrue(rules.isExcludedFile("temp1.txt"));
        assertFalse(rules.isExcludedFile("temp12.txt"));
        assertFalse(rules.isExcludedFile("src/App.java"));
    }

    @Test
    void unMotifDeDossierNExclutPasUnFichierDeMemeNom() {
        ExclusionRules rules = ExclusionRules.of(List.of("cache/"));

        assertTrue(rules.isExcludedDirectory("cache"));
        assertTrue(rules.isExcludedFile("cache/x.bin"));
        assertFalse(rules.isExcludedFile("cache"), "« cache/ » ne vise que les dossiers");
    }

    @Test
    void unDossierExcluEmporteToutSonContenuMalgreUneNegation() {
        ExclusionRules rules = ExclusionRules.of(List.of("secrets/", "!secrets/public.txt"));

        assertTrue(rules.isExcludedFile("secrets/public.txt"));
        assertTrue(rules.isExcludedFile("secrets/a/b.txt"));
    }

    // ------------------------------------------------- robustesse

    @Test
    void ignoreLesLignesInexploitablesSansPerdreLesAutres() {
        ExclusionRules rules = ExclusionRules.of(List.of("!", "   ", "#x", "/", "*.tmp"));

        assertEquals(1, rules.userRuleCount());
        assertTrue(rules.isExcludedFile("a.tmp"));
    }

    @Test
    void ignoreUnFichierDeReglesDemesure() throws IOException {
        StringBuilder oversized = new StringBuilder();
        while (oversized.length() <= ExclusionRules.MAX_RULES_FILE_BYTES) {
            oversized.append("motif-").append(oversized.length()).append('\n');
        }
        Files.writeString(root.resolve(".runnerignore"), oversized.toString());

        ExclusionRules rules = ExclusionRules.load(root, null);

        assertEquals(0, rules.userRuleCount());
        assertTrue(rules.isExcludedFile(".env"), "la liste par défaut s'applique quand même");
    }

    @Test
    void borneLeNombreDeRegles() {
        List<String> patterns = new java.util.ArrayList<>();
        for (int i = 0; i < ExclusionRules.MAX_RULES + 10; i++) {
            patterns.add("motif-" + i);
        }

        assertEquals(ExclusionRules.MAX_RULES, ExclusionRules.of(patterns).userRuleCount());
    }

    @Test
    void unRepertoireNommeRunnerignoreNEstPasLuCommeUnFichierDeRegles() throws IOException {
        Files.createDirectory(root.resolve(".runnerignore"));
        Files.writeString(root.resolve(".gitignore"), "build/\n");

        ExclusionRules rules = ExclusionRules.load(root, null);

        assertEquals(".gitignore", rules.source());
        assertTrue(rules.isExcludedFile("build/x"));
    }

    @Test
    void unCheminVideNEstJamaisExclu() {
        assertFalse(ExclusionRules.defaultsOnly().isExcludedDirectory(""));
    }
}
