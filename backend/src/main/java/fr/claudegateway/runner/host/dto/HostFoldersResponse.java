package fr.claudegateway.runner.host.dto;

import java.util.List;

/**
 * Les <b>sous-dossiers d'un poste</b> tels que l'écran les propose (F-71 / SF-71-02) : ce qu'on
 * <b>clique</b>, au lieu de le taper.
 *
 * <p>La gateway ne rend <b>jamais</b> un chemin absolu de la machine : seulement des chemins
 * relatifs à la racine déclarée par le runner — la même garantie que depuis SF-38-15, où la gateway
 * n'apprend au plus que le <b>nom</b> du dossier racine.</p>
 *
 * @param path       chemin parcouru, relatif à la racine ; chaîne vide = la racine elle-même
 * @param parentPath chemin du dossier parent, ou {@code null} à la racine — c'est ce qui permet de
 *                   remonter sans que l'écran ait à recalculer un chemin
 * @param folders    sous-dossiers immédiats, triés, sans doublon
 * @param truncated  vrai si des dossiers <b>manquent</b> : la machine a tronqué sa liste, ou le
 *                   plafond de la gateway est atteint. Une liste incomplète se <b>dit</b>
 *                   (SF-38-21) — c'est exactement le mode d'échec silencieux que F-38 a chassé
 */
public record HostFoldersResponse(String path, String parentPath, List<HostFolder> folders,
        boolean truncated) {

    /**
     * Un dossier proposé au clic.
     *
     * @param name comme il s'appelle sur la machine
     * @param path son chemin sous la racine du poste — la valeur à envoyer au rattachement
     * @param used vrai si un projet de <b>cet utilisateur</b> occupe déjà ce chemin sous ce poste.
     *             L'écran le marque et ne le propose pas : ouvrir deux fois le même dossier a déjà
     *             produit deux entités du même nom, et un utilisateur perdu
     */
    public record HostFolder(String name, String path, boolean used) {
    }
}
