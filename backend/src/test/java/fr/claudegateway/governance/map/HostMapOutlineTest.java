package fr.claudegateway.governance.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le sommaire de carte tel qu'il rejoint la consigne système (F-136 / SF-136-02).
 *
 * <p>Le test qui compte est {@link #carriesNoVolatileNumber()} : ce bloc vit dans le <b>préfixe
 * stable</b>, et tout octet qui y change invalide le cache de tout ce qui suit. Un compte de faits
 * ou une date, qui bougent à chaque tour, reconstruiraient le cache à chaque demande — exactement le
 * défaut que F-134 vient de corriger.</p>
 */
class HostMapOutlineTest {

    private HostMapFile file(String path, String title, String sections, int facts) {
        return HostMapFile.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).hostId(UUID.randomUUID())
                .path(path).title(title).sections(sections).facts(facts)
                .observedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("liste les fichiers de carte et leurs sections")
    void listsFilesAndSections() {
        String outline = HostMapOutline.of(List.of(
                file("acces.md", "Accès", "Bastions\nCoffres\nComptes de service", 641),
                file("reseau.md", "Réseau", "Proxy\nFlux sortants", 249)));

        assertThat(outline).contains("acces.md — Accès");
        assertThat(outline).contains("· Bastions");
        assertThat(outline).contains("· Comptes de service");
        assertThat(outline).contains("reseau.md — Réseau");
        assertThat(outline).contains("· Proxy");
    }

    @Test
    @DisplayName("LE CRITÈRE : aucun chiffre volatil, sinon le cache se reconstruit à chaque tour")
    void carriesNoVolatileNumber() {
        String outline = HostMapOutline.of(List.of(
                file("acces.md", "Accès", "Bastions", 641),
                file("plateformes.md", "Plateformes", "Kubernetes", 733)));

        assertThat(outline).doesNotContain("641").doesNotContain("733");
        // Ni date : « constaté le … » appartient aux faits, pas au sommaire.
        assertThat(outline).doesNotContain("2026");
    }

    @Test
    @DisplayName("deux appels sur la même carte rendent le MÊME texte, à l'octet")
    void isDeterministic() {
        List<HostMapFile> files = List.of(file("acces.md", "Accès", "Bastions\nCoffres", 641));

        assertThat(HostMapOutline.of(files)).isEqualTo(HostMapOutline.of(files));
    }

    @Test
    @DisplayName("dit qu'on explore la machine APRÈS avoir regardé ce qu'on sait")
    void tellsToLookBeforeExploring() {
        String outline = HostMapOutline.of(List.of(file("acces.md", "Accès", "Bastions", 1)));

        assertThat(outline).contains("Avant d'explorer la machine");
    }

    @Test
    @DisplayName("sans carte, aucun bloc — la consigne reste celle d'avant F-136")
    void saysNothingWithoutAMap() {
        assertThat(HostMapOutline.of(List.of())).isNull();
        assertThat(HostMapOutline.of(null)).isNull();
    }

    @Test
    @DisplayName("un fichier bavard ne mange pas la place des autres")
    void aChattyFileDoesNotStarveTheOthers() {
        StringBuilder sections = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sections.append("Section ").append(i).append('\n');
        }
        String outline = HostMapOutline.of(List.of(
                file("acces.md", "Accès", sections.toString(), 10),
                file("reseau.md", "Réseau", "Proxy", 5)));

        assertThat(outline).contains("· Section 0");
        assertThat(outline).doesNotContain("· Section 39");
        assertThat(outline).contains("autre(s) section(s)");
        // L'autre fichier est toujours là : c'est tout l'objet de la borne par fichier.
        assertThat(outline).contains("reseau.md");
    }

    @Test
    @DisplayName("le bloc est borné, et la coupe se dit")
    void isBoundedAndSaysSo() {
        // La borne par fichier (12 sections) suffit à contenir une carte ordinaire — six fichiers
        // n'atteignent jamais le plafond. Ce qui peut le franchir est le NOMBRE de fichiers, si un
        // paquet en apportait beaucoup : c'est ce cas-là qu'on borne ici.
        StringBuilder sections = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sections.append("Une section au titre plutôt long numéro ").append(i).append('\n');
        }
        List<HostMapFile> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(file("fichier-" + i + ".md", "Titre " + i, sections.toString(), 1));
        }

        String outline = HostMapOutline.of(many);

        assertThat(outline).hasSizeLessThanOrEqualTo(
                HostMapOutline.MAX_CHARS + HostMapOutline.TRUNCATION_NOTICE.length());
        assertThat(outline).endsWith(HostMapOutline.TRUNCATION_NOTICE);
    }

    @Test
    @DisplayName("une carte ordinaire tient largement dans la borne")
    void anOrdinaryMapFitsEasily() {
        // Les six fichiers du paquet « Le savoir durable », bien garnis : le sommaire reste de
        // l'ordre du kilo-octet. C'est ce qui rend son séjour dans le préfixe stable bon marché.
        StringBuilder sections = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sections.append("Section ").append(i).append('\n');
        }
        List<HostMapFile> map = List.of(
                file("README.md", "Le poste", sections.toString(), 299),
                file("acces.md", "Accès", sections.toString(), 641),
                file("reseau.md", "Réseau", sections.toString(), 249),
                file("plateformes.md", "Plateformes", sections.toString(), 733),
                file("donnees.md", "Données", sections.toString(), 217),
                file("exploitation.md", "Exploitation", sections.toString(), 454));

        assertThat(HostMapOutline.of(map))
                .hasSizeLessThan(HostMapOutline.MAX_CHARS)
                .doesNotContain(HostMapOutline.TRUNCATION_NOTICE);
    }
}
