package fr.claudegateway.atelier.deposit;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Reçoit un fichier déposé dans un terminal et l'écrit <b>là où l'agent l'atteint</b> (F-115 /
 * SF-115-01) : {@code entrees/} du workspace hébergé, ou {@code .atelier/entrees/} du poste par le
 * runner en transfert découpé. Applique les bornes, assainit le nom, vérifie le coupe-circuit et
 * l'isolation, puis enregistre le chemin déposé pour le tour (SF-115-03).
 *
 * <p><b>Provider-First / Gateway-First</b> : le service ne traite pas le fichier (ni OCR, ni
 * indexation) — il le transmet à l'endroit où l'agent le lira avec ses outils existants.</p>
 */
@Service
public class WorkspaceDepositService {

    /** Dossier de dépôt d'un workspace hébergé (le préfixe {@code entrees/} est ajouté par le service). */
    static final String RUNNER_DEPOSIT_DIR = ".atelier/entrees/";

    private final WorkspaceService workspaceService;
    private final RunnerToolGateway runnerToolGateway;
    private final RunnerLiveness runnerLiveness;
    private final AtelierDepositedFileRepository depositedFileRepository;

    private final long maxHostedBytes;
    private final long maxRunnerBytes;
    private final int maxFiles;
    private final int chunkBytes;

    public WorkspaceDepositService(WorkspaceService workspaceService,
            RunnerToolGateway runnerToolGateway, RunnerLiveness runnerLiveness,
            AtelierDepositedFileRepository depositedFileRepository,
            @Value("${app.atelier.deposit.max-hosted-bytes:8388608}") long maxHostedBytes,
            @Value("${app.atelier.deposit.max-runner-bytes:104857600}") long maxRunnerBytes,
            @Value("${app.atelier.deposit.max-files:20}") int maxFiles,
            @Value("${app.atelier.deposit.chunk-bytes:262144}") int chunkBytes) {
        this.workspaceService = workspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerLiveness = runnerLiveness;
        this.depositedFileRepository = depositedFileRepository;
        this.maxHostedBytes = maxHostedBytes;
        this.maxRunnerBytes = maxRunnerBytes;
        this.maxFiles = maxFiles;
        this.chunkBytes = chunkBytes;
    }

    /** Un fichier reçu (nom brut du client, type de média, octets). */
    public record IncomingFile(String originalName, String contentType, byte[] bytes) {
    }

    /**
     * Dépose une liste de fichiers dans un terminal. Isolation d'abord ({@code requireOwned}), puis
     * bornes, aiguillage hébergé/poste, écriture et enregistrement.
     *
     * @throws WorkspaceDepositException (statut nommé) sur tout refus
     */
    public DepositResponse deposit(UUID userId, UUID workspaceId, List<IncomingFile> files) {
        if (files == null || files.isEmpty()) {
            throw new WorkspaceDepositException(HttpStatus.BAD_REQUEST, "no_file",
                    "Aucun fichier déposé.");
        }
        if (files.size() > maxFiles) {
            throw new WorkspaceDepositException(HttpStatus.BAD_REQUEST, "too_many_files",
                    "Trop de fichiers en un dépôt (" + maxFiles + " au plus).");
        }
        // Isolation : le workspace d'autrui est introuvable (404), jamais « refusé ».
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        boolean runner = workspace.isRunnerTarget();

        List<DepositResponse.DepositedFile> results = new ArrayList<>();
        for (IncomingFile file : files) {
            String name = DepositFileName.sanitize(file.originalName());
            byte[] bytes = file.bytes() == null ? new byte[0] : file.bytes();
            String path = runner
                    ? depositToRunner(workspace, name, bytes)
                    : depositToHosted(userId, workspaceId, name, bytes, file.contentType());
            record(userId, workspaceId, path, bytes.length);
            results.add(new DepositResponse.DepositedFile(path, bytes.length,
                    runner ? "RUNNER" : "HOSTED"));
        }
        return new DepositResponse(results);
    }

    private String depositToHosted(UUID userId, UUID workspaceId, String name, byte[] bytes,
            String contentType) {
        if (bytes.length > maxHostedBytes) {
            throw tooLarge(maxHostedBytes);
        }
        return workspaceService.depositHostedFile(userId, workspaceId, name, bytes, contentType);
    }

    private String depositToRunner(Workspace workspace, String name, byte[] bytes) {
        if (bytes.length > maxRunnerBytes) {
            throw tooLarge(maxRunnerBytes);
        }
        // Coupe-circuit (SF-38-08) : un poste coupé ou hors ligne refuse le dépôt, sans rien tenter.
        if (!runnerLiveness.isAlive(workspace.getUserId(), workspace.getHostId())) {
            throw new WorkspaceDepositException(HttpStatus.CONFLICT, "runner_offline",
                    "Le poste est hors ligne : le fichier n'a pas été déposé.");
        }
        RunnerTarget target = RunnerTargets.of(workspace);
        String relPath = RUNNER_DEPOSIT_DIR + name;
        transferChunked(target, relPath, bytes);
        return relPath;
    }

    /** Transfert découpé au poste : tranches bornées, {@code offset} croissant, première tranche tronquante. */
    private void transferChunked(RunnerTarget target, String relPath, byte[] bytes) {
        if (bytes.length == 0) {
            // Fichier vide : une seule tranche vide qui crée/tronque le fichier.
            check(runnerToolGateway.writeFileBytes(target, callId(), relPath, "", 0L));
            return;
        }
        for (int pos = 0; pos < bytes.length; pos += chunkBytes) {
            int end = Math.min(pos + chunkBytes, bytes.length);
            byte[] slice = new byte[end - pos];
            System.arraycopy(bytes, pos, slice, 0, slice.length);
            String base64 = Base64.getEncoder().encodeToString(slice);
            check(runnerToolGateway.writeFileBytes(target, callId(), relPath, base64, pos));
        }
    }

    /** Traduit un échec de tranche en refus nommé, sans jamais laisser croire à un succès. */
    private void check(RunnerCallResult result) {
        if (result.ok()) {
            return;
        }
        String code = result.errorCode();
        if (isOffline(code)) {
            throw new WorkspaceDepositException(HttpStatus.CONFLICT, "runner_offline",
                    "Le poste ne répond plus : le fichier n'a pas été déposé.");
        }
        String message = result.errorMessage() == null || result.errorMessage().isBlank()
                ? "Le dépôt sur le poste a échoué."
                : result.errorMessage();
        throw new WorkspaceDepositException(HttpStatus.BAD_GATEWAY, "deposit_failed", message);
    }

    private static boolean isOffline(String code) {
        return RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(code)
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(code)
                || RunnerErrorCodes.RUNNER_TIMEOUT.equals(code)
                || RunnerErrorCodes.RUNNER_PROTOCOL_ERROR.equals(code);
    }

    private WorkspaceDepositException tooLarge(long limitBytes) {
        long mib = limitBytes / (1024 * 1024);
        return new WorkspaceDepositException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                "Fichier trop volumineux (" + mib + " Mo au plus).");
    }

    private void record(UUID userId, UUID workspaceId, String path, long size) {
        depositedFileRepository.save(AtelierDepositedFile.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .path(path)
                .sizeBytes(size)
                .build());
    }

    private static String callId() {
        return UUID.randomUUID().toString();
    }
}
