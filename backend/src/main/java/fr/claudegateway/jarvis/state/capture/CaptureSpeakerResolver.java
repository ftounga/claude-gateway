package fr.claudegateway.jarvis.state.capture;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.actions.Interlocuteur;
import fr.claudegateway.atelier.actions.InterlocuteurRepository;
import fr.claudegateway.atelier.actions.InterlocuteurService;

/**
 * <b>La personne d'un message capturé, fixée par la passerelle</b> (F-247 / SF-247-12, D13) : le nom lu est rapproché
 * des interlocuteurs connus du client (nom et variantes F-180, sous {@code user_id} + {@code host_id}), y compris à
 * l'envers (« Dupont, Rémi », « DUPONT Rémi »). Sans rapprochement sûr, le nom lu est gardé tel quel.
 */
@Component
public class CaptureSpeakerResolver {

    /** La personne retenue : {@code known} = rapprochée d'un interlocuteur connu. */
    public record Speaker(String name, boolean known) {
    }

    private final InterlocuteurRepository interlocutors;

    public CaptureSpeakerResolver(InterlocuteurRepository interlocutors) {
        this.interlocutors = interlocutors;
    }

    /** Les interlocuteurs du client, lus une fois par capture. */
    public List<Interlocuteur> known(UUID userId, UUID hostId) {
        return interlocutors.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, hostId);
    }

    /** Rapproche un nom lu ; jamais d'exception. */
    public static Speaker resolve(String read, List<Interlocuteur> known) {
        String raw = read == null ? "" : read.strip();
        if (raw.isEmpty() || known == null || known.isEmpty()) {
            return new Speaker(raw, false);
        }
        for (String candidate : candidates(raw)) {
            String n = InterlocuteurService.normalize(candidate);
            if (n == null || n.isBlank()) {
                continue;
            }
            Interlocuteur match = null;
            for (Interlocuteur i : known) {
                if (i.answersTo(n)) {
                    if (match != null && !match.getId().equals(i.getId())) {
                        return new Speaker(raw, false); // ambigu : on garde le nom lu
                    }
                    match = i;
                }
            }
            if (match != null) {
                return new Speaker(match.getDisplayName(), true);
            }
        }
        return new Speaker(raw, false);
    }

    /** Le nom lu, puis à l'endroit s'il est écrit « Nom, Prénom », puis prénom/nom échangés. */
    static List<String> candidates(String raw) {
        String noParen = raw.replaceAll("\\s*\\([^)]*\\)", "").strip();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(noParen);
        int comma = noParen.indexOf(',');
        if (comma > 0) {
            out.add((noParen.substring(comma + 1) + " " + noParen.substring(0, comma)).strip());
        }
        String[] parts = noParen.split("\\s+");
        if (parts.length == 2) {
            out.add(parts[1] + " " + parts[0]);
        }
        return List.copyOf(out);
    }
}
