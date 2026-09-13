package fr.claudegateway.governance.integrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.governance.GovernanceHostRef;

/**
 * F-95 / SF-95-02 — l'inspection <b>dans le contexte réel</b>.
 *
 * <p>Ce que ce test attrape et qu'aucune doublure ne verrait : un <b>cycle de beans</b>. Le piège
 * est documenté par {@code GovernanceMapDestinations} — un contrôle est un composant du registre, le
 * registre est une dépendance de {@code GovernancePackageService}, et {@code GovernanceActivation
 * Service} en dépend à son tour. Si l'inspection passait par ces services, le contexte refuserait
 * de démarrer, et c'est ici qu'on l'apprendrait.</p>
 *
 * <p>Il vérifie aussi la seule chose qui compte pour un contrôle de fin de tour : que sur un poste
 * <b>qui n'existe pas</b>, ou une machine qu'on ne peut pas joindre, l'inspection rende un rapport
 * <b>silencieux</b> — jamais une exception, et jamais un rapport « sain ».</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IntegriteInspectionIntegrationTest {

    @Autowired
    private IntegriteInspection inspection;

    @Test
    @DisplayName("le poste « Hébergé » n'a pas de racine : rien à inspecter, rien n'est émis")
    void theHostedHostHasNoRoot() {
        IntegriteRapport rapport =
                inspection.dePoste(UUID.randomUUID(), GovernanceHostRef.HOSTED);

        assertThat(rapport.inspecte()).isFalse();
        assertThat(rapport.bloque()).isFalse();
    }

    @Test
    @DisplayName("un poste inconnu et un projet inconnu restent silencieux, sans lever")
    void anUnknownHostOrProjectIsSilent() {
        UUID inconnu = UUID.randomUUID();

        assertThat(inspection.dePoste(inconnu, GovernanceHostRef.of(UUID.randomUUID())).inspecte())
                .isFalse();
        assertThat(inspection.deProjet(inconnu, UUID.randomUUID()).inspecte()).isFalse();
        assertThat(inspection.deProjet(null, null).inspecte()).isFalse();
    }
}
