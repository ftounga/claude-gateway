package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionKind;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.jarvis.JarvisReturnService;
import fr.claudegateway.jarvis.universe.JarvisUniverse;

/** F-248 / SF-248-02 — les gabarits : liste blanche, aucun secret, tons, repli générique ; les sources. */
class JarvisCardTemplatesTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-05T17:40:00Z");

    @Test
    @DisplayName("liste blanche : seuls les attributs du gabarit, dans son ordre, 5 au plus ; un libellé une fois")
    void whitelist() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("description", "texte long hors liste");
        attrs.put("assignee", "Rémi");
        attrs.put("status", "En revue");
        attrs.put("priority", "haute");
        attrs.put("blocked_by", "PROJ-9");
        attrs.put("updated_at", "hier");
        List<JarvisCard.Field> fields = JarvisCardTemplates.fields("TICKET", attrs);
        assertThat(fields).extracting(JarvisCard.Field::label)
                .containsExactly("Statut", "Assigné", "Mis à jour", "Bloqué par", "Priorité");
        assertThat(fields).extracting(JarvisCard.Field::value).doesNotContain("texte long hors liste");

        Map<String, String> mr = new LinkedHashMap<>();
        mr.put("state", "opened");
        mr.put("status", "opened");
        assertThat(JarvisCardTemplates.fields("MR", mr)).hasSize(1);
    }

    @Test
    @DisplayName("D8 — accès : compte, type, validité, état, portée ; jamais la valeur, même par le générique")
    void neverASecret() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("account", "deploy-bot");
        attrs.put("kind", "jeton GitLab");
        attrs.put("token", "glpat-abcdefghijklmnopqrst");
        attrs.put("value", "glpat-abcdefghijklmnopqrst");
        attrs.put("expires_at", "8/11");
        List<JarvisCard.Field> acces = JarvisCardTemplates.fields("ACCES", attrs);
        assertThat(acces).extracting(JarvisCard.Field::label).containsExactly("Compte", "Type", "Validité");
        assertThat(acces.toString()).doesNotContain("glpat");

        Map<String, String> generic = new LinkedHashMap<>();
        generic.put("password", "hunter2");
        generic.put("note", "ghp_0123456789abcdefABCDEF0123");
        generic.put("ville", "Lyon");
        generic.put("blob", "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5");
        assertThat(JarvisCardTemplates.fields("INCONNU", generic)).extracting(JarvisCard.Field::value)
                .containsExactly("Lyon");
        assertThat(JarvisCardTemplates.secretLike("api_key", "x")).isTrue();
        assertThat(JarvisCardTemplates.secretLike("status", "AKIAABCDEFGH1234")).isTrue();
        assertThat(JarvisCardTemplates.secretLike("status", "En revue")).isFalse();
    }

    @Test
    @DisplayName("tons : le mot est écrit ; rouge réservé aux alertes ; « expire dans » attend ; rendez-vous en cours attend")
    void tones() {
        assertThat(JarvisCardTemplates.tone("En revue")).isEqualTo("normal");
        assertThat(JarvisCardTemplates.tone("Bloqué")).isEqualTo("attend");
        assertThat(JarvisCardTemplates.tone("Incident")).isEqualTo("alerte");
        assertThat(JarvisCardTemplates.tone("fusionnée")).isEqualTo("ok");
        assertThat(JarvisCardTemplates.tone("pipeline échoué")).isEqualTo("alerte");
        assertThat(JarvisCardTemplates.tone("sain")).isEqualTo("ok");
        assertThat(JarvisCardTemplates.tone("dégradé")).isEqualTo("alerte");
        assertThat(JarvisCardTemplates.tone("expire dans 3 j")).isEqualTo("attend");
        assertThat(JarvisCardTemplates.tone("expiré")).isEqualTo("alerte");
        assertThat(JarvisCardTemplates.tone("à répondre")).isEqualTo("attend");
        assertThat(JarvisCardTemplates.tone("poste déconnecté")).isEqualTo("eteint");
        assertThat(JarvisCardTemplates.tone("En cours")).isEqualTo("normal");
        assertThat(JarvisCardTemplates.tone("RENDEZ_VOUS", "en cours")).isEqualTo("attend");
        assertThat(JarvisCardTemplates.status("TICKET", "  ")).isNull();
    }

    @Test
    @DisplayName("garde de couverture : tout type a un gabarit ; un type inconnu devient GENERIQUE, jamais rien")
    void coverage() {
        for (String t : JarvisCardTemplates.TYPES) {
            assertThat(t.equals("GENERIQUE") || JarvisCardTemplates.WHITELIST.containsKey(t)).as(t).isTrue();
        }
        assertThat(JarvisCardTemplates.type("NOUVEAU")).isEqualTo("GENERIQUE");
        assertThat(JarvisCardTemplates.card("fait:x", "NOUVEAU", "Titre", null, Map.of(), null, AT, "direct", null,
                List.of()).type()).isEqualTo("GENERIQUE");
    }

    @Test
    @DisplayName("sources : attente (lien du chantier, alias de la personne), à envoyer, courriel sans corps, personnes")
    void sources() {
        UUID ws = UUID.randomUUID();
        TerminalAction a = TerminalAction.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).workspaceId(ws)
                .description("Obtenir l'accès VPN").kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.DEMANDE).person("Rémi Martin").requestedTo("Rémi Martin")
                .chantierNumber(3).createdAt(AT).requestedAt(AT).updatedAt(AT).build();
        JarvisCard attente = JarvisCardSources.attente(a, null);
        assertThat(attente.id()).isEqualTo("fait:attente:" + a.getId());
        assertThat(attente.type()).isEqualTo("ATTENTE");
        assertThat(attente.status().tone()).isEqualTo("attend");
        assertThat(attente.link()).isEqualTo("/atelier/" + ws + "?chantier=3");
        assertThat(attente.aliases()).containsExactly("Rémi Martin");
        assertThat(attente.fields()).extracting(JarvisCard.Field::label).containsExactly("Depuis", "Chantier", "Qui");
        assertThat(attente.targeted()).isFalse();

        JarvisReturnService.ToSend t = new JarvisReturnService.ToSend(ws, "Edenred", UUID.randomUUID(), 2, "MAIL",
                "Point VPN", "Rémi", AT, null, "extrait", null, false, false);
        JarvisCard envoi = JarvisCardSources.envoi(t);
        assertThat(envoi.id()).startsWith("fait:envoi:").endsWith(":2");
        assertThat(envoi.status().word()).isEqualTo("à envoyer");
        assertThat(envoi.fields().toString()).doesNotContain("extrait");

        UUID mailId = UUID.randomUUID();
        JarvisCard mail = JarvisCardSources.courriel(mailId, true, AT, "remi@ex.com", "Rémi", "Budget Q4");
        assertThat(mail.id()).isEqualTo("fait:courriel:" + mailId);
        assertThat(mail.status().word()).isEqualTo("à répondre");
        assertThat(mail.link()).isNull();
        assertThat(mail.fields()).extracting(JarvisCard.Field::label).containsExactly("De", "Reçu", "Objet");

        JarvisUniverse u = new JarvisUniverse(AT, List.of(new JarvisUniverse.Client(UUID.randomUUID(), "CAGIP", false,
                true, null, List.of(new JarvisUniverse.Subject(ws, "Migration", false, false, List.of(
                        new JarvisUniverse.Chantier(3, "VPN", null, null, 1, 4, null, false, false,
                                List.of("Rémi Martin"))))),
                List.of(new JarvisUniverse.Person("Rémi Martin", List.of("remi"), "réseau")), List.of(), false)), 0);
        List<JarvisCard> people = JarvisCardSources.personnes(u, AT);
        assertThat(people).hasSize(1);
        assertThat(people.get(0).group()).isEqualTo("CAGIP");
        assertThat(people.get(0).fields()).extracting(JarvisCard.Field::value)
                .containsExactly("réseau", "CAGIP", "chantier 3 — VPN");
        assertThat(people.get(0).aliases()).contains("Rémi Martin");
    }
}
