package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;

/**
 * Ce qui relie un paquet actif aux crochets de F-50 (F-51 / SF-51-04).
 *
 * <p>C'est le second des trois niveaux de gouvernance : le <b>verrou déterministe</b>, celui qui ne
 * se négocie pas. Une règle demande ; un contrôle refuse.</p>
 *
 * <p><b>Un seul objet pour les deux crochets.</b> F-50 attend un bean par point d'accroche
 * ({@code kind()} n'en sert qu'un) ; les deux se contentent donc d'appeler cette délégation, où vit
 * la seule logique qui compte : résoudre les paquets actifs sur le projet du tour, en tirer les
 * identifiants de contrôles, et interroger — <b>dans l'ordre du catalogue</b> — ceux que le serveur
 * fournit.</p>
 *
 * <p><b>Trois replis, tous passants.</b> Sans activation, on rend « passe » <i>sans aucune lecture de
 * paquet</i> — c'est le cas de très loin le plus fréquent, et il ne doit rien coûter. Un identifiant
 * que le produit ne fournit plus est ignoré : un paquet a pu être publié avant un changement de
 * version, et punir l'utilisateur pour cela n'aurait aucun sens. Une lecture qui échoue rend
 * « passe » : un bogue de gouvernance ne condamne pas le travail de quelqu'un.</p>
 *
 * <p><b>Le premier blocage l'emporte</b>, et les suivants ne sont pas interrogés — la règle est celle
 * de F-50 : un blocage vaut <b>une</b> correction, la suivante reviendra au prochain passage.</p>
 *
 * <p><b>Isolation.</b> Le couple {@code (userId, workspaceId)} vient du contexte de F-50, construit
 * après {@code requireOwned} dans la boucle. Aucune règle ni aucun contrôle d'un autre compte ne peut
 * donc remonter.</p>
 */
@Component
public class GovernanceCheckpointDelegate {

    private static final Logger log = LoggerFactory.getLogger(GovernanceCheckpointDelegate.class);

    private final GovernanceActivationService activationService;
    private final GovernancePackageService packageService;
    private final GovernanceControlRegistry registry;

    public GovernanceCheckpointDelegate(GovernanceActivationService activationService,
            GovernancePackageService packageService, GovernanceControlRegistry registry) {
        this.activationService = activationService;
        this.packageService = packageService;
        this.registry = registry;
    }

    /**
     * Interroge les contrôles des paquets actifs sur ce projet.
     *
     * @return le premier verdict bloquant, ou {@link AtelierCheckpointVerdict#proceed()}
     */
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointKind kind,
            AtelierCheckpointContext context) {
        if (context == null || context.userId() == null || context.workspaceId() == null) {
            return AtelierCheckpointVerdict.proceed();
        }
        for (GovernanceControl control : controlsFor(context, kind)) {
            AtelierCheckpointVerdict verdict = control.evaluate(context);
            if (verdict != null && verdict.blocked()) {
                return verdict;
            }
        }
        return AtelierCheckpointVerdict.proceed();
    }

    /** Les contrôles de ce point d'accroche, cités par les paquets actifs, dans l'ordre du catalogue. */
    private List<GovernanceControl> controlsFor(AtelierCheckpointContext context,
            AtelierCheckpointKind kind) {
        List<GovernanceActivation> active;
        try {
            active = activationService.activeOn(context.userId(), context.workspaceId());
        } catch (RuntimeException ex) {
            log.debug("Contrôles de gouvernance ignorés : activations illisibles ({})",
                    ex.getClass().getSimpleName());
            return List.of();
        }
        if (active.isEmpty()) {
            return List.of(); // Rien d'actif : aucun paquet n'est même lu.
        }
        Set<String> ids = new LinkedHashSet<>();
        for (GovernanceActivation activation : active) {
            try {
                ids.addAll(packageService.require(activation.getPackageId()).controlIdList());
            } catch (RuntimeException ex) {
                // Paquet disparu : les autres continuent de s'appliquer.
                log.debug("Paquet actif illisible ({})", ex.getClass().getSimpleName());
            }
        }
        List<GovernanceControl> controls = new ArrayList<>();
        for (GovernanceControl control : registry.resolve(ids)) {
            if (kind == control.kind()) {
                controls.add(control);
            }
        }
        return List.copyOf(controls);
    }
}
