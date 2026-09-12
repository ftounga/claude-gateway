package fr.claudegateway.governance.dto;

/**
 * Le <b>contenu exact</b> d'un fichier de carte, lu sur la machine (F-92 / SF-92-02).
 *
 * <p>Le relevé ({@link GovernanceMapView}) dit <i>combien</i> ; ceci dit <b>quoi</b>. C'est ce qui
 * permet de consulter la carte d'un client — ses VPN, ses bastions, ses pièges — sans ouvrir un
 * terminal, et c'est tout l'intérêt de l'avoir écrite.</p>
 *
 * <p><b>Seuls les chemins de la carte</b> sont lisibles par cette route : une lecture arbitraire du
 * disque d'un client sous couvert de gouvernance serait exactement l'inverse de ce qu'elle
 * protège.</p>
 *
 * @param path      nom du fichier à la racine du poste
 * @param title     son titre de premier niveau, ou son nom à défaut
 * @param present   vrai s'il existe sur la machine
 * @param content   son contenu, borné ; vide s'il est absent ou illisible
 * @param truncated vrai si le contenu a été coupé — une coupe muette laisserait croire qu'on a tout lu
 * @param message   l'action corrective quand il n'y a rien à montrer, {@code null} sinon
 */
public record GovernanceMapFileContent(String path, String title, boolean present, String content,
        boolean truncated, String message) {
}
