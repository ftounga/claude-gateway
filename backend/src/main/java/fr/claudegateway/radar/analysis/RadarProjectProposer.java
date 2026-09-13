package fr.claudegateway.radar.analysis;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSubjectProjectService;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Propose le lien d'un sujet à un projet</b> (F-106 / SF-106-06) quand un échange qui le prouve
 * <b>nomme</b> un projet du poste — son nom, ou le nom de son dossier.
 *
 * <p><b>Une question, pas une certitude</b> : la ligne naît {@code PROPOSED} et n'a aucun effet tant que
 * l'utilisateur n'a pas répondu ; une paire déjà vue (confirmée ou refusée) n'est jamais reproposée.</p>
 *
 * <p><b>Déterministe</b> : une correspondance de mots entiers, casse et accents ignorés, et aucun appel au
 * fournisseur de plus. Pour borner le bruit, un nom de moins de {@value #MIN_NAME_LENGTH} caractères n'est
 * pas lu, ni le nom du poste lui-même — un projet à la racine porte le nom du client, que tout échange
 * cite.</p>
 */
@Component
public class RadarProjectProposer {

    static final int MIN_NAME_LENGTH = 3;

    private final RadarSubjectProjectService links;
    private final RunnerHostRepository hosts;

    public RadarProjectProposer(RadarSubjectProjectService links, RunnerHostRepository hosts) {
        this.links = links;
        this.hosts = hosts;
    }

    /**
     * Propose les liens du sujet d'après ces textes.
     *
     * @param scope     utilisateur et poste de l'analyse
     * @param subjectId sujet écrit
     * @param texts     messages qui prouvent le sujet et titres de leurs échanges
     * @return le nombre de propositions créées
     */
    public int propose(RadarScope scope, UUID subjectId, Collection<String> texts) {
        String haystack = normalize(String.join("\n", texts.stream().filter(Objects::nonNull).toList()));
        if (haystack.isBlank()) {
            return 0;
        }
        List<Workspace> projects = links.projectsOf(scope);
        if (projects.isEmpty()) {
            return 0;
        }
        String hostName = hosts.findByIdAndUserId(scope.hostId(), scope.userId())
                .map(host -> normalize(host.getName())).orElse("");
        int created = 0;
        for (Workspace project : projects) {
            if (namesOf(project, hostName).stream().anyMatch(name -> mentions(haystack, name))
                    && links.propose(scope, subjectId, project.getId())) {
                created++;
            }
        }
        return created;
    }

    /** Les noms lisibles d'un projet : son nom et son dossier, normalisés, sans les trop courts ni le poste. */
    static Set<String> namesOf(Workspace project, String normalizedHostName) {
        Set<String> names = new LinkedHashSet<>();
        names.add(normalize(project.getName()));
        String path = project.getProjectPath();
        if (path != null && !path.isBlank()) {
            String[] segments = path.replace('\\', '/').split("/");
            names.add(normalize(segments[segments.length - 1]));
        }
        names.removeIf(name -> name.length() < MIN_NAME_LENGTH || name.equals(normalizedHostName));
        return names;
    }

    /** Vrai si le nom figure en mot entier : ni précédé ni suivi d'une lettre ou d'un chiffre. */
    static boolean mentions(String normalizedText, String normalizedName) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizedName) + "(?![\\p{L}\\p{N}])")
                .matcher(normalizedText).find();
    }

    /** Minuscules, sans accents, espaces réduits. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
