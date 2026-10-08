package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FeeManager {
    public static final String USER_TARGET_ID = "USER";
    /** Stateless mode: every job is an independent random roll (historical default). */
    public static final String ROLL_MODE_RANDOM = "random";
    /**
     * Stateful mode: a per-coin urn with persistent credit counters (smooth weighted
     * round-robin). Every roll adds each target's percentage to its credit, the highest
     * credit wins and pays 100 back. This keeps the realised share within one job of the
     * configured percentage instead of relying on the law of large numbers.
     */
    public static final String ROLL_MODE_STATEFUL = "stateful";

    private final ConcurrentHashMap<String, AtomicReference<State>> coinStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RollState> rollStates = new ConcurrentHashMap<>();

    @Value("${proxy.fee.roll-mode:" + ROLL_MODE_RANDOM + "}")
    private volatile String rollMode = ROLL_MODE_RANDOM;

    private record State(List<FeeTarget> feeTargets, Map<String, FeeTarget> targetMap, double totalFeePercentage) {}

    /** Per-coin urn state. Survives fee-target refreshes so the balance is not reset every poll. */
    private static final class RollState {
        private Map<String, Double> credits = new LinkedHashMap<>();
    }

    public void updateTargets(String coin, List<FeeTarget> feeTargets) {
        Map<String, FeeTarget> targetMap = new HashMap<>();
        double totalFeePercentage = 0.0;

        for (FeeTarget target : feeTargets) {
            targetMap.put(target.targetId(), target);
            totalFeePercentage += target.percentage();
        }

        coinStates.computeIfAbsent(coin, k -> new AtomicReference<>()).set(new State(feeTargets, targetMap, totalFeePercentage));
        reconcileRollState(coin, feeTargets);
    }

    /** Keeps accumulated credits for targets that still exist; new targets start at zero. */
    private void reconcileRollState(String coin, List<FeeTarget> feeTargets) {
        RollState roll = rollStates.computeIfAbsent(coin, k -> new RollState());
        synchronized (roll) {
            Map<String, Double> next = new LinkedHashMap<>();
            for (FeeTarget target : feeTargets) {
                if (target.percentage() > 0)
                    next.put(target.targetId(), roll.credits.getOrDefault(target.targetId(), 0.0));
            }
            next.put(USER_TARGET_ID, roll.credits.getOrDefault(USER_TARGET_ID, 0.0));
            roll.credits = next;
        }
    }

    public FeeTarget getTarget(String coin, String targetId) {
        State state = getCoinState(coin);
        return state != null ? state.targetMap().get(targetId) : null;
    }

    public List<FeeTarget> getFeeTargets(String coin) {
        State state = getCoinState(coin);
        return state != null ? state.feeTargets() : List.of();
    }

    public String getRollMode() {
        return rollMode;
    }

    public void setRollMode(String mode) {
        this.rollMode = ROLL_MODE_STATEFUL.equalsIgnoreCase(mode) ? ROLL_MODE_STATEFUL : ROLL_MODE_RANDOM;
    }

    public String rollNextJobTarget(String coin) {
        State currentState = getCoinState(coin);

        if (currentState == null || currentState.feeTargets().isEmpty() || currentState.totalFeePercentage() <= 0) {
            return USER_TARGET_ID;
        }
        if (ROLL_MODE_STATEFUL.equalsIgnoreCase(rollMode)) {
            return rollStateful(coin, currentState);
        }
        double roll = ThreadLocalRandom.current().nextDouble(100.0);
        boolean shouldMineForUser = roll >= currentState.totalFeePercentage();

        if (shouldMineForUser) {
            return USER_TARGET_ID;
        }

        double currentThreshold = 0.0;
        for (FeeTarget target : currentState.feeTargets()) {
            currentThreshold += target.percentage();
            if (roll < currentThreshold) {
                return target.targetId();
            }
        }

        return USER_TARGET_ID;
    }

    /**
     * Smooth weighted round-robin draw. Credits stay in (-100, 100), so over any window of N
     * jobs each target's realised share deviates from its configured percentage by at most
     * one job's worth. The USER target carries the remaining (100 - totalFee) weight.
     */
    private String rollStateful(String coin, State state) {
        RollState roll = rollStates.computeIfAbsent(coin, k -> new RollState());
        synchronized (roll) {
            for (FeeTarget target : state.feeTargets()) {
                if (target.percentage() > 0)
                    roll.credits.merge(target.targetId(), target.percentage(), Double::sum);
            }
            double userWeight = Math.max(0.0, 100.0 - state.totalFeePercentage());
            roll.credits.merge(USER_TARGET_ID, userWeight, Double::sum);

            String winner = null;
            double best = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, Double> entry : roll.credits.entrySet()) {
                if (entry.getValue() > best) {
                    best = entry.getValue();
                    winner = entry.getKey();
                }
            }
            if (winner == null) return USER_TARGET_ID;
            roll.credits.merge(winner, -100.0, Double::sum);
            return winner;
        }
    }

    private State getCoinState(String coin) {
        AtomicReference<State> ref = coinStates.get(coin);
        return ref != null ? ref.get() : null;
    }
}
