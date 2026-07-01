package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

/**
 * Tests unitaires du {@link JwtService} : round-trip, expiration, altération de signature,
 * et fail-fast sur secret invalide.
 */
class JwtServiceTest {

    private static final String SECRET =
            "unit-test-jwt-secret-of-sufficient-length-0123456789-abcdefghijkl";

    private final JwtService jwtService = new JwtService(SECRET, Duration.ofHours(24));

    private User sampleUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("alice@example.com")
                .provider(AuthProvider.LOCAL)
                .role(UserRole.USER)
                .build();
    }

    @Test
    void generatesAndParsesTokenRoundTrip() {
        User user = sampleUser();

        String token = jwtService.generateToken(user);
        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get(JwtService.CLAIM_EMAIL, String.class)).isEqualTo("alice@example.com");
        assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isEqualTo("USER");
        assertThat(claims.get(JwtService.CLAIM_TOKEN_VERSION, Integer.class)).isZero();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void includesCurrentTokenVersionClaim() {
        User user = sampleUser();
        user.setTokenVersion(3);

        Claims claims = jwtService.parseClaims(jwtService.generateToken(user));

        assertThat(claims.get(JwtService.CLAIM_TOKEN_VERSION, Integer.class)).isEqualTo(3);
    }

    @Test
    void rejectsExpiredToken() {
        JwtService expiringService = new JwtService(SECRET, Duration.ofSeconds(-60));
        String expiredToken = expiringService.generateToken(sampleUser());

        assertThatThrownBy(() -> jwtService.parseClaims(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwtService.generateToken(sampleUser());
        String tampered = tamperPayload(token);

        assertThatThrownBy(() -> jwtService.parseClaims(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        JwtService otherService = new JwtService(
                "a-totally-different-secret-of-sufficient-length-9876543210-zzzz", Duration.ofHours(1));
        String foreignToken = otherService.generateToken(sampleUser());

        assertThatThrownBy(() -> jwtService.parseClaims(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void failsFastWhenSecretIsBlank() {
        assertThatThrownBy(() -> new JwtService("   ", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenSecretIsTooShort() {
        assertThatThrownBy(() -> new JwtService("too-short", Duration.ofHours(1)))
                .isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }

    private String tamperPayload(String token) {
        // Altère la charge utile : la signature (calculée sur header.payload) ne correspond plus.
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[1] = payload[1] == 'A' ? 'B' : 'A';
        return parts[0] + "." + new String(payload) + "." + parts[2];
    }
}
