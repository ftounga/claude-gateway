package fr.claudegateway.diagnostic;

import java.util.List;

import fr.claudegateway.diagnostic.ProductCapability.Signal;

/**
 * <b>La carte des capacités du produit</b> (F-156 / SF-156-01) : ce que l'application sait faire,
 * où c'est, à quelle condition ça s'active, et à quoi on voit que ça s'est déclenché.
 *
 * <p><b>Déclarée dans le code, pas dans une table.</b> Une capacité qui apparaît, disparaît ou
 * déménage le fait dans le <b>même commit</b> que le code. Une table aurait dérivé du code sans que
 * rien ne le signale — exactement le défaut que ce diagnostic traque.</p>
 *
 * <p><b>Une garde la tient honnête</b> : {@code CapabilityMapTest} vérifie que chaque chemin
 * déclaré <b>existe vraiment</b>. Une carte qui ment est pire qu'une carte absente : elle ferait
 * conclure « dormante » sur une capacité supprimée.</p>
 *
 * <p><b>Elle est ouverte</b> : une capacité s'ajoute ici, et sa seule déclaration la fait entrer
 * dans le diagnostic.</p>
 */
public final class CapabilityMap {

    private static final List<ProductCapability> CAPABILITIES = List.of(

            new ProductCapability("sous-agents",
                    "Déléguer à des sous-agents",
                    "refaire dans le tour principal un travail de recherche qui pourrait être "
                            + "délégué — et payer son contexte entier",
                    List.of("backend/src/main/java/fr/claudegateway/atelier/agent/DelegationPolicy.java"),
                    "Le tour délègue quand la demande contient un travail de recherche séparable "
                            + "et que la politique de délégation l'autorise.",
                    List.of(Signal.tool("explore"))),

            new ProductCapability("exploration-parallele",
                    "Explorer en parallèle",
                    "enchaîner des lectures une par une là où elles sont indépendantes — du temps "
                            + "d'attente pur",
                    List.of("backend/src/main/java/fr/claudegateway/atelier/AtelierExploration.java"),
                    "Plusieurs explorations indépendantes sont lancées dans le même tour.",
                    List.of(Signal.tool("explore"))),

            new ProductCapability("lecture-seule",
                    "Travailler en lecture seule",
                    "exécuter des commandes qui modifient la machine alors que la demande ne "
                            + "demandait qu'à comprendre",
                    List.of("backend/src/main/java/fr/claudegateway/atelier/AtelierChatService.java"),
                    "Le mode ANSWER_PLAN restreint la panoplie aux outils de lecture (F-120).",
                    List.of(Signal.tool("read_file"), Signal.tool("grep"))),

            new ProductCapability("cache-de-prompt",
                    "Réutiliser le cache de prompt",
                    "repayer plein tarif, à chaque tour, la consigne système et tout ce qui la "
                            + "précède — le coût croît alors en N²",
                    List.of("backend/src/main/java/fr/claudegateway/agent/AnthropicAgentProvider.java",
                            "backend/src/main/java/fr/claudegateway/atelier/promptsource/PromptSourceFileRepository.java"),
                    "Le préfixe envoyé au fournisseur reste stable d'un tour à l'autre : rien de "
                            + "volatil dans la consigne système (F-134).",
                    List.of(Signal.usage("cache_read_tokens"))),

            new ProductCapability("index-du-depot",
                    "Servir l'index du dépôt depuis la base",
                    "relancer sur le poste une recherche de fichiers que la gateway connaît déjà — "
                            + "31 recherches, 4 minutes",
                    List.of("backend/src/main/java/fr/claudegateway/atelier/repoindex/RepoIndexStore.java",
                            "backend/src/main/java/fr/claudegateway/atelier/repoindex/RepoIndexProvider.java"),
                    "L'index du projet a été amorcé sur ce poste, et le tour demande des chemins "
                            + "(SF-148-07).",
                    List.of(Signal.table("repo_index_paths"), Signal.tool("glob"))),

            new ProductCapability("compaction",
                    "Compacter le contexte",
                    "renvoyer au fournisseur un historique entier dont la moitié ne sert plus",
                    List.of("backend/src/main/java/fr/claudegateway/atelier/AtelierCompactionService.java"),
                    "Le fil dépasse le seuil de compaction et un résumé remplace les tours anciens "
                            + "(F-117).",
                    List.of(Signal.usage("input_tokens"))),

            new ProductCapability("plan",
                    "Tenir un plan",
                    "repartir de zéro à chaque tour sur une demande en plusieurs étapes, et refaire "
                            + "ce qui était déjà fait",
                    List.of("backend/src/main/java/fr/claudegateway/atelier/AtelierPlan.java"),
                    "Le tour pose ou met à jour un plan (set_plan), persisté sur le fil (F-121).",
                    List.of(Signal.tool("set_plan"))),

            new ProductCapability("memoire-de-resolutions",
                    "Rappeler une résolution déjà trouvée",
                    "re-raisonner sur une question déjà tranchée sur le même poste",
                    List.of("backend/src/main/java/fr/claudegateway/atelier/resolution/ResolutionMemoryStore.java"),
                    "Une question ressemble lexicalement à une conclusion déjà mémorisée pour ce "
                            + "poste (SF-148-08).",
                    List.of(Signal.table("resolution_memory"))),

            new ProductCapability("carte-du-poste",
                    "Donner la carte du poste au tour",
                    "faire redécouvrir à l'agent, commande après commande, ce que la gateway sait "
                            + "déjà de la machine",
                    List.of("backend/src/main/java/fr/claudegateway/governance/map/HostMapKnowledgeProvider.java"),
                    "Une carte existe pour ce poste et entre dans le contexte du tour (F-136).",
                    List.of(Signal.table("host_map_files"))));

    private CapabilityMap() {
    }

    /** La carte, dans un ordre stable. */
    public static List<ProductCapability> capabilities() {
        return CAPABILITIES;
    }

    /** Une capacité par son identifiant, ou vide. */
    public static java.util.Optional<ProductCapability> byId(String id) {
        return CAPABILITIES.stream().filter(c -> c.id().equals(id)).findFirst();
    }
}
