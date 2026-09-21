package fr.claudegateway.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>Sous quel nom part un courriel client</b> (F-110 / SF-110-06).
 *
 * <p>Le produit porte déjà la règle — <i>« rien de ce qui sort du projet ne doit suggérer qu'un
 * modèle l'a produit »</i> — et la fait respecter <b>mécaniquement</b> sur les messages de commit
 * depuis F-52. Elle n'avait jamais été appliquée aux courriels : le nom d'expéditeur annonçait
 * l'outil, à chaque envoi, dans la boîte professionnelle d'un client.</p>
 *
 * <p><b>Le nom du client n'y figure pas non plus</b>, et c'est un choix du PO : le destinataire sait
 * qui il est, et un expéditeur « X pour Y » est une tournure d'outil, pas de personne.</p>
 *
 * <p><b>Jamais vide.</b> Un courriel sans nom d'expéditeur part plus souvent en indésirable qu'un
 * courriel nommé : un réglage blanc retombe donc sur le défaut plutôt que de laisser l'adresse
 * seule.</p>
 */
@Component
public class ClientMailIdentity {

    /** Ce qui s'affiche quand rien n'est configuré. */
    public static final String DEFAULT_SENDER_NAME = "NG IT Consulting";

    /**
     * Borne du nom affiché. Au-delà, les clients de messagerie tronquent eux-mêmes, souvent au
     * milieu d'un mot : on coupe proprement plutôt que de leur laisser le faire.
     */
    static final int MAX_NAME_CHARS = 64;

    private final String senderName;

    public ClientMailIdentity(@Value("${app.mail.sender-name:}") String configured) {
        this.senderName = normalize(configured);
    }

    /** Le nom affiché de l'expéditeur, jamais vide. */
    public String senderName() {
        return senderName;
    }

    private static String normalize(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SENDER_NAME;
        }
        String cleaned = configured.strip();
        return cleaned.length() <= MAX_NAME_CHARS ? cleaned : cleaned.substring(0, MAX_NAME_CHARS).strip();
    }
}
