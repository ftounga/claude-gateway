package fr.claudegateway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-77 — Ce que les sondes Kubernetes lisent réellement.
 *
 * <p>Panne du 2026-09-12 : les trois sondes lisaient l'agrégat {@code /api/actuator/health}, qui
 * inclut l'indicateur de courrier. Le SMTP a mis 70 s à répondre, les sondes expiraient au bout
 * d'une seconde, et Kubernetes a tué quatre pods en boucle. Ces tests fixent le contrat qui rend
 * cela impossible :
 *
 * <ul>
 *   <li>{@code liveness} ne contient que l'état du processus — rien de joignable par le réseau ;
 *   <li>{@code readiness} contient la base (un pod sans base ne sert rien d'utile) et
 *       <strong>jamais</strong> le courrier ;
 *   <li>l'indicateur de courrier, lui, a un délai d'attente borné — il reste dans l'agrégat, mais
 *       ne peut plus faire attendre personne.
 * </ul>
 *
 * <p>Le contexte de test ne démarre que si les groupes sont valides : Spring vérifie au démarrage
 * que chaque membre déclaré existe ({@code validate-group-membership}). Une régression sur la
 * composition des groupes échoue donc ici, pas à 09 h 07 en production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthProbesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void le_groupe_liveness_ne_porte_que_l_etat_du_processus() throws Exception {
        mockMvc.perform(get("/api/actuator/health/liveness").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.livenessState.status").value("UP"))
                .andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.components.mail").doesNotExist())
                .andExpect(jsonPath("$.components.db").doesNotExist());
    }

    @Test
    void le_groupe_readiness_porte_la_base_et_jamais_le_courrier() throws Exception {
        mockMvc.perform(get("/api/actuator/health/readiness").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.length()").value(2))
                .andExpect(jsonPath("$.components.mail").doesNotExist());
    }

    @Test
    void l_agregat_expose_bien_les_deux_groupes_dedies() throws Exception {
        // C'est ce que la production a confirmé pendant la panne ("groups":["liveness","readiness"]).
        // Le statut n'est pas asserté : l'agrégat porte tous les indicateurs, y compris ceux qui
        // dépendent d'un tiers — c'est précisément pourquoi aucune sonde ne le lit.
        String body = mockMvc.perform(get("/api/actuator/health").contextPath("/api"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("\"liveness\"").contains("\"readiness\"");
    }

    @Test
    void l_indicateur_de_courrier_a_un_delai_d_attente_borne() throws Exception {
        // Sans ces trois propriétés, JavaMail attend indéfiniment : sortir le courrier des sondes
        // ne protégerait que les sondes, et l'agrégat resterait bloqué 70 s pour tout le reste.
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.connectiontimeout"))
                .isEqualTo("5000");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.timeout"))
                .isEqualTo("5000");
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.writetimeout"))
                .isEqualTo("5000");
    }
}
