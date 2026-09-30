package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The proxy polls the central fee-backend, so it must keep parsing responses from a backend that
 * is newer than itself. An unknown property here would fail every fee fetch and stop all mining.
 */
class FeeResponseDeserializationTest {
    private static final String RESPONSE = """
            {"coin":"pearl","referral":"solarminer","totalDevFee":2.5,
             "targets":[{"targetId":"solarminer-prl-pearlhash",
                         "poolAddress":"stratum+ssl://prl.kryptex.network:8048",
                         "workerName":"prl1house/solarminer","password":"","percentage":2.5,
                         "house":true,"aFieldFromANewerBackend":"ignored"}],
             "anotherNewerField":42}
            """;

    private static final String LEGACY_RESPONSE = """
            {"coin":"pearl","referral":"solarminer","totalDevFee":2.5,
             "targets":[{"targetId":"solarminer-prl-pearlhash",
                         "poolAddress":"stratum+ssl://prl.kryptex.network:8048",
                         "workerName":"prl1house/solarminer","password":"","percentage":2.5}]}
            """;

    @Test
    void readsTheHouseFlagAndIgnoresFieldsOfANewerBackend() throws Exception {
        FeeResponse response = new ObjectMapper().readValue(RESPONSE, FeeResponse.class);
        assertEquals(2.5, response.totalDevFee());
        FeeTarget target = response.targets().iterator().next();
        assertEquals(Boolean.TRUE, target.house());
        assertEquals("prl1house/solarminer", target.workerName());
    }

    @Test
    void keepsParsingAResponseWithoutTheHouseFlag() throws Exception {
        FeeResponse response = new ObjectMapper().readValue(LEGACY_RESPONSE, FeeResponse.class);
        assertEquals(1, response.targets().size());
        assertNull(response.targets().iterator().next().house());
    }
}
