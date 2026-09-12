package fr.claudegateway.teams;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;

/**
 * <b>Le droit Teams du compte courant</b> (F-89 / SF-89-01).
 *
 * <p>Un seul verbe, {@code GET}, et il répond {@code 200} dans tous les cas : ne pas avoir l'option
 * est un <b>état</b>, pas une erreur. C'est ce qui permet à l'accueil de la Forge de décider, sans
 * provoquer de refus, si la carte d'un poste porte ou non le geste « Terminal Teams » — un bouton
 * qui mène à un 403 ne serait pas une porte, ce serait un piège.</p>
 *
 * <p>L'identité vient exclusivement du contexte de sécurité : le droit lu est toujours celui de
 * l'appelant.</p>
 */
@RestController
@RequestMapping("/teams")
public class TeamsAccessController {

    private final TeamsAccessService teamsAccess;
    private final AtelierAccessService atelierAccess;

    public TeamsAccessController(TeamsAccessService teamsAccess, AtelierAccessService atelierAccess) {
        this.teamsAccess = teamsAccess;
        this.atelierAccess = atelierAccess;
    }

    /** Le compte peut-il ouvrir un terminal Teams ? */
    @GetMapping("/access")
    public TeamsAccessResponse access() {
        // L'accès Forge reste exigé : le volet Teams est un terminal de la Forge avant d'être une
        // option. Sans la Forge, la question ne se pose même pas.
        atelierAccess.requireAccess();
        return new TeamsAccessResponse(teamsAccess.hasAccess());
    }
}
