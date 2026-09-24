package fr.claudegateway.diagnostic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;

/**
 * <b>La lecture raisonnée du code</b> (F-157 / SF-157-04) : la seule partie du diagnostic qui
 * <b>coûte</b>.
 *
 * <p><b>Pourquoi elle existe.</b> Une optimisation absente ne laisse <b>aucune trace</b> — c'est son
 * absence qu'il faut voir. Aucun compteur ne la révèle, aucun témoin non plus : un témoin prouve
 * qu'un appel <i>est là</i>, pas qu'un meilleur chemin <i>manque</i>.</p>
 *
 * <p><b>On relaie, on n'analyse pas.</b> Claude sait lire du code ; écrire un analyseur statique
 * maison serait réimplémenter une capacité du fournisseur ({@code PROJECT.md} §3.3), et le résultat
 * serait plus faible. L'appel passe par l'interface abstraite {@link AIProvider} — aucune
 * dépendance directe à Anthropic.</p>
 *
 * <p><b>Et le résultat est une hypothèse, jamais un verdict</b> — le type le dit, l'écran le
 * répétera.</p>
 */
@Component
public class ReasonedReader {

    private static final Logger log = LoggerFactory.getLogger(ReasonedReader.class);

    /**
     * La consigne, <b>fixe</b> : une question stable rend deux lectures comparables. Elle demande
     * une piste, pas un rapport, et interdit l'affirmation.
     */
    static final String SYSTEM = """
            Tu lis le code d'une application pour répondre à UNE question précise sur UNE de ses \
            capacités. Tu ne réécris rien, tu ne proposes aucun correctif appliqué : tu expliques.

            Réponds en français, en trois parties courtes :
            1. CE QUI EMPÊCHE la capacité de se déclencher, si tu le vois dans le code montré.
            2. L'OPTIMISATION QUI N'A PAS ÉTÉ FAITE ici, s'il y en a une — c'est-à-dire un meilleur \
            chemin que le code ne prend pas. C'est la question la plus utile : une optimisation \
            absente ne laisse aucune trace, c'est son absence qu'il faut voir.
            3. CE QUE TU NE PEUX PAS SAVOIR à partir du code montré.

            Tu ne vois qu'un EXTRAIT. Dis « je ne peux pas le savoir ici » plutôt que de supposer : \
            ta réponse sera présentée comme une HYPOTHÈSE à vérifier, et une hypothèse fausse coûte \
            plus cher qu'une absence de réponse.""";

    private final AIProvider provider;
    private final DiagnosticProperties settings;

    public ReasonedReader(AIProvider provider, DiagnosticProperties settings) {
        this.provider = provider;
        this.settings = settings;
    }

    /**
     * Lit le code d'<b>une</b> capacité et rend une hypothèse.
     *
     * @param finding le constat déjà établi — il cadre la question
     * @param sources les fichiers déjà lus (SF-157-02) ; aucune lecture nouvelle
     * @return l'hypothèse, ou vide quand il n'y avait rien à lire ou que le fournisseur a échoué
     */
    public Optional<SourceHypothesis> read(CapabilityFinding finding,
                                           Map<String, SourceRead> sources) {
        ProductCapability capability = CapabilityMap.byId(finding.capabilityId()).orElse(null);
        if (capability == null) {
            return Optional.empty();
        }

        StringBuilder code = new StringBuilder();
        boolean truncated = false;
        for (String path : capability.paths()) {
            SourceRead source = sources == null ? null : sources.get(path);
            if (source == null || !source.isRead()) {
                continue;
            }
            String body = source.content();
            int room = settings.maxCodeChars() - code.length();
            if (room <= 0) {
                truncated = true;
                break;
            }
            if (body.length() > room) {
                body = body.substring(0, room);
                truncated = true;
            }
            code.append("=== ").append(path).append(" ===\n").append(body).append("\n\n");
        }

        if (code.isEmpty()) {
            // AUCUN appel fournisseur : pas de fichier, pas de question, pas de coût.
            return Optional.empty();
        }

        String question = question(capability, finding, code.toString(), truncated);
        try {
            ChatCompletionResult result = provider.complete(new ChatCompletionRequest(
                    settings.model(),
                    List.of(new ChatMessage(fr.claudegateway.ai.ChatRole.USER, question)),
                    List.of(), null, SYSTEM, settings.maxAnswerTokens(), false));
            if (result == null || result.content() == null || result.content().isBlank()) {
                return Optional.empty(); // une réponse vide n'est pas une hypothèse vide
            }
            return Optional.of(new SourceHypothesis(capability.id(), result.content().strip(),
                    truncated, result.model(), result.inputTokens(), result.outputTokens()));
        } catch (RuntimeException e) {
            // L'hypothèse manque ; le diagnostic reste. Aucun constat n'est modifié.
            log.warn("Lecture raisonnée impossible pour « {} »", capability.id(), e);
            return Optional.empty();
        }
    }

    /** La question, qui porte tout ce que le diagnostic sait déjà — pour ne pas le redemander. */
    private String question(ProductCapability capability, CapabilityFinding finding, String code,
                            boolean truncated) {
        return "Capacité : " + capability.name() + " (« " + capability.id() + " »).\n"
                + "Ce qu'elle évite : " + capability.avoids() + "\n"
                + "Sa condition d'activation : " + capability.activates() + "\n"
                + "Ce que le diagnostic a constaté : " + finding.why() + "\n"
                + (truncated
                        ? "\nATTENTION : le code ci-dessous est TRONQUÉ (plafond de "
                                + settings.maxCodeChars() + " caractères). Tiens-en compte.\n"
                        : "")
                + "\nCode :\n\n" + code;
    }
}
