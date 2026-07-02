package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record FeeResponse(
        @JsonProperty("coin") String coin,
        @JsonProperty("referral") String referral,
        @JsonProperty("totalDevFee") double totalDevFee,
        @JsonProperty("userDiscount") double userDiscount,
        @JsonProperty("targets") Set<FeeTarget> targets
) {
    @JsonCreator
    public FeeResponse {
    }
}
