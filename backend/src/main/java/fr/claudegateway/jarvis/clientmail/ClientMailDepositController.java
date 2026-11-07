package fr.claudegateway.jarvis.clientmail;

import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerPollController;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * <b>Le dépôt d'un mail Outlook par le runner</b> (F-249 / SF-249-01, D3) : {@code POST /runner/mail-deposit}, corps
 * brut, en-têtes {@code X-Runner-Token} et {@code X-File-Name}. Le jeton est vérifié <b>ici</b> (aucun filtre ne le
 * connaît, rien n'est posé dans le contexte de sécurité, D9 de F-38) ; le compte et le poste (le client) viennent
 * <b>du jeton seul</b>. Le nom du fichier ne sert qu'à l'extension ; il n'est jamais stocké ni journalisé.
 */
@RestController
public class ClientMailDepositController {

    public static final String FILE_NAME_HEADER = "X-File-Name";

    private final RunnerTokenAuthenticator authenticator;
    private final ClientMailDepositService deposits;
    private final ClientMailProperties properties;

    public ClientMailDepositController(RunnerTokenAuthenticator authenticator, ClientMailDepositService deposits,
            ClientMailProperties properties) {
        this.authenticator = authenticator;
        this.deposits = deposits;
        this.properties = properties;
    }

    @PostMapping(value = "/runner/mail-deposit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> deposit(
            @RequestHeader(value = RunnerPollController.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FILE_NAME_HEADER, required = false) String fileName,
            @RequestBody(required = false) byte[] content) {
        Optional<RunnerIdentity> identity = authenticator.authenticate(token == null ? null : token.strip());
        if (identity.isEmpty()) {
            return refuse(HttpStatus.UNAUTHORIZED, "Jeton runner refusé.");
        }
        if (!properties.isEnabled()) {
            return refuse(HttpStatus.SERVICE_UNAVAILABLE, "Dépôt des mails momentanément coupé : réessai plus tard.");
        }
        if (content == null || content.length == 0) {
            return refuse(HttpStatus.BAD_REQUEST, "Fichier vide.");
        }
        if (content.length > properties.maxBytes()) {
            return refuse(HttpStatus.PAYLOAD_TOO_LARGE, "Fichier trop lourd (" + properties.maxBytes() / (1024 * 1024)
                    + " Mo au plus).");
        }
        if (fileName == null || fileName.length() > 255) {
            return refuse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format non pris en charge : .msg ou .eml seulement.");
        }
        try {
            ClientMailDepositService.Outcome outcome = deposits.accept(identity.get().userId(),
                    identity.get().hostId(), fileName, content);
            return ResponseEntity.ok(Map.of("outcome", outcome.outcome()));
        } catch (ClientMailDepositService.UnsupportedFormatException e) {
            return refuse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
        } catch (DepositedMail.UnreadableMailException e) {
            return refuse(HttpStatus.UNPROCESSABLE_ENTITY, "Fichier illisible.");
        } catch (DataIntegrityViolationException e) {
            // Le même fichier reçu deux fois en même temps : le second est un doublon.
            return ResponseEntity.ok(Map.of("outcome", ClientMailDeposit.DUPLICATE));
        }
    }

    private static ResponseEntity<Map<String, Object>> refuse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
