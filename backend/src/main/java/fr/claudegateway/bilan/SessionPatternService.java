package fr.claudegateway.bilan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Les motifs qui reviennent</b> (F-155 / SF-155-05) — et le renvoi vers le diagnostic du produit.
 *
 * <p>On compte des <b>genres</b>, pas des phrases : un texte qu'on reformule cesserait de se
 * reconnaître d'un bilan à l'autre, et le motif disparaîtrait à la première retouche.</p>
 *
 * <p><b>Aucun lancement automatique.</b> Le diagnostic coûte cher et se lance à la demande — c'est
 * son cadrage. Ici, on dit seulement qu'il serait justifié.</p>
 */
@Service
public class SessionPatternService {

    /** Ce que le diagnostic irait chercher, genre par genre. */
    private static final Map<SessionSuggestion.Kind, String> LEADS = Map.of(
            SessionSuggestion.Kind.CACHE_FROID,
            "ce que l'application place dans la consigne système et qui change à chaque tour — "
                    + "un préfixe instable annule le cache pour tout le monde, pas pour un seul projet",
            SessionSuggestion.Kind.TOUR_HORS_NORME,
            "ce qui fait qu'un tour part chercher beaucoup de contexte : un index absent, une "
                    + "capacité livrée mais jamais déclenchée",
            SessionSuggestion.Kind.OUTIL_DOMINANT,
            "si cet outil a une voie plus courte que l'application n'emprunte pas — arrière-plan, "
                    + "périmètre réduit, résultat déjà connu",
            SessionSuggestion.Kind.ECHECS_REPETES,
            "ce que l'agent ignore de l'état réel de la machine au moment où il s'y prend — une "
                    + "information que l'application pourrait lui donner d'emblée");

    private final SessionBilanRepository repository;
    private final SessionBilanProperties settings;

    public SessionPatternService(SessionBilanRepository repository,
                                 SessionBilanProperties settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /**
     * Les motifs signalables <b>pour ce bilan</b>.
     *
     * <p>Seuls les genres <b>présents dans le bilan qu'on regarde</b> peuvent être signalés : un
     * motif fréquent ailleurs serait hors sujet ici.</p>
     */
    @Transactional(readOnly = true)
    public List<SessionPattern> patternsFor(UUID userId, SessionBilan bilan) {
        Set<SessionSuggestion.Kind> present = kindsOf(bilan.getSuggestionKinds());
        if (present.isEmpty()) {
            return List.of();
        }

        List<SessionBilan> recent = repository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, settings.patternWindow()));
        if (recent.size() <= 1) {
            return List.of(); // le premier bilan ne peut rien répéter
        }

        Map<SessionSuggestion.Kind, Integer> counts = new LinkedHashMap<>();
        for (SessionBilan past : recent) {
            for (SessionSuggestion.Kind kind : kindsOf(past.getSuggestionKinds())) {
                counts.merge(kind, 1, Integer::sum);
            }
        }

        List<SessionPattern> patterns = new ArrayList<>();
        counts.forEach((kind, seen) -> {
            if (present.contains(kind) && seen >= settings.patternThreshold()) {
                patterns.add(new SessionPattern(kind, seen, recent.size(),
                        LEADS.getOrDefault(kind, "la cause dans l'application elle-même")));
            }
        });
        patterns.sort(Comparator.comparingInt(SessionPattern::seen).reversed());
        return List.copyOf(patterns);
    }

    /** Les genres d'un bilan, en clair. Un genre inconnu ou absent est ignoré, sans erreur. */
    static Set<SessionSuggestion.Kind> kindsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of(); // les bilans d'avant SF-155-05 n'en ont pas — on ne réécrit pas le passé
        }
        java.util.LinkedHashSet<SessionSuggestion.Kind> kinds = new java.util.LinkedHashSet<>();
        for (String token : Arrays.asList(raw.split(","))) {
            String clean = token.strip().toUpperCase(Locale.ROOT);
            if (clean.isEmpty()) {
                continue;
            }
            try {
                kinds.add(SessionSuggestion.Kind.valueOf(clean));
            } catch (IllegalArgumentException ignored) {
                // Genre disparu d'une version à l'autre : ignoré. Un bilan ancien reste lisible.
            }
        }
        return kinds;
    }

    /** Les genres d'un verdict, prêts à être gardés en colonne. */
    static String kindsColumn(List<SessionSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return null;
        }
        return suggestions.stream()
                .map(SessionSuggestion::kind)
                .filter(java.util.Objects::nonNull)
                .map(Enum::name)
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
