package fr.claudegateway.atelier.actions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * <b>La reprise des interlocuteurs au démarrage</b> (F-180 / SF-180-03, D2 / D8) : les attentes d'un poste
 * qui nomment une personne sans être rattachées à un interlocuteur le sont — par du code livré, jamais
 * par une écriture manuelle en prod.
 *
 * <p>Idempotente : seules les attentes sans {@code interlocuteur_id} sont lues ; une attente rattachée
 * ne l'est jamais deux fois. Chaque rattachement se fait sous le {@code user_id} + {@code host_id}
 * <b>de l'attente</b> : rien ne passe d'un compte à l'autre. Bornée à {@value #MAX_PER_START} par
 * démarrage. Coupe-circuit {@code app.atelier.interlocuteurs.backfill.enabled=false}.</p>
 */
@Component
public class InterlocuteurBackfill {

    private static final Logger log = LoggerFactory.getLogger(InterlocuteurBackfill.class);

    static final int MAX_PER_START = 2000;

    private final TerminalActionRepository actions;
    private final InterlocuteurService interlocuteurs;
    private final boolean enabled;

    public InterlocuteurBackfill(TerminalActionRepository actions, InterlocuteurService interlocuteurs,
            @Value("${app.atelier.interlocuteurs.backfill.enabled:true}") boolean enabled) {
        this.actions = actions;
        this.interlocuteurs = interlocuteurs;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        try {
            int linked = run();
            if (linked > 0) {
                log.info("Attentes rattachées à leur interlocuteur : {}", linked);
            }
        } catch (RuntimeException e) {
            log.warn("Reprise des interlocuteurs interrompue : {}", e.getClass().getSimpleName());
        }
    }

    /** La reprise elle-même ; rend le nombre d'attentes rattachées. */
    int run() {
        int linked = 0;
        for (TerminalAction action : actions.findUnlinkedWithPerson(PageRequest.of(0, MAX_PER_START))) {
            String name = action.getRequestedTo() != null ? action.getRequestedTo() : action.getPerson();
            var found = interlocuteurs.resolve(action.getUserId(), action.getHostId(), name);
            if (found.isPresent()) {
                action.setInterlocuteurId(found.get().getId());
                actions.save(action);
                linked++;
            }
        }
        return linked;
    }
}
