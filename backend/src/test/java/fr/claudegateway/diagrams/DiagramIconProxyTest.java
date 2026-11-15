package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fr.claudegateway.atelier.video.VideoIconSource;

/** F-238 / SF-238-08 — le motif est appliqué AVANT tout appel ; une icône absente reste absente ; cache immuable. */
class DiagramIconProxyTest {

    private VideoIconSource source;
    private DiagramIconProxy proxy;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @BeforeEach
    void setUp() {
        source = mock(VideoIconSource.class);
        proxy = new DiagramIconProxy(source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws/database/rds", "aws/network/elb-application-load-balancer", "onprem/client/users",
            "aws/general/user", "k8s/compute/pod", "generic/blank/blank", "azure/a/b/c.d_e-f"})
    @DisplayName("noms valides : motif de icons.js et fournisseur permis")
    void validNames(String name) {
        assertThat(DiagramIconProxy.valid(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "aws", "AWS/database/rds", "aws/Database/rds", "aws/../secret", "aws/./rds",
            "aws/database/..", "aws\\database\\rds", "aws/database/rds.png/../../x", "aws//rds", "/aws/rds",
            "evil/database/rds", "aws/a/b/c/d", "aws/%2e%2e/x", "aws/data base/rds", "aws/database/-rds"})
    @DisplayName("noms refusés (hors motif, traversée, fournisseur) ⇒ exception, renderer jamais appelé")
    void invalidNames(String name) {
        assertThat(DiagramIconProxy.valid(name)).isFalse();
        assertThatThrownBy(() -> proxy.icon(name)).isInstanceOf(DiagramIconProxy.InvalidIconNameException.class);
        verify(source, never()).fetch(any());
    }

    @Test
    @DisplayName("icône servie puis lue du cache : le renderer n'est appelé qu'une fois")
    void servedThenCached() {
        when(source.fetch(List.of("aws/database/rds"))).thenReturn(new VideoIconSource.Batch(
                List.of(new VideoIconSource.Icon("aws/database/rds", PNG)), List.of(), List.of()));

        assertThat(proxy.icon("aws/database/rds")).contains(PNG);
        assertThat(proxy.icon("aws/database/rds")).contains(PNG);
        verify(source, times(1)).fetch(any());
    }

    @Test
    @DisplayName("icône absente ⇒ vide — jamais une autre icône, même si le service en renvoie une autre")
    void missingIsMissing() {
        when(source.fetch(any())).thenReturn(new VideoIconSource.Batch(
                List.of(new VideoIconSource.Icon("aws/database/aurora", PNG)), List.of("aws/database/rdsx"),
                List.of()));

        assertThat(proxy.icon("aws/database/rdsx")).isEmpty();
    }

    @Test
    @DisplayName("refus du service ⇒ nom invalide ; service muet ⇒ indisponibilité (non avalée)")
    void serviceFailures() {
        when(source.fetch(List.of("aws/a/b"))).thenThrow(new DiagramRejectedException("non"));
        when(source.fetch(List.of("aws/a/c"))).thenThrow(new DiagramRendererUnavailableException("muet"));

        assertThatThrownBy(() -> proxy.icon("aws/a/b")).isInstanceOf(DiagramIconProxy.InvalidIconNameException.class);
        assertThatThrownBy(() -> proxy.icon("aws/a/c")).isInstanceOf(DiagramRendererUnavailableException.class);
    }
}
