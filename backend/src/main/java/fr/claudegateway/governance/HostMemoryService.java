package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Ce qu'un poste a en mémoire, et comment le lui donner</b> (F-135 / SF-135-01).
 *
 * <p>Constat qui a motivé la feature (audit du 2026-09-21) : <b>un poste sur quatre</b> capitalisait.
 * Deux causes, et aucune des deux n'était visible depuis un écran — l'embarquement des paquets par
 * défaut ne tourne qu'à la <b>création</b> d'un poste (les postes plus anciens que le paquet ne
 * l'auront donc jamais), et une activation dont le dépôt échoue reste indéfiniment en attente sans
 * que rien ne le dise.</p>
 *
 * <p><b>Un seul geste.</b> Mettre un poste en mémoire demandait de connaître la console de
 * gouvernance, d'y activer un paquet, puis de l'appliquer. Trois étapes pour une intention unique :
 * « que ce client soit appris ». Ici, une seule.</p>
 *
 * <p><b>Rien n'échoue bruyamment.</b> Machine éteinte, paquet dépublié : l'activation reste
 * enregistrée et l'état rendu dit où l'on en est. Le geste est <b>idempotent</b> — le refaire sur un
 * poste déjà en mémoire ne repose que ce qui manque.</p>
 */
@Service
public class HostMemoryService {

    private static final Logger log = LoggerFactory.getLogger(HostMemoryService.class);

    private final GovernanceActivationService activationService;
    private final GovernanceDepositService depositService;
    private final GovernanceHostFiles hostFiles;
    private final GovernanceMapGrowthRepository growth;

    public HostMemoryService(GovernanceActivationService activationService,
            GovernanceDepositService depositService, GovernanceHostFiles hostFiles,
            GovernanceMapGrowthRepository growth) {
        this.activationService = activationService;
        this.depositService = depositService;
        this.hostFiles = hostFiles;
        this.growth = growth;
    }

    /** L'état de la mémoire de ce poste, <b>sans toucher la machine</b> : tout se lit en base. */
    @Transactional(readOnly = true)
    public HostMemoryState stateOf(UUID userId, GovernanceHostRef host) {
        return HostMemoryState.of(hostFiles.supports(host), activationService.allOn(userId, host));
    }

    /** Les faits déjà accumulés sur ce poste, tous fichiers de carte confondus. */
    @Transactional(readOnly = true)
    public int factsOf(UUID userId, GovernanceHostRef host) {
        if (host.hostId() == null) {
            return 0;
        }
        return growth.findByUserIdAndHostId(userId, host.hostId()).stream()
                .mapToInt(GovernanceMapGrowth::getFacts)
                .sum();
    }

    /**
     * <b>Met ce poste en mémoire</b> : embarque les paquets par défaut, puis pose leurs fichiers.
     *
     * <p>Le dépôt de chaque paquet est tenté séparément : une machine qui refuse un fichier ne doit
     * pas empêcher le paquet suivant d'aboutir. Un échec laisse l'état en {@link
     * HostMemoryState#PENDING} — l'utilisateur voit qu'il reste un geste, et le refaire est sans
     * risque.</p>
     *
     * <p><b>Sans transaction englobante</b>, délibérément : le dépôt parle à la machine du client.
     * Tenir une transaction ouverte pendant ces allers-retours immobiliserait une connexion de base
     * pour la durée du réseau. Chaque étape porte la sienne.</p>
     *
     * @return l'état après l'opération
     */
    public HostMemoryState remember(UUID userId, GovernanceHostRef host) {
        if (!hostFiles.supports(host)) {
            return HostMemoryState.UNSUPPORTED;
        }
        List<GovernanceActivation> embarked = activationService.embarkDefaults(userId, host);
        if (embarked.isEmpty()) {
            // Aucun paquet par défaut au catalogue : on ne crée pas une activation vide pour faire
            // croire que quelque chose a été mis en place.
            return stateOf(userId, host);
        }
        for (GovernanceActivation activation : embarked) {
            if (activation.getAppliedAt() != null) {
                continue; // Déjà posé : on ne réécrit pas la machine d'un client sans raison.
            }
            try {
                depositService.deposit(userId, host, activation.getPackageId());
            } catch (RuntimeException ex) {
                // Machine muette, droits refusés, paquet disparu : l'activation reste, l'état le
                // dira, et reprendre le geste est sans danger.
                log.warn("Dépôt de mémoire non abouti sur un poste ({})",
                        ex.getClass().getSimpleName());
            }
        }
        return stateOf(userId, host);
    }
}
