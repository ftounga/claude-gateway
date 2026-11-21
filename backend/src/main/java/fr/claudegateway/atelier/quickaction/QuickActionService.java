package fr.claudegateway.atelier.quickaction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.LaunchView;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionRequest;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionView;
import fr.claudegateway.atelier.skills.SkillCatalogService;
import fr.claudegateway.atelier.skills.SkillEntry;
import fr.claudegateway.atelier.skills.SkillTriggers;

/**
 * <b>Les actions rapides d'un terminal</b> (F-275 / SF-275-01).
 *
 * <p>La liste fusionne deux sources (D1) : les <b>actions personnalisées</b> visibles dans ce terminal (portée COMPTE,
 * POSTE de son poste, SUJET de ce terminal) puis les <b>déclencheurs des skills</b> du catalogue F-177 — sans double
 * saisie. Une action personnalisée de même phrase qu'un déclencheur le masque. Chaque action porte son dernier
 * lancement depuis le bouton (D7).</p>
 *
 * <p><b>Isolation</b> : le terminal est toujours {@code requireOwned} ; toute lecture de {@code quick_actions} et de
 * {@code quick_action_launches} porte {@code user_id} ; un sujet cible est un sujet possédé (et du même poste quand le
 * terminal en a un). Aucun identifiant de compte n'est lu dans la requête.</p>
 */
@Service
public class QuickActionService {

    /** Plafond d'actions personnalisées par compte. */
    static final int MAX_ACTIONS_PER_USER = 50;
    static final int MAX_LABEL_CHARS = 60;
    static final int MAX_PHRASE_CHARS = 2000;
    static final int MAX_KEY_CHARS = 200;
    /** Plafond d'actions tirées des skills dans une liste. */
    static final int MAX_SKILL_ACTIONS = 40;

    static final Set<String> SCOPES = Set.of(QuickAction.SUJET, QuickAction.POSTE, QuickAction.COMPTE);
    static final Set<String> CLICKS = Set.of(QuickAction.DEPOSER, QuickAction.ENVOYER);
    static final Set<String> MOMENTS = Set.of("MATIN", "APRES_MIDI", "JOURNEE");

    private final WorkspaceService workspaceService;
    private final SkillCatalogService skillCatalog;
    private final QuickActionRepository actions;
    private final QuickActionLaunchRepository launches;

    public QuickActionService(WorkspaceService workspaceService, SkillCatalogService skillCatalog,
            QuickActionRepository actions, QuickActionLaunchRepository launches) {
        this.workspaceService = workspaceService;
        this.skillCatalog = skillCatalog;
        this.actions = actions;
        this.launches = launches;
    }

    // ------------------------------------------------------------------ lecture

    /**
     * La liste d'un terminal possédé ; {@code withSkills=false} ne lit pas le poste (suggestion au chargement). Sans
     * transaction englobante : le catalogue des skills interroge le poste, aucune connexion n'est tenue pendant ce temps.
     */
    public List<QuickActionView> list(UUID userId, UUID workspaceId, boolean withSkills) {
        return list(userId, workspaceService.requireOwned(userId, workspaceId), withSkills);
    }

    /** La liste d'un terminal déjà possédé (l'outil, SF-275-02). */
    public List<QuickActionView> list(UUID userId, Workspace workspace, boolean withSkills) {
        if (!offeredIn(workspace)) {
            return List.of();
        }
        List<QuickAction> customs = visible(userId, workspace);
        Map<UUID, Optional<Workspace>> targets = new HashMap<>();
        List<Draft> drafts = new ArrayList<>();
        Set<String> customPhrases = new HashSet<>();
        for (QuickAction a : customs) {
            customPhrases.add(SkillTriggers.key(a.getPhrase()));
            Optional<Workspace> target = a.getTargetWorkspaceId() == null ? Optional.empty()
                    : targets.computeIfAbsent(a.getTargetWorkspaceId(), id -> ownedSubject(userId, id));
            drafts.add(new Draft("c:" + a.getId(), QuickActionDtos.PERSO, a.getId(), a.getLabel(), a.getPhrase(), null,
                    a.getScope(), a.getTargetWorkspaceId(), target.map(Workspace::getName).orElse(null),
                    a.getTargetWorkspaceId() != null && target.isEmpty(), a.getClickMode(), days(a.getSuggestDays()),
                    a.getSuggestMoment()));
        }
        if (withSkills) {
            drafts.addAll(skillDrafts(userId, workspace, customPhrases));
        }
        Map<String, OffsetDateTime> lastRuns = drafts.isEmpty() ? Map.of()
                : launches.findByUserIdAndActionKeyIn(userId, drafts.stream().map(Draft::key).toList()).stream()
                        .collect(Collectors.toMap(QuickActionLaunch::getActionKey, QuickActionLaunch::getLastLaunchedAt,
                                (x, y) -> x));
        return drafts.stream().map(d -> d.view(lastRuns.get(d.key()))).toList();
    }

    /** Les actions personnalisées visibles dans ce terminal (l'outil s'en sert pour désigner par libellé). */
    public List<QuickAction> visible(UUID userId, Workspace workspace) {
        return actions.findVisible(userId, workspace.getHostId(), workspace.getId());
    }

    /** Vrai si les actions rapides existent dans ce terminal (jamais Teams ni le fil Jarvis). */
    public static boolean offeredIn(Workspace workspace) {
        return workspace != null && !workspace.isTeamsTerminal() && !workspace.isJarvisThread();
    }

    private List<Draft> skillDrafts(UUID userId, Workspace workspace, Set<String> masked) {
        List<SkillEntry> catalog;
        try {
            catalog = skillCatalog.catalog(userId, workspace);
        } catch (RuntimeException ex) {
            return List.of();
        }
        List<Draft> out = new ArrayList<>();
        List<Workspace> hostSubjects = null;
        for (SkillEntry skill : catalog) {
            if (!skill.launch().declared()) {
                continue;
            }
            Workspace target = null;
            boolean missing = false;
            if (skill.launch().subject() != null) {
                if (hostSubjects == null) {
                    hostSubjects = workspace.getHostId() == null ? List.of()
                            : workspaceService.listByHost(userId, workspace.getHostId());
                }
                target = matchSubject(hostSubjects, skill.launch().subject()).orElse(null);
                missing = target == null;
            }
            String anchor = SkillEntry.POSTE.equals(skill.origin()) && workspace.getHostId() != null
                    ? workspace.getHostId().toString() : workspace.getId().toString();
            for (String trigger : skill.launch().triggers()) {
                String phraseKey = SkillTriggers.key(trigger);
                if (masked.contains(phraseKey) || out.size() >= MAX_SKILL_ACTIONS) {
                    continue;
                }
                masked.add(phraseKey);
                String key = "s:" + anchor + ":" + skill.name() + ":" + phraseKey;
                if (key.length() > MAX_KEY_CHARS) {
                    key = key.substring(0, MAX_KEY_CHARS);
                }
                out.add(new Draft(key, QuickActionDtos.SKILL, null, capitalize(trigger), trigger, skill.name(),
                        skill.origin(), target == null ? null : target.getId(), target == null ? null : target.getName(),
                        missing, skill.launch().send() ? QuickAction.ENVOYER : QuickAction.DEPOSER, List.of(), null));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ écriture

    /** Crée une action personnalisée depuis ce terminal. */
    @Transactional
    public QuickActionView create(UUID userId, UUID workspaceId, QuickActionRequest request) {
        return create(userId, workspaceService.requireOwned(userId, workspaceId), request);
    }

    @Transactional
    public QuickActionView create(UUID userId, Workspace workspace, QuickActionRequest request) {
        requireOffered(workspace);
        if (request == null) {
            throw QuickActionException.invalid("Donne au moins un libellé et une phrase.");
        }
        if (actions.countByUserId(userId) >= MAX_ACTIONS_PER_USER) {
            throw QuickActionException.invalid("Plafond de " + MAX_ACTIONS_PER_USER
                    + " actions rapides atteint : supprime-en une avant d'en ajouter.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        QuickAction action = QuickAction.builder().userId(userId).createdAt(now).updatedAt(now).build();
        applyLabel(action, request.label());
        applyPhrase(action, request.phrase());
        applyScope(action, workspace, request.scope() == null ? defaultScope(workspace) : request.scope());
        applyTarget(userId, workspace, action, request.targetWorkspaceId());
        action.setClickMode(click(request.clickMode() == null ? QuickAction.DEPOSER : request.clickMode()));
        applySuggestion(action, request.suggestDays(), request.suggestMoment());
        requireUniqueLabel(userId, workspace, action);
        return viewOf(userId, actions.save(action));
    }

    /** Modifie une action personnalisée du compte (champs fournis seulement). */
    @Transactional
    public QuickActionView update(UUID userId, UUID workspaceId, UUID actionId, QuickActionRequest request) {
        return update(userId, workspaceService.requireOwned(userId, workspaceId), actionId, request);
    }

    @Transactional
    public QuickActionView update(UUID userId, Workspace workspace, UUID actionId, QuickActionRequest request) {
        requireOffered(workspace);
        QuickAction action = requireAction(userId, actionId);
        if (request == null) {
            return viewOf(userId, action);
        }
        if (request.label() != null) {
            applyLabel(action, request.label());
        }
        if (request.phrase() != null) {
            applyPhrase(action, request.phrase());
        }
        if (request.scope() != null) {
            applyScope(action, workspace, request.scope());
        }
        if (Boolean.TRUE.equals(request.clearTarget())) {
            action.setTargetWorkspaceId(null);
        } else if (request.targetWorkspaceId() != null) {
            applyTarget(userId, workspace, action, request.targetWorkspaceId());
        }
        if (request.clickMode() != null) {
            action.setClickMode(click(request.clickMode()));
        }
        if (Boolean.TRUE.equals(request.clearSuggestion())) {
            action.setSuggestDays(null);
            action.setSuggestMoment(null);
        } else if (request.suggestDays() != null || request.suggestMoment() != null) {
            applySuggestion(action,
                    request.suggestDays() != null ? request.suggestDays() : days(action.getSuggestDays()),
                    request.suggestMoment() != null ? request.suggestMoment() : action.getSuggestMoment());
        }
        requireUniqueLabel(userId, workspace, action);
        action.setUpdatedAt(OffsetDateTime.now());
        return viewOf(userId, actions.save(action));
    }

    /** Supprime une action personnalisée du compte (et son dernier lancement). */
    @Transactional
    public QuickAction delete(UUID userId, UUID workspaceId, UUID actionId) {
        workspaceService.requireOwned(userId, workspaceId);
        return delete(userId, actionId);
    }

    @Transactional
    public QuickAction delete(UUID userId, UUID actionId) {
        QuickAction action = requireAction(userId, actionId);
        actions.delete(action);
        launches.findByUserIdAndActionKey(userId, "c:" + action.getId()).ifPresent(launches::delete);
        return action;
    }

    /** Enregistre un lancement depuis le bouton (D7). */
    @Transactional
    public LaunchView recordLaunch(UUID userId, UUID workspaceId, String rawKey) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        String key = rawKey == null ? "" : rawKey.strip();
        if (key.isEmpty() || key.length() > MAX_KEY_CHARS || !(key.startsWith("c:") || key.startsWith("s:"))) {
            throw QuickActionException.invalid("Clé d'action illisible.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        QuickActionLaunch launch = launches.findByUserIdAndActionKey(userId, key)
                .orElseGet(() -> QuickActionLaunch.builder().userId(userId).actionKey(key).launchCount(0).build());
        launch.setLastLaunchedAt(now);
        launch.setLastWorkspaceId(workspace.getId());
        launch.setLaunchCount(launch.getLaunchCount() + 1);
        launches.save(launch);
        return new LaunchView(key, now);
    }

    // ------------------------------------------------------------------ règles

    /** Portée par défaut : POSTE si le terminal a un poste, sinon SUJET (D3). */
    public static String defaultScope(Workspace workspace) {
        return workspace.getHostId() != null ? QuickAction.POSTE : QuickAction.SUJET;
    }

    /** Le sujet {@code nameOrId} du poste de ce terminal (ou ce terminal lui-même s'il n'a pas de poste). */
    public Workspace resolveSubject(UUID userId, Workspace workspace, String nameOrId) {
        String wanted = nameOrId == null ? "" : nameOrId.strip();
        if (wanted.isEmpty()) {
            throw QuickActionException.invalid("Sujet cible vide.");
        }
        try {
            return requireTargetable(userId, workspace, UUID.fromString(wanted));
        } catch (IllegalArgumentException notAnId) {
            // Un nom : cherché parmi les sujets du poste.
        }
        List<Workspace> subjects = workspace.getHostId() == null ? List.of(workspace)
                : workspaceService.listByHost(userId, workspace.getHostId());
        List<Workspace> matches = subjects.stream().filter(w -> matches(w, wanted)).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw QuickActionException.invalid("Plusieurs sujets s'appellent « " + wanted + " » : donne son id.");
        }
        String known = subjects.stream().map(Workspace::getName).filter(Objects::nonNull).limit(12)
                .collect(Collectors.joining(", "));
        throw QuickActionException.invalid("Aucun sujet « " + wanted + " » sur ce poste"
                + (known.isEmpty() ? "." : " (sujets connus : " + known + ")."));
    }

    private Workspace requireTargetable(UUID userId, Workspace workspace, UUID targetId) {
        Workspace target;
        try {
            target = workspaceService.requireOwned(userId, targetId);
        } catch (WorkspaceNotFoundException ex) {
            throw QuickActionException.invalid("Aucun sujet de ce compte ne porte cet id.");
        }
        if (target.isHostTerminal() || target.isTeamsTerminal() || target.isJarvisThread() || target.isJarvisReleve()) {
            throw QuickActionException.invalid("Ce terminal n'est pas un sujet : une action rapide vise un sujet.");
        }
        if (workspace.getHostId() != null && !workspace.getHostId().equals(target.getHostId())) {
            throw QuickActionException.invalid("Ce sujet n'est pas sur le poste de ce terminal.");
        }
        return target;
    }

    private static Optional<Workspace> matchSubject(List<Workspace> subjects, String wanted) {
        List<Workspace> found = subjects.stream().filter(w -> matches(w, wanted)).toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    private static boolean matches(Workspace w, String wanted) {
        String key = subjectKey(wanted);
        return key.equals(subjectKey(w.getName())) || key.equals(subjectKey(w.getProjectPath()));
    }

    private static String subjectKey(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.strip().replace('\\', '/');
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.toLowerCase(Locale.ROOT);
    }

    private Optional<Workspace> ownedSubject(UUID userId, UUID id) {
        try {
            return Optional.of(workspaceService.requireOwned(userId, id));
        } catch (WorkspaceNotFoundException ex) {
            return Optional.empty();
        }
    }

    private QuickAction requireAction(UUID userId, UUID actionId) {
        if (actionId == null) {
            throw QuickActionException.notFound("Action rapide introuvable.");
        }
        return actions.findByIdAndUserId(actionId, userId)
                .orElseThrow(() -> QuickActionException.notFound("Action rapide introuvable."));
    }

    private static void requireOffered(Workspace workspace) {
        if (!offeredIn(workspace)) {
            throw QuickActionException.invalid("Les actions rapides n'existent pas dans ce terminal.");
        }
    }

    private static void applyLabel(QuickAction action, String raw) {
        String label = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (label.isEmpty()) {
            throw QuickActionException.invalid("Le libellé est obligatoire.");
        }
        if (label.length() > MAX_LABEL_CHARS) {
            throw QuickActionException.invalid("Libellé trop long (" + MAX_LABEL_CHARS + " caractères au plus).");
        }
        action.setLabel(label);
        action.setLabelKey(SkillTriggers.key(label));
    }

    private static void applyPhrase(QuickAction action, String raw) {
        String phrase = raw == null ? "" : raw.strip();
        if (phrase.isEmpty()) {
            throw QuickActionException.invalid("La phrase de démarrage est obligatoire.");
        }
        if (phrase.length() > MAX_PHRASE_CHARS) {
            throw QuickActionException.invalid("Phrase trop longue (" + MAX_PHRASE_CHARS + " caractères au plus).");
        }
        action.setPhrase(phrase);
    }

    private static void applyScope(QuickAction action, Workspace workspace, String raw) {
        String scope = raw.strip().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw QuickActionException.invalid("Portée inconnue : SUJET, POSTE ou COMPTE.");
        }
        if (QuickAction.POSTE.equals(scope) && workspace.getHostId() == null) {
            throw QuickActionException.invalid("Ce terminal n'a pas de poste : portée SUJET ou COMPTE.");
        }
        action.setScope(scope);
        action.setHostId(QuickAction.POSTE.equals(scope) ? workspace.getHostId() : null);
        action.setWorkspaceId(QuickAction.SUJET.equals(scope) ? workspace.getId() : null);
    }

    private void applyTarget(UUID userId, Workspace workspace, QuickAction action, UUID targetId) {
        action.setTargetWorkspaceId(targetId == null ? null : requireTargetable(userId, workspace, targetId).getId());
    }

    private static String click(String raw) {
        String mode = raw.strip().toUpperCase(Locale.ROOT);
        if (!CLICKS.contains(mode)) {
            throw QuickActionException.invalid("Clic inconnu : DEPOSER ou ENVOYER.");
        }
        return mode;
    }

    private static void applySuggestion(QuickAction action, List<Integer> rawDays, String rawMoment) {
        if ((rawDays == null || rawDays.isEmpty()) && (rawMoment == null || rawMoment.isBlank())) {
            action.setSuggestDays(null);
            action.setSuggestMoment(null);
            return;
        }
        Set<Integer> days = new TreeSet<>();
        if (rawDays == null || rawDays.isEmpty()) {
            days.addAll(List.of(1, 2, 3, 4, 5));
        } else {
            for (Integer d : rawDays) {
                if (d == null || d < 1 || d > 7) {
                    throw QuickActionException.invalid("Jour de suggestion hors 1 (lundi) … 7 (dimanche).");
                }
                days.add(d);
            }
        }
        String moment = rawMoment == null || rawMoment.isBlank() ? "JOURNEE" : rawMoment.strip().toUpperCase(Locale.ROOT);
        if (!MOMENTS.contains(moment)) {
            throw QuickActionException.invalid("Moment inconnu : MATIN, APRES_MIDI ou JOURNEE.");
        }
        action.setSuggestDays(days.stream().map(String::valueOf).collect(Collectors.joining(",")));
        action.setSuggestMoment(moment);
    }

    private void requireUniqueLabel(UUID userId, Workspace workspace, QuickAction action) {
        boolean taken = visible(userId, workspace).stream()
                .anyMatch(other -> !other.getId().equals(action.getId()) && other.getLabelKey().equals(action.getLabelKey()));
        if (taken) {
            throw QuickActionException.conflict("Une action « " + action.getLabel() + " » existe déjà ici.");
        }
    }

    private QuickActionView viewOf(UUID userId, QuickAction a) {
        Optional<Workspace> target = a.getTargetWorkspaceId() == null ? Optional.empty()
                : ownedSubject(userId, a.getTargetWorkspaceId());
        OffsetDateTime last = launches.findByUserIdAndActionKey(userId, "c:" + a.getId())
                .map(QuickActionLaunch::getLastLaunchedAt).orElse(null);
        return new Draft("c:" + a.getId(), QuickActionDtos.PERSO, a.getId(), a.getLabel(), a.getPhrase(), null,
                a.getScope(), a.getTargetWorkspaceId(), target.map(Workspace::getName).orElse(null),
                a.getTargetWorkspaceId() != null && target.isEmpty(), a.getClickMode(), days(a.getSuggestDays()),
                a.getSuggestMoment()).view(last);
    }

    static List<Integer> days(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /** Une action de la liste avant son dernier lancement. */
    private record Draft(String key, String source, UUID id, String label, String phrase, String skill, String scope,
            UUID targetWorkspaceId, String targetName, boolean targetMissing, String clickMode, List<Integer> suggestDays,
            String suggestMoment) {

        QuickActionView view(OffsetDateTime lastRunAt) {
            return new QuickActionView(key, source, id, label, phrase, skill, scope, targetWorkspaceId, targetName,
                    targetMissing, clickMode, suggestDays, suggestMoment, lastRunAt);
        }
    }
}
