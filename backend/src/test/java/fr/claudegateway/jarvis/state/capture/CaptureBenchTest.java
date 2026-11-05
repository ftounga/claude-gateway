package fr.claudegateway.jarvis.state.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.actions.Interlocuteur;

/**
 * F-247 / SF-247-12 — <b>banc CAPTURES</b> (part déterministe) : 10 lectures anonymisées, telles qu'un modèle les rend,
 * passées par la garde, la résolution des dates et le rapprochement des personnes. Porte : 100 % sur les
 * interlocuteurs et les dates, texte gardé à l'identique. La lecture d'image par le modèle se mesure en direct
 * (précondition de déploiement, comme le banc CONVERSATION).
 */
class CaptureBenchTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** Dépôt : jeudi 5 novembre 2026, 10:15 à Paris. */
    private static final OffsetDateTime DEPOSIT = OffsetDateTime.of(2026, 11, 5, 10, 15, 0, 0,
            java.time.ZoneOffset.ofHours(1));

    private static Interlocuteur person(String display, String... variants) {
        Interlocuteur i = Interlocuteur.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
                .hostId(UUID.randomUUID()).displayName(display)
                .normalizedName(fr.claudegateway.atelier.actions.InterlocuteurService.normalize(display)).build();
        i.addVariants(List.of(variants).stream().map(fr.claudegateway.atelier.actions.InterlocuteurService::normalize)
                .toList());
        return i;
    }

    private static final List<Interlocuteur> KNOWN = List.of(person("Rémi Dupont", "remi"),
            person("Sophie Martin"), person("Karim Benali", "k benali"));

    private record Case(String raw, String speaker, boolean known, String local, boolean approx, String text) {
    }

    private static String one(String speaker, String stamp, String text) {
        return "{\"conversation\":true,\"plateforme\":\"teams\",\"titre\":\"Migration SSO\",\"messages\":[{"
                + "\"interlocuteur\":\"" + speaker + "\",\"horodatage_visible\":\"" + stamp + "\",\"texte\":\"" + text
                + "\"}]}";
    }

    private static final List<Case> BENCH = List.of(
            new Case(one("Rémi Dupont", "Hier 14:32", "Le ticket PROJ-123 passe en revue demain."), "Rémi Dupont", true,
                    "2026-11-04T14:32", false, "Le ticket PROJ-123 passe en revue demain."),
            new Case(one("Dupont, Rémi", "09:58", "On garde la date du 12."), "Rémi Dupont", true, "2026-11-05T09:58",
                    false, "On garde la date du 12."),
            new Case(one("DUPONT Rémi", "lundi 17:05", "Je relance l'équipe réseau."), "Rémi Dupont", true,
                    "2026-11-02T17:05", false, "Je relance l'équipe réseau."),
            new Case(one("Sophie Martin", "04/11 08:10", "La recette est validée."), "Sophie Martin", true,
                    "2026-11-04T08:10", false, "La recette est validée."),
            new Case(one("Sophie Martin (Externe)", "3 nov. 16:40", "MR !42 mergée."), "Sophie Martin", true,
                    "2026-11-03T16:40", false, "MR !42 mergée."),
            new Case(one("K Benali", "2:15 PM", "Le pipeline est vert."), "Karim Benali", true, "2026-11-04T14:15",
                    false, "Le pipeline est vert."),
            new Case(one("Inconnu Lambert", "Aujourd'hui 10:01", "Bonjour, une question rapide."), "Inconnu Lambert",
                    false, "2026-11-05T10:01", false, "Bonjour, une question rapide."),
            new Case(one("Rémi Dupont", "", "Sans heure visible."), "Rémi Dupont", true, "2026-11-05T10:15", true,
                    "Sans heure visible."),
            new Case(one("Sophie Martin", "2026-10-30 11:00", "Point hebdo décalé."), "Sophie Martin", true,
                    "2026-10-30T11:00", false, "Point hebdo décalé."),
            new Case(one("remi", "Yesterday 6:05 PM", "Déployé en préprod : api 2.14.1"), "Rémi Dupont", true,
                    "2026-11-04T18:05", false, "Déployé en préprod : api 2.14.1"));

    @Test
    @DisplayName("Banc CAPTURES : 10/10 interlocuteurs et dates exacts, texte recopié à l'identique")
    void bench() {
        int ok = 0;
        for (Case c : BENCH) {
            CaptureReadParser.Reading r = CaptureReadParser.parse(c.raw());
            assertThat(r.status()).isEqualTo(CaptureReadParser.Status.LUE);
            CaptureReadParser.Line l = r.lines().get(0);
            CaptureSpeakerResolver.Speaker who = CaptureSpeakerResolver.resolve(l.speaker(), KNOWN);
            CaptureTimeResolver.Resolved at = CaptureTimeResolver.resolve(l.stamp(), DEPOSIT);
            String local = at.at().atZoneSameInstant(PARIS).toLocalDateTime().toString();
            if (who.name().equals(c.speaker()) && who.known() == c.known() && local.equals(c.local())
                    && at.approx() == c.approx() && l.text().equals(c.text())) {
                ok++;
            } else {
                throw new AssertionError("Cas manqué : " + c + " -> " + who + " " + local + " " + at.approx());
            }
        }
        assertThat(ok).isEqualTo(BENCH.size());
    }

    @Test
    @DisplayName("Garde : sortie non conforme ⇒ rien n'est gardé ; « pas une conversation » ⇒ rien")
    void guard() {
        assertThat(CaptureReadParser.parse("Voici la conversation : Rémi dit…").status())
                .isEqualTo(CaptureReadParser.Status.ILLISIBLE);
        assertThat(CaptureReadParser.parse("{\"conversation\":false}").status())
                .isEqualTo(CaptureReadParser.Status.NON_CONVERSATION);
        assertThat(CaptureReadParser.parse("{\"conversation\":true,\"plateforme\":\"teams\",\"messages\":[]}").status())
                .isEqualTo(CaptureReadParser.Status.ILLISIBLE);
        assertThat(CaptureReadParser.parse("{\"conversation\":true,\"plateforme\":\"fax\",\"messages\":[{"
                + "\"interlocuteur\":\"A\",\"texte\":\"b\"}]}").status()).isEqualTo(CaptureReadParser.Status.ILLISIBLE);
        assertThat(CaptureReadParser.parse("{\"conversation\":true,\"plateforme\":\"mail\",\"messages\":[{"
                + "\"interlocuteur\":\"\",\"texte\":\"b\"}]}").status()).isEqualTo(CaptureReadParser.Status.ILLISIBLE);
        assertThat(CaptureReadParser.parse("```json\n" + one("A", "", "b") + "\n```").status())
                .isEqualTo(CaptureReadParser.Status.LUE);
    }

    @Test
    @DisplayName("Dates : jamais dans le futur, une heure plus tardive que le dépôt est celle de la veille")
    void dates() {
        assertThat(CaptureTimeResolver.resolve("23:50", DEPOSIT).at().atZoneSameInstant(PARIS).toLocalDate())
                .hasToString("2026-11-04");
        assertThat(CaptureTimeResolver.resolve("12/12", DEPOSIT).at().atZoneSameInstant(PARIS).toLocalDate())
                .hasToString("2025-12-12");
        assertThat(CaptureTimeResolver.resolve("n'importe quoi", DEPOSIT).approx()).isTrue();
        assertThat(CaptureTimeResolver.resolve("mardi", DEPOSIT).approx()).isTrue();
    }

    @Test
    @DisplayName("Personnes : un nom ambigu n'est pas rapproché")
    void ambiguous() {
        List<Interlocuteur> twins = List.of(person("Rémi Dupont", "remi"), person("Rémi Durand", "remi"));
        assertThat(CaptureSpeakerResolver.resolve("Rémi", twins).known()).isFalse();
    }
}
