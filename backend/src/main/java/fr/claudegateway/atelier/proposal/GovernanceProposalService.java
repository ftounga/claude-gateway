package fr.claudegateway.atelier.proposal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.promptsource.PromptSourceStore;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * <b>L'agent propose, l'utilisateur valide</b> (F-177 / SF-177-02, décisions D3 et D4).
 *
 * <p>{@link #propose} ne fait que <b>lire</b> le fichier visé et ranger la proposition : rien ne
 * s'écrit chez le client dans son dos. {@link #apply} — le clic [Appliquer] — relit le fichier,
 * <b>refuse s'il a changé</b> depuis la proposition, écrit via le runner (même chemin que le dépôt de
 * gouvernance) et trace l'empreinte écrite.</p>
 *
 * <p><b>Isolation</b> : le projet vient toujours de {@code requireOwned} (le tour, ou l'URL vérifiée) ;
 * la proposition se relit par {@code id + user_id + workspace_id} ; la racine visée est celle du poste
 * <b>de ce projet</b>. Le contenu des fichiers n'est jamais journalisé.</p>
 */
@Service
public class GovernanceProposalService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceProposalService.class);

    public static final String REGLE = "REGLE";
    public static final String SKILL = "SKILL";
    public static final String GABARIT = "GABARIT";
    public static final String POSTE = "POSTE";
    public static final String SUJET = "SUJET";

    /** Fichier des règles du client. */
    public static final String RULES_FILE = PromptSourceStore.GOVERNANCE_FILE;
    /** Dossier des skills (même préfixe que le catalogue de la consigne). */
    public static final String SKILLS_DIR = ".claude/skills/";
    /** Dossier des gabarits. */
    public static final String TEMPLATES_DIR = ".claude/gabarits/";

    static final int MAX_NAME_CHARS = 120;
    static final int MAX_SLUG_CHARS = 60;
    static final int MAX_RULE_CHARS = 4_000;
    static final int MAX_FILE_CHARS = 20_000;
    static final int MAX_REASON_CHARS = 1_000;
    static final int MAX_DIFF_LINES = 200;

    /** Nom d'outil du journal runner pour l'écriture validée. */
    static final String AUDIT_TOOL = "gouvernance_appliquer";
    static final String NOT_FOUND = "not_found";

    private final GovernanceProposalRepository proposals;
    private final WorkspaceService workspaceService;
    private final RunnerToolGateway gateway;
    private final RunnerAuditService auditService;
    private final ObjectMapper objectMapper;
    private PromptSourceStore promptSourceStore;

    public GovernanceProposalService(GovernanceProposalRepository proposals, WorkspaceService workspaceService,
            RunnerToolGateway gateway, RunnerAuditService auditService, ObjectMapper objectMapper) {
        this.proposals = proposals;
        this.workspaceService = workspaceService;
        this.gateway = gateway;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setPromptSourceStore(PromptSourceStore promptSourceStore) {
        this.promptSourceStore = promptSourceStore;
    }

    // ------------------------------------------------------------------ proposer

    /**
     * Range une proposition et rend sa carte. <b>N'écrit rien</b> sur la machine.
     *
     * @param workspace le projet du tour, déjà chargé par {@code requireOwned}
     * @throws InvalidProposalException avec le message destiné à l'agent
     */
    @Transactional
    public GovernanceProposalBlock propose(UUID userId, Workspace workspace, String rawType, String rawScope,
            String rawName, String rawContent, String rawReason) {
        String type = upper(rawType);
        String scope = upper(rawScope);
        if (!List.of(REGLE, SKILL, GABARIT).contains(type)) {
            throw new InvalidProposalException("type doit valoir REGLE, SKILL ou GABARIT.");
        }
        if (!List.of(POSTE, SUJET).contains(scope)) {
            throw new InvalidProposalException("portee doit valoir POSTE ou SUJET.");
        }
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_CHARS) {
            throw new InvalidProposalException("nom obligatoire, " + MAX_NAME_CHARS + " caractères au plus.");
        }
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty()) {
            throw new InvalidProposalException("contenu obligatoire : le texte de la règle, du skill ou du gabarit.");
        }
        int max = REGLE.equals(type) ? MAX_RULE_CHARS : MAX_FILE_CHARS;
        if (content.length() > max) {
            throw new InvalidProposalException("contenu trop long (" + max + " caractères au plus) : resserre-le.");
        }
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() > MAX_REASON_CHARS) {
            reason = reason.substring(0, MAX_REASON_CHARS);
        }
        checkScope(workspace, scope);
        String path = pathOf(type, name);
        if (path == null) {
            throw new InvalidProposalException("nom inutilisable comme nom de fichier : donne un nom avec des lettres.");
        }

        Optional<String> current = readCurrent(workspace, scope, path, false);
        String before = current.orElse(null);
        String after;
        List<GovernanceProposalBlock.DiffLine> diff = new ArrayList<>();
        if (REGLE.equals(type)) {
            after = upsertRule(before, name, content, diff);
        } else {
            after = withFrontMatter(type, name, content, reason);
            if (before != null) {
                before.lines().forEach(line -> diff.add(new GovernanceProposalBlock.DiffLine("DEL", line)));
            }
            after.lines().forEach(line -> diff.add(new GovernanceProposalBlock.DiffLine("ADD", line)));
        }
        if (before != null && before.equals(after)) {
            throw new InvalidProposalException("Rien à changer : " + path + " contient déjà exactement cela.");
        }
        int omitted = Math.max(0, diff.size() - MAX_DIFF_LINES);
        List<GovernanceProposalBlock.DiffLine> shown = List.copyOf(diff.subList(0, Math.min(diff.size(),
                MAX_DIFF_LINES)));

        GovernanceProposal saved = proposals.save(GovernanceProposal.builder()
                .userId(userId).workspaceId(workspace.getId()).hostId(workspace.getHostId())
                .type(type).scope(scope).name(name).path(path).reason(reason.isEmpty() ? null : reason)
                .content(after).diffJson(toJson(shown))
                .baseDigest(before == null ? GovernanceProposal.ABSENT : digest(before))
                .status(GovernanceProposal.PENDING).createdAt(OffsetDateTime.now())
                .build());
        return new GovernanceProposalBlock(saved.getId(), type, scope, name, path,
                reason.isEmpty() ? null : reason, before == null, shown, omitted);
    }

    // ------------------------------------------------------------------ décider

    /** La proposition et son statut. */
    @Transactional(readOnly = true)
    public GovernanceProposalView get(UUID userId, UUID workspaceId, UUID proposalId) {
        workspaceService.requireOwned(userId, workspaceId);
        return GovernanceProposalView.of(require(userId, workspaceId, proposalId));
    }

    /**
     * [Appliquer] : écrit le fichier, si — et seulement si — il n'a pas changé depuis la proposition.
     *
     * @throws ProposalConflictException  déjà décidée, ou fichier modifié depuis
     * @throws ProposalUnreachableException poste injoignable ou écriture refusée
     */
    @Transactional
    public GovernanceProposalView apply(UUID userId, UUID workspaceId, UUID proposalId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        GovernanceProposal proposal = require(userId, workspaceId, proposalId);
        if (!GovernanceProposal.PENDING.equals(proposal.getStatus())) {
            throw new ProposalConflictException("Cette proposition est déjà "
                    + (GovernanceProposal.APPLIED.equals(proposal.getStatus()) ? "appliquée." : "refusée."));
        }
        checkScope(workspace, proposal.getScope());
        Optional<String> current = readCurrent(workspace, proposal.getScope(), proposal.getPath(), true);
        String now = current.map(GovernanceProposalService::digest).orElse(GovernanceProposal.ABSENT);
        if (!now.equals(proposal.getBaseDigest())) {
            throw new ProposalConflictException(proposal.getPath() + " a changé depuis la proposition : "
                    + "redemande-la à l'agent pour repartir du fichier actuel.");
        }
        write(userId, workspace, proposal.getScope(), proposal.getPath(), proposal.getContent());
        proposal.setStatus(GovernanceProposal.APPLIED);
        proposal.setAppliedDigest(digest(proposal.getContent()));
        proposal.setDecidedAt(OffsetDateTime.now());
        proposals.save(proposal);
        refreshPromptCache(userId, workspace, proposal);
        return GovernanceProposalView.of(proposal);
    }

    /** [Refuser] : la proposition est écartée, rien n'est écrit. */
    @Transactional
    public GovernanceProposalView refuse(UUID userId, UUID workspaceId, UUID proposalId) {
        workspaceService.requireOwned(userId, workspaceId);
        GovernanceProposal proposal = require(userId, workspaceId, proposalId);
        if (!GovernanceProposal.PENDING.equals(proposal.getStatus())) {
            throw new ProposalConflictException("Cette proposition est déjà décidée.");
        }
        proposal.setStatus(GovernanceProposal.REFUSED);
        proposal.setDecidedAt(OffsetDateTime.now());
        proposals.save(proposal);
        return GovernanceProposalView.of(proposal);
    }

    // ------------------------------------------------------------------ internes

    private GovernanceProposal require(UUID userId, UUID workspaceId, UUID proposalId) {
        return proposals.findByIdAndUserIdAndWorkspaceId(proposalId, userId, workspaceId)
                .orElseThrow(() -> new ProposalNotFoundException("Proposition introuvable."));
    }

    /** La portée doit avoir un sens pour ce projet. */
    private static void checkScope(Workspace workspace, String scope) {
        if (POSTE.equals(scope) && (workspace.getHostId() == null || !workspace.isRunnerTarget())) {
            throw new InvalidProposalException("Ce projet n'a pas de poste : propose avec portee SUJET.");
        }
        if (SUJET.equals(scope) && workspace.isHostTerminal()) {
            throw new InvalidProposalException("Au terminal du poste, il n'y a pas de sujet courant : "
                    + "propose avec portee POSTE (ou depuis le terminal du sujet).");
        }
    }

    /** Le fichier visé, relatif à la racine choisie ; {@code null} si le nom ne donne aucun nom de fichier. */
    static String pathOf(String type, String name) {
        if (REGLE.equals(type)) {
            return RULES_FILE;
        }
        String slug = slug(name);
        if (slug.isEmpty()) {
            return null;
        }
        return (SKILL.equals(type) ? SKILLS_DIR : TEMPLATES_DIR) + slug + ".md";
    }

    /** {@code « Ticket Jira ! »} → {@code ticket-jira}. */
    static String slug(String name) {
        String plain = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String slug = plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG_CHARS) {
            slug = slug.substring(0, MAX_SLUG_CHARS).replaceAll("-+$", "");
        }
        return slug;
    }

    /**
     * Ajoute la règle comme section {@code ## nom} de {@code GOUVERNANCE.md}, ou remplace la section de
     * même titre. Remplit {@code diff}.
     */
    static String upsertRule(String before, String name, String body, List<GovernanceProposalBlock.DiffLine> diff) {
        String heading = "## " + name;
        String section = heading + "\n\n" + body.strip() + "\n";
        if (before == null || before.isBlank()) {
            String created = "# Gouvernance\n\n" + section;
            created.lines().forEach(line -> diff.add(new GovernanceProposalBlock.DiffLine("ADD", line)));
            return created;
        }
        List<String> lines = before.lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equalsIgnoreCase(heading)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            String trimmed = before.stripTrailing();
            diff.add(new GovernanceProposalBlock.DiffLine("CTX", lines.isEmpty() ? "" : lines.get(lines.size() - 1)));
            section.lines().forEach(line -> diff.add(new GovernanceProposalBlock.DiffLine("ADD", line)));
            return trimmed + "\n\n" + section;
        }
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ") || lines.get(i).startsWith("# ")) {
                end = i;
                break;
            }
        }
        lines.subList(start, end).forEach(line -> diff.add(new GovernanceProposalBlock.DiffLine("DEL", line)));
        section.lines().forEach(line -> diff.add(new GovernanceProposalBlock.DiffLine("ADD", line)));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < start; i++) {
            out.append(lines.get(i)).append('\n');
        }
        out.append(section);
        if (end < lines.size()) {
            out.append('\n');
            for (int i = end; i < lines.size(); i++) {
                out.append(lines.get(i)).append('\n');
            }
        }
        return out.toString();
    }

    /** Un skill sans en-tête reçoit {@code name}/{@code description}, lus par le catalogue de la consigne. */
    static String withFrontMatter(String type, String name, String content, String reason) {
        String body = content.strip() + "\n";
        if (!SKILL.equals(type) || body.startsWith("---")) {
            return body;
        }
        String description = (reason == null || reason.isBlank() ? name : reason).lines().findFirst()
                .orElse(name).strip();
        if (description.length() > 200) {
            description = description.substring(0, 200);
        }
        return "---\nname: " + slug(name) + "\ndescription: " + description + "\n---\n\n" + body;
    }

    /** Cible de lecture/écriture : racine du poste ({@code POSTE}) ou dossier du projet ({@code SUJET}). */
    private static RunnerTarget targetOf(Workspace workspace, String scope, boolean outsideTurn) {
        if (POSTE.equals(scope)) {
            return new RunnerTarget(workspace.getHostId(), outsideTurn ? null : workspace.getId(), "");
        }
        return RunnerTargets.of(workspace);
    }

    /**
     * Contenu actuel du fichier, ou vide s'il n'existe pas.
     *
     * @throws ProposalUnreachableException si la machine ne répond pas ou refuse : le doute n'écrit pas
     */
    private Optional<String> readCurrent(Workspace workspace, String scope, String path, boolean outsideTurn) {
        if (SUJET.equals(scope) && !workspace.isRunnerTarget()) {
            try {
                return Optional.of(workspaceService.readFile(workspace.getUserId(), workspace.getId(), path));
            } catch (RuntimeException ex) {
                return Optional.empty(); // Stockage hébergé : un fichier absent se lit comme une erreur.
            }
        }
        RunnerCallResult result = gateway.readFile(targetOf(workspace, scope, outsideTurn),
                UUID.randomUUID().toString(), path);
        if (result != null && result.ok()) {
            return Optional.of(result.content() == null ? "" : result.content());
        }
        if (result != null && NOT_FOUND.equals(result.errorCode())) {
            return Optional.empty();
        }
        throw new ProposalUnreachableException("Le poste n'a pas pu lire " + path
                + " : vérifie qu'il est en ligne, puis réessaie.");
    }

    private void write(UUID userId, Workspace workspace, String scope, String path, String content) {
        if (SUJET.equals(scope) && !workspace.isRunnerTarget()) {
            workspaceService.writeFile(userId, workspace.getId(), path, content);
            return;
        }
        RunnerTarget target = targetOf(workspace, scope, true);
        String callId = UUID.randomUUID().toString();
        RunnerCallResult result = gateway.writeFile(target, callId, path, content);
        auditService.recordCall(userId, target, callId, AUDIT_TOOL, path, result);
        if (result == null || !result.ok()) {
            log.info("Proposition de gouvernance non écrite (code={})", result == null ? null : result.errorCode());
            throw new ProposalUnreachableException("Le poste n'a pas pu écrire " + path
                    + " : vérifie qu'il est en ligne, puis réessaie.");
        }
    }

    /** Le tour suivant voit le fichier validé, dans ce projet et — pour les règles du poste — partout. */
    private void refreshPromptCache(UUID userId, Workspace workspace, GovernanceProposal proposal) {
        if (promptSourceStore == null || !workspace.isRunnerTarget()) {
            return;
        }
        try {
            if (POSTE.equals(proposal.getScope()) && RULES_FILE.equals(proposal.getPath())) {
                List<Workspace> ofHost = new ArrayList<>(workspaceService.listByHost(userId, workspace.getHostId()));
                workspaceService.findHostTerminal(userId, workspace.getHostId()).ifPresent(ofHost::add);
                promptSourceStore.putHostGovernance(userId, ofHost, proposal.getContent());
            } else if (SUJET.equals(proposal.getScope()) || workspace.isHostTerminal()) {
                promptSourceStore.putFile(userId, workspace, proposal.getPath(), proposal.getContent());
            }
        } catch (RuntimeException ex) {
            log.debug("Cache de consigne non mis à jour après application ({})", ex.getClass().getSimpleName());
        }
    }

    /** Les lignes de diff d'une proposition rangée. */
    public List<GovernanceProposalBlock.DiffLine> diffOf(GovernanceProposal proposal) {
        if (proposal.getDiffJson() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(proposal.getDiffJson(),
                    new TypeReference<List<GovernanceProposalBlock.DiffLine>>() { });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String toJson(List<GovernanceProposalBlock.DiffLine> diff) {
        try {
            return objectMapper.writeValueAsString(diff);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String upper(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    }

    static String digest(String content) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            return "h" + content.hashCode();
        }
    }
}
