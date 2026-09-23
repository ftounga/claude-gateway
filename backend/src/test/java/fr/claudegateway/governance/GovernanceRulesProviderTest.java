package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * F-51 / SF-51-04 — les règles d'un paquet actif rejoignent la consigne système, nommées, bornées, et
 * seulement pour le projet du tour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceRulesProviderTest {

    @Mock
    private GovernanceActivationService activationService;
    @Mock
    private GovernancePackageService packageService;

    private GovernanceRulesProvider provider;

    private final UUID alice = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();
    /** Le poste qui gouverne ce dossier : depuis F-75, l'activation ne vit plus sur le projet. */
    private final UUID host = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        provider = new GovernanceRulesProvider(activationService, packageService);
    }

    private GovernancePackage pkg(String name, String rules) {
        GovernancePackage pkg = GovernancePackage.builder().id(UUID.randomUUID())
                .slug(name.toLowerCase()).name(name).version(1).published(true).rules(rules).build();
        when(packageService.require(pkg.getId())).thenReturn(pkg);
        return pkg;
    }

    private GovernancePackage profile(String slug, String name, String rules) {
        GovernancePackage pkg = GovernancePackage.builder().id(UUID.randomUUID())
                .slug(slug).name(name).version(1).published(true).rules(rules).build();
        when(packageService.require(pkg.getId())).thenReturn(pkg);
        return pkg;
    }

    private GovernanceActivation activationOf(GovernancePackage pkg) {
        return GovernanceActivation.builder().userId(alice).hostId(host)
                .packageId(pkg.getId()).appliedVersion(1)
                .status(GovernanceActivationStatus.APPLIED).build();
    }

    @Test
    @DisplayName("aucun paquet actif : rien n'est ajouté, et aucun paquet n'est même lu")
    void noActivationMeansNoReadAtAll() {
        when(activationService.activeOnWorkspace(alice, workspace)).thenReturn(List.of());

        assertThat(provider.rulesFor(alice, workspace)).isNull();
        verify(packageService, never()).require(any());
    }

    @Test
    @DisplayName("un paquet actif porte ses règles, sous son nom")
    void namesEachPackage() {
        GovernancePackage livrables = pkg("Livrables sans trace", "Aucun livrable ne suggère un LLM.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(livrables)));

        String rules = provider.rulesFor(alice, workspace);

        assertThat(rules).contains("## Livrables sans trace")
                .contains("Aucun livrable ne suggère un LLM.");
    }

    @Test
    @DisplayName("deux paquets apparaissent dans l'ordre d'activation")
    void keepsActivationOrder() {
        GovernancePackage premier = pkg("Premier", "Règle A.");
        GovernancePackage second = pkg("Second", "Règle B.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(premier), activationOf(second)));

        String rules = provider.rulesFor(alice, workspace);

        assertThat(rules.indexOf("## Premier")).isLessThan(rules.indexOf("## Second"));
    }

    @Test
    @DisplayName("un paquet sans règles n'ajoute pas de titre vide")
    void skipsPackageWithoutRules() {
        GovernancePackage muet = pkg("Muet", "   ");
        GovernancePackage parlant = pkg("Parlant", "Règle.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(muet), activationOf(parlant)));

        assertThat(provider.rulesFor(alice, workspace)).doesNotContain("## Muet")
                .contains("## Parlant");
    }

    @Test
    @DisplayName("si aucun paquet actif ne porte de règles, rien n'est ajouté")
    void allSilentMeansNothing() {
        GovernancePackage muet = pkg("Muet", null);
        when(activationService.activeOnWorkspace(alice, workspace)).thenReturn(List.of(activationOf(muet)));

        assertThat(provider.rulesFor(alice, workspace)).isNull();
    }

    @Test
    @DisplayName("le bloc est coupé à sa borne, et la coupe se dit")
    void truncatesAndSaysSo() {
        GovernancePackage enorme = pkg("Énorme",
                "x".repeat(GovernanceRulesProvider.MAX_RULES_BLOCK_CHARS + 500));
        when(activationService.activeOnWorkspace(alice, workspace)).thenReturn(List.of(activationOf(enorme)));

        String rules = provider.rulesFor(alice, workspace);

        assertThat(rules).hasSize(GovernanceRulesProvider.MAX_RULES_BLOCK_CHARS
                + GovernanceRulesProvider.TRUNCATION_NOTICE.length());
        assertThat(rules).endsWith(GovernanceRulesProvider.TRUNCATION_NOTICE);
    }

    @Test
    @DisplayName("un paquet disparu n'empêche pas les autres de s'appliquer")
    void missingPackageDoesNotBreakTheRest() {
        GovernancePackage parlant = pkg("Parlant", "Règle.");
        UUID gone = UUID.randomUUID();
        when(packageService.require(gone))
                .thenThrow(new GovernancePackageNotFoundException("Paquet introuvable."));
        when(activationService.activeOnWorkspace(alice, workspace)).thenReturn(List.of(
                GovernanceActivation.builder().userId(alice).hostId(host).packageId(gone)
                        .appliedVersion(1).status(GovernanceActivationStatus.APPLIED).build(),
                activationOf(parlant)));

        assertThat(provider.rulesFor(alice, workspace)).contains("## Parlant");
    }

    @Test
    @DisplayName("une lecture impossible rend un tour sans règles, pas un tour raté")
    void unreadableActivationsAreIgnored() {
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThat(provider.rulesFor(alice, workspace)).isNull();
    }

    @Test
    @DisplayName("sans utilisateur ni projet, on ne va rien chercher")
    void noIdentityMeansNoLookup() {
        assertThat(provider.rulesFor(null, workspace)).isNull();
        assertThat(provider.rulesFor(alice, null)).isNull();
        verify(activationService, never()).activeOnWorkspace(any(), any());
    }

    // ---------------------------------------------- F-148 / SF-148-02 : profil qui remplace l'amorce

    @Test
    @DisplayName("un profil actif fournit sa 1re phrase comme rôle")
    void activeProfileYieldsItsRoleSentence() {
        GovernancePackage archi = profile("profil-architecte", "Profil — Architecte",
                "Tu interviens comme **architecte** sur l'infrastructure d'un client. Ce n'est pas un "
                        + "travail de développement : tu comprends un existant.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(archi)));

        String role = provider.activeProfileRole(alice, workspace);

        // 1re phrase seulement, gras Markdown retiré.
        assertThat(role).isEqualTo("Tu interviens comme architecte sur l'infrastructure d'un client.");
    }

    @Test
    @DisplayName("le premier profil actif (ordre d'activation) donne le rôle")
    void firstActiveProfileWins() {
        GovernancePackage premier = profile("profil-architecte", "Architecte", "Tu es architecte. Suite.");
        GovernancePackage second = profile("profil-securite", "Sécurité", "Tu es en sécurité. Suite.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(premier), activationOf(second)));

        assertThat(provider.activeProfileRole(alice, workspace)).isEqualTo("Tu es architecte.");
    }

    @Test
    @DisplayName("un paquet non-profil ne fournit jamais de rôle")
    void nonProfilePackageIsNeverARole() {
        GovernancePackage livrables = pkg("Livrables", "Aucune trace de LLM. Point.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(livrables)));

        assertThat(provider.activeProfileRole(alice, workspace)).isNull();
    }

    @Test
    @DisplayName("un profil sans règles n'impose aucun rôle")
    void blankProfileYieldsNoRole() {
        GovernancePackage muet = profile("profil-donnees", "Données", "   ");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(muet)));

        assertThat(provider.activeProfileRole(alice, workspace)).isNull();
    }

    @Test
    @DisplayName("aucune activation, ou identité nulle : pas de rôle et pas de lecture inutile")
    void noProfileMeansNoRole() {
        when(activationService.activeOnWorkspace(alice, workspace)).thenReturn(List.of());
        assertThat(provider.activeProfileRole(alice, workspace)).isNull();
        assertThat(provider.activeProfileRole(null, workspace)).isNull();
        assertThat(provider.activeProfileRole(alice, null)).isNull();
    }

    @Test
    @DisplayName("une lecture impossible rend l'amorce générique, pas un tour raté")
    void unreadableProfileFallsBack() {
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThat(provider.activeProfileRole(alice, workspace)).isNull();
    }

    @Test
    @DisplayName("la phrase de rôle est bornée")
    void roleSentenceIsBounded() {
        String noPeriod = "x".repeat(GovernanceRulesProvider.PROFILE_ROLE_MAX_CHARS + 200);
        assertThat(GovernanceRulesProvider.roleSentenceOf(noPeriod))
                .hasSize(GovernanceRulesProvider.PROFILE_ROLE_MAX_CHARS);
    }

    @Test
    @DisplayName("SF-149-01 : un profil appliqué (applied_at posé) injecte règle ET rôle")
    void appliedProfileInjectsBothRuleAndRole() {
        // Ancre F-149 / SF-149-01. Un profil sans fichier est désormais marqué appliqué dès
        // l'activation (applied_at posé, même machine injoignable) — c'est ce que garantit
        // GovernanceDepositServiceTest. Il est donc rendu par activeOnWorkspace (barrière deposited(),
        // F-135), et le provider en tire ses règles ET sa phrase de rôle.
        GovernancePackage archi = profile("profil-architecte", "Architecte",
                "Tu interviens comme architecte. Aucune trace de LLM.");
        GovernanceActivation applied = GovernanceActivation.builder().userId(alice).hostId(host)
                .packageId(archi.getId()).appliedVersion(1)
                .status(GovernanceActivationStatus.APPLIED)
                .appliedAt(java.time.OffsetDateTime.now()).build();
        when(activationService.activeOnWorkspace(alice, workspace)).thenReturn(List.of(applied));

        assertThat(provider.rulesFor(alice, workspace))
                .contains("## Architecte").contains("Tu interviens comme architecte.");
        assertThat(provider.activeProfileRole(alice, workspace))
                .isEqualTo("Tu interviens comme architecte.");
    }

    @Test
    @DisplayName("le rôle est lu pour le couple (utilisateur, projet) du tour, et lui seul")
    void roleReadsOnlyTheTurnScope() {
        GovernancePackage archi = profile("profil-architecte", "Architecte", "Tu es architecte. Suite.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(archi)));

        provider.activeProfileRole(alice, workspace);

        verify(activationService).activeOnWorkspace(alice, workspace);
    }

    @Test
    @DisplayName("les règles sont lues pour le couple (utilisateur, projet) du tour, et lui seul")
    void readsOnlyTheTurnScope() {
        GovernancePackage parlant = pkg("Parlant", "Règle.");
        when(activationService.activeOnWorkspace(alice, workspace))
                .thenReturn(List.of(activationOf(parlant)));

        provider.rulesFor(alice, workspace);

        verify(activationService).activeOnWorkspace(alice, workspace);
        org.mockito.Mockito.verifyNoMoreInteractions(activationService);
    }
}
