package fr.claudegateway.governance.control;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.governance.GovernanceControl;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.juge.JugeAvis;
import fr.claudegateway.governance.juge.JugeIndependantService;
import fr.claudegateway.governance.juge.JugeMemo;

/**
 * <b>Le juge indépendant</b>, branché en fin de tour (F-94 / SF-94-03).
 *
 * <p>C'est le filet sémantique que le prompt d'origine décrivait et que F-52 n'avait pas livré : un
 * <b>second appel</b> qui compare la carte du poste aux notes des projets, au lieu de lire le
 * marqueur que le modèle pose lui-même.</p>
 *
 * <p><b>Il ne remplace pas {@link JugeFinDeTourControl}, il le complète.</b> Les deux ne jugent pas
 * la même chose et ne peuvent donc pas se contredire :</p>
 *
 * <table border="1">
 *   <caption>Ce que chacun attrape</caption>
 *   <tr><th></th><th>{@code juge-fin-de-tour}</th><th>{@code juge-independant}</th></tr>
 *   <tr><td>Ce qu'il lit</td><td>le <b>marqueur</b> déclaré</td>
 *       <td><b>la carte et les notes</b>, sur la machine</td></tr>
 *   <tr><td>Ce qu'il attrape</td><td>le modèle qui <b>sait</b> qu'il n'a pas promu</td>
 *       <td>le modèle qui a <b>oublié</b> — et qui oubliera aussi de le déclarer</td></tr>
 *   <tr><td>Ce qu'il coûte</td><td>rien</td><td>un appel, et seulement si le tour a écrit</td></tr>
 * </table>
 *
 * <p><b>Il ne coûte que quand il peut servir.</b> C'est le marqueur {@code .infra-dirty} du prompt,
 * transposé : le juge n'est consulté que si le tour a <b>écrit</b>. Et le produit n'a pas besoin
 * d'un fichier témoin — la boucle sait déjà ce que le tour a écrit
 * ({@code AtelierCheckpointContext.writtenPaths}). En poser un sur la machine d'un client serait un
 * fichier de plus à nettoyer, et une seconde source de vérité pour ce qu'on sait déjà.</p>
 *
 * <p><b>Best-effort, jamais une autorité.</b> Le message le dit à celui qui le lit : <i>filet
 * best-effort — à vérifier avant d'agir</i>. Le modèle est invité à vérifier chaque élément, et à
 * <b>l'ignorer</b> s'il ne tient pas. Et F-50 rend la main après trois refus : un juge ne prend
 * jamais le message d'un utilisateur en otage.</p>
 *
 * <p><b>Le repli alerte.</b> Verdict absent ou illisible → on n'a rien vérifié, donc on le
 * <b>signale</b>. C'est l'exigence écrite de la feature : le filet doit échouer bruyamment. En
 * revanche, un juge <b>indisponible</b> — coupe-circuit, fournisseur muet, délai dépassé — laisse
 * passer : un filet qui crie parce que le réseau tousse n'est plus lu.</p>
 */
@Component
public class JugeIndependantControl implements GovernanceControl {

    private static final Logger log = LoggerFactory.getLogger(JugeIndependantControl.class);

    /** Identifiant cité par les paquets. Immuable : un paquet publié le référence. */
    public static final String ID = "juge-independant";

    /** Ce qui dit, en tête de chaque refus, que ce n'est pas une autorité. */
    public static final String BEST_EFFORT = "filet best-effort — à vérifier avant d'agir. ";

    private final JugeIndependantService juge;
    private final JugeMemo memo;
    private final GovernanceMapDestinations destinations;

    public JugeIndependantControl(JugeIndependantService juge, JugeMemo memo,
            GovernanceMapDestinations destinations) {
        this.juge = juge;
        this.memo = memo;
        this.destinations = destinations;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AtelierCheckpointKind kind() {
        return AtelierCheckpointKind.END_OF_TURN;
    }

    @Override
    public String description() {
        return "Compare, par un second appel, la carte du poste aux notes des projets et signale ce "
                + "qui est cité dans les notes et absent de la carte ; ne tourne que si le tour a "
                + "écrit, et alerte si le verdict est illisible.";
    }

    @Override
    public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
        if (context == null || context.userId() == null || context.workspaceId() == null) {
            return AtelierCheckpointVerdict.proceed();
        }
        if (context.machineOffline()) {
            // F-93 / SF-93-04 : la machine ne répond pas — rien à comparer, et aucun aller-retour.
            return AtelierCheckpointVerdict.proceed();
        }
        List<String> ecrits = context.writtenPaths();
        if (ecrits.isEmpty()) {
            // Le tour n'a rien écrit : il n'a rien fait apparaître. Aucun appel.
            return AtelierCheckpointVerdict.proceed();
        }
        if (memo.dejaJuge(context.userId(), context.workspaceId(), ecrits)) {
            // Même question qu'au passage précédent : on ne la repose pas.
            return AtelierCheckpointVerdict.proceed();
        }
        JugeAvis avis = consulter(context);
        memo.retenir(context.userId(), context.workspaceId(), ecrits);
        if (!avis.aSignaler()) {
            return AtelierCheckpointVerdict.proceed();
        }
        String carte = destinations.citedForProject(context.userId(), context.workspaceId());
        return avis.issue() == JugeAvis.Issue.ELEMENTS
                ? AtelierCheckpointVerdict.block(elements(avis, carte))
                : AtelierCheckpointVerdict.block(illisible(carte));
    }

    // -------------------------------------------------------------- internes

    /** Consulte le juge sans jamais laisser une défaillance casser le tour de quelqu'un. */
    private JugeAvis consulter(AtelierCheckpointContext context) {
        try {
            return juge.consulter(context.userId(), context.workspaceId());
        } catch (RuntimeException ex) {
            log.debug("Juge indépendant ignoré ({})", ex.getClass().getSimpleName());
            return JugeAvis.indisponible();
        }
    }

    /** Le message d'une liste : ce qu'on a vu, et <b>le geste</b> pour chacun — vérifier d'abord. */
    private static String elements(JugeAvis avis, String carte) {
        return BEST_EFFORT + "Un second regard signale, en comparant la carte du poste aux notes du "
                + "projet : " + avis.cited() + ". Pour chacun : vérifie-le dans le fichier cité ; "
                + "s'il est durable et réellement absent de la carte, range-le dans l'un de ces "
                + "fichiers (" + carte + ") et trace-le coché dans STATE.md « - [x] <élément> -> "
                + "promu dans <fichier> » ; s'il y figure déjà ou s'il n'est pas durable, ignore-le. "
                + "Reprends ensuite ta réponse.";
    }

    /** Le repli qui alerte : rien n'a été vérifié, et le message dit quoi relire soi-même. */
    private static String illisible(String carte) {
        return BEST_EFFORT + "Le second regard n'a PAS rendu de verdict lisible : rien n'a donc été "
                + "vérifié, et il se peut qu'un élément durable manque à la carte. Relis toi-même "
                + "les fichiers « .md » de ce projet, promeus dans la carte du poste (" + carte
                + ") ce qui est durable et n'y figure pas, trace-le coché dans STATE.md, puis "
                + "reprends ta réponse.";
    }
}
