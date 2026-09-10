package fr.claudegateway.governance.control;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;

/**
 * <b>Un commit ne dit pas quel outil l'a écrit</b> (F-52 / SF-52-01) — le premier verrou
 * déterministe du produit.
 *
 * <p>C'est la moitié <i>mécanique</i> de la règle des livrables : rien de ce qui sort d'un projet ne
 * doit désigner le modèle qui l'a produit. L'autre moitié — le style, le ton, les tournures — reste
 * une <b>règle</b>, parce qu'elle ne se vérifie pas sans juger du sens. Un message de commit, lui,
 * se vérifie : les traces qu'un assistant y laisse sont des <b>marqueurs fixes</b>, et une liste
 * close suffit à les refuser.</p>
 *
 * <p><b>Pourquoi ce point d'accroche.</b> Un commit se fait par {@code bash}. La porte de
 * confirmation (SF-38-08) demande une autorisation sans rien lire de la commande, et se débraye par
 * projet (SF-38-20) : elle ne peut pas porter ce refus. D'où le point {@code BEFORE_COMMAND} de
 * SF-52-01, atteint avant toute émission — la commande refusée n'a jamais tourné.</p>
 *
 * <p><b>Deux questions indépendantes</b>, et c'est délibéré : la commande <i>fabrique-t-elle un
 * commit</i>, et le texte de la commande <i>porte-t-il un marqueur</i> ? On ne cherche pas à isoler
 * le message à l'intérieur de la ligne : les guillemets, les {@code $(…)}, les fichiers passés par
 * {@code -F} et les continuations rendraient l'extraction fragile, et un contrôle fragile sur une
 * ligne de commande est un contrôle qu'on contourne sans le vouloir. Le prix est une conjonction
 * refusée par excès ({@code git commit -m "propre" && echo "Co-Authored-By: Claude"}), et c'est le
 * bon sens du refus.</p>
 *
 * <p><b>La liste est close, jamais heuristique.</b> Un commit qui cite « Anthropic » dans une phrase,
 * ou un projet qui s'appelle {@code claude-gateway}, passe. Un verrou qui hurle à tort est un verrou
 * qu'on décroche (arbitrage A6 du cadrage).</p>
 */
@Component
public class CommitSansTraceLlmControl implements GovernanceControl {

    /** Identifiant cité par les paquets. Immuable : un paquet publié le référence. */
    public static final String ID = "commit-sans-trace-llm";

    /**
     * Un marqueur refusé, et le mot qui sert à le nommer au modèle.
     *
     * @param pattern la reconnaissance, insensible à la casse
     * @param label   ce que le message correctif nomme — jamais « un motif interdit », qui ne se
     *                corrige pas
     */
    private record Marker(Pattern pattern, String label) {
    }

    /**
     * Les marqueurs refusés. Liste <b>close</b> et volontairement courte : chaque entrée est une
     * trace que les assistants ajoutent d'eux-mêmes, jamais un mot du vocabulaire courant.
     */
    private static final List<Marker> MARKERS = List.of(
            new Marker(Pattern.compile("co-authored-by:\\s*claude", Pattern.CASE_INSENSITIVE),
                    "une co-signature « Co-Authored-By » au nom d'un assistant"),
            new Marker(Pattern.compile("generated with[^\\n]{0,24}claude", Pattern.CASE_INSENSITIVE),
                    "une mention « Generated with… »"),
            new Marker(Pattern.compile("claude-session\\s*:", Pattern.CASE_INSENSITIVE),
                    "un lien de session d'assistant"),
            new Marker(Pattern.compile("noreply@anthropic\\.com", Pattern.CASE_INSENSITIVE),
                    "une adresse de co-auteur de fournisseur"),
            new Marker(Pattern.compile("\\x{1F916}"),
                    "l'émoji robot"));

    /**
     * Ce qui coupe une ligne de commande en invocations. On ne cherche pas à interpréter un shell —
     * seulement à savoir si l'une des invocations est un {@code git commit}.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[;&|\\n\\r()]+");

    /** Options de {@code git} qui prennent une valeur séparée, à sauter avant la sous-commande. */
    private static final List<String> VALUED_OPTIONS = List.of("-C", "-c", "--git-dir", "--work-tree",
            "--namespace", "--exec-path");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AtelierCheckpointKind kind() {
        return AtelierCheckpointKind.BEFORE_COMMAND;
    }

    @Override
    public String description() {
        return "Refuse un git commit dont le message porte une trace d'assistant "
                + "(co-signature, mention « Generated with », lien de session, émoji robot).";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        String command = context == null ? null : context.command();
        if (command == null || command.isBlank() || !createsCommit(command)) {
            return AtelierCheckpointVerdict.proceed();
        }
        for (Marker marker : MARKERS) {
            if (marker.pattern().matcher(command).find()) {
                return AtelierCheckpointVerdict.block("le message de commit porte " + marker.label()
                        + ". Réécris-le sans cette mention — un livrable ne désigne jamais l'outil "
                        + "qui l'a produit — puis relance la commande.");
            }
        }
        return AtelierCheckpointVerdict.proceed();
    }

    /** Vrai si l'une des invocations de la ligne est un {@code git commit} (y compris {@code --amend}). */
    static boolean createsCommit(String command) {
        for (String segment : SEPARATORS.split(command)) {
            if (isGitCommit(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vrai si ce fragment invoque {@code git} avec la sous-commande {@code commit}.
     *
     * <p>Les options de {@code git} qui précèdent la sous-commande sont sautées, avec leur valeur
     * quand elles en prennent une séparée ({@code git -C /chemin commit}). C'est ce qui distingue un
     * vrai commit d'un {@code git log --grep=commit}, dont la sous-commande est {@code log}.</p>
     */
    private static boolean isGitCommit(String segment) {
        String[] tokens = segment.trim().split("\\s+");
        int index = 0;
        while (index < tokens.length && isEnvironmentAssignment(tokens[index])) {
            index++;
        }
        if (index >= tokens.length || !isGitExecutable(tokens[index])) {
            return false;
        }
        index++;
        while (index < tokens.length) {
            String token = tokens[index];
            if (!token.startsWith("-")) {
                return "commit".equals(token);
            }
            index += VALUED_OPTIONS.contains(token) ? 2 : 1;
        }
        return false;
    }

    /** {@code FOO=bar git commit …} : le préfixe d'environnement n'est pas la commande. */
    private static boolean isEnvironmentAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0 || token.startsWith("-")) {
            return false;
        }
        // Un chemin AVANT le `=` fait de ce jeton un exécutable, pas une affectation.
        int slash = token.indexOf('/');
        return slash < 0 || slash > equals;
    }

    /** {@code git}, {@code /usr/bin/git}, {@code git.exe} — le nom, quel que soit le chemin. */
    private static boolean isGitExecutable(String token) {
        String name = token.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith("\"") || name.endsWith("'")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.startsWith("\"") || name.startsWith("'")) {
            name = name.substring(1);
        }
        return "git".equals(name) || "git.exe".equals(name);
    }
}
