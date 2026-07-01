package fr.claudegateway.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.dto.AuthResponse;
import fr.claudegateway.auth.dto.LoginRequest;
import fr.claudegateway.auth.dto.RegisterRequest;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
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
}
