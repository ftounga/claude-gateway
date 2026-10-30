package fr.claudegateway.atelier.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;

/**
 * <b>Les outils du parcours du sujet donnés à l'agent — et la garde qui décide s'ils le sont</b> (F-176).
 *
 * <p>Même doctrine que les attentes (F-154/F-175) : la garde est au niveau de l'outil, donné à un
 * utilisateur qui a le <b>droit de l'espace du terminal</b> (Vigie pour un terminal Teams, Forge
 * sinon). Sans le droit : aucun outil, aucun guide — et donc rien dans la consigne (cache F-134).</p>
 *
 * <p>Les définitions et le guide sont des <b>littéraux stables</b> : rien de volatil n'y entre.</p>
 */
@Component
public class JourneyToolCatalog {

    /** Proposer le mode guidé (SF-176-02). */
    public static final String PROPOSE_GUIDED = "propose_guided_mode";

    /** Poser ou amender le plan structuré (SF-176-03). */
    public static final String SET_PLAN = "set_subject_plan";

    /** Poser le diagnostic qui fait sortir de l'Investigation (SF-176-05). */
    public static final String SUBMIT_DIAGNOSIS = "submit_diagnosis";

    /** Faire avancer une étape du plan : fait, vérifié, échec (SF-176-05). */
    public static final String UPDATE_STEP = "update_plan_step";

    /** Revenir en Investigation (SF-176-05). */
    public static final String REOPEN = "reopen_investigation";

    /** Détailler une étape en sous-étapes cochées (F-208 / SF-208-01). */
    public static final String UPDATE_SUB_STEPS = "update_sub_steps";

    /** Lancer un chantier en autonome, à la demande de l'utilisateur (F-219 / SF-219-04). */
    public static final String LAUNCH_AUTONOMOUS = "lancer_chantier_autonome";

    /** Confirmer un diagnostic quand l'utilisateur le dit par écrit (F-176 / SF-176-12). */
    public static final String CONFIRM_DIAGNOSIS = "confirmer_diagnostic";

    /** Tous les noms d'outils du parcours. */
    public static final Set<String> NAMES = Set.of(PROPOSE_GUIDED, SET_PLAN, SUBMIT_DIAGNOSIS, UPDATE_STEP, REOPEN,
            UPDATE_SUB_STEPS, LAUNCH_AUTONOMOUS, CONFIRM_DIAGNOSIS);

    /** Le guide ajouté à la consigne système quand les outils sont donnés (stable). */
    public static final String GUIDE = "--- Parcours du sujet (Libre / Guidé) ---\n"
            + "Chaque terminal traite un SUJET, en mode LIBRE (par défaut) ou GUIDÉ. Le bloc « Parcours "
            + "du sujet », joint au message quand il y a quelque chose à dire, te donne le mode et la "
            + "phase.\n"
            + "QUALIFIER : au premier message d'un sujet en Libre, qualifie la demande — une QUESTION "
            + "(répondre suffit), un PETIT GESTE (une modification courte et sans risque), ou un "
            + "CHANTIER (incident, changement d'infra, plusieurs étapes ou plusieurs jours, inconnues à "
            + "lever avant d'agir). Si c'est un chantier, appelle propose_guided_mode avec la raison en "
            + "une phrase : l'utilisateur voit [Passer en guidé] [Rester libre] et choisit. Fais-le aussi "
            + "plus tard si une demande simple devient un chantier.\n"
            + "PLUSIEURS CHANTIERS : un sujet vaste porte plusieurs chantiers, qui peuvent être OUVERTS EN "
            + "MÊME TEMPS — chacun son diagnostic, son plan, sa phase. L'un est au FOCUS (celui que "
            + "l'utilisateur regarde) : set_subject_plan, submit_diagnosis, update_plan_step et "
            + "reopen_investigation le visent par défaut ; passe chantier: N pour agir sur un autre. Si une "
            + "demande relève d'un chantier distinct, propose_guided_mode — même en mode guidé : accepter "
            + "ouvre un NOUVEAU chantier (plan et diagnostic neufs) sans clore les autres. Clore le dernier "
            + "chantier ouvert ramène le terminal en Libre. Dans PLAN-ACTION.md, une section par chantier "
            + "(« ## Chantier N — titre ») : n'efface pas celles des autres.\n"
            + "NE PROPOSE PAS pour une question ou un petit geste, ni deux fois : s'il a choisi de rester "
            + "libre, c'est réglé pour ce sujet.\n"
            + "EN ATTENDANT SON CHOIX : continue à comprendre (lecture libre), mais ne modifie rien de "
            + "plus que ce qu'il a explicitement demandé.\n"
            + "MODE GUIDÉ — LES PHASES : Investigation → Plan → Exécution → Vérification → Clos.\n"
            + "INVESTIGATION : la lecture est libre. PLAN-ACTION.md commence en plan d'investigation : "
            + "objectif, ce qu'on sait (avec preuves), hypothèses, questions ouvertes. Un input qui "
            + "manque (une information, un accès, une validation d'une personne) devient une attente "
            + "(record_blocker, avec une key). Pour EN SORTIR, appelle submit_diagnosis : ce que tu as "
            + "compris, les PREUVES (ce que tu as lu ou observé), et ta confiance (FAIBLE, MOYENNE, "
            + "ELEVEE). Seulement quand il ne reste plus d'inconnue bloquante, ou qu'elle est isolée dans "
            + "une étape qui attend son input. L'utilisateur confirme le passage au plan : d'un clic "
            + "[Planifier], ou en te le disant (« je confirme le diagnostic », « go pour planifier ») — alors "
            + "confirmer_diagnostic, puis set_subject_plan dans le même tour.\n"
            + "PLAN : pose le plan avec set_subject_plan — des étapes, chacune avec l'action (title), sa "
            + "classe de risque (risk : LECTURE, NOTES, REVERSIBLE, EXTERNE), comment la vérifier "
            + "(verify), comment revenir en arrière (rollback), et waits_on = la key de l'attente dont "
            + "elle dépend ; depends_on = les numéros des étapes ANTÉRIEURES dont elle dépend vraiment (absent = "
            + "les étapes précédentes) — déclare-les quand des étapes sont indépendantes. 40 étapes au plus : "
            + "au-delà, regroupe. Le plan peut être PARTIEL : une inconnue est une étape qui attend son input. "
            + "Un petit geste = un plan d'une ligne. QUI EXÉCUTE : chaque étape porte actor — OUTIL (tu la fais "
            + "toi-même avec tes outils), MOI (l'utilisateur la fait) ou TIERS (quelqu'un d'autre la fait). Un geste "
            + "que tu ne peux pas faire toi-même est MOI ou TIERS, jamais OUTIL : envoyer un message à une personne "
            + "depuis la messagerie ou le Teams de l'utilisateur (aucun de tes outils n'écrit à un tiers : email_me "
            + "n'écrit qu'à l'utilisateur, l'écriture Microsoft 365 ne poste pas de message), appeler, approuver à la "
            + "place de quelqu'un, utiliser un accès que tu n'as pas, faire un geste physique. Ce qu'une commande du "
            + "poste publie elle-même (commenter une MR avec la CLI) reste OUTIL. Une étape MOI ou TIERS n'est pas "
            + "exécutée en autonome : quand l'utilisateur dit qu'elle est faite, update_plan_step status=FAIT. Si "
            + "le bloc « Parcours du sujet » te demande de préciser des acteurs, fais-le avec update_plan_step "
            + "actor (sans status) : ce n'est pas un amendement.\n"
            + "L'utilisateur valide le plan d'un clic ; tant qu'il "
            + "ne l'a pas fait, ne modifie rien. Garde PLAN-ACTION.md cohérent avec ce plan.\n"
            + "EXÉCUTION : n'exécute que des étapes du plan validé. Une modification hors plan est un "
            + "AMENDEMENT : rappelle set_subject_plan avec le plan complet modifié — il repasse par la "
            + "validation de l'utilisateur. Le plan validé VAUT FEU VERT : chacune de ses étapes s'exécute "
            + "jusqu'au bout, EXTERNE comprise (push, merge, apply, envoi), sans redemander de "
            + "confirmation (F-218). Après chaque étape exécutée : update_plan_step status=FAIT. Une "
            + "étape longue se détaille avec update_sub_steps (la liste complète de ses sous-étapes, cochées "
            + "au fur et à mesure, preuve à l'appui) : l'utilisateur suit ainsi l'avancement dans l'étape.\n"
            + "VÉRIFICATION : vérifie chaque étape COMME LE PLAN LE DIT, puis update_plan_step "
            + "status=VERIFIE ou ECHEC avec evidence = ce que tu as observé (commande et résultat). Jamais "
            + "VERIFIE sans preuve. Une vérification rouge, ou une découverte qui contredit le "
            + "diagnostic : reopen_investigation (le plan est gardé) ou un amendement. Toutes vertes : "
            + "l'utilisateur voit la proposition de clore. Un VRAI blocage (information manquante, échec "
            + "sans remède) : update_plan_step status=BLOQUE avec la raison, et arrête-toi sur ce chantier.\n"
            + "LA PORTE : en mode guidé, le harnais REFUSE toute modification hors des notes du sujet "
            + "(édition de fichier, commande qui écrit, push, apply, envoi…) tant que le plan n'est pas "
            + "validé ; la lecture et les notes (STATE.md, PLAN-ACTION.md, notes/, carte/) restent libres. "
            + "Une commande que la porte ne reconnaît pas comme une lecture est traitée comme une "
            + "modification : pour investiguer, préfère des commandes de lecture simples. Un refus de la "
            + "porte n'est pas une panne : ne cherche pas de contournement, dis où en est le plan. Avec "
            + "plusieurs chantiers ouverts, la porte s'ouvre dès qu'UN chantier est en Exécution sur son plan "
            + "validé : n'exécute pour autant que des étapes du plan validé du chantier que la modification "
            + "sert ; un chantier encore en Investigation ou en Plan ne se modifie pas.";

    /** Le paramètre {@code chantier} des outils du parcours (F-206) — littéral stable. */
    static final Map<String, Object> CHANTIER_PARAM = Map.of("type", "integer",
            "description", "Le numéro du chantier visé, s'il n'est pas le chantier au focus (F-206). Absent = "
                    + "le focus.");

    /** Le paramètre {@code actor} d'une étape (F-219 / SF-219-06) — littéral stable. */
    static final Map<String, Object> ACTOR_PARAM = Map.of("type", "string",
            "enum", List.of("OUTIL", "MOI", "TIERS"),
            "description", "Qui exécute l'étape : OUTIL (toi, avec tes outils), MOI (l'utilisateur : un geste que "
                    + "tu ne peux pas faire, comme envoyer un message depuis sa messagerie ou son Teams), TIERS "
                    + "(quelqu'un d'autre).");

    private final SpaceEntitlementService entitlements;

    @Autowired
    public JourneyToolCatalog(SpaceEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Catalogue <b>vide</b> : les outils ne sont jamais donnés (formes historiques, tests). */
    public static JourneyToolCatalog none() {
        return new JourneyToolCatalog(null);
    }

    /** Vrai si ce nom d'outil est l'un de ceux du parcours. */
    public static boolean isJourneyTool(String tool) {
        return tool != null && NAMES.contains(tool);
    }

    /** Vrai si les outils sont ouverts pour ce tour (droit de l'espace du terminal). */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        if (entitlements == null || userId == null || workspace == null) {
            return false;
        }
        EntitlementSpace space = workspace.isTeamsTerminal()
                ? EntitlementSpace.VIGIE : EntitlementSpace.FORGE;
        try {
            return entitlements.isEntitled(userId, space);
        } catch (RuntimeException e) {
            return false; // abonnement illisible : fermé, comme toute garde
        }
    }

    /** Les outils à donner à l'agent pour ce tour, ou la liste vide. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        if (!isOpenFor(userId, workspace)) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        tools.add(proposeGuidedDefinition());
        tools.add(setPlanDefinition());
        tools.add(submitDiagnosisDefinition());
        tools.add(updateStepDefinition());
        tools.add(reopenDefinition());
        tools.add(updateSubStepsDefinition());
        tools.add(launchAutonomousDefinition());
        tools.add(confirmDiagnosisDefinition());
        return tools;
    }

    static AgentTool setPlanDefinition() {
        Map<String, Object> step = Map.of("type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string",
                                "description", "L'action de l'étape (300 caractères au plus)."),
                        "risk", Map.of("type", "string",
                                "description", "LECTURE, NOTES (STATE.md, PLAN-ACTION.md, notes, carte), "
                                        + "REVERSIBLE (édition en branche, fichier local) ou EXTERNE (push, "
                                        + "merge, apply, kubectl apply/delete, envoi, prod).",
                                "enum", List.of("LECTURE", "NOTES", "REVERSIBLE", "EXTERNE")),
                        "verify", Map.of("type", "string",
                                "description", "Comment on saura qu'elle a marché."),
                        "rollback", Map.of("type", "string",
                                "description", "Comment revenir en arrière."),
                        "waits_on", Map.of("type", "string",
                                "description", "La key de l'attente (record_blocker) dont elle dépend, s'il y "
                                        + "en a une."),
                        "depends_on", Map.of("type", "array", "items", Map.of("type", "integer"),
                                "description", "Les numéros (à partir de 1) des étapes ANTÉRIEURES dont elle "
                                        + "dépend. Absent = les étapes précédentes."),
                        "actor", ACTOR_PARAM),
                "required", List.of("title", "risk", "actor"));
        return new AgentTool(SET_PLAN,
                "Pose (ou amende) le PLAN du sujet guidé : la liste COMPLÈTE des étapes, qui remplace la "
                        + "précédente. L'utilisateur le valide d'un clic ; une modification d'un plan déjà "
                        + "validé est un amendement, revalidé. Mode guidé seulement.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "steps", Map.of("type", "array", "items", step,
                                        "description", "Les étapes, dans l'ordre (40 au plus)."),
                                "chantier", CHANTIER_PARAM),
                        "required", List.of("steps")));
    }

    static AgentTool submitDiagnosisDefinition() {
        return new AgentTool(SUBMIT_DIAGNOSIS,
                "Pose le DIAGNOSTIC du sujet guidé pour sortir de l'Investigation : ce que tu as compris, "
                        + "les preuves, ta confiance. L'utilisateur confirme le passage au plan.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "diagnosis", Map.of("type", "string",
                                        "description", "Ce que tu as compris : la cause, et comment la traiter."),
                                "evidence", Map.of("type", "string",
                                        "description", "Les preuves : ce que tu as lu ou observé (fichiers, "
                                                + "commandes et leurs résultats)."),
                                "confidence", Map.of("type", "string",
                                        "description", "Ta confiance dans ce diagnostic.",
                                        "enum", List.of("FAIBLE", "MOYENNE", "ELEVEE")),
                                "chantier", CHANTIER_PARAM),
                        "required", List.of("diagnosis", "evidence", "confidence")));
    }

    static AgentTool updateStepDefinition() {
        return new AgentTool(UPDATE_STEP,
                "Fait avancer une étape du plan validé : FAIT après l'avoir exécutée, VERIFIE ou ECHEC "
                        + "après l'avoir vérifiée comme le plan le dit — avec la preuve. BLOQUE sur un vrai "
                        + "blocage (information manquante, échec sans remède), avec la raison ; A_FAIRE la "
                        + "débloque. actor (sans status) précise qui exécute l'étape : ce n'est pas un "
                        + "amendement. Donne status, actor, ou les deux.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "step", Map.of("type", "integer",
                                        "description", "Le numéro de l'étape, à partir de 1."),
                                "status", Map.of("type", "string",
                                        "enum", List.of("FAIT", "VERIFIE", "ECHEC", "BLOQUE", "A_FAIRE")),
                                "evidence", Map.of("type", "string",
                                        "description", "Ce que tu as observé (requis pour VERIFIE et ECHEC) ; la "
                                                + "raison du blocage (requise pour BLOQUE)."),
                                "actor", ACTOR_PARAM,
                                "chantier", CHANTIER_PARAM),
                        "required", List.of("step")));
    }

    static AgentTool updateSubStepsDefinition() {
        Map<String, Object> subStep = Map.of("type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string",
                                "description", "La sous-étape (200 caractères au plus)."),
                        "done", Map.of("type", "boolean", "description", "Faite ou non."),
                        "evidence", Map.of("type", "string",
                                "description", "Ce que tu as observé, si elle est faite.")),
                "required", List.of("title", "done"));
        return new AgentTool(UPDATE_SUB_STEPS,
                "Détaille une étape du plan validé en SOUS-ÉTAPES cochées : la liste COMPLÈTE, qui remplace la "
                        + "précédente (12 au plus). Ce n'est pas un amendement : l'étape reste la même, "
                        + "l'utilisateur voit où tu en es dedans.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "step", Map.of("type", "integer",
                                        "description", "Le numéro de l'étape, à partir de 1."),
                                "sub_steps", Map.of("type", "array", "items", subStep,
                                        "description", "Les sous-étapes, dans l'ordre."),
                                "chantier", CHANTIER_PARAM),
                        "required", List.of("step", "sub_steps")));
    }

    static AgentTool launchAutonomousDefinition() {
        return new AgentTool(LAUNCH_AUTONOMOUS,
                "Lance un chantier EN AUTONOME, quand l'utilisateur le demande (« exécute le chantier 2 en "
                        + "autonome ») : la gateway vérifie qu'il est prêt (plan validé, rien de bloqué, aucune "
                        + "attente ni question) et le départ part dès la fin de ce tour ; il enchaîne alors toutes "
                        + "les étapes sans question. mode=UNTIL_BLOCKED quand il demande d'avancer jusqu'au "
                        + "blocage (« fais toutes les étapes à ta portée ») : il suffit d'un plan validé et d'une "
                        + "étape à la portée de l'outil ; la course contourne ce qui attend une décision ou "
                        + "quelqu'un, et s'arrête quand plus rien n'est à sa portée. Jamais de ta propre initiative.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "chantier", Map.of("type", "integer",
                                        "description", "Le numéro du chantier à lancer."),
                                "mode", Map.of("type", "string", "enum", List.of("STRICT", "UNTIL_BLOCKED"),
                                        "description", "STRICT (défaut) : tout le chantier, préflight vert. "
                                                + "UNTIL_BLOCKED : avancer jusqu'au blocage.")),
                        "required", List.of("chantier")));
    }

    static AgentTool confirmDiagnosisDefinition() {
        return new AgentTool(CONFIRM_DIAGNOSIS,
                "Confirme le DIAGNOSTIC d'un chantier en attente (« Prêt à planifier ») quand l'utilisateur te "
                        + "le dit explicitement dans son message (« je confirme le diagnostic du chantier 6 », "
                        + "« go pour la planification ») : le chantier passe en Plan, comme sur un clic "
                        + "[Planifier]. Pose ensuite le plan (set_subject_plan) dans le même tour. Jamais de ta "
                        + "propre initiative, ni dans une course autonome.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "chantier", Map.of("type", "integer",
                                        "description", "Le numéro du chantier dont le diagnostic est confirmé."),
                                "user_words", Map.of("type", "string",
                                        "description", "Les mots de l'utilisateur qui confirment, cités.")),
                        "required", List.of("chantier", "user_words")));
    }

    static AgentTool reopenDefinition() {
        return new AgentTool(REOPEN,
                "Ramène le sujet guidé en Investigation quand une découverte contredit le diagnostic ou "
                        + "qu'une vérification échoue. Le plan est gardé ; les modifications attendent un "
                        + "nouveau plan validé.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "reason", Map.of("type", "string",
                                        "description", "Ce qui contredit le diagnostic (300 caractères au plus)."),
                                "chantier", CHANTIER_PARAM),
                        "required", List.of("reason")));
    }

    static AgentTool proposeGuidedDefinition() {
        return new AgentTool(PROPOSE_GUIDED,
                "Propose à l'utilisateur de passer ce sujet en mode GUIDÉ (investigation, plan validé, "
                        + "exécution, vérification) quand la demande est un CHANTIER : incident, changement "
                        + "d'infra, plusieurs étapes ou inconnues à lever avant d'agir. Il voit [Passer en "
                        + "guidé] [Rester libre] et choisit. Sur un sujet déjà guidé : propose un chantier DE "
                        + "PLUS, distinct (les chantiers ouverts restent ouverts). Jamais pour une question ou "
                        + "un petit geste.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "reason", Map.of("type", "string",
                                        "description", "Pourquoi c'est un chantier, en une phrase lisible par "
                                                + "l'utilisateur (300 caractères au plus).")),
                        "required", List.of("reason")));
    }
}
