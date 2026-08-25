package fr.claudegateway.atelier;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.agent.AtelierAgentProperties;
import fr.claudegateway.atelier.git.GitWorkspaceService;

/**
 * Résout les instructions portées par un projet (F-34 / SF-34-01), lues à l'<b>ouverture</b> d'une
 * session d'exécution pour être ajoutées au prompt système de l'agent.
 *
 * <p>La lecture est déléguée à {@link GitWorkspaceService}, qui couvre les deux sources : le stockage
 * objet pour un projet d'archive, et la branche montée pour un projet Git (avec priorité au fichier
 * déjà réécrit par la session). Elle exige un workspace <b>possédé</b> : l'isolation {@code user_id}
 * n'est pas rejouée ici, elle est portée par les services de lecture.</p>
 *
 * <p><b>Best-effort assumé</b> : un fichier illisible (binaire, trop volumineux, dépôt momentanément
 * injoignable) ne fait pas échouer l'ouverture de session. Bloquer le travail d'un utilisateur pour
 * un fichier de consignes serait disproportionné ; la session s'ouvre alors comme avant F-34.</p>
 */
@Service
public class ProjectInstructionsService {

    private static final Logger log = LoggerFactory.getLogger(ProjectInstructionsService.class);

    private final GitWorkspaceService gitWorkspaceService;
    private final AtelierAgentProperties properties;

    public ProjectInstructionsService(GitWorkspaceService gitWorkspaceService,
            AtelierAgentProperties properties) {
        this.gitWorkspaceService = gitWorkspaceService;
        this.properties = properties;
    }

    /**
     * Instructions du projet, prêtes à être injectées.
     *
     * @param userId    propriétaire (isolation appliquée par les services de lecture)
     * @param workspace workspace <b>déjà vérifié comme possédé</b> par l'appelant
     * @return les instructions retenues, ou vide si le projet n'en porte pas (ou si elles sont
     *         illisibles)
     */
    public Optional<ProjectInstructions> resolve(UUID userId, Workspace workspace) {
        for (String path : ProjectInstructions.CANDIDATE_PATHS) {
            Optional<String> content = readIfPresent(userId, workspace, path);
            if (content.isPresent()) {
                return Optional.of(bound(path, content.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Lit un candidat, ou renvoie vide s'il est absent ou illisible.
     *
     * <p>L'attrape-tout est délibéré : les causes d'échec sont nombreuses (fichier absent, binaire,
     * trop volumineux, jeton retiré, GitHub injoignable) et aboutissent toutes à la même conduite —
     * ouvrir la session sans instructions plutôt que refuser de travailler. Rien n'est journalisé du
     * contenu ni du jeton.</p>
     */
    private Optional<String> readIfPresent(UUID userId, Workspace workspace, String path) {
        try {
            String content = gitWorkspaceService.readFile(userId, workspace, path);
            // Un fichier vide ou blanc vaut absence : surcharger le prompt avec du vide n'apporte
            // rien et masquerait le comportement d'origine.
            return content == null || content.isBlank() ? Optional.empty() : Optional.of(content);
        } catch (RuntimeException ex) {
            log.debug("Instructions de projet ignorées pour {} : lecture impossible ({}).",
                    path, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Borne le contenu à {@code maxInstructionsChars} (D3 du cadrage). Un fichier démesuré
     * consommerait à chaque session le contexte utile au travail ; la troncature est <b>dite</b>,
     * pour que l'agent ne prenne pas un texte coupé pour un texte complet.
     */
    private ProjectInstructions bound(String path, String content) {
        int max = properties.maxInstructionsChars();
        if (content.length() <= max) {
            return new ProjectInstructions(path, content, false);
        }
        String truncated = content.substring(0, max)
                + "\n\n[…] Instructions tronquées à " + max + " caractères.";
        return new ProjectInstructions(path, truncated, true);
    }
}
