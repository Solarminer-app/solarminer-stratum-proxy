package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyTelemetryServiceTest {
    @Test
    void estimatesPearlHashrateFromTheDifficultyEmbeddedInTheJobId() {
        ProxyTelemetryService telemetry = new ProxyTelemetryService();
        String session = telemetry.connected("pearl", "127.0.0.1");
        telemetry.poolMessage(session, FeeManager.USER_TARGET_ID,
                "{\"id\":null,\"method\":\"mining.notify\",\"params\":{\"job_id\":\"32fc29f1_8000000\",\"target\":\"00\"}}");

        accept(telemetry, session, FeeManager.USER_TARGET_ID, 1);

        ProxyTelemetryService.CoinMetrics metrics = telemetry.metrics("pearl", List.of(), "READY");
        assertEquals(8_000_000d, metrics.averageAcceptedShareDifficulty());
        assertNotNull(metrics.estimatedHashrateHps());
        assertEquals(8_000_000d * 4_294_967_296d / 60d, metrics.estimatedHashrateHps(), 0.01d);
    }

    @Test
    void usesThePublishedKryptexDefaultWhenItsJobIdHasNoDifficulty() {
        ProxyTelemetryService telemetry = new ProxyTelemetryService();
        String session = telemetry.connected("pearl", "127.0.0.1");
        telemetry.userPool(session, "stratum+ssl://prl.kryptex.network:8048");
        telemetry.poolMessage(session, FeeManager.USER_TARGET_ID,
                "{\"id\":null,\"method\":\"mining.notify\",\"params\":{\"job_id\":\"opaque-kryptex-job\",\"target\":\"00\"}}");

        accept(telemetry, session, FeeManager.USER_TARGET_ID, 2);

        ProxyTelemetryService.CoinMetrics metrics = telemetry.metrics("pearl", List.of(), "READY");
        assertEquals(2_097_152d, metrics.averageAcceptedShareDifficulty());
        assertNotNull(metrics.estimatedHashrateHps());
        assertTrue(metrics.estimatedHashrateHps() > 0);
    }

    private static void accept(ProxyTelemetryService telemetry, String session, String target, int id) {
        telemetry.upstreamRequest(session, target,
                "{\"id\":" + id + ",\"method\":\"mining.submit\",\"params\":{}}");
        telemetry.upstreamResponse(session, target, "{\"id\":" + id + ",\"result\":true,\"error\":null}");
    }
}
