package fr.claudegateway.diagnostic;

import java.util.List;

/**
 * <b>Une capacité du produit</b> (F-156 / SF-156-01) : ce que l'application sait faire, où c'est
 * dans le code, à quelle condition ça s'active, et à quoi on voit que ça s'est déclenché.
 *
 * <p><b>Pourquoi elle existe.</b> La trace dit « 31 recherches de fichiers, 4 minutes cumulées ».
 * Deux lectures : ou bien l'application <b>ne sait pas</b> servir un index (il faut le créer), ou
 * bien elle sait et <b>ne s'est pas déclenchée</b> (rien à développer, un branchement à réparer).
 * Sans cette carte on ne peut pas trancher — et on développerait ce qui existe déjà.</p>
 *
 * @param id        identifiant stable — c'est lui qu'on compte, jamais le nom
 * @param name      nom lisible, tel qu'un humain le dirait
 * @param avoids    le gaspillage que la capacité supprime quand elle se déclenche
 * @param paths     où elle vit, en chemins <b>réels</b> du dépôt (un test le vérifie)
 * @param activates la condition d'activation, en une phrase
 * @param signals   ce qui prouve qu'elle s'est déclenchée — un outil appelé, ou une marque dans
 *                  les mesures existantes ; c'est ce que SF-156-03 ira chercher
 * @param wirings   ce qui prouve qu'elle est encore <b>branchée</b> dans le code (F-157 / SF-157-01),
 *                  éventuellement vide. Sans eux, « présente mais jamais déclenchée » et
 *                  « débranchée par un remaniement » se confondent sous le mot « dormante » — et
 *                  l'on va chercher un réglage là où il manque une ligne de code
 */
public record ProductCapability(
        String id,
        String name,
        String avoids,
        List<String> paths,
        String activates,
        List<Signal> signals,
        List<Wiring> wirings) {

    /** Forme sans témoin de branchement : une capacité dont le câblage n'est pas vérifiable ainsi. */
    public ProductCapability(String id, String name, String avoids, List<String> paths,
                             String activates, List<Signal> signals) {
        this(id, name, avoids, paths, activates, signals, List.of());
    }

    /**
     * <b>Un témoin de branchement</b> (F-157 / SF-157-01) : un fragment littéral qui <b>doit</b> se
     * trouver dans un fichier donné pour que la capacité soit branchée.
     *
     * <p><b>Ce n'est pas de l'analyse de code</b> : c'est la vérification d'un fait déclaré. Aucun
     * modèle, aucun jeton. Une garde de build vérifie que chaque fragment est réellement présent —
     * un témoin faux ferait conclure « débranchée » sur une capacité qui marche, ce qui est pire
     * que pas de témoin du tout.</p>
     *
     * @param path     le fichier, qui doit être <b>l'un des chemins</b> de la capacité
     * @param fragment le fragment littéral attendu
     * @param proves   ce que sa présence prouve, en une phrase — sans quoi un fragment de code
     *                 isolé serait illisible dans un rapport
     */
    public record Wiring(String path, String fragment, String proves) {
    }

    /**
     * Ce à quoi on voit qu'une capacité s'est déclenchée.
     *
     * @param kind  la nature du signal
     * @param value l'outil, la colonne ou la marque à chercher
     */
    public record Signal(Kind kind, String value) {

        /** La nature d'un signal — elle dit <b>où</b> aller le chercher. */
        public enum Kind {
            /** Un outil appelé, visible dans {@code runner_audit.tool}. */
            TOOL,
            /** Une grandeur de {@code usage_turns} (ex. {@code cache_read_tokens}). */
            USAGE,
            /** Une ligne écrite dans une table que la capacité alimente. */
            TABLE
        }

        public static Signal tool(String tool) {
            return new Signal(Kind.TOOL, tool);
        }

        public static Signal usage(String column) {
            return new Signal(Kind.USAGE, column);
        }

        public static Signal table(String table) {
            return new Signal(Kind.TABLE, table);
        }
    }
}
