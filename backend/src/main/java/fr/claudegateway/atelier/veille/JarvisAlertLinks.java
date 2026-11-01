package fr.claudegateway.atelier.veille;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Où mène une alerte de veille</b> (F-230 / SF-230-03) : vers Jarvis ({@code /jarvis?alerte=…}, la route de
 * F-231) dès qu'elle est annoncée ({@code app.jarvis.route-enabled=true}) ; avant, <b>repli</b> sur le fil du sujet
 * ({@code /atelier/{sujet}?alerte=…}), où la bande des veilles montre l'alerte. Les identifiants sont opaques :
 * aucun nom de sujet ni contenu ne voyage dans le lien (D4 de F-153).
 */
@Component
public class JarvisAlertLinks {

    private final boolean jarvisRoute;

    public JarvisAlertLinks(@Value("${app.jarvis.route-enabled:false}") boolean jarvisRoute) {
        this.jarvisRoute = jarvisRoute;
    }

    /** Le lien de cette alerte. */
    public String linkFor(UUID alertId, UUID workspaceId) {
        if (jarvisRoute || workspaceId == null) {
            return "/jarvis?alerte=" + alertId;
        }
        return "/atelier/" + workspaceId + "?alerte=" + alertId;
    }
}
