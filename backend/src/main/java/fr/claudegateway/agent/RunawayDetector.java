package fr.claudegateway.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Détecteur d'<b>emballement</b> d'un appel en flux (F-172 / SF-172-07) : un raisonnement caché qui
 * produit des jetons sans fin et sans jamais rien rendre de visible.
 *
 * <p>Diagnostic du 2026-11-05 : environ un tour sur cent, sur Opus 5.5 en flux, raisonnait jusqu'au
 * plafond {@code max_tokens} sans émettre un seul caractère — le même message renvoyé réussissait en
 * 30 à 60 s. Aucun appel réussi n'a dépassé 18 104 jetons.</p>
 *
 * <p><b>Règle</b> : un appel est un emballement s'il n'a encore rien produit de visible et que la
 * sortie produite dépasse le seuil. Visible = un morceau de texte, une note de progression lisible, ou
 * tout bloc qui n'est pas du raisonnement (appel d'outil — même en cours d'écriture —, outil serveur,
 * texte). Dès la première activité visible, l'appel n'est <b>plus jamais</b> coupé : on ne plafonne
 * pas le raisonnement d'un appel qui avance.</p>
 *
 * <p><b>Sortie produite</b> : le flux ne la rapporte qu'à la fin ({@code message_delta}). En cours de
 * route, seul le temps est observable : la sortie est estimée par la durée écoulée depuis le début du
 * message multipliée par un débit configuré. La sortie rapportée, si elle arrive, prime quand elle est
 * plus grande.</p>
 *
 * <p>Pur et sans horloge propre : l'appelant passe l'instant (millisecondes). Ni contenu, ni clé n'y
 * sont gardés — seulement des nombres et des types de blocs, pour le journal.</p>
 */
public final class RunawayDetector {

    private final int thresholdTokens;
    private final int tokensPerSecond;
    private final long createdAtMillis;
    private long startMillis = -1L;
    private boolean visible;
    private int reportedOutputTokens;
    private int events;
    private int reasoningChars;
    private int textChars;
    private final List<String> blockTypes = new ArrayList<>();
    private String openToolName;
    private int openToolInputChars;

    /**
     * @param thresholdTokens  jetons sans rien de visible au-delà desquels l'appel s'emballe
     * @param tokensPerSecond  débit d'estimation de la sortie
     * @param nowMillis        instant de création (repli si le message ne s'annonce jamais)
     */
    public RunawayDetector(int thresholdTokens, int tokensPerSecond, long nowMillis) {
        this.thresholdTokens = Math.max(1, thresholdTokens);
        this.tokensPerSecond = Math.max(1, tokensPerSecond);
        this.createdAtMillis = nowMillis;
    }

    /** Le message commence ({@code message_start}) : la génération démarre, l'estimation aussi. */
    public void messageStarted(long nowMillis) {
        events++;
        if (startMillis < 0) {
            startMillis = nowMillis;
        }
    }

    /** Un bloc s'ouvre. Tout bloc qui n'est pas du raisonnement est une activité visible. */
    public void blockStarted(String type, String toolName) {
        events++;
        String safe = type == null || type.isBlank() ? "?" : type;
        blockTypes.add(safe);
        if (!"thinking".equals(safe) && !"redacted_thinking".equals(safe)) {
            visible = true;
        }
        if ("tool_use".equals(safe) || "server_tool_use".equals(safe)) {
            openToolName = toolName == null || toolName.isBlank() ? "?" : toolName;
            openToolInputChars = 0;
        }
    }

    /** Un bloc se ferme : l'outil n'est plus « en cours ». */
    public void blockStopped() {
        events++;
        openToolName = null;
    }

    /** Morceau de texte de la réponse : activité visible dès qu'il est non vide. */
    public void text(String piece) {
        events++;
        if (piece != null && !piece.isEmpty()) {
            textChars += piece.length();
            visible = true;
        }
    }

    /**
     * Morceau de raisonnement. Lisible (non vide), c'est une note de progression ou un résumé : une
     * activité visible. Vide, c'est le raisonnement caché qui continue.
     */
    public void reasoning(String piece) {
        events++;
        if (piece != null && !piece.isEmpty()) {
            reasoningChars += piece.length();
            visible = true;
        }
    }

    /** Morceau de l'entrée JSON d'un outil en cours d'écriture. */
    public void toolInput(String piece) {
        events++;
        if (piece != null) {
            openToolInputChars += piece.length();
        }
        visible = true;
    }

    /** Sortie rapportée par le flux (cumulée), quand il la donne. */
    public void reportedOutput(int outputTokens) {
        reportedOutputTokens = Math.max(reportedOutputTokens, Math.max(0, outputTokens));
    }

    /** Tout autre événement (ping, signature…) : compté, sans effet. */
    public void otherEvent() {
        events++;
    }

    /** Sortie produite à cet instant : rapportée, ou estimée par la durée — la plus grande. */
    public int producedTokens(long nowMillis) {
        long origin = startMillis >= 0 ? startMillis : createdAtMillis;
        long elapsed = Math.max(0L, nowMillis - origin);
        long estimated = elapsed * tokensPerSecond / 1000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(estimated, reportedOutputTokens));
    }

    /** Vrai si l'appel s'emballe : rien de visible et une sortie au-delà du seuil. */
    public boolean runaway(long nowMillis) {
        return !visible && producedTokens(nowMillis) > thresholdTokens;
    }

    /** Vrai si l'appel a déjà produit quelque chose de visible (il ne sera plus coupé). */
    public boolean visible() {
        return visible;
    }

    /** Résumé chiffré pour le journal — jamais de contenu. */
    public String diagnostic(long nowMillis) {
        long origin = startMillis >= 0 ? startMillis : createdAtMillis;
        return "blocs=" + blockTypes + " raisonnement_car=" + reasoningChars + " texte_car=" + textChars
                + " outil_en_cours=" + (openToolName == null ? "aucun" : openToolName + "(" + openToolInputChars
                + " car)") + " jetons_sortie=" + producedTokens(nowMillis) + " (rapportés="
                + reportedOutputTokens + ") durée_ms=" + Math.max(0L, nowMillis - origin)
                + " événements=" + events;
    }
}
