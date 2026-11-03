package fr.claudegateway.jarvis.fastbrain;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionService;
import fr.claudegateway.atelier.veille.JarvisWatchAlert;
import fr.claudegateway.atelier.veille.JarvisWatchAlertRepository;
import fr.claudegateway.jarvis.JarvisReplies;
import fr.claudegateway.jarvis.JarvisReturnService;
import fr.claudegateway.jarvis.universe.JarvisUniverse;
import fr.claudegateway.jarvis.universe.JarvisUniverseService;

/**
 * <b>Le contexte compact du cerveau rapide</b> (F-235 / SF-235-02, D5) — ce qu'il sait, et <b>tout</b> ce qu'il sait.
 *
 * <p>Construit côté serveur, <b>sans appel modèle</b>, sous le {@code user_id} du principal : univers, attentes,
 * « à envoyer », délégations en cours, derniers échanges du fil, alertes de veille, coupure. Chaque bloc porte son
 * heure ; chaque bloc et le total sont bornés en caractères. Un bloc en échec est omis — jamais d'exception.</p>
 *
 * <p>Il voyage <b>dans le message</b>, jamais dans la consigne système (préfixe stable, cache intact).</p>
 */
@Component
public class JarvisFastContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastContextBuilder.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final int MAX_TOTAL = 20_000;
    static final int MAX_UNIVERSE = 9_000;
    static final int MAX_BLOCK = 2_500;
    static final int MAX_MESSAGE = 400;
    static final int THREAD_MESSAGES = 6;
    static final int MAX_ALERTS = 5;
    static final int MAX_ITEMS = 10;
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.FRENCH);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH);
    private static final DateTimeFormatter DAY_HOUR = DateTimeFormatter.ofPattern("d/MM HH:mm", Locale.FRENCH);

    private final Clock clock;
    private final JarvisDelegationRegistry delegations;
    private JarvisUniverseService universe;
    private TerminalActionService actions;
    private JarvisReturnService returns;
    private JarvisWatchAlertRepository alerts;
    private AtelierMessageRepository messages;
    /** F-245 / SF-245-02 (D11) — le fil Jarvis du compte. Facultatif. */
    private fr.claudegateway.jarvis.thread.JarvisThreadService threads;

    public JarvisFastContextBuilder(Clock clock, JarvisDelegationRegistry delegations) {
        this.clock = clock;
        this.delegations = delegations;
    }

    @Autowired(required = false)
    public void setSources(JarvisUniverseService universe, TerminalActionService actions, JarvisReturnService returns,
            JarvisWatchAlertRepository alerts, AtelierMessageRepository messages) {
        this.universe = universe;
        this.actions = actions;
        this.returns = returns;
        this.alerts = alerts;
        this.messages = messages;
    }

    @Autowired(required = false)
    public void setThreads(fr.claudegateway.jarvis.thread.JarvisThreadService threads) {
        this.threads = threads;
    }

    /** Le contexte d'un échange (univers compris — forme d'avant F-239, gardée pour le banc et les tests). */
    public String build(UUID userId, Workspace workspace, Integer chantier, String heardBeforeCut) {
        return build(userId, workspace, chantier, heardBeforeCut, true);
    }

    /**
     * F-239 / SF-239-08 — <b>l'état de la conversation du jour</b>, dans le message (volatil, jamais dans la consigne) :
     * la consigne s'en sert pour la question de politesse (une seule, à la première salutation du jour).
     */
    public static String dayState(boolean firstOfDay, boolean courtesyQuestionAsked) {
        return "## Conversation du jour\nPremière salutation du jour : " + (firstOfDay ? "oui" : "non")
                + "\nQuestion de politesse déjà posée : " + (courtesyQuestionAsked ? "oui" : "non");
    }

    /**
     * F-239 / SF-239-11 — <b>l'univers de référence</b>, sans heure : il ne change que si l'univers change, et peut donc
     * former le préfixe relu du cache (en tête de conversation, jamais dans la consigne système). Vide si illisible.
     */
    public String reference(UUID userId) {
        StringBuilder out = new StringBuilder();
        block(out, "Univers", MAX_UNIVERSE, () -> universe(userId));
        return out.toString().strip();
    }

    /**
     * Le contexte d'un échange ; {@code withUniverse} faux (F-239 / SF-239-11) : l'univers est donné à part
     * ({@link #reference}), seule son heure d'état figure ici.
     */
    public String build(UUID userId, Workspace workspace, Integer chantier, String heardBeforeCut,
            boolean withUniverse) {
        String at = stamp(HOUR);
        StringBuilder out = new StringBuilder();
        out.append("## Maintenant\n").append(stamp(NOW)).append('\n');
        if (workspace != null) {
            out.append("Sujet courant : ").append(workspace.getName()).append(" (id ").append(workspace.getId())
                    .append(")\n");
        }
        if (chantier != null) {
            out.append("Chantier courant : ").append(chantier).append('\n');
        }
        if (withUniverse) {
            block(out, "Univers (état à " + at + ")", MAX_UNIVERSE, () -> universe(userId));
        } else {
            out.append("Univers : celui de la référence en tête de conversation, état à ").append(at).append('\n');
        }
        if (workspace != null) {
            block(out, "Attentes ouvertes du sujet courant (état à " + at + ")", MAX_BLOCK,
                    () -> attentes(userId, workspace.getId()));
        }
        block(out, "À envoyer (état à " + at + ")", MAX_BLOCK, () -> toSend(userId));
        block(out, "Délégations en cours à Opus (état à " + at + ")", MAX_BLOCK, () -> running(userId));
        // F-245 / SF-245-02 (D11) — ce qui vient de se dire, où que ce soit : le fil Jarvis (6 au plus), puis le fil du
        // sujet en vue (6 au plus), toujours dans le message. Le fil Jarvis n'est relu que s'il existe (jamais créé ici).
        java.util.Optional<UUID> jarvisThread = jarvisThreadId(userId);
        jarvisThread.filter(id -> workspace == null || !id.equals(workspace.getId()))
                .ifPresent(id -> block(out, "Derniers échanges du fil Jarvis (état à " + at + ")", MAX_BLOCK + 1_000,
                        () -> thread(userId, id, null)));
        if (workspace != null) {
            block(out, "Derniers échanges du fil du sujet courant (état à " + at + ")", MAX_BLOCK + 1_000,
                    () -> thread(userId, workspace.getId(), chantier));
        }
        block(out, "Alertes de veille des dernières 24 h", MAX_BLOCK, () -> alerts(userId));
        if (heardBeforeCut != null && !heardBeforeCut.isBlank()) {
            out.append("\n## Coupure\nJarvis a été coupé après : « ")
                    .append(cut(heardBeforeCut.replaceAll("\\s+", " "), 600)).append(" »\n");
        }
        return cut(out.toString(), MAX_TOTAL);
    }

    private void block(StringBuilder out, String title, int max, Supplier<String> body) {
        try {
            String text = body.get();
            if (text == null || text.isBlank()) {
                return;
            }
            out.append("\n## ").append(title).append('\n').append(cut(text.strip(), max)).append('\n');
        } catch (RuntimeException e) {
            log.debug("Contexte rapide : bloc « {} » omis ({})", title, e.getClass().getSimpleName());
        }
    }

    String universe(UUID userId) {
        if (universe == null) {
            return "";
        }
        JarvisUniverse u = universe.universe(userId);
        StringBuilder b = new StringBuilder();
        for (JarvisUniverse.Client client : u.clients()) {
            b.append("Client ").append(client.name());
            if (!client.virtual()) {
                b.append(client.connected() ? " (poste connecté)" : " (poste déconnecté)");
            }
            b.append('\n');
            for (JarvisUniverse.Subject subject : client.subjects()) {
                b.append("- Sujet ").append(subject.name()).append(" (id ").append(subject.id()).append(')');
                if (subject.live()) {
                    b.append(" — terminal ouvert");
                }
                b.append('\n');
                for (JarvisUniverse.Chantier c : subject.chantiers()) {
                    b.append("  - chantier ").append(c.number()).append(" — ").append(c.title());
                    if (c.phaseLabel() != null) {
                        b.append(" — ").append(c.phaseLabel());
                    }
                    b.append(" — ").append(c.done()).append('/').append(c.total()).append(" étapes");
                    if (c.step() != null) {
                        b.append(" — étape en cours : ").append(c.step().title());
                    }
                    if (c.waitingOn() != null && !c.waitingOn().isEmpty()) {
                        b.append(" — attend : ").append(String.join(", ", c.waitingOn()));
                    }
                    b.append('\n');
                }
            }
            if (client.people() != null && !client.people().isEmpty()) {
                List<String> people = new ArrayList<>();
                for (JarvisUniverse.Person p : client.people()) {
                    people.add(p.role() == null || p.role().isBlank() ? p.name() : p.name() + " (" + p.role() + ")");
                }
                b.append("  Interlocuteurs : ").append(String.join(", ", people)).append('\n');
            }
        }
        return b.toString();
    }

    String attentes(UUID userId, UUID workspaceId) {
        if (actions == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (TerminalAction a : actions.list(userId, workspaceId, true)) {
            if (n++ >= MAX_ITEMS) {
                break;
            }
            b.append("- ").append(cut(oneLine(a.getDescription()), 200));
            if (a.getStatus() != null) {
                b.append(" (").append(a.getStatus().name().toLowerCase(Locale.ROOT)).append(')');
            }
            b.append('\n');
        }
        return b.toString();
    }

    String toSend(UUID userId) {
        if (returns == null) {
            return "";
        }
        JarvisReturnService.ToSendList list = returns.toSend(userId, null);
        if (list == null || list.count() == 0) {
            return "Rien à envoyer.";
        }
        StringBuilder b = new StringBuilder().append(list.count()).append(" message(s) à envoyer :\n");
        int n = 0;
        for (JarvisReturnService.ToSend item : list.items()) {
            if (n++ >= MAX_ITEMS) {
                break;
            }
            b.append("- ").append(item.workspaceName());
            if (item.recipient() != null) {
                b.append(" — pour ").append(item.recipient());
            }
            if (item.subject() != null) {
                b.append(" — « ").append(cut(oneLine(item.subject()), 120)).append(" »");
            }
            b.append('\n');
        }
        return b.toString();
    }

    String running(UUID userId) {
        List<JarvisDelegationRegistry.Running> list = delegations == null ? List.of() : delegations.running(userId);
        if (list.isEmpty()) {
            return "Aucune.";
        }
        StringBuilder b = new StringBuilder();
        for (JarvisDelegationRegistry.Running r : list) {
            b.append("- ").append(r.label() == null || r.label().isBlank() ? "demande" : r.label())
                    .append(" — sujet ").append(r.subjectName());
            if (r.chantier() != null) {
                b.append(", chantier ").append(r.chantier());
            }
            b.append(" — depuis ").append(HOUR.format(r.since().atZone(ZONE))).append('\n');
        }
        return b.toString();
    }

    private java.util.Optional<UUID> jarvisThreadId(UUID userId) {
        if (threads == null) {
            return java.util.Optional.empty();
        }
        try {
            return threads.idOf(userId);
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    String thread(UUID userId, UUID workspaceId, Integer chantier) {
        if (messages == null) {
            return "";
        }
        List<AtelierMessage> recent = new ArrayList<>();
        for (AtelierMessage m : messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId)) {
            if (m.getChantierNumber() == null || m.getChantierNumber().equals(chantier)) {
                recent.add(m);
            }
            if (recent.size() >= THREAD_MESSAGES) {
                break;
            }
        }
        Collections.reverse(recent);
        StringBuilder b = new StringBuilder();
        for (AtelierMessage m : recent) {
            boolean user = "USER".equals(m.getRole());
            String when = m.getCreatedAt() == null ? "" : HOUR.format(m.getCreatedAt().atZoneSameInstant(ZONE)) + " ";
            String content = user ? oneLine(m.getContent()) : assistantDigest(m.getContent());
            // F-245 / SF-245-02 : une réponse du cerveau rapide est celle de Jarvis, pas d'Opus.
            String who = user ? "Monsieur : " : m.isFastBrain() ? "Jarvis : " : "Opus : ";
            b.append("- ").append(when).append(who).append(cut(content, MAX_MESSAGE))
                    .append('\n');
        }
        return b.toString();
    }

    /** Une réponse d'Opus réduite à sa ligne 🎙 puis à son début. */
    static String assistantDigest(String content) {
        String oral = JarvisReplies.oralLine(content);
        String rest = oneLine(content == null ? "" : content.replaceFirst("(?m)^\\s*🎙[^\\n]*\\n?", ""));
        if (oral == null || oral.isBlank()) {
            return rest;
        }
        return rest.isBlank() ? oral : oral + " — " + rest;
    }

    String alerts(UUID userId) {
        if (alerts == null) {
            return "";
        }
        List<JarvisWatchAlert> list = alerts.findByUserIdAndCreatedAtGreaterThanEqual(userId,
                OffsetDateTime.now(clock).minusHours(24));
        if (list.isEmpty()) {
            return "";
        }
        List<JarvisWatchAlert> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (JarvisWatchAlert a : sorted) {
            if (n++ >= MAX_ALERTS) {
                break;
            }
            out.append("- ").append(DAY_HOUR.format(a.getCreatedAt().atZoneSameInstant(ZONE))).append(' ')
                    .append(cut(oneLine(a.getSentence()), 300)).append('\n');
        }
        return out.toString();
    }

    private String stamp(DateTimeFormatter format) {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZONE).format(format);
    }

    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
