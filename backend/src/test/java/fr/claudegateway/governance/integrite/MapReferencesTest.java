package fr.claudegateway.governance.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-95 / SF-95-01 — les chemins du poste qu'une carte cite, et <b>ce qu'on n'ira jamais vérifier</b>.
 *
 * <p>Sans la liste d'exceptions, le contrôle des liens morts serait inutilisable : une carte est
 * pleine de chemins volontairement indicatifs — des formes, des exemples, des chemins de la machine
 * du client. Ces tests gardent chacune des six catégories écartées.</p>
 */
class MapReferencesTest {

    private static final Set<String> CARTE =
            Set.of("README.md", "acces.md", "reseau.md", "plateformes.md");

    @Test
    @DisplayName("un chemin cité dans un fait est retenu")
    void aPathCitedInAFactIsKept() {
        List<String> references = MapReferences.of("""
                ## Plateformes

                Le sujet de bascule vit dans `migration-dns/STATE.md`, constaté le 2026-09-13.
                Voir aussi [le plan](bascule-b2b/PLAN-ACTION.md).
                """, CARTE);

        assertThat(references)
                .containsExactly("migration-dns/STATE.md", "bascule-b2b/PLAN-ACTION.md");
    }

    @Test
    @DisplayName("les consignes du gabarit — citations et blocs de code — sont écartées")
    void templateInstructionsAreIgnored() {
        List<String> references = MapReferences.of("""
                ## Accès

                > Le détail vit dans `acces-du-client/secrets.md`.

                ```
                repos/exemple/NOTES.md
                ```

                Le bastion est décrit dans `bastions/bst-01.md`.
                """, CARTE);

        assertThat(references).containsExactly("bastions/bst-01.md");
    }

    @Test
    @DisplayName("URL, chemins absolus et formes à chevrons ne sont pas des chemins du poste")
    void urlsAbsolutePathsAndPlaceholdersAreExcluded() {
        List<String> references = MapReferences.of("""
                ## Réseau

                Wiki : [le wiki](https://wiki.client.fr/reseau), `mailto:reseau@client.fr`.
                Sur le serveur : `/etc/resolv.conf`, `~/.ssh/config`, `C:\\Users\\ops\\notes.md`.
                Forme : `repos/<dépôt>`, `chemin/vers`, `../ailleurs/note.md`.
                """, CARTE);

        assertThat(references).isEmpty();
    }

    @Test
    @DisplayName("un fichier de la carte n'est pas un lien mort — il est déjà vérifié ailleurs")
    void mapFilesAreNotDeadLinkCandidates() {
        List<String> references = MapReferences.of(
                "Le détail est dans `acces.md`, et le reste dans `sujets/vpn.md`.", CARTE);

        assertThat(references).containsExactly("sujets/vpn.md");
    }

    @Test
    @DisplayName("la ponctuation de fin et le « ./ » de tête ne font pas deux chemins différents")
    void punctuationAndLeadingDotSlashAreNormalised() {
        List<String> references = MapReferences.of(
                "Voir `./migration-dns/STATE.md`, puis [là](migration-dns/STATE.md).", CARTE);

        assertThat(references).containsExactly("migration-dns/STATE.md");
    }

    @Test
    @DisplayName("la liste est bornée")
    void theListIsBounded() {
        StringBuilder carte = new StringBuilder("## Sujets\n\n");
        for (int i = 0; i < MapReferences.MAX_REFERENCES + 5; i++) {
            carte.append("- sujet ").append(i).append(" : `sujet-").append(i).append("/STATE.md`\n");
        }

        assertThat(MapReferences.of(carte.toString(), CARTE))
                .hasSize(MapReferences.MAX_REFERENCES);
    }

    @Test
    @DisplayName("SF-148-09 — un répertoire cité comme une carte est écarté (jamais lu)")
    void aCitedDirectoryIsNotAMapFile() {
        List<String> references = MapReferences.of("""
                ## Sujets

                Voir le sujet [lzi](lzi/), la plateforme `data-platform/`, les dépôts dans `repos/`,
                et le socle `socle-reseau-corp/`.
                """, CARTE);

        assertThat(references).isEmpty();
    }

    @Test
    @DisplayName("SF-148-09 — un dépôt (« corp.git », « repos/nom ») n'est pas un fichier de carte")
    void aRepositoryIsNotAMapFile() {
        List<String> references = MapReferences.of(
                "Le dépôt est dans `corporate-center/corp.git`, cloné sous `repos/portail-client`.",
                CARTE);

        assertThat(references).isEmpty();
    }

    @Test
    @DisplayName("SF-148-09 — « STATE.md »/« PLAN-ACTION.md » nus, et « .md » seul, sont écartés")
    void bareSubjectFileNamesAtRootAreExcluded() {
        List<String> references = MapReferences.of("""
                ## Convention

                Chaque sujet porte son `STATE.md` et son `PLAN-ACTION.md` ; tout `.md` est daté.
                """, CARTE);

        assertThat(references).isEmpty();
    }

    @Test
    @DisplayName("SF-148-09 — un « STATE.md »/« PLAN-ACTION.md » DANS un sujet reste retenu")
    void subjectScopedStateAndPlanAreKept() {
        List<String> references = MapReferences.of(
                "Le fil est dans [l'état](lzi/STATE.md) et le [plan](lzi/PLAN-ACTION.md).", CARTE);

        assertThat(references).containsExactly("lzi/STATE.md", "lzi/PLAN-ACTION.md");
    }

    @Test
    @DisplayName("un contenu nul ne lève pas")
    void nullContentIsSurvivable() {
        assertThat(MapReferences.of(null, CARTE)).isEmpty();
        assertThat(MapReferences.of("   ", null)).isEmpty();
    }

    @Test
    @DisplayName("les gabarits de carte livrés ne citent aucun chemin à vérifier")
    void theShippedMapTemplatesCiteNothingToCheck() throws IOException {
        for (String fichier : List.of("README", "acces", "reseau", "plateformes", "donnees",
                "exploitation")) {
            String contenu = ressource("governance/savoir-durable/carte/" + fichier + ".md");

            assertThat(MapReferences.of(contenu, CARTE))
                    .as("gabarit « %s.md »", fichier).isEmpty();
        }
    }

    private static String ressource(String chemin) throws IOException {
        try (InputStream stream =
                MapReferencesTest.class.getClassLoader().getResourceAsStream(chemin)) {
            assertThat(stream).as("ressource « %s »", chemin).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
