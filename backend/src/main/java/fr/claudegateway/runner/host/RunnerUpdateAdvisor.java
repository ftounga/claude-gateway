package fr.claudegateway.runner.host;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fr.claudegateway.runner.ServedRunnerVersion;
import fr.claudegateway.runner.host.dto.RunnerUpdateView;
import fr.claudegateway.runner.host.dto.RunnerUpdateView.Status;
import fr.claudegateway.runner.update.RunnerUpdateArtifacts;
import fr.claudegateway.runner.update.RunnerUpdateJournalRepository;
import fr.claudegateway.runner.update.RunnerUpdateProgress;

/**
 * Dit où en est le runner d'un poste (F-111 / SF-111-01) : à jour, mise à jour disponible d'un clic,
 * manuelle une dernière fois, manuelle faute de Java — et si elle est <b>requise</b>.
 *
 * <p>Tout est tiré de ce que le runner a <b>déclaré</b> et de ce que la gateway <b>sert</b> : rien
 * n'est deviné. Et rien n'est refusé : un runner en retard continue de travailler.</p>
 */
@Component
public class RunnerUpdateAdvisor {

    /** Niveau de contrat à partir duquel le runner comprend la commande {@code update} (SF-111-04). */
    public static final int UPDATE_CONTRACT = 2;

    private final ServedRunnerVersion served;
    /** Les artefacts signés servis (F-111 / SF-111-03) : notes et possibilité d'installer. */
    private final RunnerUpdateArtifacts artifacts;
    /** Le journal des mises à jour (F-111 / SF-111-04) : la dernière de chaque poste. */
    private final RunnerUpdateJournalRepository journal;

    @Autowired
    public RunnerUpdateAdvisor(ServedRunnerVersion served, RunnerUpdateArtifacts artifacts,
            RunnerUpdateJournalRepository journal) {
        this.served = served;
        this.artifacts = artifacts;
        this.journal = journal;
    }

    /** Sans journal (tests). */
    public RunnerUpdateAdvisor(ServedRunnerVersion served, RunnerUpdateArtifacts artifacts) {
        this(served, artifacts, null);
    }

    /** Sans artefacts de mise à jour (tests) : rien n'est installable, aucune note. */
    public RunnerUpdateAdvisor(ServedRunnerVersion served) {
        this(served, null, null);
    }

    /** Le conseil pour ce poste, sachant s'il sert Teams. */
    public RunnerUpdateView advise(RunnerHost host, boolean usesTeams) {
        RunnerUpdateView view = advise(host, served.version(), served.minJava(), usesTeams);
        if (artifacts != null) {
            // Les notes et la signature ne valent que pour la version que la gateway compare : un
            // manifeste d'une autre version (empaquetage incohérent) ne promet rien.
            RunnerUpdateView base = view;
            view = artifacts.manifest()
                    .filter(manifest -> manifest.id().equals(base.servedId()))
                    .map(manifest -> base.withArtifacts(manifest.notes(), artifacts.signedUpdateAvailable(),
                            oneClick(base, host, artifacts.signedUpdateAvailable())))
                    .orElse(base);
        }
        if (journal != null && host.getId() != null) {
            OffsetDateTime now = OffsetDateTime.now();
            RunnerUpdateView base = view;
            view = journal.findFirstByHostIdOrderByRequestedAtDesc(host.getId())
                    .map(entry -> base.withProgress(RunnerUpdateProgress.of(entry, now)))
                    .orElse(base);
        }
        return view;
    }

    /** Le bouton a un sens : disponible, version signée servie, runner qui comprend la commande. */
    static boolean oneClick(RunnerUpdateView view, RunnerHost host, boolean signed) {
        return Status.AVAILABLE.name().equals(view.status()) && signed && host.getRunnerContract() != null
                && host.getRunnerContract() >= UPDATE_CONTRACT;
    }

    /**
     * Calcul pur, sans composant : c'est lui que les tests exercent.
     *
     * @param servedId identifiant de la version servie, ou {@code null}
     * @param minJava  Java minimal de la version servie
     */
    static RunnerUpdateView advise(RunnerHost host, String servedId, int minJava, boolean usesTeams) {
        String installedId = host.getRunnerVersion();
        List<String> capabilities = host.getRunnerCapabilities() == null ? List.of()
                : Arrays.asList(host.getRunnerCapabilities().split(","));
        // Un runner d'avant F-111 (aucun contrat déclaré) privé de `teams` : c'est le cas vécu du
        // terminal Teams sans ses outils. Un runner récent sans `teams` a été lancé avec --no-teams.
        boolean teamsMissing = installedId != null && host.getRunnerContract() == null
                && !capabilities.contains("teams");
        Status status = status(host, installedId, servedId, minJava);
        boolean older = status == Status.AVAILABLE || status == Status.MANUAL_LAST_TIME
                || status == Status.MANUAL_JAVA;
        return new RunnerUpdateView(status.name(), older && usesTeams && teamsMissing,
                RunnerVersions.semantic(installedId), installedId, RunnerVersions.semantic(servedId),
                servedId, host.getRunnerJava(), minJava, teamsMissing, List.of(), false, false, null);
    }

    private static Status status(RunnerHost host, String installedId, String servedId, int minJava) {
        if (servedId == null || installedId == null) {
            return Status.UNKNOWN;
        }
        Integer order = RunnerVersions.compare(installedId, servedId).orElse(null);
        if (order == null) {
            return Status.UNKNOWN;
        }
        if (order >= 0) {
            return Status.UP_TO_DATE;
        }
        if (!Boolean.TRUE.equals(host.getRunnerLauncher())) {
            return Status.MANUAL_LAST_TIME;
        }
        Integer java = host.getRunnerJava();
        if (java != null && java < minJava) {
            return Status.MANUAL_JAVA;
        }
        return Status.AVAILABLE;
    }
}
