package fr.claudegateway.jarvis.portrait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import fr.claudegateway.byok.LocalAesByokKeyCipher;

/** F-241 / SF-241-01 — bornes (D2, D4), « à revoir » (D3), sections, scellé. */
class JarvisPortraitRulesTest {

    @Test
    void tokenBoundIsSixThousandChars() {
        assertThat(JarvisPortraitRules.MAX_CHARS).isEqualTo(6_000);
        assertThat(JarvisPortraitRules.tokens(0)).isZero();
        assertThat(JarvisPortraitRules.tokens(1)).isEqualTo(1);
        assertThat(JarvisPortraitRules.tokens(6_000)).isEqualTo(1_500);
        assertThat(JarvisPortraitRules.tooLarge(6_000)).isFalse();
        assertThat(JarvisPortraitRules.tooLarge(6_001)).isTrue();
        assertThat(JarvisPortraitRules.tooLargeMessage(6_400)).contains("1500 jetons").contains("raccourcissez")
                .contains("6400");
    }

    @Test
    void staleAfterTwelveMonths() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        assertThat(JarvisPortraitRules.stale(LocalDate.of(2025, 11, 2), today)).isFalse();
        assertThat(JarvisPortraitRules.stale(LocalDate.of(2025, 11, 1), today)).isTrue();
        assertThat(JarvisPortraitRules.stale(null, today)).isFalse();
    }

    @Test
    void normalizesSouvenirsAndParsesSections() {
        assertThat(JarvisPortraitRules.normalizeSouvenir("  ma  fille\n est née ")).isEqualTo("ma fille est née");
        assertThat(JarvisPortraitRules.normalizeSouvenir(null)).isEmpty();
        assertThat(PortraitSection.parse("entourage")).isEqualTo(PortraitSection.ENTOURAGE);
        assertThat(PortraitSection.parse("inconnue")).isNull();
        assertThat(PortraitSection.ENTOURAGE.sensitive()).isTrue();
        assertThat(PortraitSection.MOMENT.sensitive()).isTrue();
        assertThat(PortraitSection.QUI.sensitive()).isFalse();
        assertThat(PortraitSection.INTERDITS.title()).isEqualTo("À ne pas aborder");
    }

    @Test
    void sealRoundTripsAndHidesPlainText() {
        PortraitSeal seal = new PortraitSeal(
                new LocalAesByokKeyCipher("Ynlvay1sb2NhbC10ZXN0LW1hc3Rlci1rZXktMzJieXQ="));
        String sealed = seal.seal("Ma femme s'appelle Claire");
        assertThat(sealed.split("\\.")).hasSize(3);
        assertThat(sealed).doesNotContain("Claire");
        assertThat(seal.open(sealed)).isEqualTo("Ma femme s'appelle Claire");
        assertThat(seal.open(null)).isNull();
        assertThatThrownBy(() -> seal.open("pas-un-scelle")).isInstanceOf(IllegalStateException.class);
    }
}
