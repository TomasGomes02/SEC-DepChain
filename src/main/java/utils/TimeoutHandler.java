package utils;

import consensus.BlockchainState;
import logger.Logger;
import consensus.BlockchainConsensus;
import transactions.Transaction;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TimeoutHandler {
    public final ScheduledExecutorService timeoutExecutor;
    public ScheduledFuture<?> currentTimeout;
    public long timeoutDurationMs;
    private final BlockchainConsensus member;
    private final BlockchainState state;

    public TimeoutHandler(BlockchainConsensus member) {
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        this.timeoutDurationMs = 20000;
        this.member = member;
        this.state = member.getState();
    }

    public void onTimeout() {
        synchronized (member) {
            ArrayList<Transaction> pendingTransactions = member.getState().pendingTransactions;
            if (pendingTransactions.isEmpty()) {
                return;
            }
            Logger.info("" + member.getId(), "[TIMEOUT] Timed out in view "
                    + state.getCurrView() + " — moving to view " + (state.getCurrView() + 1));

            while (state.getPhase() != consensus.ConsensusPhase.PREPARE) {
                state.nextPhase();
            }
            member.setReadyToPropose(false);
            state.nextView();
            member.sendNewView();
        }
    }

    public void resetTimeout() {
        Logger.debug("" + member.getId(), "[TIMEOUT] Resetting timeout on phase " +
                member.getState().getPhase());
        scheduleTimeout(timeoutDurationMs);
    }

    private void scheduleTimeout(long delayMs) {
        if (currentTimeout != null && !currentTimeout.isDone()) {
            currentTimeout.cancel(false);
        }
        currentTimeout = timeoutExecutor.schedule(this::onTimeout, delayMs, TimeUnit.MILLISECONDS);
    }
}
