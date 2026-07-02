package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

public record FeeTarget(
        String targetId,
        String poolAddress,
        String workerName,
        String password,
        double percentage
) {
}