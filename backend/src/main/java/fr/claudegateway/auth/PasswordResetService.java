package fr.claudegateway.auth;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import fr.claudegateway.email.EmailService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;

/**
 * Cycle de vie des tokens de réinitialisation de mot de passe : demande (forgot) et
 * application (reset). Les règles métier (anti-énumération, comptes OAuth-only, usage unique,
 * expiration, hachage BCrypt du nouveau mot de passe) vivent ici.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetTokenRepository tokenRepository;
    private final SecureTokenGenerator tokenGenerator;
    private final EmailService emailService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final String frontendUrl;
    private final Duration tokenTtl;

    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            SecureTokenGenerator tokenGenerator,
            EmailService emailService,
            UserService userService,
            PasswordEncoder passwordEncoder,
            @Value("${app.frontend-url:http://localhost:4200}") String frontendUrl,
            @Value("${app.password-reset.token-ttl:PT1H}") Duration tokenTtl) {
        this.tokenRepository = tokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.emailService = emailService;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.frontendUrl = frontendUrl;
        this.tokenTtl = tokenTtl;
    }

    /**
     * Traite une demande de réinitialisation. Ne révèle jamais si le compte existe : la réponse
     * appelante est toujours identique. Un e-mail n'est envoyé que pour un compte LOCAL disposant
     * d'un mot de passe (les comptes OAuth-only sont ignorés silencieusement).
     */
    @Transactional
    public void requestReset(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        userService.findByEmail(email)
                .filter(user -> StringUtils.hasText(user.getPasswordHash()))
                .ifPresentOrElse(
                        this::createAndSend,
                        () -> log.debug("Demande de reset ignorée (compte absent ou OAuth-only)"));
    }

    private void createAndSend(User user) {
        String token = tokenGenerator.generate();
        tokenRepository.save(PasswordResetToken.builder()
                .userId(user.getId())
                .token(token)
                .expiresAt(OffsetDateTime.now().plus(tokenTtl))
                .build());

        String link = frontendUrl + "/auth/reset?token=" + token;
        emailService.sendPasswordReset(user.getEmail(), link);
    }

    /**
     * Applique un nouveau mot de passe à partir d'un token valide. Le mot de passe est haché en
     * BCrypt ; le token est consommé (usage unique).
     *
     * @throws InvalidPasswordResetTokenException si le token est inconnu, expiré ou déjà consommé
     */
    @Transactional
    public void reset(String token, String newRawPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .filter(candidate -> candidate.isUsableAt(OffsetDateTime.now()))
                .orElseThrow(InvalidPasswordResetTokenException::new);

        String passwordHash = passwordEncoder.encode(newRawPassword);
        userService.updatePassword(resetToken.getUserId(), passwordHash);

        resetToken.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(resetToken);
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
