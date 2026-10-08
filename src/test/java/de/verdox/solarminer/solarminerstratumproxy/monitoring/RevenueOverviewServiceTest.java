package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevenueOverviewServiceTest {
    private static final double SHARE_UNIT = 4_294_967_296d;

    @Test
    void ranksRunningCoinsByNetworkProfitabilityAndSplitsGrossValueByFeeTarget() throws Exception {
        String networks = """
                [{"coin":"bitcoin","ticker":"BTC","available":true,"stale":false,"networkHashrateHps":1e18,
                  "difficulty":1.3e13,"targetBlockSeconds":600,"blockReward":3.125,"priceUsd":60000,
                  "updatedAt":"2026-10-07T00:00:00Z"},
                 {"coin":"monero","ticker":"XMR","available":true,"stale":false,"networkHashrateHps":2e12,
                  "difficulty":1e12,"targetBlockSeconds":120,"blockReward":1.0,"priceUsd":200,
                  "updatedAt":"2026-10-07T00:00:00Z"}]""";
        Fixture fixture = start(networks, "{\"btc\":60000,\"xmr\":200}");
        try {
            ProxyTelemetryService telemetry = new ProxyTelemetryService();
            // One accepted share per routing target on Bitcoin, one on the Monero user pool.
            for (String target : List.of("USER", "HOUSE", "REFERRER")) {
                acceptShare(telemetry, "bitcoin", target, 4);
            }
            acceptShare(telemetry, "monero", "USER", 2);

            FeeManager fees = new FeeManager();
            fees.updateTargets("bitcoin", List.of(
                    new FeeTarget("HOUSE", "pool.example:3333", "house", "x", 2, true),
                    new FeeTarget("REFERRER", "pool.example:3333", "partner", "x", 3, false)));

            RevenueOverviewService service = new RevenueOverviewService(
                    properties(3333, 3335, 3334), fees, telemetry, fixture.currency());
            RevenueOverviewService.Overview overview = awaitOverview(service, fixture.currency(), 2);

            assertEquals(2, overview.activeCoins());
            assertEquals(3, overview.configuredCoins());
            assertEquals(List.of("monero", "bitcoin"),
                    overview.coins().stream().map(RevenueOverviewService.CoinRow::coin).toList());

            // Realised revenue divided by the measured hashrate: (86400/120 · 1 XMR · 200 USD · 60 s) / 2e12 H/s
            assertEquals(4.32e-6, overview.coins().get(0).profitabilityUsdPerDayPerHps(), 1e-9);
            // (86400/600 · 3.125 BTC · 60000 USD · 60 s) / 1e18 H/s
            assertEquals(1.62e-9, overview.coins().get(1).profitabilityUsdPerDayPerHps(), 1e-12);

            double bitcoinShareUsd = 4 * SHARE_UNIT / 1e18 * (86400.0 / 600) * 3.125 * 60000;
            double moneroUsd = 2 * SHARE_UNIT / 2e12 * (86400.0 / 120) * 1.0 * 200;
            assertEquals(bitcoinShareUsd, overview.houseFeeUsdPer24h(), 1e-9);
            assertEquals(bitcoinShareUsd, overview.referrerFeeUsdPer24h(), 1e-9);
            assertEquals(bitcoinShareUsd + moneroUsd, overview.userPoolUsdPer24h(), 1e-9);
            assertEquals(3 * bitcoinShareUsd + moneroUsd, overview.grossUsdPer24h(), 1e-9);

            RevenueOverviewService.CoinRow bitcoin = overview.coins().get(1);
            assertEquals(3333, bitcoin.port());
            assertEquals(3 * bitcoinShareUsd, bitcoin.realUsdPer24h(), 1e-9);
            assertTrue(bitcoin.projectedUsdPer24h() > 0);
            assertEquals(bitcoin.realUsdPer24h() / bitcoin.estimatedHashrateHps(),
                    bitcoin.profitabilityUsdPerDayPerHps(), 1e-15);
            assertTrue(overview.coins().stream().noneMatch(row -> row.coin().equals("pearl")));
        } finally { fixture.server().stop(0); }
    }

    @Test
    void runningCoinWithoutUsableSnapshotStaysListedWithoutRevenue() throws Exception {
        String networks = """
                [{"coin":"bitcoin","ticker":"BTC","available":true,"stale":true,"networkHashrateHps":1e18,
                  "difficulty":1.3e13,"targetBlockSeconds":600,"blockReward":3.125,"priceUsd":60000,
                  "updatedAt":"2026-10-06T00:00:00Z"}]""";
        Fixture fixture = start(networks, "{\"btc\":60000}");
        try {
            ProxyTelemetryService telemetry = new ProxyTelemetryService();
            telemetry.connected("bitcoin", "127.0.0.1");
            RevenueOverviewService service = new RevenueOverviewService(
                    properties(3333, 3335, 3334), new FeeManager(), telemetry, fixture.currency());
            RevenueOverviewService.Overview overview = awaitOverview(service, fixture.currency(), 1);

            assertEquals(1, overview.coins().size());
            RevenueOverviewService.CoinRow bitcoin = overview.coins().get(0);
            assertEquals(1, bitcoin.connectedWorkers());
            assertNull(bitcoin.realUsdPer24h());
            assertEquals(0d, bitcoin.profitabilityUsdPerDayPerHps());
            assertEquals(60000d, bitcoin.priceUsd());
            assertNull(overview.grossUsdPer24h());
            assertTrue(Boolean.TRUE.equals(bitcoin.currencyDataStale()));
        } finally { fixture.server().stop(0); }
    }

    private static void acceptShare(ProxyTelemetryService telemetry, String coin, String targetId, double difficulty) {
        String session = telemetry.connected(coin, "127.0.0.1");
        telemetry.poolMessage(session, targetId, "{\"method\":\"mining.set_difficulty\",\"params\":[" + difficulty + "]}");
        telemetry.upstreamRequest(session, targetId, "{\"id\":7,\"method\":\"mining.submit\",\"params\":[]}");
        telemetry.upstreamResponse(session, targetId, "{\"id\":7,\"result\":true,\"error\":null}");
        telemetry.disconnected(session);
    }

    private static ProxyProperties properties(int bitcoinPort, int moneroPort, int pearlPort) {
        ProxyProperties properties = new ProxyProperties();
        Map<String, ProxyProperties.CoinConfig> coins = new LinkedHashMap<>();
        coins.put("bitcoin", coinConfig(bitcoinPort));
        coins.put("monero", coinConfig(moneroPort));
        coins.put("pearl", coinConfig(pearlPort));
        properties.setCoins(coins);
        return properties;
    }

    private static ProxyProperties.CoinConfig coinConfig(int port) {
        ProxyProperties.CoinConfig config = new ProxyProperties.CoinConfig();
        config.setPort(port);
        return config;
    }

    /** The Currency Service refresh runs on a virtual thread, so the first overview can still be unpriced. */
    private static RevenueOverviewService.Overview awaitOverview(RevenueOverviewService service,
                                                                 CurrencySnapshotService currency,
                                                                 int expectedSnapshots) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (currency.snapshots().size() < expectedSnapshots && System.nanoTime() < deadline) Thread.sleep(20);
        return service.overview();
    }

    private static Fixture start(String miningNetworks, String coinPrices) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/coin-prices", exchange -> respond(exchange, coinPrices));
        server.createContext("/mining-networks", exchange -> respond(exchange, miningNetworks));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        CurrencySnapshotService currency = new CurrencySnapshotService(new ObjectMapper(),
                URI.create(base + "/mining-networks"), URI.create(base + "/coin-prices"));
        return new Fixture(server, currency);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) { output.write(payload); }
    }

    private record Fixture(HttpServer server, CurrencySnapshotService currency) { }
}
