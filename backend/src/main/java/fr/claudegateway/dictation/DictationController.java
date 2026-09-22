package fr.claudegateway.dictation;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.teams.meeting.stt.TranscriptionProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * <b>Dicter sa demande</b> (F-145 / SF-145-01) : on parle, l'écran écrit.
 *
 * <p><b>Ce que cette route n'est pas</b> : un service de transcription ouvert. Elle exige un compte
 * connecté <b>et</b> l'accès à la Forge, n'accepte qu'une liste fermée de formats audio, et borne la
 * taille avant de charger quoi que ce soit en mémoire.</p>
 *
 * <p><b>Rien n'est conservé</b> : l'audio vit le temps de l'appel. Ni disque, ni base, ni journal —
 * c'est de la parole, souvent prononcée chez un client.</p>
 */
@RestController
@RequestMapping("/atelier")
public class DictationController {

    private final DictationService dictationService;
    private final AtelierAccessService atelierAccess;
    private final TranscriptionProperties properties;

    public DictationController(DictationService dictationService,
            AtelierAccessService atelierAccess, TranscriptionProperties properties) {
        this.dictationService = dictationService;
        this.atelierAccess = atelierAccess;
        this.properties = properties;
    }

    /**
     * Transcrit un extrait dicté, et rend son texte.
     *
     * @param audio l'extrait, au format produit par le micro du navigateur
     */
    @PostMapping("/transcription")
    public ResponseEntity<Map<String, String>> transcribe(@RequestParam("audio") MultipartFile audio)
            throws IOException {
        atelierAccess.requireAccess();
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aucun extrait reçu."));
        }
        if (audio.getSize() > properties.maxAudioBytes()) {
            // Refusé AVANT de lire les octets : une borne qui ne protège qu'après le chargement en
            // mémoire ne protège de rien.
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "Extrait trop long pour être transcrit."));
        }
        try {
            String text = dictationService.transcribe(audio.getBytes(), audio.getContentType());
            return ResponseEntity.ok(Map.of("text", text));
        } catch (UnsupportedDictationAudioException ex) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error", "Ce format audio n'est pas accepté."));
        } catch (TranscriptionProviderUnavailableException ex) {
            // Drapeau fort de F-128 : sans clé, aucun octet n'est parti. On le dit franchement.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "La dictée n'est pas configurée sur cette installation."));
        } catch (TranscriptionProviderException ex) {
            // Le message du fournisseur n'est pas relayé : il peut porter des détails d'appel.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "La transcription a échoué. Réessayez."));
        }
    }
}
