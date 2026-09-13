package fr.claudegateway.radar.sync;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.RadarRunnerUnavailableException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarTeamsDisabledException;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;

/**
 * <b>La vérification guidée</b> (F-100 / SF-100-01) : demander au runner du poste ce qu'il voit de la
 * session Microsoft, fusionner avec ce qu'il avait déjà vu, l'enregistrer sur le poste.
 *
 * <p><b>Aucune adresse n'est demandée ni détectée</b> (correction du PO) : la vérification porte sur
 * ce qui change vraiment d'un client à l'autre — la session et les droits.</p>
 */
@Service
public class RadarVerificationService {

    private final RadarRunnerCalls calls;
    private final RadarHostSettingsRepository settings;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RadarVerificationService(RadarRunnerCalls calls, RadarHostSettingsRepository settings,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager, Clock clock) {
        this.calls = calls;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Vérifie maintenant. L'appel au runner est fait <b>hors transaction</b> (il peut durer 20 s) ; rien
     * n'est écrit s'il échoue.
     *
     * @throws RadarRunnerUnavailableException poste hors ligne, délai dépassé, réponse illisible
     * @throws RadarTeamsDisabledException     volet Teams absent du poste
     */
    public RadarVerification verify(RadarScope scope) {
        RunnerCallResult result = calls.call(scope, RadarRunnerCalls.VERIFY, objectMapper.createObjectNode(),
                RadarRunnerCalls.VERIFY_TIMEOUT_MS);
        if (!result.ok()) {
            throw failure(result);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        RadarVerification seen;
        try {
            seen = RadarVerification.fromRunner(objectMapper.readTree(result.content()), now);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new RadarRunnerUnavailableException(
                    "Réponse illisible du poste : mettez le runner à jour, puis recommencez.");
        }
        return transactions.execute(status -> {
            RadarHostSettings row = settingsOf(scope);
            RadarVerification merged = seen.mergedOnto(read(row));
            row.setVerification(write(merged));
            row.setVerifiedAt(now);
            settings.save(row);
            return merged;
        });
    }

    /** La dernière vérification du poste, sans appeler le runner. */
    @Transactional(readOnly = true)
    public RadarVerification current(RadarScope scope) {
        return settings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .map(this::read)
                .orElse(RadarVerification.none());
    }

    /** Recommence la vérification : les cases sont décochées. */
    @Transactional
    public RadarVerification reset(RadarScope scope) {
        settings.findByUserIdAndHostId(scope.userId(), scope.hostId()).ifPresent(row -> {
            row.setVerification(null);
            row.setVerifiedAt(null);
            settings.save(row);
        });
        return RadarVerification.none();
    }

    /** Les réglages du poste, créés à la première écriture. */
    RadarHostSettings settingsOf(RadarScope scope) {
        return settings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .orElseGet(() -> RadarHostSettings.builder().userId(scope.userId()).hostId(scope.hostId()).build());
    }

    private RadarVerification read(RadarHostSettings row) {
        if (row == null || row.getVerification() == null) {
            return RadarVerification.none();
        }
        try {
            RadarVerification stored = objectMapper.readValue(row.getVerification(), RadarVerification.class);
            return stored.session() == null ? RadarVerification.none() : stored;
        } catch (JsonProcessingException e) {
            return RadarVerification.none(); // un état illisible se revérifie, il ne se devine pas
        }
    }

    private String write(RadarVerification verification) {
        try {
            String json = objectMapper.writeValueAsString(verification);
            return json.length() <= RadarHostSettings.MAX_VERIFICATION_CHARS ? json : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static RuntimeException failure(RunnerCallResult result) {
        if (RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())) {
            return new RadarTeamsDisabledException("Le volet Teams n'est pas actif sur ce poste (désactivé, ou "
                    + "runner trop ancien) : activez-le ou mettez le runner à jour, puis recommencez.");
        }
        if (RunnerErrorCodes.RUNNER_TIMEOUT.equals(result.errorCode())) {
            return new RadarRunnerUnavailableException("Le poste n'a pas répondu à temps : vérifiez que Teams est "
                    + "ouvert dans Chrome, puis recommencez.");
        }
        return new RadarRunnerUnavailableException("Poste hors ligne : lancez le runner, puis recommencez.");
    }
}
