package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeeManagerRollModeTest {

    private static FeeTarget target(String id, double percentage) {
        return new FeeTarget(id, "stratum+tcp://pool.example:1010", "worker", "x", percentage, true);
    }

    @Test
    void statelessModeStillFallsBackToUserWithoutTargets() {
        FeeManager manager = new FeeManager();
        assertEquals(FeeManager.USER_TARGET_ID, manager.rollNextJobTarget("bitcoin"));
        manager.updateTargets("bitcoin", List.of(target("house", 0)));
        assertEquals(FeeManager.USER_TARGET_ID, manager.rollNextJobTarget("bitcoin"));
    }

    @Test
    void statefulModeKeepsRealisedShareWithinOneJobOfConfiguredPercentages() {
        FeeManager manager = new FeeManager();
        manager.setRollMode(FeeManager.ROLL_MODE_STATEFUL);
        manager.updateTargets("bitcoin", List.of(target("house", 7.5), target("referrer", 2.5)));

        int jobs = 10_000;
        int house = 0, referrer = 0, user = 0;
        for (int i = 0; i < jobs; i++) {
            switch (manager.rollNextJobTarget("bitcoin")) {
                case "house" -> house++;
                case "referrer" -> referrer++;
                case FeeManager.USER_TARGET_ID -> user++;
                default -> fail("unexpected target");
            }
        }
        // Smooth weighted round-robin: each target is within one job of its exact share.
        assertEquals(750, house);
        assertEquals(250, referrer);
        assertEquals(9000, user);
    }

    @Test
    void statefulModeBalancesAcrossFeeTargetRefreshes() {
        FeeManager refreshed = new FeeManager();
        refreshed.setRollMode(FeeManager.ROLL_MODE_STATEFUL);
        FeeManager untouched = new FeeManager();
        untouched.setRollMode(FeeManager.ROLL_MODE_STATEFUL);
        List<FeeTarget> targets = List.of(target("house", 10), target("referrer", 10));
        refreshed.updateTargets("monero", targets);
        untouched.updateTargets("monero", targets);
        // A fee-backend refresh with the same targets must not reset the accumulated balance:
        // the roll schedule continues exactly as if the refresh never happened.
        for (int i = 0; i < 40; i++) {
            if (i == 17) refreshed.updateTargets("monero", targets);
            assertEquals(untouched.rollNextJobTarget("monero"), refreshed.rollNextJobTarget("monero"));
        }
    }

    @Test
    void statefulModeDropsRemovedTargetsAndStartsNewOnesAtZero() {
        FeeManager manager = new FeeManager();
        manager.setRollMode(FeeManager.ROLL_MODE_STATEFUL);
        manager.updateTargets("monero", List.of(target("house", 10), target("referrer", 10)));
        manager.rollNextJobTarget("monero");
        manager.updateTargets("monero", List.of(target("house", 20)));
        int house = 0;
        for (int i = 0; i < 10; i++) if ("house".equals(manager.rollNextJobTarget("monero"))) house++;
        assertEquals(2, house);
    }

    @Test
    void rollModeNormalizesUnknownValuesToRandom() {
        FeeManager manager = new FeeManager();
        manager.setRollMode("nonsense");
        assertEquals(FeeManager.ROLL_MODE_RANDOM, manager.getRollMode());
        manager.setRollMode("STATEFUL");
        assertEquals(FeeManager.ROLL_MODE_STATEFUL, manager.getRollMode());
    }
}
