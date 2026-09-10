package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Les contrôles que <b>le serveur</b> connaît (F-51 / SF-51-01) — l'unique source de vérité de ce
 * qu'un paquet a le droit de citer.
 *
 * <p>Le registre est peuplé par Spring à partir des beans {@link GovernanceControl} présents dans le
 * produit. Il n'y a aucun autre moyen d'y entrer : ni fichier chargé, ni script, ni identifiant
 * déclaré à la volée par un paquet. C'est ce qui permet à la publication de <b>refuser</b> un
 * identifiant inconnu au lieu de créer, en silence, un paquet qui ne contrôlerait rien.</p>
 *
 * <p><b>Vide est un état normal.</b> F-51 ne livre aucun contrôle — F-52 apportera les premiers. Un
 * registre vide ne doit donc rien casser : un paquet sans contrôle reste parfaitement valide.</p>
 */
@Component
public class GovernanceControlRegistry {

    private static final Logger log = LoggerFactory.getLogger(GovernanceControlRegistry.class);

    /** Nombre maximal de contrôles qu'un paquet peut citer. Au-delà, ce n'est plus une gouvernance. */
    public static final int MAX_CONTROLS_PER_PACKAGE = 20;

    private final Map<String, GovernanceControl> byId;

    public GovernanceControlRegistry(List<GovernanceControl> controls) {
        Map<String, GovernanceControl> index = new LinkedHashMap<>();
        for (GovernanceControl control : controls) {
            String id = control == null ? null : control.id();
            if (id == null || id.isBlank()) {
                log.warn("Contrôle ignoré : identifiant absent ({})",
                        control == null ? "null" : control.getClass().getSimpleName());
                continue;
            }
            GovernanceControl previous = index.putIfAbsent(id, control);
            if (previous != null) {
                // Le premier gagne, et on le dit : deux contrôles sous le même identifiant rendraient
                // le comportement dépendant de l'ordre de découverte de Spring.
                log.warn("Contrôle en double ignoré : {} déjà fourni par {}", id,
                        previous.getClass().getSimpleName());
            }
        }
        this.byId = Map.copyOf(index);
    }

    /** Registre vide — l'état livré par F-51, et le point de départ des tests. */
    public static GovernanceControlRegistry empty() {
        return new GovernanceControlRegistry(List.of());
    }

    /** Tous les contrôles connus, pour l'écran d'administration. */
    public List<GovernanceControl> all() {
        return List.copyOf(byId.values());
    }

    /** Le contrôle portant cet identifiant, s'il existe. */
    public Optional<GovernanceControl> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** Vrai si cet identifiant correspond à un contrôle du serveur. */
    public boolean exists(String id) {
        return find(id).isPresent();
    }

    /**
     * Les contrôles correspondant à ces identifiants, dans l'ordre reçu, sans doublon.
     *
     * <p>Un identifiant <b>inconnu est ignoré</b> ici : la validation a lieu à la publication, et un
     * paquet peut survivre au retrait d'un contrôle du produit. Refuser d'exécuter les autres
     * contrôles parce que l'un d'eux n'existe plus punirait l'utilisateur d'un changement de
     * version.</p>
     */
    public List<GovernanceControl> resolve(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<GovernanceControl> resolved = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            find(id).ifPresent(resolved::add);
        }
        return List.copyOf(resolved);
    }
}
