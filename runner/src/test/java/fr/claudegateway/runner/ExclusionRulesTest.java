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
 * Filtre de listage du runner (F-38 / SF-38-10, revu par F-73 / SF-73-01) : bruit de construction,
 * {@code .runnerignore} avec repli {@code .gitignore}, motifs résolus relativement au dossier du
 * projet. <b>Plus aucune liste de secrets</b> : tout est négociable, parce que plus rien ici ne
 * prétend protéger.
 */
class ExclusionRulesTest {

    @TempDir
    Path root;

    // ------------------------------------------------- plus aucune liste de secrets (F-73)

    @Test
    void nExcluePlusAucunSecretSansFichierDeRegles() {
        ExclusionRules rules = ExclusionRules.load(root, null);

        assertEquals("(aucun)", rules.source());
        assertEquals(0, rules.userRuleCount());
        // Chacune de ces lignes affirmait l'inverse avant le 2026-09-12. La garde ne tenait que
        // sur les outils fichiers : `cat .env` par bash n'a jamais rien rencontré.
        assertFalse(rules.isExcludedFile(".env"));
        assertFalse(rules.isExcludedFile("cert.pem"));
        assertFalse(rules.isExcludedFile("infra/tls/serveur.pem"));
        assertFalse(rules.isExcludedFile("id_rsa"));
        assertFalse(rules.isExcludedFile(".ssh/id_rsa.pub"));
        assertFalse(rules.isExcludedFile(".aws/credentials"));
        assertFalse(rules.isExcludedFile(".kube/config"));
        assertFalse(rules.isExcludedFile(".ssh/config"));
    }

    @Test
    void uneRegleUtilisateurSurUnSecretResteNegociable() throws IOException {
        Files.writeString(root.resolve(".runnerignore"), ".env\n!apps/api/.env\n");

        ExclusionRules rules = ExclusionRules.load(root, null);

        assertTrue(rules.isExcludedFile(".env"), "l'utilisateur peut toujours écarter ce qu'il veut");
        assertFalse(rules.isExcludedFile("apps/api/.env"), "et sa négation l'emporte désormais");
    }

    @Test
    void neExcluteNiClaudeMdNiLesSkills() {
        ExclusionRules rules = ExclusionRules.noiseOnly();

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
    // Sans le bruit par défaut (SF-38-21) : ce test exerce la MÉCANIQUE des motifs — ancrage,
    // négation, profondeur — et vingt motifs supplémentaires en fausseraient la lecture.
    void appliqueLesMotifsGitignoreCourants() {
        ExclusionRules rules = ExclusionRules.ofWithoutNoise(List.of(
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
        assertTrue(rules.isExcludedFile("node_modules/x.js"), "le bruit s'écarte quand même");
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
        assertFalse(ExclusionRules.noiseOnly().isExcludedDirectory(""));
    }

    // --- Bruit de construction écarté par défaut (F-38 / SF-38-21) ---------------------------

    @Test
    void ecarteLeBruitDeConstructionSansAucuneRegle() {
        // Le banc d'essai : 40 590 fichiers dont 40 112 dans node_modules, et l'utilisateur
        // recevait 4 829 lignes de dépendances au lieu des 478 fichiers de son projet.
        ExclusionRules rules = ExclusionRules.noiseOnly();

        assertTrue(rules.isExcludedFile("frontend/node_modules/rxjs/index.js"));
        assertTrue(rules.isExcludedFile("backend/target/classes/App.class"));
        assertTrue(rules.isExcludedFile("frontend/.angular/cache/x"));
        assertTrue(rules.isExcludedFile("api/__pycache__/mod.pyc"));
        // Le projet lui-même reste entier.
        assertFalse(rules.isExcludedFile("backend/src/main/java/App.java"));
        assertFalse(rules.isExcludedFile("frontend/src/app/app.ts"));
        assertFalse(rules.isExcludedFile("CLAUDE.md"));
    }

    @Test
    void uneNegationExpliciteAnnuleLeBruitParDefaut() {
        // On écarte du bruit pour que la liste reste lisible ; on ne protège rien.
        ExclusionRules rules = ExclusionRules.of(List.of("!node_modules/"));

        assertFalse(rules.isExcludedFile("frontend/node_modules/rxjs/index.js"));
    }

    @Test
    void toutEstNegociableDepuisF73() {
        // Plus aucune règle n'écrase les autres : la dernière qui correspond l'emporte, point.
        ExclusionRules rules = ExclusionRules.of(List.of("!.env", "!node_modules/"));

        assertFalse(rules.isExcludedFile(".env"));
        assertFalse(rules.isExcludedFile("frontend/node_modules/rxjs/index.js"));
    }
}
