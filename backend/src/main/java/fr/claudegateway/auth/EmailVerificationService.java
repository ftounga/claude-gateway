package fr.claudegateway.auth;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.email.EmailService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;

/**
 * Cycle de vie des tokens de vérification d'e-mail : création + envoi à l'inscription,
 * puis validation à la confirmation. Les règles métier (expiration, usage unique) vivent ici.
 */
@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final SecureTokenGenerator tokenGenerator;
    private final EmailService emailService;
    private final UserService userService;
    private final String frontendUrl;
    private final Duration tokenTtl;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            SecureTokenGenerator tokenGenerator,
            EmailService emailService,
            UserService userService,
            @Value("${app.frontend-url:http://localhost:4200}") String frontendUrl,
            @Value("${app.verification.token-ttl:PT24H}") Duration tokenTtl) {
        this.tokenRepository = tokenRepository;
        this.tokenGenerator = tokenGenerator;
        this.emailService = emailService;
        this.userService = userService;
        this.frontendUrl = frontendUrl;
        this.tokenTtl = tokenTtl;
    }

    /**
     * Génère et persiste un token pour {@code user}, puis envoie le lien de vérification.
     * Appelé à l'inscription (SF-01-02) et lors d'un changement d'e-mail (SF-01-06).
     */
    @Transactional
    public void createAndSend(User user) {
        String token = tokenGenerator.generate();
        tokenRepository.save(EmailVerificationToken.builder()
                .userId(user.getId())
                .token(token)
                .expiresAt(OffsetDateTime.now().plus(tokenTtl))
                .build());

        String link = frontendUrl + "/auth/verify?token=" + token;
        emailService.sendEmailVerification(user.getEmail(), link);
    }

    /**
     * Valide un token et marque l'e-mail de l'utilisateur comme vérifié. Le token est consommé
     * (usage unique).
     *
     * @return l'utilisateur dont l'e-mail vient d'être vérifié
     * @throws InvalidVerificationTokenException si le token est inconnu, expiré ou déjà consommé
     */
    @Transactional
    public User verify(String token) {
        EmailVerificationToken verificationToken = tokenRepository.findByToken(token)
                .filter(candidate -> candidate.isUsableAt(OffsetDateTime.now()))
                .orElseThrow(InvalidVerificationTokenException::new);

        User user = userService.markEmailVerified(verificationToken.getUserId());
        verificationToken.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(verificationToken);
        return user;
    }
}
