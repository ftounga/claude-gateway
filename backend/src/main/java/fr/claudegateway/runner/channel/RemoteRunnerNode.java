package fr.claudegateway.runner.channel;

/**
 * Nœud <b>distant</b> hébergeant la socket d'un runner (F-38 / SF-38-12). C'est la seule chose que le
 * registre sait dire de plus qu'une présence : « le runner de ce workspace vit là-bas, à cette
 * adresse ».
 *
 * <p>L'adresse est celle du <b>connecteur interne</b> du pod distant ({@code http://{POD_IP}:8081}),
 * jamais dérivée du {@code nodeId} : ce dernier est un identifiant d'instance tiré au hasard au
 * démarrage, il ne désigne aucune machine.</p>
 *
 * @param nodeId  identifiant d'instance du pod distant — journalisation et corrélation uniquement
 * @param baseUrl adresse de base de son connecteur interne, sans chemin ({@code http://10.0.1.7:8081})
 */
public record RemoteRunnerNode(String nodeId, String baseUrl) {
}
