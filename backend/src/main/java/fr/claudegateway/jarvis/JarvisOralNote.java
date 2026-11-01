package fr.claudegateway.jarvis;

/**
 * <b>La consigne orale de Jarvis</b> (F-231 / SF-231-03, D3, D11) — jointe <b>au message</b> d'un tour né à la voix.
 *
 * <p><b>Jamais dans la consigne système</b> : elle ne vaut que pour certains tours ; l'y mettre ferait varier le
 * préfixe et casserait le cache (F-134, « préfixe stable = rien de volatil »). Texte <b>fixe</b>, identique pour
 * tous, ajouté après la parole de l'utilisateur ; le message rangé dans le fil reste sa parole.</p>
 *
 * <p>Ce qu'elle demande : une phrase orale en tête (la ligne « 🎙 », deux phrases au plus) — on ne lit pas dix
 * minutes de travail ; et, pour un message à un tiers, le préparer sans l'envoyer (Jarvis n'envoie rien à un tiers)
 * et, sur « envoie-le-moi », se l'adresser par {@code email_me}, qui n'écrit qu'au PO.</p>
 *
 * <p>SF-231-04 (D4) : une action qui engage est <b>reformulée puis confirmée</b> — l'agent demande « Tu confirmes ? »
 * et n'agit qu'au tour suivant, sur un « oui » ; l'écran n'envoie jamais une réponse ambiguë.</p>
 */
public final class JarvisOralNote {

    /** Le texte de la consigne orale. */
    public static final String TEXT = """
            [Mode Jarvis — cette demande a été dite à la voix ; ta réponse sera lue à voix haute.]
            - Commence ta réponse par UNE ligne qui débute par « 🎙 » : au plus deux phrases courtes, à dire à \
            l'oral (ni liste, ni code, ni Markdown). Le détail suit, à l'écran.
            - Un message pour un tiers (encart mail ou message), tu le prépares sans jamais l'envoyer ; la ligne 🎙 \
            dit alors « Message pour <destinataire> prêt, à envoyer depuis le terminal ».
            - Si l'utilisateur demande « envoie-le-moi », adresse-le-lui par email_me : il ne part qu'à lui.
            - Avant toute action qui engage (lancer un chantier en autonome, envoyer un courriel, valider une étape, \
            publier, pousser du code), n'agis pas : reformule-la en une phrase dans la ligne 🎙, terminée par « Tu \
            confirmes ? ». Tu n'agis qu'au tour suivant, sur un « oui » explicite ; « non », un silence ou une réponse \
            ambiguë : tu n'agis pas.""";

    private JarvisOralNote() {
    }

    /** La consigne du tour, suivie de la consigne orale. */
    public static String appendTo(String consigne) {
        return (consigne == null ? "" : consigne) + "\n\n" + TEXT;
    }
}
