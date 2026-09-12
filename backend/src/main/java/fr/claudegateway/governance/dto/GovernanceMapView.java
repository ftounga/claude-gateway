package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * <b>Ce que la machine sait</b> — le relevé de la carte d'un poste (F-92 / SF-92-02).
 *
 * <p>C'est ce qui fait exister la carte pour l'utilisateur : <b>un fichier qu'on ne voit jamais n'est
 * pas un savoir, c'est un fichier</b>. Tant qu'elle ne se lisait qu'en ouvrant un terminal, personne
 * ne pouvait constater ce que le PO demande — que « à chaque projet qu'on rajoute, la connaissance de
 * l'infra augmente ».</p>
 *
 * <p><b>Trois « non » différents</b>, et c'est pour cela qu'il y a trois drapeaux plutôt qu'un :
 * {@code supported} à faux = ce poste n'est pas une machine, il n'aura jamais de carte ;
 * {@code governed} à faux = rien n'est activé, il n'y a pas de carte à attendre ;
 * {@code readable} à faux = la carte existe peut-être, mais la machine n'a pas répondu. Les réponses
 * à donner à l'utilisateur sont trois gestes distincts, et {@code message} porte le bon.</p>
 *
 * @param hostRef  le poste, tel qu'il s'écrit dans une URL
 * @param hostId   son identifiant, ou {@code null} pour le poste « Hébergé » (F-71)
 * @param hostName son nom lisible
 * @param supported faux si ce poste n'a pas de racine où poser une carte
 * @param governed  faux si aucun paquet actif sur ce poste n'apporte de carte
 * @param readable  faux si la racine n'a pas pu être lue
 * @param message   l'action corrective quand rien ne peut être montré, {@code null} sinon
 * @param files     un relevé par fichier de carte attendu, dans l'ordre du paquet
 * @param filesExpected nombre de fichiers de carte attendus
 * @param filesPresent  nombre de ceux qui existent réellement sur la machine
 * @param sections  total des sections de la carte
 * @param facts     <b>total des faits</b> — le seul chiffre qui répond à la question du PO
 * @param growth    <b>ce que la carte a gagné</b> depuis la première lecture (F-93 / SF-93-02), ou
 *                  {@code null} s'il n'y a rien à en dire : un « +0 » affiché chaque jour serait pire
 *                  que rien — il apprendrait qu'on ne gagne rien
 */
public record GovernanceMapView(String hostRef, UUID hostId, String hostName, boolean supported,
        boolean governed, boolean readable, String message, List<GovernanceMapFileView> files,
        int filesExpected, int filesPresent, int sections, int facts,
        GovernanceMapGrowthView growth) {
}
