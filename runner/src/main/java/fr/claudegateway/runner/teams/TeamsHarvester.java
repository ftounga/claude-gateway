package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Le morceau difficile</b> (F-88 / SF-88-01) : lire un fil sur une <b>fenêtre de temps</b> alors
 * que la liste est virtualisée — seuls les messages demandés existent.
 *
 * <p>La boucle est toujours la même : <b>récolter ce qui est arrivé</b>, voir si la fenêtre est
 * couverte, sinon <b>faire remonter la page d'un écran</b> et attendre. Ce qui est délicat n'est pas
 * le geste, c'est le <b>recollement</b> : les pages se chevauchent (doublons), la page peut bouger
 * sans rien rapporter (trou), et elle peut s'arrêter avant la période demandée (fenêtre incomplète).
 * Les trois situations sont <b>nommées</b>, jamais tues.</p>
 *
 * <p>Le dédoublonnage n'est pas fait ici : il est fait <b>une fois</b>, dans {@link TeamsLedger}, par
 * fusion sur l'identifiant. La boucle ne s'occupe que de savoir <b>quand s'arrêter</b> et
 * <b>quoi déclarer</b>.</p>
 */
final class TeamsHarvester {

    /**
     * Deux gestes de suite sans un seul objet nouveau : on arrête et on nomme le trou. Un seul ne
     * suffirait pas — la page peut rendre la main avant d'avoir reçu la réponse du geste précédent,
     * et crier au trou à chaque latence rendrait l'avertissement inaudible.
     */
    static final int IDLE_GESTURES_BEFORE_GAP = 2;

    private final TeamsLedger ledger;
    private final PageGestures gestures;
    private final BrowserLink link;

    TeamsHarvester(BrowserLink link, TeamsLedger ledger, PageGestures gestures) {
        this.link = link;
        this.ledger = ledger;
        this.gestures = gestures;
    }

    /**
     * Récolte <b>sans rien déplacer</b> : ce que la page a déjà reçu, plus un coup de coude qui
     * la fait rafraîchir en remettant la vue où elle était. C'est la récolte des outils qui ne
     * lisent pas un fil précis (mentions, réunions).
     */
    void harvestInPlace(TeamsReadWindow window) {
        ledger.absorb(link.observer().collect(), window);
        gestures.nudge();
        ledger.absorb(link.observer().collect(), window);
    }

    /**
     * Lit un fil sur une fenêtre.
     *
     * @param conversationId le fil demandé ; vide → <b>le fil affiché</b>
     * @param window         la fenêtre demandée, plafond compris (D4)
     */
    Harvest readConversation(String conversationId, TeamsReadWindow window) {
        List<TeamsGap> gaps = new ArrayList<>();
        String shownBefore = gestures.shownConversationId();
        String target = conversationId == null || conversationId.isBlank()
                ? shownBefore : conversationId.strip();
        String viewport = "";

        // Première récolte AVANT tout geste : le fil demandé est peut-être déjà dans le registre,
        // et le lire coûte alors zéro geste — c'est tout l'intérêt d'observer plutôt que d'appeler.
        ledger.absorb(link.observer().collect(), window);
        gaps.addAll(ledger.lastGaps());

        if (target.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.CONVERSATION_NOT_REACHED, "fenêtre Teams",
                    "aucun fil nommé dans la demande, et aucun fil affiché"));
            return failed(gaps, window, viewport, target);
        }

        boolean moved = false;
        if (!target.equals(shownBefore)) {
            if (!gestures.show(target)) {
                gaps.add(TeamsGap.of(TeamsGapKind.CONVERSATION_NOT_REACHED, target,
                        "ce fil n'a pas pu être affiché dans votre fenêtre Teams"));
                // On continue quand même : le registre en sait peut-être déjà quelque chose, et un
                // résultat partiel EXPLICITEMENT incomplet vaut mieux qu'un refus sec.
            } else {
                moved = true;
                viewport = "J'ai ouvert ce fil dans votre fenêtre Teams pour le lire.";
            }
        }

        boolean capReached = false;
        boolean reachedStart = false;
        int idle = 0;
        for (int gesture = 0; gesture <= PageGestures.MAX_SCROLL_GESTURES; gesture++) {
            int fresh = ledger.absorb(link.observer().collect(), window);
            ledger.lastGaps().forEach(gap -> fold(gaps, gap));
            if (gesture > 0) {
                idle = fresh == 0 ? idle + 1 : 0;
                if (idle >= IDLE_GESTURES_BEFORE_GAP) {
                    gaps.add(TeamsGap.of(TeamsGapKind.PAGINATION_STOPPED, target,
                            "la page a défilé sans plus rien rapporter"));
                    break;
                }
            }
            if (ledger.messagesOf(target, window).size() > window.cap()) {
                capReached = true;
                break;
            }
            if (covered(target, window)) {
                break;
            }
            if (gesture == PageGestures.MAX_SCROLL_GESTURES) {
                gaps.add(TeamsGap.of(TeamsGapKind.CAP_REACHED, target,
                        PageGestures.MAX_SCROLL_GESTURES + " remontées d'écran"));
                break;
            }
            if (!gestures.scroll()) {
                // La page ne remonte plus. Deux situations que rien ne distingue à l'œil : on est au
                // DÉBUT du fil, ou la page REFUSE de remonter. C'est Teams lui-même qui tranche —
                // sa dernière page dit s'il restait quelque chose avant. Sans ce signal, on
                // supposerait « tout lu », et une lecture courte se dirait complète à tort.
                reachedStart = !ledger.announcesMoreBefore(target);
                if (!reachedStart) {
                    gaps.add(TeamsGap.of(TeamsGapKind.SCROLL_EXHAUSTED, target,
                            "la page ne remonte plus, et Teams annonce encore des messages avant"));
                }
                break;
            }
            moved = true;
        }

        List<TeamsMessage> messages = ledger.messagesOf(target, window);
        if (messages.size() > window.cap()) {
            // Le plafond mord du côté ANCIEN : on remonte depuis le récent, c'est le début du fil
            // qu'on n'a pas atteint — et c'est ce que dit la phrase de D4.
            messages = new ArrayList<>(messages.subList(messages.size() - window.cap(),
                    messages.size()));
            capReached = true;
        }
        if (capReached) {
            gaps.add(TeamsGap.of(TeamsGapKind.CAP_REACHED, target,
                    "plafond de " + window.cap() + " messages"));
            reachedStart = false;
        }
        if (messages.isEmpty() && gaps.isEmpty() && ledger.bodiesRead() == 0) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, target,
                    "Teams n'a rien servi sur ce fil depuis le rattachement"));
        }
        if (moved && viewport.isEmpty()) {
            viewport = "J'ai fait défiler ce fil dans votre fenêtre Teams pour le lire.";
        }
        if (!shownBefore.isEmpty() && !shownBefore.equals(target) && gestures.restore(shownBefore)) {
            viewport = viewport + " Le fil que vous aviez ouvert a été remis.";
        }

        Instant oldest = messages.isEmpty() ? null : messages.get(0).sentAt();
        Instant newest = messages.isEmpty() ? null : messages.get(messages.size() - 1).sentAt();
        TeamsReadWindow covered = window.covering(oldest, newest, capReached, reachedStart);
        return new Harvest(target, messages, gaps, covered, ledger.health(), viewport.strip());
    }

    /** Un manque de plus, fondu avec son jumeau s'il y en a déjà un : on compte, on ne répète pas. */
    private static void fold(List<TeamsGap> gaps, TeamsGap gap) {
        for (int index = 0; index < gaps.size(); index++) {
            TeamsGap existing = gaps.get(index);
            if (existing.kind() == gap.kind() && existing.where().equals(gap.where())
                    && existing.detail().equals(gap.detail())) {
                gaps.set(index, new TeamsGap(existing.kind(), existing.where(), existing.detail(),
                        existing.count() + gap.count()));
                return;
            }
        }
        gaps.add(gap);
    }

    /** Vrai quand le registre contient déjà un message antérieur au début demandé. */
    private boolean covered(String conversationId, TeamsReadWindow window) {
        if (window.requestedFrom() == null) {
            return false; // « tout le fil » : on ne s'arrête que quand la page ne remonte plus
        }
        Instant oldest = ledger.oldestObservedIn(conversationId);
        return oldest != null && !oldest.isAfter(window.requestedFrom());
    }

    private Harvest failed(List<TeamsGap> gaps, TeamsReadWindow window, String viewport,
            String target) {
        return new Harvest(target, List.of(), gaps, window.covering(null, null, false, false),
                ledger.health(), viewport);
    }

    /** Ce qu'une récolte rend : les messages, les manques, la fenêtre RÉELLEMENT lue, la santé. */
    record Harvest(String conversationId, List<TeamsMessage> messages, List<TeamsGap> gaps,
            TeamsReadWindow window, TeamsHealth health, String viewport) {

        Harvest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            viewport = viewport == null ? "" : viewport.strip();
        }

        TeamsReading<TeamsMessage> reading() {
            return new TeamsReading<>(messages, gaps, window, health);
        }
    }
}
