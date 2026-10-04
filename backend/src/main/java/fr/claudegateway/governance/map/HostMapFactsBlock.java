package fr.claudegateway.governance.map;

import java.time.LocalDate;
import java.util.List;

import fr.claudegateway.governance.map.index.HostMapFact;
import fr.claudegateway.governance.map.index.HostMapSearch;

/**
 * <b>Le bloc de faits joint au message du tour</b>, à partir de l'index (F-174 / SF-174-03, D6).
 *
 * <p>Même en-tête et même consigne de péremption que F-137 / F-139 : l'agent reconnaît le bloc. Ce
 * qui change : chaque fait cite sa <b>section</b> en plus de son fichier, et les bornes sont celles
 * de D6 (20 faits, 6 000 caractères par défaut). Le bloc rejoint la <b>consigne du tour</b>, jamais le
 * bloc système (préfixe de cache stable, F-171).</p>
 *
 * <p>La borne de caractères ne coupe jamais un fait en deux : un fait qui ne tient plus n'est pas
 * joint, et le bloc le dit.</p>
 */
public final class HostMapFactsBlock {

    /** Longueur d'un fait repris : une ligne de carte, pas un chapitre (comme F-137). */
    static final int MAX_FACT_CHARS = 300;

    static final String MORE_NOTICE =
            "… d'autres faits de la carte répondent aussi : interroge-la avec carte_chercher plutôt que de la fouiller.\n";

    /** La consigne des pièges, ajoutée seulement s'il y en a un (SF-174-04). */
    static final String PITFALL_NOTICE =
            "Ce qui est marqué « piège » est une erreur déjà commise ou un comportement trompeur sur ce "
                    + "poste : tiens-en compte AVANT d'agir.\n";

    private HostMapFactsBlock() {
    }

    /**
     * Compose le bloc, ou {@code null} s'il n'y a rien à joindre.
     *
     * @param today      la date du jour (marque « à re-vérifier »), ou {@code null}
     * @param maxAgeDays âge au-delà duquel un fait est dit à re-vérifier ({@code <= 0} : jamais)
     * @param maxChars   borne du bloc entier
     */
    public static String render(List<HostMapSearch.Hit> hits, LocalDate today, int maxAgeDays,
            int maxChars) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        boolean anyStale = false;
        boolean truncated = false;
        boolean anyPitfall = false;
        int budget = maxChars - HostFactLookup.HEADER.length() - HostFactLookup.STALE_NOTICE.length()
                - PITFALL_NOTICE.length() - MORE_NOTICE.length();
        for (HostMapSearch.Hit hit : hits) {
            HostMapFact fact = hit.fact();
            boolean stale = HostFactLookup.isStale(fact.getText(), today, maxAgeDays);
            boolean pitfall = HostMapFact.PIEGE.equals(fact.getKind());
            String line = line(fact, marks(fact, today) + (stale ? HostFactLookup.STALE_MARK : ""));
            if (body.length() + line.length() > budget) {
                truncated = true;
                break;
            }
            anyStale |= stale;
            anyPitfall |= pitfall;
            body.append(line);
        }
        if (body.length() == 0) {
            return null;
        }
        return HostFactLookup.HEADER + (anyPitfall ? PITFALL_NOTICE : "")
                + (anyStale ? HostFactLookup.STALE_NOTICE : "") + body + (truncated ? MORE_NOTICE : "");
    }

    /**
     * Les marques de nature d'un fait (SF-174-04) : {@code ⟨piège⟩}, ou
     * {@code ⟨échéance AAAA-MM-JJ — dans N j⟩} / {@code — dépassée depuis N j}.
     */
    public static String marks(HostMapFact fact, LocalDate today) {
        if (HostMapFact.PIEGE.equals(fact.getKind())) {
            return "  ⟨piège⟩";
        }
        if (HostMapFact.ECHEANCE.equals(fact.getKind()) && fact.getDueOn() != null) {
            StringBuilder mark = new StringBuilder("  ⟨échéance ").append(fact.getDueOn());
            if (today != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, fact.getDueOn());
                if (days > 0) {
                    mark.append(" — dans ").append(days).append(" j");
                } else if (days == 0) {
                    mark.append(" — aujourd'hui");
                } else {
                    mark.append(" — dépassée depuis ").append(-days).append(" j");
                }
            }
            return mark.append('⟩').toString();
        }
        return "";
    }

    /** Une ligne du bloc : le fait, sa source, et ses marques. */
    public static String line(HostMapFact fact, String marks) {
        String text = fact.getText();
        if (text.length() > MAX_FACT_CHARS) {
            text = text.substring(0, MAX_FACT_CHARS) + "…";
        }
        StringBuilder line = new StringBuilder("- ").append(text).append("  [").append(fact.getPath());
        if (fact.getHeading() != null && !fact.getHeading().isBlank()) {
            line.append(" § ").append(fact.getHeading().strip());
        }
        return line.append(']').append(marks == null ? "" : marks).append('\n').toString();
    }
}
