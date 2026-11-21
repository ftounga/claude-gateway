package fr.claudegateway.atelier.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-275 / SF-275-01 — les déclencheurs déclarés dans l'en-tête d'un skill, sans configuration. */
class SkillTriggersTest {

    /** L'en-tête réel du skill de poste suivi-hebdo (EDENRED), lu en prod le 2026-11-21. */
    static final String SUIVI_HEBDO = """
            ---
            description: Point hebdo des remédiations Critical/High (Alarm4Tech, lot secrets, VulnMan) : relevé, \
            registre, escalades, dashboards PNG du comité, vérification au dashboard ALARM. Déclenché par « point \
            hebdo », « suivi du jour » ou « prépare le comité ».
            ---

            # suivi-hebdo

            ## Chaque jour : « suivi du jour » (5 minutes)
            """;

    @Test
    void theRealSuiviHebdoHeaderDeclaresItsThreeTriggers() {
        SkillLaunch launch = SkillTriggers.parse(SUIVI_HEBDO);

        assertThat(launch.triggers()).containsExactly("point hebdo", "suivi du jour", "prépare le comité");
        assertThat(launch.subject()).isNull();
        assertThat(launch.send()).isFalse();
    }

    @Test
    void anInlineListKeyIsRead() {
        SkillLaunch launch = SkillTriggers.parse("---\ndescription: x\ndeclencheurs: [point hebdo, après comité]\n---\n");

        assertThat(launch.triggers()).containsExactly("point hebdo", "après comité");
    }

    @Test
    void aYamlListAndTheSubjectAndClickKeysAreRead() {
        SkillLaunch launch = SkillTriggers.parse("""
                ---
                description: Suivi.
                déclencheurs:
                  - « suivi du jour »
                  - point hebdo
                sujet: reporting-securite
                clic: envoyer
                ---
                """);

        assertThat(launch.triggers()).containsExactly("suivi du jour", "point hebdo");
        assertThat(launch.subject()).isEqualTo("reporting-securite");
        assertThat(launch.send()).isTrue();
    }

    @Test
    void anEnglishTriggersKeyWithCommasIsRead() {
        assertThat(SkillTriggers.parse("---\ntriggers: weekly review, daily check\n---\n").triggers())
                .containsExactly("weekly review", "daily check");
    }

    @Test
    void straightQuotesAfterTheAnnounceWordAreRead() {
        assertThat(SkillTriggers.parse("---\ndescription: Revue \"hors\" sujet. Se lance par \"revue MR\".\n---\n")
                .triggers()).containsExactly("revue MR");
    }

    @Test
    void aDescriptionWithoutAnnounceWordDeclaresNothing() {
        assertThat(SkillTriggers.parse("---\ndescription: Rédige un « ticket » Jira.\n---\n").declared()).isFalse();
    }

    @Test
    void aSkillWithoutFrontMatterDeclaresNothingEvenWithQuotes() {
        assertThat(SkillTriggers.parse("# x\nDéclenché par « point hebdo ».").declared()).isFalse();
        assertThat(SkillTriggers.parse(null)).isEqualTo(SkillLaunch.NONE);
    }

    @Test
    void triggersAreDeduplicatedAndCappedAtFive() {
        SkillLaunch launch = SkillTriggers.parse(
                "---\ndeclencheurs: a, A, b, c, d, e, f\n---\n");

        assertThat(launch.triggers()).containsExactly("a", "b", "c", "d", "e");
    }
}
