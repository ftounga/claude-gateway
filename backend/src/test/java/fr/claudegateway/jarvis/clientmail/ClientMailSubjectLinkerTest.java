package fr.claudegateway.jarvis.clientmail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.notices.NoticeTexts;

/** F-249 / SF-249-03 — le rattachement par les mots et les clés, sans IA ; les phrases des annonces. */
class ClientMailSubjectLinkerTest {

    @Test
    @DisplayName("Mots : la moitié des mots distinctifs dans l'objet ; accents et casse ignorés")
    void words() {
        assertThat(ClientMailSubjectLinker.wordsMatch("Alarme Fortec", "RE: Fortec — alarme levée", "")).isTrue();
        assertThat(ClientMailSubjectLinker.wordsMatch("Migration Kafka Prod", "Kafka : point", "")).isFalse();
        assertThat(ClientMailSubjectLinker.wordsMatch("Migration Kafka Prod", "Kafka migration", "")).isTrue();
        assertThat(ClientMailSubjectLinker.wordsMatch("Réseau éclaté", "reseau ECLATE", "")).isTrue();
    }

    @Test
    @DisplayName("Un seul mot : dans l'objet seulement ; tous les mots dans objet + extrait suffisent")
    void singleWordAndBody() {
        assertThat(ClientMailSubjectLinker.wordsMatch("Fortec", "Point hebdo", "On parle de Fortec")).isFalse();
        assertThat(ClientMailSubjectLinker.wordsMatch("Fortec", "Fortec ok", "")).isTrue();
        assertThat(ClientMailSubjectLinker.wordsMatch("Migration Kafka Prod", "Point", "kafka prod migration ok"))
                .isTrue();
    }

    @Test
    @DisplayName("Mots vides et mots courts écartés ; sigle en capitales gardé")
    void distinctive() {
        assertThat(ClientMailSubjectLinker.distinctive("Projet de suivi")).isEmpty();
        assertThat(ClientMailSubjectLinker.distinctive("SSO Azure")).containsExactly("sso", "azure");
        assertThat(ClientMailSubjectLinker.wordsMatch("SSO", "Bascule SSO demain", "")).isTrue();
        assertThat(ClientMailSubjectLinker.wordsMatch("Projet de suivi", "Projet de suivi", "")).isFalse();
    }

    @Test
    @DisplayName("Clés de ticket et objet normalisé")
    void keysAndSubject() {
        assertThat(ClientMailSubjectLinker.keys("Voir FORT-42 et fort-43, pas COVID")).containsExactly("FORT-42",
                "FORT-43");
        assertThat(ClientMailSubjectLinker.keys("version 2-3")).isEmpty();
        assertThat(ClientMailSubjectLinker.normalizedSubject("RE: TR : Alarme  Fortec !"))
                .isEqualTo(ClientMailSubjectLinker.normalizedSubject("alarme fortec"));
    }

    @Test
    @DisplayName("Phrases : affichée chiffrée, dite en lettres, nom borné")
    void texts() {
        var t = NoticeTexts.mailsOnSubject(2, "Alarme Fortec", "EDENRED");
        assertThat(t.display()).isEqualTo("2 mails sur « Alarme Fortec » (EDENRED)");
        assertThat(t.speech()).isEqualTo("Deux nouveaux mails sur « Alarme Fortec », chez EDENRED.");
        assertThat(NoticeTexts.mailsOnSubject(1, "X", "C").display()).isEqualTo("1 mail sur « X » (C)");
        assertThat(NoticeTexts.name("a".repeat(100))).hasSize(60).endsWith("…");
    }
}
