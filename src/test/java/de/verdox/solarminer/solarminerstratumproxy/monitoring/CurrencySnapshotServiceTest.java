package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CurrencySnapshotServiceTest {
    @Test
    void priceQuotesRemainAvailableWithoutMiningNetworkSnapshot() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/coin-prices", exchange -> {
            byte[] body = "{\"btc\":84035,\"dcr\":18.04,\"qtc\":101.97}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/mining-networks", exchange -> {
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            CurrencySnapshotService service = new CurrencySnapshotService(new ObjectMapper(),
                    URI.create(base + "/mining-networks"), URI.create(base + "/coin-prices"));
            service.snapshots();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (service.priceUsd("quantus") == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(84035d, service.priceUsd("bitcoin"));
            assertEquals(18.04d, service.priceUsd("decred"));
            assertEquals(101.97d, service.priceUsd("quantus"));
            assertNull(service.priceUsd("unknown"));
        } finally { server.stop(0); }
    }
}
