package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FeeManager {
    public static final String USER_TARGET_ID = "USER";

    private final AtomicReference<State> state = new AtomicReference<>(new State(List.of(), Map.of(), 0.0));

    private record State(List<FeeTarget> feeTargets, Map<String, FeeTarget> targetMap, double totalFeePercentage) {}

    public void updateTargets(List<FeeTarget> feeTargets) {
        Map<String, FeeTarget> targetMap = new HashMap<>();
        double totalFeePercentage = 0.0;

        for (FeeTarget target : feeTargets) {
            targetMap.put(target.targetId(), target);
            totalFeePercentage += target.percentage();
        }

        this.state.set(new State(feeTargets, targetMap, totalFeePercentage));
    }

    public FeeTarget getTarget(String targetId) {
        return state.get().targetMap().get(targetId);
    }

    public List<FeeTarget> getFeeTargets() {
        return state.get().feeTargets();
    }

    public String rollNextJobTarget() {
        State currentState = state.get();
        if (currentState.feeTargets().isEmpty() || currentState.totalFeePercentage() <= 0) {
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
}