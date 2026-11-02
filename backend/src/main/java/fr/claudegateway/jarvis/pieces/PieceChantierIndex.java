package fr.claudegateway.jarvis.pieces;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>Le rattachement d'une pièce à un chantier</b> (F-237 / SF-237-01, cadrage §4.2) — pur, déterministe.
 *
 * <ol>
 *   <li>{@code MESSAGE} : le message lié porte {@code chantier_number}, ou l'identifiant de la pièce apparaît dans la
 *       trace d'outils / le bloc riche d'un message de chantier ;</li>
 *   <li>{@code WINDOW} : sinon, la date tombe dans la fenêtre d'<b>un seul</b> chantier (premier → dernier message ;
 *       le chantier courant reste ouvert jusqu'à maintenant) ;</li>
 *   <li>{@code NONE} : sinon. Jarvis n'attribue jamais une pièce à un chantier d'autorité.</li>
 * </ol>
 */
public final class PieceChantierIndex {

    /** Un message de chantier : son numéro, sa date, et ce qu'il cite (trace d'outils + bloc riche). */
    public record ChantierMessage(UUID id, int chantier, OffsetDateTime at, String trace) {
    }

    /** Le rattachement rendu. */
    public record Link(Integer chantier, Piece.ChantierLink link) {
        static final Link NONE = new Link(null, Piece.ChantierLink.NONE);
    }

    private record Window(OffsetDateTime from, OffsetDateTime to) {
        boolean contains(OffsetDateTime at) {
            return !at.isBefore(from) && (to == null || !at.isAfter(to));
        }
    }

    private final Map<UUID, Integer> byMessage = new HashMap<>();
    private final List<ChantierMessage> traced = new ArrayList<>();
    private final Map<Integer, Window> windows = new LinkedHashMap<>();

    /**
     * @param messages     les messages de chantier du sujet
     * @param openChantier le chantier courant (resté ouvert jusqu'à maintenant), {@code null} si inconnu
     * @param openSince    son ouverture déclarée, {@code null} si inconnue
     */
    public PieceChantierIndex(List<ChantierMessage> messages, Integer openChantier, OffsetDateTime openSince) {
        for (ChantierMessage m : messages) {
            byMessage.put(m.id(), m.chantier());
            if (m.trace() != null && !m.trace().isBlank()) {
                traced.add(m);
            }
            Window w = windows.get(m.chantier());
            if (w == null) {
                windows.put(m.chantier(), new Window(m.at(), m.at()));
            } else {
                OffsetDateTime from = m.at().isBefore(w.from()) ? m.at() : w.from();
                OffsetDateTime to = m.at().isAfter(w.to()) ? m.at() : w.to();
                windows.put(m.chantier(), new Window(from, to));
            }
        }
        if (openChantier != null) {
            Window w = windows.get(openChantier);
            OffsetDateTime from = w == null ? openSince
                    : (openSince != null && openSince.isBefore(w.from()) ? openSince : w.from());
            if (from != null) {
                windows.put(openChantier, new Window(from, null));
            }
        }
    }

    /** Le chantier du message, s'il en a un. */
    public Integer chantierOf(UUID messageId) {
        return messageId == null ? null : byMessage.get(messageId);
    }

    /**
     * Le rattachement d'une pièce : par son message, sinon par l'un des jetons cités dans une trace de chantier,
     * sinon par fenêtre.
     */
    public Link link(UUID messageId, OffsetDateTime at, String... tokens) {
        Integer direct = chantierOf(messageId);
        if (direct != null) {
            return new Link(direct, Piece.ChantierLink.MESSAGE);
        }
        for (String token : tokens) {
            if (token == null || token.length() < 8) {
                continue; // un jeton trop court citerait n'importe quoi
            }
            for (ChantierMessage m : traced) {
                if (m.trace().contains(token)) {
                    return new Link(m.chantier(), Piece.ChantierLink.MESSAGE);
                }
            }
        }
        return byWindow(at);
    }

    /** Le rattachement par fenêtre seule : un seul chantier ouvert à cette date, sinon aucun. */
    public Link byWindow(OffsetDateTime at) {
        if (at == null) {
            return Link.NONE;
        }
        Integer found = null;
        for (Map.Entry<Integer, Window> e : windows.entrySet()) {
            if (e.getValue().contains(at)) {
                if (found != null) {
                    return Link.NONE; // chantiers concurrents : on ne tranche pas
                }
                found = e.getKey();
            }
        }
        return found == null ? Link.NONE : new Link(found, Piece.ChantierLink.WINDOW);
    }
}
