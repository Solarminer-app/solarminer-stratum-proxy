package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import java.util.Set;

public record FeeResponse(
        String coin,
        String referral,
        double totalDevFee,
        double userDiscount,
        Set<FeeTarget> targets
) {
}
