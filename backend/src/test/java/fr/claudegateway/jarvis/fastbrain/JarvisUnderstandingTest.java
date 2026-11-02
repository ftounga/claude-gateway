package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** La garde de la relecture (F-239 / SF-239-05, D3) : corriger, jamais inventer. */
class JarvisUnderstandingTest {

    @Test
    void namesAndMisheardWordsAreCorrected() {
        assertThat(JarvisUnderstanding.accept("Est-ce que le client Cagibi est connecté", 
                "Est-ce que le client CAGIP est connecté ?")).isEqualTo("Est-ce que le client CAGIP est connecté ?");
        assertThat(JarvisUnderstanding.accept("Bonjour Travis.", "Bonjour Jarvis.")).isEqualTo("Bonjour Jarvis.");
        assertThat(JarvisUnderstanding.accept("Explique-moi le réseau sur l'architecture de Kajip.",
                "Explique-moi le réseau de l'architecture de CAGIP.")).isNotNull();
    }

    @Test
    void anAddedActionIsRefused() {
        assertThat(JarvisUnderstanding.accept("Regarde le ticket LDIC-223", "Envoie le ticket LDIC-223")).isNull();
        assertThat(JarvisUnderstanding.accept("Le mail à Paul", "Envoie le mail à Paul")).isNull();
        // Le verbe était dit : la relecture le garde.
        assertThat(JarvisUnderstanding.accept("Envoi le mail à Paul", "Envoie le mail à Paul.")).isNotNull();
    }

    @Test
    void aRewriteTooFarFromTheWordsIsRefused() {
        assertThat(JarvisUnderstanding.accept("Vente chez le via Edenred.",
                "Pouvez-vous me donner l'état complet des chantiers ouverts chez Edenred ?")).isNull();
        assertThat(JarvisUnderstanding.accept("Où en est agenor ?", "  ")).isNull();
        assertThat(JarvisUnderstanding.accept("Où en est agenor ?", null)).isNull();
    }
}
