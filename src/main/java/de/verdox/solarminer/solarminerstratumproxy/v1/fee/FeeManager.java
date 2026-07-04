package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FeeManager {
    public static final String USER_TARGET_ID = "USER";

    private final ConcurrentHashMap<String, AtomicReference<State>> coinStates = new ConcurrentHashMap<>();

    private record State(List<FeeTarget> feeTargets, Map<String, FeeTarget> targetMap, double totalFeePercentage) {}

    public void updateTargets(String coin, List<FeeTarget> feeTargets) {
        Map<String, FeeTarget> targetMap = new HashMap<>();
        double totalFeePercentage = 0.0;

        for (FeeTarget target : feeTargets) {
            targetMap.put(target.targetId(), target);
            totalFeePercentage += target.percentage();
        }

        coinStates.computeIfAbsent(coin, k -> new AtomicReference<>())
                .set(new State(feeTargets, targetMap, totalFeePercentage));
    }

    public FeeTarget getTarget(String coin, String targetId) {
        State state = getCoinState(coin);
        return state != null ? state.targetMap().get(targetId) : null;
    }

    public List<FeeTarget> getFeeTargets(String coin) {
        State state = getCoinState(coin);
        return state != null ? state.feeTargets() : List.of();
    }

    public String rollNextJobTarget(String coin) {
        State currentState = getCoinState(coin);
        if (currentState == null || currentState.feeTargets().isEmpty() || currentState.totalFeePercentage() <= 0) {
            return USER_TARGET_ID;
        }

        double roll = ThreadLocalRandom.current().nextDouble(100.0);

        if (roll >= currentState.totalFeePercentage()) {
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

    private State getCoinState(String coin) {
        AtomicReference<State> ref = coinStates.get(coin);
        return ref != null ? ref.get() : null;
    }
}