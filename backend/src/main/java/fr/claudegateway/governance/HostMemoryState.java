package fr.claudegateway.governance;

import java.util.Collection;

/**
 * Ce qu'un poste a <b>en mémoire</b> (F-135 / SF-135-01).
 *
 * <p>Trois états, trois gestes différents — et c'est bien pour cela qu'il en faut trois : les
 * confondre en « pas de carte » enverrait l'utilisateur au mauvais endroit. Mesuré le 2026-09-21,
 * les quatre postes de production occupaient trois de ces cases.</p>
 */
public enum HostMemoryState {

    /** Rien n'est activé : ce poste n'apprendra jamais rien tant qu'on ne le lui demande pas. */
    ABSENT,

    /**
     * Activé, mais <b>les fichiers ne sont pas posés</b> — le dépôt n'a jamais abouti.
     *
     * <p>C'est l'état le plus trompeur : de l'extérieur, le poste semble gouverné. Avant SF-135-01,
     * son agent recevait même une doctrine décrivant une carte absente de sa machine.</p>
     */
    PENDING,

    /** Les fichiers sont posés : la carte existe, et elle grossit. */
    ACTIVE,

    /** Poste « Hébergé » : pas de machine, donc pas de racine, donc pas de carte. Sans objet. */
    UNSUPPORTED;

    /**
     * L'état déduit des activations d'un poste.
     *
     * <p><b>Fonction pure, et volontairement le seul endroit où cette règle est écrite.</b> Elle
     * sert à la liste des postes comme au geste de mise en mémoire ; deux copies auraient divergé,
     * et l'écran aurait fini par annoncer un état que le geste contredit.</p>
     *
     * <p><b>Le critère est {@code appliedAt}, jamais le statut</b> : un paquet mis à jour repasse en
     * attente alors que ses fichiers sont bel et bien posés.</p>
     *
     * @param supported   ce poste a une racine (faux pour « Hébergé »)
     * @param activations toutes les activations du poste, déposées ou non
     */
    public static HostMemoryState of(boolean supported,
            Collection<GovernanceActivation> activations) {
        if (!supported) {
            return UNSUPPORTED;
        }
        if (activations == null || activations.isEmpty()) {
            return ABSENT;
        }
        // Un seul paquet réellement posé suffit : la carte existe.
        return activations.stream().anyMatch(activation -> activation.getAppliedAt() != null)
                ? ACTIVE
                : PENDING;
    }
}
