package fr.claudegateway.atelier;

import java.util.List;
import java.util.UUID;

import fr.claudegateway.atelier.actions.AttenteBlock;
import fr.claudegateway.atelier.proposal.GovernanceProposalBlock;
import fr.claudegateway.mail.ClientMailReceipt;
import fr.claudegateway.pages.PageBlock;
import fr.claudegateway.teams.block.TeamsBlockCard;

/**
 * <b>Le relevé d'un tour</b> (F-185 / SF-185-02) : enveloppe l'écouteur du tour streamé, lui transmet
 * <b>tout</b> sans rien changer, et retient ce qui décide de la notification de fin de tour — une
 * décision laissée à confirmer, une demande restée sans réponse, un poste perdu.
 *
 * <p>Un relevé par tour : les drapeaux sont {@code volatile}, l'écouteur pouvant être appelé depuis
 * le fil d'un sous-agent.</p>
 */
final class TurnSignals implements AtelierProgressListener {

    /** Le libellé d'une résolution par délai, côté question comme côté autorisation. */
    static final String TIMEOUT = "timeout";

    private final AtelierProgressListener delegate;
    private volatile boolean validationAwaiting;
    private volatile boolean timedOut;
    private volatile boolean machineLost;

    TurnSignals(AtelierProgressListener delegate) {
        this.delegate = delegate == null ? AtelierProgressListener.NOOP : delegate;
    }

    /** Une passation, une proposition de gouvernance ou une fermeture d'attente attend un geste. */
    boolean validationAwaiting() {
        return validationAwaiting;
    }

    /** Une question ou une autorisation est restée sans réponse dans le délai. */
    boolean timedOut() {
        return timedOut;
    }

    /** Le poste a été perdu pendant le tour. */
    boolean machineLost() {
        return machineLost;
    }

    @Override
    public void onAction(AtelierStepEvent step) {
        delegate.onAction(step);
    }

    @Override
    public void onText(String text) {
        delegate.onText(text);
    }

    @Override
    public void onConfirmRequest(AtelierConfirmRequest request) {
        delegate.onConfirmRequest(request);
    }

    @Override
    public void onConfirmResolved(AtelierConfirmResolved resolved) {
        if (resolved != null && TIMEOUT.equals(resolved.decision())) {
            timedOut = true;
        }
        delegate.onConfirmResolved(resolved);
    }

    @Override
    public void onQuestion(AtelierQuestionRequest request) {
        delegate.onQuestion(request);
    }

    @Override
    public void onQuestionResolved(AtelierQuestionResolved resolved) {
        if (resolved != null && TIMEOUT.equals(resolved.status())) {
            timedOut = true;
        }
        delegate.onQuestionResolved(resolved);
    }

    @Override
    public void onOutput(String chunk) {
        delegate.onOutput(chunk);
    }

    @Override
    public void onPlan(AtelierPlan plan) {
        delegate.onPlan(plan);
    }

    @Override
    public void onProgress(long tokens) {
        delegate.onProgress(tokens);
    }

    @Override
    public void onCompactionStarted() {
        delegate.onCompactionStarted();
    }

    @Override
    public void onCompactionDone(int summarizedTurns) {
        delegate.onCompactionDone(summarizedTurns);
    }

    @Override
    public void onRecalled(String repere) {
        delegate.onRecalled(repere);
    }

    @Override
    public void onCard(String toolUseId, TeamsBlockCard card) {
        delegate.onCard(toolUseId, card);
    }

    @Override
    public void onEmail(String toolUseId, ClientMailReceipt receipt) {
        delegate.onEmail(toolUseId, receipt);
    }

    @Override
    public void onPage(String toolUseId, PageBlock page) {
        delegate.onPage(toolUseId, page);
    }

    @Override
    public void onAttente(String toolUseId, AttenteBlock attente) {
        if (attente != null && AttenteBlock.PROPOSED.equals(attente.kind())) {
            validationAwaiting = true;
        }
        delegate.onAttente(toolUseId, attente);
    }

    @Override
    public void onHandoff(String toolUseId, SubjectHandoff handoff) {
        if (handoff != null) {
            validationAwaiting = true;
        }
        delegate.onHandoff(toolUseId, handoff);
    }

    @Override
    public void onGovernanceProposal(String toolUseId, GovernanceProposalBlock proposal) {
        if (proposal != null) {
            validationAwaiting = true;
        }
        delegate.onGovernanceProposal(toolUseId, proposal);
    }

    @Override
    public void onRunnerOffline(UUID hostId) {
        machineLost = true;
        delegate.onRunnerOffline(hostId);
    }

    @Override
    public List<AtelierSteer> takeSteers() {
        return delegate.takeSteers();
    }

    @Override
    public void onSteerApplied(AtelierSteer steer, int step) {
        delegate.onSteerApplied(steer, step);
    }
}
