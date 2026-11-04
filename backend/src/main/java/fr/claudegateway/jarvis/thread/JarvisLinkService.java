package fr.claudegateway.jarvis.thread;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.JarvisReplies;

/**
 * <b>La ligne-lien du fil Jarvis</b> (F-245 / SF-245-03, D5, D6) : quand une délégation part vers un sujet ou un
 * chantier, le fil Jarvis en garde une ligne {@code role = LINK} — « ↗ Helios · chantier 3 — « … » · en cours » —
 * qui passe à « fait » (avec la ligne 🎙 de la réponse) ou « échec » à la fin du tour, et ouvre la réponse d'un
 * toucher. Le sujet cible ne reçoit rien de plus (son historique reste propre).
 *
 * <p><b>Isolation</b> : le sujet cible est vérifié par {@code findByIdAndUserId} ; une ligne n'est mise à jour que
 * sous le {@code user_id} de son auteur. <b>Best-effort</b> : jamais d'exception vers l'appelant.</p>
 */
@Service
public class JarvisLinkService {

    private static final Logger log = LoggerFactory.getLogger(JarvisLinkService.class);

    /** La marque d'une ligne-lien. */
    public static final String ARROW = "↗";
    static final int MAX_LABEL = 120;
    static final int MAX_ORAL = 300;

    private final AtelierMessageRepository messages;
    private final WorkspaceRepository workspaces;
    private final JarvisThreadService threads;

    public JarvisLinkService(AtelierMessageRepository messages, WorkspaceRepository workspaces,
            JarvisThreadService threads) {
        this.messages = messages;
        this.workspaces = workspaces;
        this.threads = threads;
    }

    /**
     * Écrit la ligne-lien « en cours » d'une délégation vers {@code (target, chantier)} dans le fil Jarvis du compte.
     * Rend son identifiant, ou {@code null} : sujet d'autrui ou introuvable, cible = le fil Jarvis, panne.
     */
    public UUID open(UUID userId, UUID target, Integer chantier, String label) {
        if (userId == null || target == null) {
            return null;
        }
        try {
            Workspace subject = workspaces.findByIdAndUserId(target, userId).orElse(null);
            if (subject == null || subject.isJarvisThread()) {
                return null;
            }
            Workspace thread = threads.open(userId);
            AtelierMessage link = messages.save(AtelierMessage.builder()
                    .workspaceId(thread.getId()).userId(userId).role(AtelierMessage.ROLE_LINK)
                    .content(display(subject.getName(), chantier, label, AtelierMessage.LINK_RUNNING, null, false))
                    .refWorkspaceId(subject.getId()).refChantierNumber(chantier)
                    .linkStatus(AtelierMessage.LINK_RUNNING).build());
            return link.getId();
        } catch (RuntimeException e) {
            log.debug("Ligne-lien non écrite ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Termine la ligne-lien {@code linkId} du compte : {@code reply} = la réponse d'Opus (ou {@code null} sur une
     * erreur) ; {@code interrupted} = le tour a été interrompu. Une ligne d'un autre compte, inconnue ou déjà
     * terminée est ignorée.
     */
    public void finish(UUID userId, UUID linkId, AtelierMessage reply, boolean interrupted) {
        if (userId == null || linkId == null) {
            return;
        }
        try {
            AtelierMessage link = messages.findByIdAndUserId(linkId, userId).orElse(null);
            if (link == null || !link.isLink() || !AtelierMessage.LINK_RUNNING.equals(link.getLinkStatus())) {
                return;
            }
            String status = reply == null || interrupted ? AtelierMessage.LINK_FAILED : AtelierMessage.LINK_DONE;
            String name = workspaces.findByIdAndUserId(link.getRefWorkspaceId(), userId).map(Workspace::getName)
                    .orElse(null);
            String label = labelOf(link.getContent());
            String oral = reply == null || interrupted ? null : JarvisReplies.oralLine(reply.getContent());
            link.setLinkStatus(status);
            link.setRefMessageId(reply == null ? null : reply.getId());
            link.setContent(display(name == null ? nameOf(link.getContent()) : name, link.getRefChantierNumber(),
                    label, status, oral, interrupted));
            messages.save(link);
        } catch (RuntimeException e) {
            log.debug("Ligne-lien non terminée ({})", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ rendu (pur)

    /** Le texte affiché : « ↗ Helios · chantier 3 — « libellé » · en cours », puis la ligne 🎙 sur « fait ». */
    static String display(String name, Integer chantier, String label, String status, String oral,
            boolean interrupted) {
        StringBuilder b = new StringBuilder(ARROW).append(' ')
                .append(name == null || name.isBlank() ? "Sujet" : name.strip());
        if (chantier != null) {
            b.append(" · chantier ").append(chantier);
        }
        b.append(" — « ").append(cut(clean(label), MAX_LABEL)).append(" » · ").append(word(status, interrupted));
        if (AtelierMessage.LINK_DONE.equals(status) && oral != null && !oral.isBlank()) {
            b.append('\n').append(JarvisReplies.ORAL_MARK).append(' ').append(cut(clean(oral), MAX_ORAL));
        }
        return b.toString();
    }

    /**
     * D6 — ce qu'Opus relit d'une ligne-lien dans le fil Jarvis : un texte court, jamais le contenu du chantier.
     * « [Transmis à Helios · chantier 3 — « … » · fait — 🎙 …] ».
     */
    public static String replay(String content) {
        String text = content == null ? "" : content.strip();
        if (text.startsWith(ARROW)) {
            text = text.substring(ARROW.length()).strip();
        }
        return "[Transmis à " + text.replace("\n", " — ") + "]";
    }

    private static String word(String status, boolean interrupted) {
        if (AtelierMessage.LINK_DONE.equals(status)) {
            return "fait";
        }
        if (AtelierMessage.LINK_FAILED.equals(status)) {
            return interrupted ? "interrompu" : "échec";
        }
        return "en cours";
    }

    /** Le libellé entre « » d'une ligne déjà écrite. */
    static String labelOf(String content) {
        if (content == null) {
            return "";
        }
        int start = content.indexOf("— « ");
        int end = content.lastIndexOf(" » · ");
        return start < 0 || end < start + 4 ? "" : content.substring(start + 4, end);
    }

    /** Le nom du sujet d'une ligne déjà écrite (repli si le sujet a disparu). */
    static String nameOf(String content) {
        if (content == null || !content.startsWith(ARROW)) {
            return null;
        }
        String rest = content.substring(ARROW.length()).strip();
        int end = rest.indexOf(" · chantier ");
        if (end < 0) {
            end = rest.indexOf(" — « ");
        }
        return end < 0 ? null : rest.substring(0, end);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").replace('«', '"').replace('»', '"').strip();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1).strip() + "…";
    }
}
