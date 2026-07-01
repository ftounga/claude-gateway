package fr.claudegateway.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.dto.AuthResponse;
import fr.claudegateway.auth.dto.LoginRequest;
import fr.claudegateway.auth.dto.RegisterRequest;
import fr.claudegateway.auth.dto.VerifyEmailResponse;
import fr.claudegateway.user.User;
import jakarta.validation.Valid;

/**
 * Endpoints publics d'authentification email / mot de passe ({@code /api/auth/**}).
 *
 * <p>Le controller ne porte aucune règle métier : il valide la requête (Bean Validation) et
 * délègue à l'{@link AuthService}. Les endpoints sont publics (voir {@code SecurityConfig}).</p>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public MeResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.password());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @GetMapping("/verify")
    public VerifyEmailResponse verify(@RequestParam("token") String token) {
        User user = emailVerificationService.verify(token);
        return new VerifyEmailResponse(true, user.getEmail());
    }
}
