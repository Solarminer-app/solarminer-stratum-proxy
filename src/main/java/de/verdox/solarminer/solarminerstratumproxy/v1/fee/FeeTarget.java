package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FeeTarget(
        String targetId,
        String poolAddress,
        String workerName,
        String password,
        double percentage,
        /** Null when an older fee-backend does not mark the house target yet. */
        Boolean house
) {
}