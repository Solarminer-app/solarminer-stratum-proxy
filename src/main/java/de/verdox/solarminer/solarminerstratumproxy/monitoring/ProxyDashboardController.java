package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import de.verdox.solarminer.solarminerstratumproxy.v1.connection.StratumNettyServer;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class ProxyDashboardController {
    private final ProxyProperties properties;
    private final FeeManager fees;
    private final StratumNettyServer server;
    private final ProxyTelemetryService telemetry;
    private final CurrencySnapshotService currency;

    public ProxyDashboardController(ProxyProperties properties, FeeManager fees, StratumNettyServer server,
                                    ProxyTelemetryService telemetry, CurrencySnapshotService currency) {
        this.properties = properties; this.fees = fees; this.server = server;
        this.telemetry = telemetry; this.currency = currency;
    }

    @GetMapping({"/api/dashboard", "/embedded-dashboard/api/dashboard"})
    public Dashboard dashboard() {
        List<Coin> coins = new ArrayList<>();
        var snapshots = currency.snapshots();
        properties.getCoins().forEach((coin, config) -> {
            var metrics = telemetry.metrics(coin, fees.getFeeTargets(coin), server.listenerStatus(coin));
            var snapshot = snapshots.get(coin.toLowerCase());
            List<Target> targets = metrics.targets().stream().map(target -> {
                double coinsPerDay = snapshot == null || snapshot.stale() ? 0
                        : snapshot.estimatedCoinsPerDay(target.acceptedWorkHashes24h()) / 24.0;
                return new Target(target.targetId(), target.label(), target.pool(), target.house(), target.configuredFeePercent(),
                        target.routedJobPercent(), target.routedJobs24h(), target.submittedShares(), target.acceptedShares(),
                        target.rejectedShares(), target.staleShares(), target.averageAcceptedDifficulty(), target.estimatedHashrateHps(),
                        target.connectedWorkers(), target.lastAcceptedShareAt(), target.acceptedWorkHashes24h(),
                        snapshot == null || snapshot.stale() ? null : coinsPerDay,
                        snapshot == null || snapshot.stale() ? null : coinsPerDay * snapshot.priceUsd());
            }).toList();
            double value = targets.stream().map(Target::estimatedUsdPerHour).filter(v -> v != null).mapToDouble(Double::doubleValue).sum();
            coins.add(new Coin(coin, config.getPort(), metrics.listenerStatus(), metrics.connectedWorkers(), metrics.upstreamConnections(),
                    metrics.submittedShares(), metrics.acceptedShares(), metrics.rejectedShares(), metrics.averageAcceptedShareDifficulty(),
                    metrics.estimatedHashrateHps(), metrics.sampledAt(), targets, snapshot == null ? null : snapshot.ticker(),
                    snapshot == null ? null : snapshot.priceUsd(), snapshot == null ? null : snapshot.stale(),
                    snapshot == null ? null : snapshot.updatedAt(), snapshot == null ? null : value));
        });
        return new Dashboard(coins, currency.status());
    }

    @GetMapping({"/api/dashboard/console", "/embedded-dashboard/api/dashboard/console"})
    public List<ProxyTelemetryService.ConsoleEvent> console(@RequestParam(defaultValue = "all") String coin,
            @RequestParam(defaultValue = "all") String level, @RequestParam(defaultValue = "100") int limit) {
        return telemetry.console(coin, level, limit);
    }

    public record Dashboard(List<Coin> coins, String currencyServiceError) { }
    public record Coin(String coin, int port, String listenerStatus, int connectedWorkers, int upstreamConnections,
                       long submittedShares, long acceptedShares, long rejectedShares, double averageAcceptedShareDifficulty,
                       Double estimatedHashrateHps, java.time.Instant sampledAt, List<Target> targets, String ticker,
                       Double priceUsd, Boolean currencyDataStale, String currencyDataUpdatedAt, Double estimatedUsdPerHour) { }
    public record Target(String targetId, String label, String pool, Boolean house, double configuredFeePercent,
                         double routedJobPercent, long routedJobs24h, long submittedShares, long acceptedShares,
                         long rejectedShares, long staleShares, double averageAcceptedDifficulty, Double estimatedHashrateHps,
                         int connectedWorkers, String lastAcceptedShareAt, double acceptedWorkHashes24h,
                         Double estimatedCoinsPerHour, Double estimatedUsdPerHour) { }
}
