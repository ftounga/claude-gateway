package fr.claudegateway.governance.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les faits qui répondent à la question (F-137 / SF-137-01).
 *
 * <p>Deux façons d'échouer, et les deux sont graves : <b>ne rien trouver</b> quand la carte sait, et
 * <b>tout remonter</b> sur un mot courant — auquel cas le rappel noie la question qu'il devait
 * servir.</p>
 */
class HostFactLookupTest {

    private HostMapFile file(String path, String content) {
        return HostMapFile.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).hostId(UUID.randomUUID())
                .path(path).title(path).content(content).facts(0)
                .observedAt(OffsetDateTime.now())
                .build();
    }

    private final HostMapFile acces = file("acces.md", """
            # Accès

            ## Bastions
            - Le bastion lzi est le seul point d'entrée, constaté le 2026-09-14, source : équipe socle
            - L'accès au bastion passe par CyberArk, constaté le 2026-09-15, source : RSSI

            ## Coffres
            - CyberArk garde les comptes à privilèges, constaté le 2026-09-15
            """);

    private final HostMapFile reseau = file("reseau.md", """
            # Réseau

            ## Proxy
            - Le proxy Zscaler intercepte le TLS, constaté le 2026-09-16, source : test terrain
            - portal.ng-itconsulting.com est joignable depuis le poste, constaté le 2026-09-18
            """);

    // ---------------------------------------------------- les termes distinctifs

    @Test
    @DisplayName("reconnaît les noms d'infrastructure")
    void recognisesInfrastructureNames() {
        assertThat(HostFactLookup.distinctiveTerms(
                "est-ce que lzi passe par CyberArk pour joindre portal.ng-itconsulting.com ?"))
                .contains("cyberark", "portal.ng-itconsulting.com");
    }

    @Test
    @DisplayName("ignore les mots ordinaires, qui ramèneraient toute la carte")
    void ignoresOrdinaryWords() {
        assertThat(HostFactLookup.distinctiveTerms(
                "quel est le serveur de bastion pour cet acces au reseau du client"))
                .isEmpty();
    }

    @Test
    @DisplayName("retient les sigles, les identifiants et les domaines")
    void keepsAcronymsIdentifiersAndDomains() {
        assertThat(HostFactLookup.distinctiveTerms("CAGIP utilise claude-gateway sur k8s-prod-01."))
                .containsExactlyInAnyOrder("cagip", "claude-gateway", "k8s-prod-01");
    }

    @Test
    @DisplayName("la ponctuation de fin de phrase ne fait pas partie du nom")
    void trailingPunctuationIsNotPartOfTheName() {
        assertThat(HostFactLookup.distinctiveTerms("on passe par `CyberArk`, et par Zscaler."))
                .contains("cyberark");
    }

    // ---------------------------------------------------- les faits joints

    @Test
    @DisplayName("LE CRITÈRE : une question sur un élément connu reçoit ce qu'on en sait")
    void aquestionAboutAKnownThingGetsWhatWeKnow() {
        String block = HostFactLookup.factsFor(List.of(acces, reseau),
                "est-ce que CyberArk garde les comptes à privilèges ?");

        assertThat(block).contains("CyberArk garde les comptes à privilèges");
        assertThat(block).contains("constaté le 2026-09-15");
        // Le fichier d'origine voyage avec le fait : l'agent peut y retourner.
        assertThat(block).contains("[acces.md]");
    }

    @Test
    @DisplayName("un fait porté par deux termes cités n'est joint qu'une fois")
    void afactIsNeverRepeated() {
        String block = HostFactLookup.factsFor(List.of(acces),
                "le lien entre lzi et CyberArk ?");

        assertThat(block.split("L'accès au bastion passe par CyberArk", -1)).hasSize(2);
    }

    @Test
    @DisplayName("ne joint rien quand la question ne cite rien de connu")
    void saysNothingWhenNothingIsCited() {
        assertThat(HostFactLookup.factsFor(List.of(acces, reseau),
                "peux-tu me resumer la situation du client")).isNull();
    }

    @Test
    @DisplayName("ne joint rien quand le terme est inconnu de la carte")
    void saysNothingForAnUnknownTerm() {
        assertThat(HostFactLookup.factsFor(List.of(acces), "et Vault-HashiCorp ?")).isNull();
    }

    @Test
    @DisplayName("sans carte, rien")
    void saysNothingWithoutAMap() {
        assertThat(HostFactLookup.factsFor(List.of(), "CyberArk ?")).isNull();
        assertThat(HostFactLookup.factsFor(null, "CyberArk ?")).isNull();
    }

    @Test
    @DisplayName("les titres et les consignes du gabarit ne sont pas des faits")
    void headingsAndTemplateNotesAreNotFacts() {
        HostMapFile file = file("acces.md", """
                # CyberArk

                > Note du gabarit : décrivez ici CyberArk.

                - CyberArk est en 12.6, constaté le 2026-09-20
                """);

        String block = HostFactLookup.factsFor(List.of(file), "quelle version de CyberArk ?");

        assertThat(block).contains("CyberArk est en 12.6");
        assertThat(block).doesNotContain("Note du gabarit");
        assertThat(block).doesNotContain("# CyberArk");
    }

    @Test
    @DisplayName("un terme omniprésent dans un fichier est ignoré : il décrit la carte, pas la question")
    void anOmnipresentTermIsIgnored() {
        StringBuilder content = new StringBuilder("# Accès\n\n");
        for (int i = 0; i < 20; i++) {
            content.append("- CyberArk apparaît à la ligne ").append(i).append('\n');
        }

        assertThat(HostFactLookup.factsFor(List.of(file("acces.md", content.toString())),
                "et CyberArk ?")).isNull();
    }

    @Test
    @DisplayName("le bloc est borné : un rappel, jamais la carte entière")
    void theBlockIsBounded() {
        StringBuilder content = new StringBuilder("# Plateformes\n\n");
        for (int i = 0; i < 40; i++) {
            content.append("- k8s-prod-").append(i)
                    .append(" est un cluster de production, constaté le 2026-09-20\n");
        }
        List<HostMapFile> files = List.of(file("plateformes.md", content.toString()));
        StringBuilder question = new StringBuilder("état de ");
        for (int i = 0; i < 40; i++) {
            question.append("k8s-prod-").append(i).append(' ');
        }

        String block = HostFactLookup.factsFor(files, question.toString());

        assertThat(block).isNotNull();
        assertThat(block.lines().filter(line -> line.startsWith("- ")).count())
                .isLessThanOrEqualTo(HostFactLookup.MAX_FACTS);
        assertThat(block).hasSizeLessThanOrEqualTo(HostFactLookup.MAX_CHARS + 2);
    }

    @Test
    @DisplayName("un fait très long est coupé, pas recopié en entier")
    void averyLongFactIsTrimmed() {
        String longLine = "- CyberArk " + "x".repeat(1_000);
        String block = HostFactLookup.factsFor(List.of(file("acces.md", "# A\n\n" + longLine)),
                "CyberArk ?");

        assertThat(block).isNotNull();
        assertThat(block.length()).isLessThan(longLine.length());
        assertThat(block).contains("…");
    }
}
