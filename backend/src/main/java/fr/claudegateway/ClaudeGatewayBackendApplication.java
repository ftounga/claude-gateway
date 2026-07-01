package fr.claudegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// Authentification 100 % JWT stateless : on désactive l'utilisateur en mémoire par défaut
// de Spring Security (et le mot de passe généré qu'il journalise).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ClaudeGatewayBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ClaudeGatewayBackendApplication.class, args);
	}

}
