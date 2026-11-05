package fr.claudegateway.jarvis.cards;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionStatus;
import fr.claudegateway.jarvis.JarvisReturnService;
import fr.claudegateway.jarvis.universe.JarvisUniverse;

/**
 * <b>Les fiches des faits déjà lus par le contexte du cerveau rapide</b> (F-248 / SF-248-02, cadrage §4.2) — attentes,
 * « à envoyer », courriels du bref, personnes de l'univers. Pur : chaque fiche est dérivée d'une ligne <b>déjà relue
 * sous {@code user_id}</b> par la source qui l'a mise dans le contexte, sans aucune lecture de plus.
 */
public final class JarvisCardSources {

    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DAY_HOUR = DateTimeFormatter.ofPattern("d/MM HH:mm", Locale.FRENCH);

    private JarvisCardSources() {
    }

    /** « 3/11 18:40 », heure de Paris (posée par la passerelle). */
    static String at(OffsetDateTime t) {
        return t == null ? null : DAY_HOUR.format(t.atZoneSameInstant(ZONE));
    }

    /** La route d'un sujet, et de son chantier s'il est connu. */
    static String subjectLink(UUID workspaceId, Integer chantier) {
        if (workspaceId == null) {
            return null;
        }
        return "/atelier/" + workspaceId + (chantier == null ? "" : "?chantier=" + chantier);
    }

    /** Une attente ouverte du sujet courant. */
    public static JarvisCard attente(TerminalAction a, String group) {
        Map<String, String> attrs = new LinkedHashMap<>();
        OffsetDateTime since = a.getRequestedAt() != null ? a.getRequestedAt() : a.getCreatedAt();
        attrs.put("since", at(since));
        attrs.put("chantier", a.getChantierNumber() == null ? null : "Chantier " + a.getChantierNumber());
        String who = a.getRequestedTo() != null ? a.getRequestedTo() : a.getPerson();
        attrs.put("who", who);
        String word = a.getStatus() == TerminalActionStatus.DEMANDE ? "demandé — attend la réponse"
                : a.getStatus() == TerminalActionStatus.A_FAIRE ? "à faire" : a.getStatus() == null ? null
                        : a.getStatus().name().toLowerCase(Locale.ROOT);
        return JarvisCardTemplates.card("fait:attente:" + a.getId(), JarvisCardTemplates.ATTENTE,
                a.getDescription(), word, attrs, subjectLink(a.getWorkspaceId(), a.getChantierNumber()),
                a.getCreatedAt(), "direct", group, JarvisCardTemplates.aliases(a.getPerson(), a.getRequestedTo()));
    }

    /** Un message « à envoyer ». */
    public static JarvisCard envoi(JarvisReturnService.ToSend t) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("since", at(t.proposedAt()));
        attrs.put("chantier", t.chantierNumber() == null ? null : "Chantier " + t.chantierNumber());
        attrs.put("who", t.recipient());
        String title = (t.subject() == null || t.subject().isBlank() ? "Message" : t.subject())
                + (t.workspaceName() == null ? "" : " — " + t.workspaceName());
        return JarvisCardTemplates.card("fait:envoi:" + t.messageId() + ":" + t.blockIndex(),
                JarvisCardTemplates.ATTENTE, title, "à envoyer", attrs,
                subjectLink(t.workspaceId(), t.chantierNumber()), t.proposedAt(), "direct", t.workspaceName(),
                JarvisCardTemplates.aliases(t.recipient(), t.subject()));
    }

    /**
     * Un courriel du bref : l'en-tête seul (jamais le corps : la visionneuse F-242 reste le seul endroit où il
     * s'affiche, D9) ; aucun lien (le toucher ouvre la visionneuse).
     */
    public static JarvisCard courriel(UUID id, boolean toHandle, OffsetDateTime receivedAt, String from,
            String fromName, String subject) {
        Map<String, String> attrs = new LinkedHashMap<>();
        String sender = fromName == null || fromName.isBlank() ? from : fromName;
        attrs.put("from", sender);
        attrs.put("received", at(receivedAt));
        attrs.put("subject", subject);
        return JarvisCardTemplates.card("fait:courriel:" + id, JarvisCardTemplates.COURRIEL,
                subject == null || subject.isBlank() ? "(sans objet)" : subject, toHandle ? "à répondre" : "à lire",
                attrs, null, receivedAt, "direct", null, JarvisCardTemplates.aliases(fromName, subject));
    }

    /** Les interlocuteurs d'un univers, avec ce qui attend d'eux (chantiers où ils doivent agir). */
    public static List<JarvisCard> personnes(JarvisUniverse universe, OffsetDateTime now) {
        List<JarvisCard> out = new ArrayList<>();
        if (universe == null || universe.clients() == null) {
            return out;
        }
        for (JarvisUniverse.Client client : universe.clients()) {
            if (client.people() == null) {
                continue;
            }
            for (JarvisUniverse.Person p : client.people()) {
                if (p.name() == null || p.name().isBlank()) {
                    continue;
                }
                List<String> waiting = new ArrayList<>();
                for (JarvisUniverse.Subject s : client.subjects()) {
                    for (JarvisUniverse.Chantier c : s.chantiers()) {
                        if (c.waitingOn() != null && c.waitingOn().stream().anyMatch(w -> w != null
                                && w.equalsIgnoreCase(p.name()))) {
                            waiting.add("chantier " + c.number() + " — " + c.title());
                        }
                    }
                }
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("role", p.role());
                attrs.put("client", client.name());
                attrs.put("waiting", waiting.isEmpty() ? null : String.join(" ; ", waiting));
                List<String> aliases = new ArrayList<>();
                aliases.add(p.name());
                if (p.variants() != null) {
                    aliases.addAll(p.variants());
                }
                out.add(JarvisCardTemplates.card("fait:personne:" + JarvisCardTemplates.normalize(client.name())
                        + ":" + JarvisCardTemplates.normalize(p.name()), JarvisCardTemplates.PERSONNE, p.name(),
                        null, attrs, null, now, "direct", client.name(),
                        JarvisCardTemplates.aliases(aliases.toArray(String[]::new))));
            }
        }
        return out;
    }
}
