package fr.claudegateway.fileformats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.fileformats.dto.FileFormatProfileResponse;
import fr.claudegateway.fileformats.dto.FileFormatsResponse;
import fr.claudegateway.ocr.OcrProperties;
import fr.claudegateway.upload.UploadProperties;

/**
 * Publie les listes blanches de formats du serveur (F-85 / SF-85-01), pour que l'écran pose un
 * attribut {@code accept} <b>dérivé</b> de ce que le serveur accepte réellement.
 *
 * <p><b>Pourquoi un endpoint plutôt qu'une liste recopiée dans le frontend.</b> Les deux listes
 * ({@code app.upload.allowed-types}, {@code app.ocr.allowed-types}) vivent en configuration
 * serveur. Les recopier dans un gabarit Angular — ce que faisait l'écran « bibliothèque » — crée
 * deux sources de vérité qui divergent au premier format ajouté, et un sélecteur qui propose ce que
 * le serveur refusera. Ici, le contrôleur lit <b>les mêmes objets de propriétés</b> que ceux dont la
 * validation se sert pour refuser : ajouter un type en configuration le fait apparaître à l'écran
 * sans toucher une ligne de frontend.
 *
 * <p><b>Aucune donnée d'utilisateur.</b> La réponse est de la configuration d'application, identique
 * pour tout le monde : il n'y a rien à filtrer par {@code user_id}. L'endpoint reste néanmoins
 * authentifié (règle {@code anyRequest().authenticated()}), comme le reste de l'API.
 */
@RestController
@RequestMapping("/file-formats")
public class FileFormatsController {

    private final UploadProperties uploadProperties;
    private final OcrProperties ocrProperties;

    public FileFormatsController(UploadProperties uploadProperties, OcrProperties ocrProperties) {
        this.uploadProperties = uploadProperties;
        this.ocrProperties = ocrProperties;
    }

    /** Les formats acceptés par chaque chemin de dépôt, tels que le serveur les applique. */
    @GetMapping
    public FileFormatsResponse formats() {
        return new FileFormatsResponse(
                new FileFormatProfileResponse(
                        ocrProperties.normalizedAllowedTypes(), ocrProperties.maxBytes()),
                new FileFormatProfileResponse(
                        uploadProperties.normalizedAllowedTypes(), uploadProperties.maxBytes()));
    }
}
