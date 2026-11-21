package fr.claudegateway.atelier.journey;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Exécute les outils du parcours</b> (F-176). La garde est posée <b>avant</b>, par la boucle. Le
 * {@code userId} et le {@code workspace} viennent <b>du tour</b> — aucun identifiant n'est lu dans les
 * paramètres de l'outil.
 *
 * <p>Toute erreur est un <b>résultat d'outil en erreur</b>, jamais une exception : le parcours ne
 * doit pas tuer le tour qu'il organise.</p>
 */
@Component
public class JourneyToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(JourneyToolExecutor.class);

    private final SubjectJourneyService service;

    /** Le départ en autonome (F-219 / SF-219-04) ; absent dans les tests historiques. */
    private ChantierAutonomyLauncher autonomy;

    public JourneyToolExecutor(SubjectJourneyService service) {
        this.service = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAutonomyLauncher(@org.springframework.context.annotation.Lazy ChantierAutonomyLauncher autonomy) {
        this.autonomy = autonomy;
    }

    /** Le résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
        static Outcome ok(String content) {
            return new Outcome(content, false);
        }

        static Outcome error(String content) {
            return new Outcome(content, true);
        }
    }

    /**
     * Ce que le tour sait de son utilisateur (F-222 / SF-222-04) : s'il tourne <b>sans utilisateur</b> (course
     * autonome, routine) et ce que l'utilisateur a <b>écrit</b> pendant ce tour (message et précisions). Sert à
     * vérifier une validation écrite : jamais une citation inventée, jamais dans une course autonome.
     *
     * @param headless  aucun utilisateur n'écrit dans ce tour
     * @param userWords les textes écrits par l'utilisateur pendant ce tour, dans l'ordre
     */
    public record TurnContext(boolean headless, java.util.List<String> userWords) {

        /** Contexte inconnu : traité comme un tour sans utilisateur (aucune validation écrite possible). */
        public static final TurnContext UNKNOWN = new TurnContext(true, java.util.List.of());

        public TurnContext {
            userWords = userWords == null ? java.util.List.of()
                    : userWords.stream().filter(java.util.Objects::nonNull).toList();
        }

        /** Un tour d'utilisateur, avec ce qu'il a écrit. */
        public static TurnContext ofUser(java.util.List<String> userWords) {
            return new TurnContext(false, userWords);
        }
    }

    /** Aiguille un appel d'outil du parcours, sans contexte du tour (aucune validation écrite possible). */
    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input) {
        return execute(userId, workspace, tool, input, TurnContext.UNKNOWN);
    }

    /** Aiguille un appel d'outil du parcours. */
    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input, TurnContext turn) {
        TurnContext context = turn == null ? TurnContext.UNKNOWN : turn;
        try {
            return switch (tool) {
                case JourneyToolCatalog.PROPOSE_GUIDED -> proposeGuided(userId, workspace, input);
                case JourneyToolCatalog.SET_PLAN -> setPlan(userId, workspace, input);
                case JourneyToolCatalog.SUBMIT_DIAGNOSIS -> submitDiagnosis(userId, workspace, input);
                case JourneyToolCatalog.UPDATE_STEP -> updateStep(userId, workspace, input);
                case JourneyToolCatalog.REOPEN -> reopen(userId, workspace, input);
                case JourneyToolCatalog.UPDATE_SUB_STEPS -> updateSubSteps(userId, workspace, input);
                case JourneyToolCatalog.LAUNCH_AUTONOMOUS -> launchAutonomous(userId, workspace, input);
                case JourneyToolCatalog.CONFIRM_DIAGNOSIS -> confirmDiagnosis(userId, workspace, input);
                case JourneyToolCatalog.VALIDATE_PLAN -> validatePlan(userId, workspace, input, context);
                case JourneyToolCatalog.CLOSE_CHANTIER -> closeChantier(userId, workspace, input, context);
                case JourneyToolCatalog.OUT_OF_PLAN -> outOfPlan(userId, workspace, input, context);
                default -> Outcome.error("Outil du parcours inconnu : " + tool);
            };
        } catch (InvalidJourneyException e) {
            return Outcome.error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Outil du parcours en échec ({})", tool, e);
            return Outcome.error("Le parcours du sujet n'a pas pu être mis à jour. Continue sans, et "
                    + "dis-le à l'utilisateur plutôt que de réessayer.");
        }
    }

    private Outcome proposeGuided(UUID userId, Workspace workspace, JsonNode input) {
        SubjectJourneyService.ProposalOutcome outcome =
                service.proposeGuided(userId, workspace.getId(), text(input, "reason"));
        return switch (outcome) {
            case PROPOSED -> Outcome.ok("Proposition affichée à l'utilisateur : [Passer en guidé] [Rester "
                    + "libre]. Le sujet reste en Libre tant qu'il n'a pas choisi : continue à comprendre "
                    + "(lecture libre), sans rien modifier de plus que ce qu'il a explicitement demandé, et "
                    + "dis-lui en une phrase pourquoi tu proposes le mode guidé.");
            case ALREADY_PROPOSED -> Outcome.ok("La proposition attend déjà son choix : n'en reparle pas.");
            case ALREADY_GUIDED -> Outcome.ok("Le sujet est déjà en mode guidé : suis le parcours.");
            case DECLINED -> Outcome.ok("L'utilisateur a choisi de RESTER LIBRE sur ce sujet : ne repropose "
                    + "pas le mode guidé, avance en libre.");
            case PROPOSED_NEW_CHANTIER -> Outcome.ok("Proposition affichée à l'utilisateur : [Ouvrir le chantier] "
                    + "[Pas maintenant]. S'il accepte, un chantier DE PLUS s'ouvre (Investigation, plan neuf) et "
                    + "prend le focus ; les chantiers ouverts restent ouverts, intacts. En attendant, continue le "
                    + "chantier en cours et dis-lui en une phrase pourquoi c'est un chantier distinct.");
        };
    }

    private Outcome setPlan(UUID userId, Workspace workspace, JsonNode input) {
        JourneyPlan plan = JourneyPlan.fromToolInput(input == null ? null : input.get("steps"));
        Integer chantier = chantierOf(input, workspace);
        boolean sameObjective = input != null && input.path("same_objective").asBoolean(false);
        SubjectJourneyService.PlanChange change = service.setPlan(userId, workspace.getId(), chantier, plan,
                sameObjective);
        return prefixed(chantier, switch (change.outcome()) {
            case SET -> Outcome.ok("Plan v" + change.version() + " posé (" + plan.steps().size() + " étapes). "
                    + "Il attend la VALIDATION de l'utilisateur, d'un clic : il le voit déjà à l'écran. "
                    + "Présente-le en une phrase au plus, puis termine ton tour ; ne modifie rien avant qu'il "
                    + "l'ait validé.");
            case AMENDMENT -> Outcome.ok("Amendement : plan v" + change.version() + " posé. L'objectif change : il "
                    + "remplace le plan validé et doit être REVALIDÉ par l'utilisateur (un clic, ou un « go » écrit "
                    + "→ valider_plan). Dis-lui ce qui change et pourquoi, et ne modifie rien avant sa validation.");
            case AMENDMENT_APPLIED -> Outcome.ok("Amendement dans l'objectif : plan v" + change.version()
                    + " appliqué SANS CLIC et journalisé ; le plan reste validé. Annonce-le en une phrase dans ta "
                    + "réponse (ce qui change et pourquoi), puis continue l'exécution.");
            case AMENDMENT_EXTERNAL -> Outcome.ok("Amendement : plan v" + change.version() + " posé, mais il AJOUTE "
                    + "ou MODIFIE une étape externe / irréversible : même dans l'objectif, il n'est pas appliqué sans "
                    + "clic. Il doit être REVALIDÉ par l'utilisateur (un clic, ou un « go » écrit → valider_plan). "
                    + "Dis-lui ce qui change et pourquoi, et ne modifie rien avant sa validation.");
            case NOT_GUIDED -> Outcome.error("Le sujet est en mode Libre : le plan structuré sert au mode "
                    + "guidé. Utilise set_plan pour organiser ton tour, ou propose le mode guidé.");
            case CLOSED -> Outcome.error("Le sujet est clos : rien à planifier. S'il faut reprendre, "
                    + "l'utilisateur rouvre le sujet en mode guidé.");
            case EMPTY -> Outcome.error("Aucune étape lisible : chaque étape a au moins un title et un risk.");
            case NEEDS_DIAGNOSIS -> Outcome.error("Le sujet est en Investigation : pose d'abord ton diagnostic "
                    + "(submit_diagnosis, avec tes preuves et ta confiance). L'utilisateur confirme le passage "
                    + "au plan ; ensuite seulement, set_subject_plan.");
        });
    }

    private Outcome submitDiagnosis(UUID userId, Workspace workspace, JsonNode input) {
        Integer chantier = chantierOf(input, workspace);
        SubjectJourneyService.DiagnosisOutcome outcome = service.submitDiagnosis(userId, workspace.getId(),
                chantier, text(input, "diagnosis"), text(input, "evidence"), text(input, "confidence"));
        return prefixed(chantier, switch (outcome) {
            case PROPOSED -> Outcome.ok("Diagnostic posé. L'utilisateur voit « Prêt à planifier » : [Planifier] "
                    + "[Continuer l'investigation]. Résume-lui le diagnostic et tes preuves en quelques lignes, "
                    + "et attends son choix avant de poser le plan.");
            case NOT_INVESTIGATING -> Outcome.error("Le sujet n'est pas en Investigation : le diagnostic est déjà "
                    + "posé. S'il est contredit, utilise reopen_investigation.");
            case NOT_GUIDED -> Outcome.error("Le sujet est en mode Libre : le diagnostic sert au mode guidé.");
        });
    }

    private Outcome updateStep(UUID userId, Workspace workspace, JsonNode input) {
        int step = input == null ? 0 : input.path("step").asInt(0);
        Integer chantier = chantierOf(input, workspace);
        String actor = text(input, "actor");
        if (actor.isEmpty()) {
            actor = text(input, "acteur");
        }
        SubjectJourneyService.StepChange change = service.updateStep(userId, workspace.getId(), chantier, step,
                text(input, "status"), text(input, "evidence"), actor);
        return prefixed(chantier, switch (change.outcome()) {
            case UPDATED -> Outcome.ok("Étape " + step + " à jour.");
            case TO_VERIFICATION -> Outcome.ok("Étape " + step + " à jour. Plus aucune étape à exécuter : le sujet "
                    + "passe en VÉRIFICATION. Vérifie chaque étape comme le plan le dit, preuves à l'appui ; on ne "
                    + "modifie plus.");
            case ALL_VERIFIED -> Outcome.ok("Toutes les étapes sont vérifiées. L'utilisateur voit la proposition "
                    + "de clore le sujet : résume-lui ce qui a été fait et vérifié. S'il demande par écrit de clore : "
                    + "cloturer_chantier.");
            case FAILED -> Outcome.ok("Étape " + step + " en ÉCHEC. Ne force pas : si la cause est comprise, "
                    + "amende le plan (set_subject_plan) ; sinon reopen_investigation.");
            case BLOCKED -> Outcome.ok("Étape " + step + " BLOQUÉE, raison enregistrée. Arrête-toi là sur ce "
                    + "chantier : dis à l'utilisateur ce qui manque et ce qu'il faut pour reprendre. Quand c'est "
                    + "levé, update_plan_step status=A_FAIRE la débloque.");
            case UNBLOCKED -> Outcome.ok("Étape " + step + " débloquée : de nouveau à faire.");
            case ACTOR_SET -> Outcome.ok("Étape " + step + " : " + actorText(change.plan(), step) + ". Ce n'est pas "
                    + "un amendement : le plan reste validé.");
            case NOT_EXECUTING -> Outcome.error("Aucun plan validé en cours d'exécution : rien à faire avancer.");
            case NO_SUCH_STEP -> Outcome.error("Il n'y a pas d'étape " + step + " dans le plan (numérotées à "
                    + "partir de 1).");
            case NOT_GUIDED -> Outcome.error("Le sujet est en mode Libre.");
        });
    }

    /** « exécutée par vous (MOI) » — ce que l'agent vient de poser. */
    private static String actorText(JourneyPlan plan, int step) {
        JourneyPlan.Actor actor = plan.steps().get(step - 1).effectiveActor();
        return "exécutée " + actor.label() + " (" + actor.name() + ")";
    }

    private Outcome updateSubSteps(UUID userId, Workspace workspace, JsonNode input) {
        int step = input == null ? 0 : input.path("step").asInt(0);
        Integer chantier = chantierOf(input, workspace);
        java.util.List<JourneyPlan.SubStep> subSteps =
                JourneyPlan.subStepsFromToolInput(input == null ? null : input.get("sub_steps"));
        SubjectJourneyService.StepChange change = service.updateSubSteps(userId, workspace.getId(), chantier, step,
                subSteps);
        return prefixed(chantier, switch (change.outcome()) {
            case NOT_EXECUTING -> Outcome.error("Aucun plan validé en cours d'exécution : rien à détailler.");
            case NO_SUCH_STEP -> Outcome.error("Il n'y a pas d'étape " + step + " dans le plan (numérotées à "
                    + "partir de 1).");
            case NOT_GUIDED -> Outcome.error("Le sujet est en mode Libre.");
            default -> {
                JourneyPlan.Step s = change.plan().steps().get(step - 1);
                yield Outcome.ok("Étape " + step + " : " + s.subStepsDone() + "/" + s.subSteps().size()
                        + " sous-étape(s) faite(s). Quand l'étape entière est exécutée : update_plan_step "
                        + "status=FAIT.");
            }
        });
    }

    private Outcome launchAutonomous(UUID userId, Workspace workspace, JsonNode input) {
        Integer chantier = chantier(input); // explicite : jamais déduit du fil
        if (chantier == null) {
            return Outcome.error("chantier est requis : le numéro du chantier à lancer en autonome.");
        }
        if (autonomy == null) {
            return Outcome.error("Le départ en autonome est indisponible ici : dis-le à l'utilisateur.");
        }
        AutonomyMode mode;
        try {
            mode = AutonomyMode.parse(text(input, "mode"));
        } catch (IllegalArgumentException e) {
            return Outcome.error(e.getMessage());
        }
        ChantierAutonomyLauncher.Result result = autonomy.schedule(userId, workspace, chantier, mode);
        return result.scheduled() ? Outcome.ok(result.message()) : Outcome.error(result.message());
    }

    /**
     * La confirmation écrite du diagnostic (F-176 / SF-176-12) : « je confirme », « go pour planifier » valent le
     * clic [Planifier]. Le chantier est explicite, et la citation de l'utilisateur est exigée : jamais d'initiative.
     */
    private Outcome confirmDiagnosis(UUID userId, Workspace workspace, JsonNode input) {
        Integer chantier = chantier(input); // explicite : jamais déduit du fil
        if (chantier == null) {
            return Outcome.error("chantier est requis : le numéro du chantier dont l'utilisateur confirme le "
                    + "diagnostic.");
        }
        if (text(input, "user_words").isEmpty()) {
            return Outcome.error("user_words est requis : cite les mots de l'utilisateur qui confirment. Sans "
                    + "confirmation de sa part, ne confirme pas.");
        }
        service.confirmDiagnosis(userId, workspace.getId(), chantier);
        return prefixed(chantier, Outcome.ok("Diagnostic confirmé : le chantier passe en PLAN. Pose maintenant le "
                + "plan (set_subject_plan, chantier=" + chantier + ") ; il attendra la validation de l'utilisateur."));
    }

    /**
     * La validation écrite du plan (F-222 / SF-222-01, durcie par SF-222-04) : un « go » vaut le clic [Valider]. Le
     * chantier et la <b>version lue</b> sont explicites ; la citation doit figurer dans ce que l'utilisateur a écrit
     * pendant ce tour ; jamais dans un tour sans utilisateur (course autonome, routine). Le journal garde le canal.
     */
    private Outcome validatePlan(UUID userId, Workspace workspace, JsonNode input, TurnContext turn) {
        Integer chantier = chantier(input); // explicite : jamais déduit du fil
        if (chantier == null) {
            return Outcome.error("chantier est requis : le numéro du chantier dont l'utilisateur valide le plan.");
        }
        Integer version = version(input);
        if (version == null) {
            return Outcome.error("version est requise : la version du plan (vN) que l'utilisateur a lue et valide. "
                    + "Un plan amendé depuis se revalide.");
        }
        String words = text(input, "user_words");
        if (words.isEmpty()) {
            return Outcome.error("user_words est requis : cite les mots de l'utilisateur qui valident. Sans "
                    + "validation de sa part, ne valide pas.");
        }
        if (turn.headless()) {
            return Outcome.error("Une course autonome (ou une routine) ne valide jamais un plan : personne n'y écrit. "
                    + "Laisse le plan en attente ; l'utilisateur le validera d'un clic ou par écrit.");
        }
        if (!quoted(words, turn.userWords())) {
            return Outcome.error("Ces mots ne figurent pas dans ce que l'utilisateur a écrit pendant ce tour : cite-le "
                    + "exactement. Sans validation écrite de sa part dans ce tour, laisse le plan en attente.");
        }
        service.validatePlan(userId, workspace.getId(), chantier, version, channel(words));
        return prefixed(chantier, Outcome.ok("Plan v" + version + " validé par l'utilisateur : le chantier passe en "
                + "EXÉCUTION. Exécute ses étapes jusqu'au bout (update_plan_step après chacune)."));
    }

    /**
     * La clôture écrite d'un chantier (F-261 / SF-261-01) : « tu peux clore » vaut le clic [Clore], aux mêmes
     * conditions que {@code valider_plan} — chantier explicite, citation figurant dans ce que l'utilisateur a écrit
     * pendant ce tour, jamais dans un tour sans utilisateur. Le service garde la phase (Vérification, toutes les
     * étapes vérifiées) ; le journal garde le canal.
     */
    private Outcome closeChantier(UUID userId, Workspace workspace, JsonNode input, TurnContext turn) {
        Integer chantier = chantier(input); // explicite : jamais déduit du fil
        if (chantier == null) {
            return Outcome.error("chantier est requis : le numéro du chantier que l'utilisateur demande de clore.");
        }
        String words = text(input, "user_words");
        if (words.isEmpty()) {
            return Outcome.error("user_words est requis : cite les mots de l'utilisateur qui demandent la clôture. "
                    + "Sans demande de sa part, ne clos pas.");
        }
        String refusal = writtenRefusal(words, turn, "Une course autonome (ou une routine) ne clôt jamais un "
                + "chantier : personne n'y écrit. Laisse-le en Vérification ; l'utilisateur le clora d'un clic ou "
                + "par écrit.", "laisse le chantier ouvert");
        if (refusal != null) {
            return Outcome.error(refusal);
        }
        SubjectJourney focus = service.closeOnUserWords(userId, workspace.getId(), chantier, channel(words));
        String after = focus.isGuided() && focus.getPhase() != JourneyPhase.CLOS
                ? "Le chantier " + focus.getChantierNumber() + " est maintenant au focus."
                : "C'était le dernier chantier ouvert : le terminal revient en Libre.";
        return prefixed(chantier, Outcome.ok("Clos sur la demande de l'utilisateur, comme sur un clic [Clore] ; il "
                + "reste consultable. " + after + " Dis-le-lui en une phrase."));
    }

    /** Longueur maximale de l'objet d'un geste hors chantier. */
    static final int MAX_OUT_OF_PLAN_OBJECT = 200;

    /**
     * <b>Le couloir hors chantier</b> (F-274 / SF-274-01) : l'utilisateur demande par écrit, dans ce tour, un geste qui
     * n'appartient au plan d'aucun chantier — ou qui sert plusieurs chantiers. La citation est vérifiée comme un « go »
     * (jamais dans une course autonome) ; la porte s'ouvre pour le reste de ce tour seulement, et le geste est
     * journalisé une fois par chantier rattaché.
     */
    private Outcome outOfPlan(UUID userId, Workspace workspace, JsonNode input, TurnContext turn) {
        String objet = text(input, "objet");
        if (objet.isEmpty()) {
            return Outcome.error("objet est requis : le geste demandé, en quelques mots (« clôture SIP-10567 », « page "
                    + "État des lieux v7 »).");
        }
        if (objet.length() > MAX_OUT_OF_PLAN_OBJECT) {
            objet = objet.substring(0, MAX_OUT_OF_PLAN_OBJECT - 1).strip() + "…";
        }
        String words = text(input, "user_words");
        if (words.isEmpty()) {
            return Outcome.error("user_words est requis : cite les mots de l'utilisateur qui demandent ce geste. Sans "
                    + "demande écrite de sa part dans ce tour, ce n'est pas un geste hors chantier.");
        }
        String refusal = writtenRefusal(words, turn, "Une course autonome (ou une routine) n'a pas de couloir hors "
                + "chantier : personne n'y écrit. Tiens-toi au plan du chantier.", "tiens-toi aux plans validés");
        if (refusal != null) {
            return Outcome.error(refusal);
        }
        java.util.List<Integer> cited = new java.util.ArrayList<>();
        JsonNode list = input == null ? null : input.get("chantiers");
        if (list != null && list.isArray()) {
            for (JsonNode n : list) {
                int number = n.isNumber() ? n.asInt(0) : n.isTextual() ? parse(n.asText()) : 0;
                if (number >= 1) {
                    cited.add(number);
                }
            }
        }
        SubjectJourneyService.OutOfPlanRecord recorded =
                service.recordOutOfPlan(userId, workspace.getId(), cited, objet, channel(words));
        if (!recorded.guided()) {
            return Outcome.error("Le couloir hors chantier n'existe qu'en mode guidé, avec un chantier ouvert : ici, "
                    + "fais simplement ce que l'utilisateur demande.");
        }
        workspace.setTurnOutOfPlan(true);
        String where = recorded.attached().isEmpty() ? "hors de tout chantier"
                : recorded.attached().size() == 1 ? "rattaché au chantier " + recorded.attached().get(0)
                : "transverse, rattaché aux chantiers " + recorded.attached().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
        String ignored = recorded.ignored().isEmpty() ? "" : " Chantier(s) " + recorded.ignored().stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))
                + " ignoré(s) : ni ouvert(s) ni connu(s) de ce sujet.";
        return Outcome.ok("Couloir hors chantier ouvert pour ce tour (" + where + ") : la porte du parcours laisse "
                + "passer les modifications de ce geste jusqu'à la fin du tour, sans amendement de plan. Exécute la "
                + "demande telle que l'utilisateur l'a formulée, pas plus — ce qu'il n'a pas nommé suit les règles "
                + "habituelles. Annonce-le en une ligne dans ta réponse (« hors chantier » ou « transverse »)."
                + ignored);
    }

    /**
     * Pourquoi une citation écrite est refusée (F-222 / SF-222-04, partagé par F-261), ou {@code null} si elle vaut :
     * jamais dans un tour sans utilisateur, et seulement si elle figure dans ce qu'il a écrit pendant ce tour.
     */
    public static String writtenRefusal(String words, TurnContext turn, String headlessMessage, String otherwise) {
        TurnContext context = turn == null ? TurnContext.UNKNOWN : turn;
        if (context.headless()) {
            return headlessMessage;
        }
        if (!quoted(words, context.userWords())) {
            return "Ces mots ne figurent pas dans ce que l'utilisateur a écrit pendant ce tour : cite-le exactement. "
                    + "Sans demande écrite de sa part dans ce tour, " + otherwise + ".";
        }
        return null;
    }

    /** Le canal journalisé d'un geste écrit : {@code écrit « citation »}, tronqué à 120 caractères. */
    public static String channel(String words) {
        return "écrit « " + (words.length() > 120 ? words.substring(0, 120) + "…" : words) + " »";
    }

    /** La version donnée ({@code 3}, {@code "v3"}), ou {@code null}. */
    static Integer version(JsonNode input) {
        if (input == null || !input.hasNonNull("version")) {
            return null;
        }
        JsonNode value = input.get("version");
        int n = value.isNumber() ? value.asInt(0) : value.isTextual() ? parse(value.asText()) : 0;
        return n >= 1 ? n : null;
    }

    /**
     * Vrai si la citation figure, mot pour mot, dans l'un des textes de l'utilisateur — casse, accents, ponctuation
     * et espaces ignorés (F-222 / SF-222-04). Une citation sans aucun mot ne vaut rien.
     */
    public static boolean quoted(String words, java.util.List<String> userWords) {
        String needle = normalized(words);
        if (needle.isEmpty() || userWords == null) {
            return false;
        }
        for (String said : userWords) {
            String hay = normalized(said);
            if (!hay.isEmpty() && (" " + hay + " ").contains(" " + needle + " ")) {
                return true;
            }
        }
        return false;
    }

    /** Minuscules, sans accents, la ponctuation remplacée par des espaces, les espaces resserrés. */
    static String normalized(String value) {
        if (value == null) {
            return "";
        }
        String plain = java.text.Normalizer.normalize(value.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private Outcome reopen(UUID userId, Workspace workspace, JsonNode input) {
        Integer chantier = chantierOf(input, workspace);
        boolean reopened = service.reopenInvestigation(userId, workspace.getId(), chantier, text(input, "reason"));
        return prefixed(chantier, reopened
                ? Outcome.ok("Retour en INVESTIGATION. Le plan est gardé ; rien ne se modifie avant un nouveau "
                        + "diagnostic et un plan validé. Dis à l'utilisateur ce qui a contredit le diagnostic.")
                : Outcome.error("Le sujet n'est pas en mode guidé (ou il est clos) : rien à rouvrir."));
    }

    /**
     * Le chantier visé (F-206) : un entier ≥ 1, ou {@code null} = le focus. Un numéro, jamais un
     * identifiant : il est relatif au terminal du tour.
     */
    /**
     * Le chantier visé par un outil du parcours, dans le <b>fil</b> du tour (F-221 / SF-221-01) : le numéro
     * donné, sinon le chantier dont le tour relit le fil ({@link Workspace#getTurnChantier()}), sinon
     * {@code null} = le focus. Dans le volet du chantier 8, « update_plan_step » sans numéro vise le 8 —
     * jamais le focus du volet d'à côté.
     */
    static Integer chantierOf(JsonNode input, Workspace workspace) {
        Integer given = chantier(input);
        if (given != null) {
            return given;
        }
        Integer lane = workspace == null ? null : workspace.getTurnChantier();
        return lane != null && lane >= 1 ? lane : null;
    }

    public static Integer chantier(JsonNode input) {
        if (input == null || !input.hasNonNull("chantier")) {
            return null;
        }
        JsonNode value = input.get("chantier");
        int n = value.isNumber() ? value.asInt(0) : value.isTextual() ? parse(value.asText()) : 0;
        return n >= 1 ? n : null;
    }

    private static int parse(String raw) {
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Le résultat cite le chantier visé quand il est donné. */
    private static Outcome prefixed(Integer chantier, Outcome outcome) {
        return chantier == null ? outcome : new Outcome("Chantier " + chantier + " : " + outcome.content(),
                outcome.error());
    }

    static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }
}
