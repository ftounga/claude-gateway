package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-271 / SF-271-01 (D3) — la clé de ticket d'un chantier. */
class ChantierTicketTest {

    @Test
    @DisplayName("une clé se lit en majuscules ; une phrase ou une forme sans tiret n'en est pas une")
    void normalized() {
        assertThat(ChantierTicket.normalized(" ldic-325 ")).isEqualTo("LDIC-325");
        assertThat(ChantierTicket.normalized("LDIC-325")).isEqualTo("LDIC-325");
        assertThat(ChantierTicket.normalized("ldic325")).isNull();
        assertThat(ChantierTicket.normalized("le ticket LDIC-325")).isNull();
        assertThat(ChantierTicket.normalized("")).isNull();
        assertThat(ChantierTicket.normalized(null)).isNull();
    }

    @Test
    @DisplayName("le titre porte la clé en tête, sans la répéter ; sans clé lisible, la raison seule")
    void title() {
        assertThat(ChantierTicket.title("LDIC-325", "Créer le topic SNS d'ingestion"))
                .isEqualTo("LDIC-325 — Créer le topic SNS d'ingestion");
        assertThat(ChantierTicket.title("ldic-325", "LDIC-325 : topic SNS")).isEqualTo("LDIC-325 : topic SNS");
        assertThat(ChantierTicket.title("pas une clé", "Topic SNS")).isEqualTo("Topic SNS");
        assertThat(ChantierTicket.title(null, "Topic SNS")).isEqualTo("Topic SNS");
        assertThat(ChantierTicket.title("LDIC-325", "")).isEqualTo("LDIC-325");
    }

    @Test
    @DisplayName("les clés d'un texte libre, mot entier, casse ignorée ; carries compare la clé exacte")
    void keysIn() {
        assertThat(ChantierTicket.keysIn("Lance LDIC-325 et ldic-330, pas LDIC-3250x"))
                .containsExactly("LDIC-325", "LDIC-330");
        assertThat(ChantierTicket.keysIn("Lance le chantier 4")).isEmpty();
        assertThat(ChantierTicket.carries("LDIC-325 — topic SNS", "ldic-325")).isTrue();
        assertThat(ChantierTicket.carries("LDIC-3250 — autre", "LDIC-325")).isFalse();
        assertThat(ChantierTicket.carries(null, "LDIC-325")).isFalse();
    }
}
