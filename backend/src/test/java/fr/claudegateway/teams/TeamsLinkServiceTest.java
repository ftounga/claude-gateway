package fr.claudegateway.teams;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.teams.TeamsLinkService.TeamsLink;

/**
 * F-87 / SF-87-03 — la traduction de ce que dit la machine en <b>état d'écran</b>.
 *
 * <p>Le point vérifié ici est moins la lecture du JSON que la <b>règle</b> : quoi qu'il arrive,
 * l'écran reçoit un état et une phrase. Une machine éteinte, un runner trop ancien ou une réponse
 * illisible sont des états — jamais une page en erreur.</p>
 */
class TeamsLinkServiceTest {

    private final TeamsLinkService service =
            new TeamsLinkService(null, null, null, new ObjectMapper());

    @Test
    @DisplayName("Une liaison saine est rendue avec sa santé et la version observée")
    void a_healthy_link_is_translated() {
        TeamsLink link = service.parse("""
                {"state":"LINKED","label":"Teams relié","sentence":"Teams relié (Chrome/140).",
                 "remedy":"","browser":"Chrome/140","conclusive":true,
                 "health":{"verdict":"FULL","recognizedFields":7,"expectedFields":7,
                           "missingFields":[],"observedApiVersions":["v1"]}}""");

        assertThat(link.state()).isEqualTo(TeamsLinkService.LINKED);
        assertThat(link.label()).isEqualTo("Teams relié");
        assertThat(link.healthVerdict()).isEqualTo("FULL");
        assertThat(link.recognizedFields()).isEqualTo(7);
        assertThat(link.observedApiVersions()).containsExactly("v1");
        assertThat(link.conclusive()).isTrue();
    }

    @Test
    @DisplayName("« Teams a changé » remonte tel quel, avec ce qui n'est plus reconnu")
    void a_changed_teams_is_reported_as_such() {
        TeamsLink link = service.parse("""
                {"state":"TEAMS_CHANGED","label":"Teams a changé",
                 "sentence":"Teams a changé : le produit ne sait plus lire ses réponses.",
                 "remedy":"Rien ne sera produit tant que l'adaptateur n'aura pas été mis à jour.",
                 "health":{"verdict":"NONE","recognizedFields":0,"expectedFields":7,
                           "missingFields":["messages","originalarrivaltime"],
                           "observedApiVersions":["beta"]}}""");

        assertThat(link.state()).isEqualTo(TeamsLinkService.TEAMS_CHANGED);
        assertThat(link.missingFields()).contains("originalarrivaltime");
        assertThat(link.observedApiVersions()).containsExactly("beta");
        assertThat(link.remedy()).contains("mis à jour");
    }

    @Test
    @DisplayName("Un état inconnu retombe sur « navigateur non détecté », jamais sur du vide")
    void an_unknown_state_falls_back() {
        TeamsLink link = service.parse("{\"state\":\"QUELQUE_CHOSE_DE_NOUVEAU\"}");

        assertThat(link.state()).isEqualTo(TeamsLinkService.BROWSER_NOT_DETECTED);
        assertThat(link.label()).isEqualTo("Teams : navigateur non détecté");
    }

    @Test
    @DisplayName("Une réponse illisible est un ÉTAT, avec le remède qui va avec")
    void an_unreadable_answer_is_a_state() {
        TeamsLink link = service.parse("ceci n'est pas du JSON");

        assertThat(link.state()).isEqualTo(TeamsLinkService.BROWSER_NOT_DETECTED);
        assertThat(link.sentence()).contains("inattendu");
        assertThat(link.remedy()).contains("Mettez le runner à jour");
    }

    @Test
    @DisplayName("Le libellé est toujours écrit, pour les trois états")
    void every_state_has_a_written_label() {
        assertThat(TeamsLinkService.labelFor(TeamsLinkService.LINKED)).isEqualTo("Teams relié");
        assertThat(TeamsLinkService.labelFor(TeamsLinkService.TEAMS_CHANGED))
                .isEqualTo("Teams a changé");
        assertThat(TeamsLinkService.labelFor(TeamsLinkService.BROWSER_NOT_DETECTED))
                .isEqualTo("Teams : navigateur non détecté");
    }
}
