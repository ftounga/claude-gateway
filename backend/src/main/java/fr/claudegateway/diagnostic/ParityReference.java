package fr.claudegateway.diagnostic;

import java.util.List;

/**
 * <b>La liste de référence de la parité</b> (F-156 / SF-156-04).
 *
 * <p>Jusqu'ici la parité se discutait de mémoire. Deux colonnes — <i>présente ?</i> et
 * <i>déclenchée ?</i> — la rendent <b>constatable</b>, et le diagnostic naît de l'écart.</p>
 *
 * <p>Déclarée dans le code, comme la carte, et pour la même raison : une liste en base aurait dérivé
 * du produit sans que rien ne le signale. Une garde vérifie que chaque capacité pointée
 * <b>existe</b>.</p>
 */
public final class ParityReference {

    private static final List<ReferenceCapability> REFERENCES = List.of(

            ReferenceCapability.carriedBy("deleguer", "Déléguer à des sous-agents",
                    "sortir un travail de recherche du tour principal, sans lui faire payer tout "
                            + "le contexte",
                    "sous-agents"),

            ReferenceCapability.carriedBy("explorer-en-parallele", "Explorer en parallèle",
                    "mener de front des lectures indépendantes au lieu de les enchaîner",
                    "exploration-parallele"),

            ReferenceCapability.carriedBy("lecture-seule", "Travailler en lecture seule",
                    "répondre sans rien modifier quand la demande ne demandait qu'à comprendre",
                    "lecture-seule"),

            ReferenceCapability.carriedBy("cache", "Réutiliser le cache de prompt",
                    "ne pas repayer, à chaque tour, tout ce qui n'a pas changé",
                    "cache-de-prompt"),

            ReferenceCapability.carriedBy("indexer", "Indexer le dépôt",
                    "retrouver un chemin sans relancer une recherche sur la machine",
                    "index-du-depot"),

            ReferenceCapability.carriedBy("compacter", "Compacter le contexte",
                    "tenir une session longue sans renvoyer un historique entier à chaque tour",
                    "compaction"),

            ReferenceCapability.carriedBy("planifier", "Tenir un plan",
                    "garder le fil d'une demande en plusieurs étapes d'un tour à l'autre",
                    "plan"),

            ReferenceCapability.carriedBy("memoire", "Se souvenir d'une résolution",
                    "ne pas re-raisonner sur une question déjà tranchée",
                    "memoire-de-resolutions"),

            ReferenceCapability.carriedBy("connaitre-la-machine", "Connaître la machine",
                    "savoir ce qui est installé sans le redécouvrir commande après commande",
                    "carte-du-poste"),

            // ÉCARTÉE, ET C'EST ÉCRIT. F-39 range les hooks « hors périmètre » : les proposer à
            // chaque rapport rendrait le diagnostic insupportable.
            ReferenceCapability.excluded("hooks", "Hooks de cycle de vie",
                    "déclencher des scripts de l'utilisateur à des moments choisis du tour",
                    "écartée du périmètre par F-39 — la gouvernance passe par les paquets déposés "
                            + "sur le poste (F-51/F-75), pas par des points d'accroche"));

    private ParityReference() {
    }

    /** La liste, dans un ordre stable. */
    public static List<ReferenceCapability> references() {
        return REFERENCES;
    }
}
