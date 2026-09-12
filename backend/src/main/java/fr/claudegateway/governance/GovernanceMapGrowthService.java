package fr.claudegateway.governance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.governance.dto.GovernanceMapGainView;
import fr.claudegateway.governance.dto.GovernanceMapGrowthView;

/**
 * <b>Ce que la carte a gagné</b>, constaté d'une lecture à l'autre (F-93 / SF-93-02).
 *
 * <p>SF-92-02 compte les faits d'une carte, et l'écran les affiche. Un compteur ne montre pourtant
 * <b>aucune augmentation</b> : il dit 16 aujourd'hui et 16 demain, et rien ne distingue une carte qui
 * grossit d'une carte morte. Ce service retient ce qu'une lecture a vu, pour que la suivante puisse
 * dire ce qui a changé — et pour que la promotion, qui coûte un geste à chaque tour, rende enfin
 * quelque chose de visible à celui qui la fait.</p>
 *
 * <p><b>On constate, on ne croit pas sur parole.</b> Ce qui est mesuré est le <b>delta observé</b> du
 * nombre de faits, jamais ce qu'un modèle a déclaré avoir promu : le cadrage a déjà jugé
 * l'auto-déclaration insuffisante — un modèle qui oublie de promouvoir oubliera de le déclarer. Le
 * delta, lui, vaut quelle que soit la main qui a écrit : un tour d'agent, le terminal du poste, ou
 * l'utilisateur dans son éditeur.</p>
 *
 * <p><b>Un compte incomplet n'est pas un compte.</b> Les fichiers absents, illisibles ou dont la
 * lecture a été <b>tronquée</b> ne sont pas observés : l'appelant les écarte. Une lecture tronquée
 * sous-estime, et retenue comme référence elle ferait apparaître un gain fantôme à la première
 * lecture complète.</p>
 *
 * <p><b>Il n'empêche jamais de lire la carte.</b> Toute panne d'écriture est absorbée ici et le
 * relevé est rendu sans son bloc de croissance. Perdre une trace de croissance coûte une phrase ;
 * perdre la carte coûterait la feature.</p>
 *
 * <p><b>Rien quand il n'y a rien à dire</b> : le bloc est {@code null} tant qu'aucune observation
 * n'existe. Un « +0 » affiché chaque jour serait pire que rien — il apprendrait qu'on ne gagne
 * rien.</p>
 */
@Service
public class GovernanceMapGrowthService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceMapGrowthService.class);

    /**
     * Gains récents rendus. Autant que de fichiers de carte livrés par le paquet du produit : au-delà,
     * ce n'est plus un fait qu'on constate, c'est une liste.
     */
    public static final int MAX_RECENT = 6;

    private final GovernanceMapGrowthRecorder recorder;

    public GovernanceMapGrowthService(GovernanceMapGrowthRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Ce qu'une lecture vient de voir d'un fichier de carte.
     *
     * @param path  le fichier
     * @param title son titre, tel que la carte le porte
     * @param facts le nombre de faits comptés
     */
    public record Observation(String path, String title, int facts) {
    }

    /**
     * Retient ce qu'une lecture a vu, et rend ce que la carte a gagné.
     *
     * @return le bloc de croissance, ou {@code null} s'il n'y a rien à dire
     */
    public GovernanceMapGrowthView observe(UUID userId, GovernanceHostRef host,
            List<Observation> observations) {
        if (userId == null || host == null || observations == null || observations.isEmpty()) {
            return null;
        }
        try {
            return view(recorder.record(userId, host, observations), observations);
        } catch (RuntimeException ex) {
            log.debug("Croissance de la carte non retenue ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    // -------------------------------------------------------------- internes

    /** Le bloc rendu à l'écran : une phrase, et jusqu'à six lignes. */
    private static GovernanceMapGrowthView view(List<GovernanceMapGrowth> rows,
            List<Observation> observations) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, String> titles = new HashMap<>();
        for (Observation observation : observations) {
            titles.put(observation.path(), observation.title());
        }
        OffsetDateTime since = null;
        int sinceFacts = 0;
        int facts = 0;
        List<GovernanceMapGainView> gains = new ArrayList<>();
        for (GovernanceMapGrowth row : rows) {
            sinceFacts += row.getFirstFacts();
            facts += row.getFacts();
            if (since == null || row.getFirstSeenAt().isBefore(since)) {
                since = row.getFirstSeenAt();
            }
            if (row.getLastGain() != null && row.getLastGainAt() != null) {
                gains.add(new GovernanceMapGainView(row.getPath(),
                        titles.getOrDefault(row.getPath(), row.getPath()), row.getLastGain(),
                        row.getLastGainAt()));
            }
        }
        gains.sort(Comparator.comparing(GovernanceMapGainView::gainedAt).reversed());
        return new GovernanceMapGrowthView(since, sinceFacts,
                // Jamais négatif : une carte rangée n'a pas perdu un savoir.
                Math.max(0, facts - sinceFacts),
                List.copyOf(gains.subList(0, Math.min(gains.size(), MAX_RECENT))));
    }
}
