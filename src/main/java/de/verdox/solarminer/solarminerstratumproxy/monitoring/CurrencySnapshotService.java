package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Public aggregate mining-network snapshots for indicative gross-value estimates. */
@Service
public class CurrencySnapshotService {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final URI endpoint;
    private volatile Instant refreshedAt = Instant.EPOCH;
    private volatile boolean refreshing;
    private volatile String error;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    public CurrencySnapshotService(ObjectMapper mapper,
            @Value("${solarminer.currency.mining-networks-url:https://currency.solarminer.app/api/v1/public/mining-networks}") URI endpoint) {
        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    public Map<String, Snapshot> snapshots() {
        if (Instant.now().isAfter(refreshedAt.plus(Duration.ofMinutes(10))) && !refreshing) refresh();
        return Map.copyOf(snapshots);
    }

    public String status() { snapshots(); return error; }

    private synchronized void refresh() {
        if (refreshing || Instant.now().isBefore(refreshedAt.plus(Duration.ofMinutes(10)))) return;
        refreshing = true;
        Thread.startVirtualThread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/json").header("User-Agent", "SolarMiner-Stratum-Proxy/1.0").GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Currency Service HTTP " + response.statusCode());
                JsonNode root = mapper.readTree(response.body());
                if (!root.isArray()) throw new IllegalStateException("Currency Service response was not an array");
                for (JsonNode row : root) {
                    if (!row.path("available").asBoolean(false)) continue;
                    double hashrate = row.path("networkHashrateHps").asDouble();
                    double interval = row.path("targetBlockSeconds").asDouble();
                    double reward = row.path("blockReward").asDouble();
                    double price = row.path("priceUsd").asDouble();
                    if (!(hashrate > 0 && interval > 0 && reward > 0 && price > 0)) continue;
                    snapshots.put(row.path("coin").asText().toLowerCase(), new Snapshot(hashrate, row.path("difficulty").asDouble(), interval,
                            reward, price, row.path("updatedAt").asText(null), row.path("stale").asBoolean(false),
                            row.path("ticker").asText(row.path("coin").asText().toUpperCase())));
                }
                error = null;
                refreshedAt = Instant.now();
            } catch (Exception exception) {
                error = "Currency Service refresh failed (" + exception.getClass().getSimpleName() + ")";
                refreshedAt = Instant.now();
            } finally { refreshing = false; }
        });
    }

    public record Snapshot(double networkHashrateHps, double difficulty, double targetBlockSeconds,
                           double blockReward, double priceUsd, String updatedAt, boolean stale, String ticker) {
        public double estimatedCoinsPerDay(double acceptedWorkHashes) {
            return acceptedWorkHashes <= 0 ? 0 : acceptedWorkHashes / networkHashrateHps
                    * (86400.0 / targetBlockSeconds) * blockReward;
        }
    }
}
