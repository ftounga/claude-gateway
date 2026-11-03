package fr.claudegateway.jarvis.mail;

import java.util.List;

/**
 * Ce qui est scellé d'un courrier en cache (F-242 / SF-242-02) : en-têtes lisibles et extrait borné. Jamais
 * journalisé, jamais envoyé en dehors de la passerelle sauf au modèle, <b>balisé non fiable</b> (SF-242-03).
 *
 * @param from     adresse de l'expéditeur (minuscules)
 * @param fromName nom affiché de l'expéditeur
 * @param to       destinataires directs
 * @param cc       copies
 * @param replyTo  adresse de réponse, si différente
 * @param subject  objet
 * @param snippet  extrait texte, 2 000 caractères au plus
 */
public record MailHeaders(String from, String fromName, List<String> to, List<String> cc, String replyTo,
        String subject, String snippet) {

    public MailHeaders {
        from = from == null ? "" : from;
        fromName = fromName == null ? "" : fromName;
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        subject = subject == null ? "" : subject;
        snippet = snippet == null ? "" : snippet;
    }
}
