package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Cross-coin revenue statement: only ports that currently mine, ranked by realised revenue per hashrate. */
@Service
public class RevenueOverviewService {
    private final ProxyProperties properties;
    private final FeeManager fees;
    private final ProxyTelemetryService telemetry;
    private final CurrencySnapshotService currency;

    public RevenueOverviewService(ProxyProperties properties, FeeManager fees,
                                  ProxyTelemetryService telemetry, CurrencySnapshotService currency) {
        this.properties = properties;
        this.fees = fees;
        this.telemetry = telemetry;
        this.currency = currency;
    }

    public Overview overview() {
        Map<String, CurrencySnapshotService.Snapshot> snapshots = currency.snapshots();
        List<CoinRow> rows = new ArrayList<>();
        for (String coin : properties.getCoins().keySet()) {
            ProxyTelemetryService.CoinMetrics metrics = telemetry.metrics(coin, fees.getFeeTargets(coin), null);
            // Telemetry reports 0 H/s for a coin it can price but never measured, so only a positive rate counts.
            boolean mining = metrics.connectedWorkers() > 0 || metrics.acceptedShares() > 0
                    || (metrics.estimatedHashrateHps() != null && metrics.estimatedHashrateHps() > 0);
            if (!mining) continue;
            rows.add(row(coin, metrics, snapshots.get(coin.toLowerCase())));
        }
        rows.sort(Comparator.comparingDouble(CoinRow::profitabilityUsdPerDayPerHps).reversed()
                .thenComparing(CoinRow::realUsdPer24h, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CoinRow::coin));

        double hashrate = rows.stream().filter(row -> row.estimatedHashrateHps() != null)
                .mapToDouble(CoinRow::estimatedHashrateHps).sum();
        return new Overview(Instant.now(), sum(rows, CoinRow::realUsdPer24h), sum(rows, CoinRow::projectedUsdPer24h),
                sum(rows, CoinRow::userPoolUsdPer24h), sum(rows, CoinRow::houseFeeUsdPer24h),
                sum(rows, CoinRow::referrerFeeUsdPer24h), rows.isEmpty() ? null : hashrate,
                rows.size(), properties.getCoins().size(),
                Math.toIntExact(rows.stream().mapToLong(CoinRow::connectedWorkers).sum()), List.copyOf(rows));
    }

    private CoinRow row(String coin, ProxyTelemetryService.CoinMetrics metrics, CurrencySnapshotService.Snapshot snapshot) {
        boolean usable = snapshot != null && !snapshot.stale();
        double user = 0, house = 0, referrer = 0, real = 0, projected = 0;
        boolean hasReal = false, hasProjected = false;
        for (ProxyTelemetryService.TargetMetrics target : metrics.targets()) {
            if (!usable) continue;
            double targetReal = snapshot.usdPerDayFromAcceptedWork(target.acceptedWorkHashes24h());
            real += targetReal;
            if (targetReal > 0) hasReal = true;
            double targetProjected = target.estimatedHashrateHps() == null ? 0
                    : snapshot.projectedUsdPerDay(target.estimatedHashrateHps());
            projected += targetProjected;
            if (targetProjected > 0) hasProjected = true;
            if (FeeManager.USER_TARGET_ID.equals(target.targetId())) user += targetReal;
            else if (Boolean.TRUE.equals(target.house())) house += targetReal;
            else referrer += targetReal;
        }
        // Profitability is what the measured hashrate actually earns; without hashrate there is nothing to rank.
        double hashrate = metrics.estimatedHashrateHps() == null ? 0 : metrics.estimatedHashrateHps();
        double profitability = hashrate > 0 && hasReal ? real / hashrate : 0;
        ProxyProperties.CoinConfig config = properties.getCoins().get(coin);
        Double price = usable ? snapshot.priceUsd() : null;
        if (price == null) price = currency.priceUsd(coin);
        return new CoinRow(coin, config == null ? 0 : config.getPort(),
                usable ? snapshot.ticker() : coin.toUpperCase(), price, metrics.estimatedHashrateHps(),
                hasReal ? real : null, hasProjected ? projected : null, profitability,
                user, house, referrer, metrics.acceptedShares(), metrics.rejectedShares(),
                metrics.connectedWorkers(), snapshot == null ? null : snapshot.stale(),
                snapshot == null ? null : snapshot.updatedAt());
    }

    private static Double sum(List<CoinRow> rows, Function<CoinRow, Double> value) {
        double total = 0;
        boolean present = false;
        for (CoinRow row : rows) {
            Double single = value.apply(row);
            if (single == null) continue;
            total += single;
            present = true;
        }
        return present ? total : null;
    }

    public record Overview(Instant sampledAt, Double grossUsdPer24h, Double projectedUsdPer24h,
                           Double userPoolUsdPer24h, Double houseFeeUsdPer24h, Double referrerFeeUsdPer24h,
                           Double totalHashrateHps, int activeCoins, int configuredCoins, int connectedWorkers,
                           List<CoinRow> coins) { }

    public record CoinRow(String coin, int port, String ticker, Double priceUsd, Double estimatedHashrateHps,
                          Double realUsdPer24h, Double projectedUsdPer24h, double profitabilityUsdPerDayPerHps,
                          Double userPoolUsdPer24h, Double houseFeeUsdPer24h, Double referrerFeeUsdPer24h,
                          long acceptedShares, long rejectedShares, int connectedWorkers,
                          Boolean currencyDataStale, String currencyDataUpdatedAt) { }
}
