package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.map.index.HostMapPatterns.Identifier;

/** La couche déterministe de l'extraction (F-174 / SF-174-02, D2 a). */
class HostMapPatternsTest {

    private static List<String> values(String line) {
        return HostMapPatterns.identifiers(line).stream().map(Identifier::value).toList();
    }

    @Test
    @DisplayName("reconnaît un compte AWS, un ARN, une URL, une IP, un CIDR et un domaine")
    void recognisesExactIdentifiers() {
        List<Identifier> found = HostMapPatterns.identifiers(
                "- compte 123456789012 (prod) ; rôle arn:aws:iam::123456789012:role/Admin ; "
                        + "console https://lzi.cagip.fr/console. ; bastion 10.20.30.40, réseau 10.0.0.0/16, "
                        + "dns api.corp.cagip.fr.");
        assertThat(found).extracting(Identifier::kind, Identifier::value).contains(
                org.assertj.core.groups.Tuple.tuple("compte_aws", "123456789012"),
                org.assertj.core.groups.Tuple.tuple("arn", "arn:aws:iam::123456789012:role/Admin"),
                org.assertj.core.groups.Tuple.tuple("url", "https://lzi.cagip.fr/console"),
                org.assertj.core.groups.Tuple.tuple("ip", "10.20.30.40"),
                org.assertj.core.groups.Tuple.tuple("ip", "10.0.0.0/16"),
                org.assertj.core.groups.Tuple.tuple("domaine", "api.corp.cagip.fr"));
        // Le domaine de l'URL n'est pas un second identifiant.
        assertThat(values("voir https://lzi.cagip.fr/x")).containsExactly("https://lzi.cagip.fr/x");
    }

    @Test
    @DisplayName("un nom de fichier n'est pas un domaine, un chemin n'est pas un dépôt")
    void filesAreNotIdentifiers() {
        assertThat(values("- voir acces.md et plateformes.md")).isEmpty();
        assertThat(values("- le dépôt contient docs/acces.md")).isEmpty();
        assertThat(values("- et/ou selon le cas")).isEmpty();
    }

    @Test
    @DisplayName("un dépôt groupe/projet n'est reconnu que dans une ligne qui parle de dépôt")
    void repositoriesNeedContext() {
        assertThat(values("- dépôt GitLab socle/landing-zone, branche main"))
                .contains("socle/landing-zone");
        assertThat(values("- socle/landing-zone")).isEmpty();
    }

    @Test
    @DisplayName("la date de constat, l'échéance et le piège")
    void datesAndPitfalls() {
        String line = "- jeton GitLab périme le 2026-10-09, constaté le 2026-09-14";
        assertThat(HostMapPatterns.observedOn(line)).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(HostMapPatterns.deadline(line)).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(HostMapPatterns.deadline("- bastion ouvert, constaté le 2026-09-14")).isNull();
        assertThat(HostMapPatterns.isPitfall("- ⚠ le proxy coupe les websockets")).isTrue();
        assertThat(HostMapPatterns.isPitfall("- Piège : la console exige le VPN")).isTrue();
        assertThat(HostMapPatterns.isPitfall("- bastion ouvert")).isFalse();
    }
}
