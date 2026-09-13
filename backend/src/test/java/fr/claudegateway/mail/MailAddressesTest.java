package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Normalisation et validation d'une adresse de réception (F-110 / SF-110-01). */
class MailAddressesTest {

    @Test
    void normalizesSpacesAndCase() {
        assertThat(MailAddresses.normalize("  Franck.Tounga@CAGIP.fr ")).isEqualTo("franck.tounga@cagip.fr");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b.fr", "prenom.nom+cagip@ca-gip.credit-agricole.fr", "x_y@sub.domain.io"})
    void acceptsPlausibleAddresses(String address) {
        assertThat(MailAddresses.normalize(address)).isEqualTo(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sans-arobase.fr", "a@b", "a@@b.fr", "a b@c.fr", "@cagip.fr", "a@.fr", "a..b@c.fr",
            ".a@c.fr", "a.@c.fr", "a@c.fr\nBcc: x@y.fr"})
    void refusesWhatCannotBeAMailbox(String address) {
        assertThatThrownBy(() -> MailAddresses.normalize(address))
                .isInstanceOf(InvalidMailAddressException.class);
    }

    @Test
    void refusesMissingAndTooLong() {
        assertThatThrownBy(() -> MailAddresses.normalize(null)).isInstanceOf(InvalidMailAddressException.class);
        assertThatThrownBy(() -> MailAddresses.normalize("  ")).isInstanceOf(InvalidMailAddressException.class);
        String tooLong = "a".repeat(60) + "@" + "d".repeat(60) + "." + "e".repeat(60) + "." + "f".repeat(60) + "." + "g".repeat(20) + ".fr";
        assertThat(tooLong.length()).isGreaterThan(MailAddresses.MAX_LENGTH);
        assertThatThrownBy(() -> MailAddresses.normalize(tooLong))
                .isInstanceOf(InvalidMailAddressException.class)
                .hasMessageContaining("trop longue");
    }
}
