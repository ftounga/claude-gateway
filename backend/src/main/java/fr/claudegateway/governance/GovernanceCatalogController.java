package fr.claudegateway.governance;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.governance.dto.GovernancePackageView;

/**
 * Le catalogue de gouvernance tel qu'un <b>utilisateur connecté</b> le lit (F-51 / SF-51-01).
 *
 * <p>Seuls les paquets <b>publiés</b> y figurent, et chaque fichier apporté y est présenté par son
 * <b>chemin et son genre</b> — jamais son contenu. C'est ce qui permet à l'écran de tenir la promesse
 * de la feature : annoncer ce qu'un paquet va écrire, et où, <b>avant</b> qu'on l'active.</p>
 *
 * <p>Aucune donnée utilisateur n'est lue ici : un paquet publié est un contenu produit, identique
 * pour tout le monde. La sélection personnelle et les activations — qui, elles, portent
 * {@code user_id} — arrivent en SF-51-02.</p>
 */
@RestController
@RequestMapping("/governance")
public class GovernanceCatalogController {

    private final GovernancePackageService service;

    public GovernanceCatalogController(GovernancePackageService service) {
        this.service = service;
    }

    /** Les paquets publiés, par nom. */
    @GetMapping("/packages")
    public List<GovernancePackageView> packages() {
        return service.listPublished();
    }
}
