package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Le choix du jeton (F-194 / SF-194-03) : un code fourni l'emporte sur le jeton stocké, sauf s'il
 * est refusé ; et le runner nomme son poste.
 */
class TokenAcquisitionTest {

    private static final Path FILE = Path.of("/home/u/dev/.claude-runner/token.json");

    private final List<String> lines = new ArrayList<>();
    private final TokenAcquisition acquisition = new TokenAcquisition(
            new Console(lines::add, ConsoleEncoding.forCharset(StandardCharsets.UTF_8)));
    private final AtomicInteger pairings = new AtomicInteger();

    private static StoredToken token(String value, String hostName, String previousRoot) {
        return new StoredToken(value, UUID.randomUUID(), OffsetDateTime.now().plusDays(30),
                hostName, previousRoot);
    }

    private TokenAcquisition.Pairer pairsAs(StoredToken token) {
        return () -> {
            pairings.incrementAndGet();
            return token;
        };
    }

    private TokenAcquisition.Pairer refused(int status) {
        return () -> {
            pairings.incrementAndGet();
            throw new PairingClient.PairingException(
                    "Appairage refusé (code invalide, expiré ou déjà utilisé)", status);
        };
    }

    private String printed() {
        return String.join("\n", lines);
    }

    @Test
    void withoutCodeTheStoredTokenIsReusedAndItsHostNamed() {
        StoredToken stored = token("ancien", "EDENRED", null);

        Optional<StoredToken> chosen = acquisition.obtain(Optional.of(stored), false, FILE,
                pairsAs(token("neuf", "X", null)));

        assertSame(stored, chosen.orElseThrow());
        assertEquals(0, pairings.get());
        assertTrue(printed().contains("Poste     : « EDENRED »"));
    }

    @Test
    void aCodeReplacesTheStoredToken() {
        // Le 12/10 : relancer EDENRED avec le bon code réutilisait le jeton de FREE.
        StoredToken fresh = token("neuf", "EDENRED", null);

        Optional<StoredToken> chosen = acquisition.obtain(Optional.of(token("ancien", "FREE", null)),
                true, FILE, pairsAs(fresh));

        assertSame(fresh, chosen.orElseThrow());
        assertEquals(1, pairings.get());
        assertTrue(printed().contains("il remplace le jeton stocké"));
        assertTrue(printed().contains("« EDENRED »"));
    }

    @Test
    void aRefusedCodeKeepsTheStoredToken() {
        // Le lanceur relance avec ses arguments d'origine : un code consommé ne doit rien arrêter.
        StoredToken stored = token("ancien", "EDENRED", null);

        Optional<StoredToken> chosen = acquisition.obtain(Optional.of(stored), true, FILE,
                refused(TokenAcquisition.CODE_REFUSED));

        assertSame(stored, chosen.orElseThrow());
        assertTrue(printed().contains("le jeton stocké est conservé"));
    }

    @Test
    void aRefusedCodeWithoutStoredTokenFailsAsBefore() {
        assertThrows(PairingClient.PairingException.class, () -> acquisition.obtain(
                Optional.empty(), true, FILE, refused(TokenAcquisition.CODE_REFUSED)));
    }

    @Test
    void anotherPairingFailureIsNotHiddenByTheStoredToken() {
        assertThrows(PairingClient.PairingException.class, () -> acquisition.obtain(
                Optional.of(token("ancien", "EDENRED", null)), true, FILE, refused(500)));
    }

    @Test
    void nothingStoredAndNoCodeLeavesTheCallerToSayWhatIsMissing() {
        assertFalse(acquisition.obtain(Optional.empty(), false, FILE, pairsAs(token("x", null, null)))
                .isPresent());
        assertEquals(0, pairings.get());
    }

    @Test
    void warnsWhenThisMachineTakesTheHostOfAnother() {
        acquisition.obtain(Optional.empty(), true, FILE, pairsAs(token("neuf", "FREE", "infra")));

        assertTrue(printed().contains("Poste     : « FREE »"));
        assertTrue(printed().contains("WARN"));
        assertTrue(printed().contains("racine « infra »"));
        assertTrue(printed().contains("Réappairer ce poste"));
    }

    @Test
    void anOlderGatewayNamesNothing() {
        acquisition.obtain(Optional.empty(), true, FILE,
                pairsAs(new StoredToken("neuf", UUID.randomUUID(), OffsetDateTime.now().plusDays(30))));

        assertFalse(printed().contains("Poste"));
        assertFalse(printed().contains("WARN"));
    }
}
