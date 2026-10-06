package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-memory, credential-free proxy observability. No Stratum payloads are retained. */
@Service
public class ProxyTelemetryService {
    private static final int EVENT_LIMIT = 500;
    private static final Duration HISTORY = Duration.ofHours(24);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Map<String, Target>> targets = new HashMap<>();
    private final Map<String, PendingShare> pendingShares = new HashMap<>();
    private final ArrayDeque<ConsoleEvent> events = new ArrayDeque<>();
    private final Map<String, Double> difficultyUnits = Map.of(
            "bitcoin", 4_294_967_296d,
            "monero", 4_294_967_296d,
            "ravencoin", 4_294_967_296d,
            "ethereumclassic", 4_294_967_296d,
            "decred", 4_294_967_296d,
            // QTC's published static share difficulty is expressed as expected hashes.
            "quantus", 1d);

    public synchronized String connected(String coin, String remoteAddress) {
        String id = UUID.randomUUID().toString();
        Session session = new Session(id, coin, Instant.now());
        sessions.put(id, session);
        event("INFO", coin, "Miner connected");
        return id;
    }

    public synchronized void disconnected(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session == null) return;
        pendingShares.keySet().removeIf(key -> key.startsWith(sessionId + "|"));
        event("INFO", session.coin, "Miner disconnected");
    }

    public synchronized void upstream(String sessionId, String targetId, boolean connected, String detail) {
        Session session = sessions.get(sessionId);
        if (session == null) return;
        session.upstreams.put(targetId, connected);
        String safeDetail = detail == null || detail.isBlank() ? "" : ": " + detail;
        event(connected ? "SUCCESS" : "ERROR", session.coin,
                (connected ? "Upstream connected · " : "Upstream unavailable · ") + targetId + safeDetail);
    }

    public synchronized void assignedJob(String sessionId, String targetId) {
        Session session = sessions.get(sessionId);
        if (session == null) return;
        Target target = target(session.coin, targetId);
        target.jobs.addLast(Instant.now());
        prune(target.jobs, Instant.now().minus(HISTORY));
    }

    public synchronized void userPool(String sessionId, String address) {
        Session session = sessions.get(sessionId);
        if (session != null) session.userPool = safePool(address);
    }

    public synchronized void currentTarget(String sessionId, String targetId) {
        Session session = sessions.get(sessionId);
        if (session != null) session.currentTargetId = targetId;
    }

    /** Observe only Stratum metadata needed for counters; the message itself is discarded. */
    public synchronized void poolMessage(String sessionId, String targetId, String raw) {
        Session session = sessions.get(sessionId);
        if (session == null) return;
        try {
            JsonNode message = MAPPER.readTree(raw);
            String method = message.path("method").asText("");
            double difficulty = difficulty(message, method);
            if (difficulty > 0 && Double.isFinite(difficulty)) session.difficulties.put(targetId, difficulty);
        } catch (Exception ignored) { }
    }

    public synchronized void upstreamRequest(String sessionId, String targetId, String raw) {
        Session session = sessions.get(sessionId);
        if (session == null) return;
        try {
            JsonNode message = MAPPER.readTree(raw);
            String method = message.path("method").asText("");
            if (!"mining.submit".equals(method) && !"submit".equals(method)) return;
            JsonNode id = message.get("id");
            if (id == null || id.isNull()) return;
            double shareDifficulty = session.difficulties.getOrDefault(targetId, 0d);
            String key = pendingKey(sessionId, targetId, id.asText());
            pendingShares.put(key, new PendingShare(session.coin, targetId, shareDifficulty, Instant.now()));
            target(session.coin, targetId).submitted++;
        } catch (Exception ignored) { }
    }

    public synchronized void upstreamResponse(String sessionId, String targetId, String raw) {
        try {
            JsonNode message = MAPPER.readTree(raw);
            JsonNode id = message.get("id");
            if (id == null || id.isNull()) return;
            PendingShare pending = pendingShares.remove(pendingKey(sessionId, targetId, id.asText()));
            if (pending == null) return;
            boolean accepted = accepted(message);
            recordShare(pending, accepted, accepted ? null : rejectionKind(message));
        } catch (Exception ignored) { }
    }

    public synchronized void localReject(String sessionId, String reason) {
        Session session = sessions.get(sessionId);
        if (session == null) return;
        String targetId = session.currentTargetId;
        Target target = target(session.coin, targetId);
        target.submitted++;
        target.rejected++;
        if (reason != null && reason.toLowerCase().contains("stale")) target.stale++;
        event("WARN", session.coin, "Share rejected by proxy" + (reason == null ? "" : " · " + safeText(reason)));
    }

    public synchronized void event(String level, String coin, String message) {
        events.addLast(new ConsoleEvent(Instant.now(), level, coin == null ? "system" : coin,
                safeText(message == null ? "" : message)));
        while (events.size() > EVENT_LIMIT) events.removeFirst();
    }

    public synchronized List<ConsoleEvent> console(String coin, String level, int requestedLimit) {
        int limit = Math.max(1, Math.min(300, requestedLimit));
        List<ConsoleEvent> found = new ArrayList<>();
        for (var iterator = events.descendingIterator(); iterator.hasNext() && found.size() < limit;) {
            ConsoleEvent entry = iterator.next();
            if ((coin == null || coin.isBlank() || "all".equalsIgnoreCase(coin) || entry.coin().equals(coin))
                    && (level == null || level.isBlank() || "all".equalsIgnoreCase(level) || entry.level().equalsIgnoreCase(level)))
                found.add(entry);
        }
        return List.copyOf(found);
    }

    public synchronized CoinMetrics metrics(String coin, List<FeeTarget> feeTargets, String listenerStatus) {
        Instant now = Instant.now();
        Map<String, Target> coinTargets = targets.computeIfAbsent(coin, ignored -> new LinkedHashMap<>());
        coinTargets.computeIfAbsent(FeeManager.USER_TARGET_ID, ignored -> new Target());
        for (FeeTarget feeTarget : feeTargets) coinTargets.computeIfAbsent(feeTarget.targetId(), ignored -> new Target());

        List<TargetMetrics> targetMetrics = new ArrayList<>();
        long totalAccepted = 0, totalRejected = 0, totalSubmitted = 0;
        double totalRate = 0, acceptedDifficulty = 0, feePercentage = 0;
        long acceptedDifficultySamples = 0;
        boolean rateAvailable = difficultyUnits.containsKey(coin);
        for (FeeTarget feeTarget : feeTargets) if (feeTarget.percentage() > 0) feePercentage += feeTarget.percentage();
        long routedJobs = coinTargets.values().stream().mapToLong(target -> {
            prune(target.jobs, now.minus(HISTORY)); return target.jobs.size();
        }).sum();

        for (Map.Entry<String, Target> entry : coinTargets.entrySet()) {
            String targetId = entry.getKey();
            Target state = entry.getValue();
            prune(state.jobs, now.minus(HISTORY));
            pruneShares(state.shares, now.minus(HISTORY));
            long accepted = state.accepted, rejected = state.rejected;
            totalAccepted += accepted; totalRejected += rejected; totalSubmitted += state.submitted;
            double diffSum = 0, work15m = 0, work24h = 0;
            Instant firstRecentWork = null;
            for (ShareSample share : state.shares) {
                if (share.accepted() && share.difficulty() > 0) {
                    diffSum += share.difficulty();
                    acceptedDifficultySamples++;
                    if (share.at().isAfter(now.minus(RATE_WINDOW))) {
                        work15m += share.workHashes();
                        if (firstRecentWork == null || share.at().isBefore(firstRecentWork)) firstRecentWork = share.at();
                    }
                    work24h += share.workHashes();
                }
            }
            double avgDifficulty = accepted == 0 ? 0 : state.acceptedDifficultySum / Math.max(1, accepted);
            Double estimatedRate = null;
            if (rateAvailable) {
                long activeSeconds = firstRecentWork == null ? 0 : Math.max(60,
                        Math.min(RATE_WINDOW.toSeconds(), Duration.between(firstRecentWork, now).toSeconds()));
                if (activeSeconds > 0 && work15m > 0) {
                    estimatedRate = work15m / activeSeconds;
                    totalRate += estimatedRate;
                }
            }
            acceptedDifficulty += diffSum;
            long connected = sessions.values().stream().filter(session -> session.coin.equals(coin)
                    && session.upstreams.getOrDefault(targetId, false)).count();
            long routed = state.jobs.size();
            double pct = routedJobs == 0 ? 0 : routed * 100.0 / routedJobs;
            String pool = FeeManager.USER_TARGET_ID.equals(targetId)
                    ? sessions.values().stream().filter(session -> session.coin.equals(coin) && session.userPool != null)
                    .map(session -> session.userPool).findFirst().orElse(null) : feeTargets.stream()
                    .filter(target -> target.targetId().equals(targetId)).map(target -> safePool(target.poolAddress()))
                    .findFirst().orElse(null);
            FeeTarget configured = feeTargets.stream().filter(target -> target.targetId().equals(targetId)).findFirst().orElse(null);
            double configuredPercent = FeeManager.USER_TARGET_ID.equals(targetId)
                    ? Math.max(0, 100.0 - feePercentage) : configured == null ? 0 : configured.percentage();
            targetMetrics.add(new TargetMetrics(targetId, FeeManager.USER_TARGET_ID.equals(targetId) ? "User pool" : targetId,
                    pool, FeeManager.USER_TARGET_ID.equals(targetId) ? null : configured == null ? null : configured.house(),
                    configuredPercent, pct, routed, state.submitted, accepted, rejected, state.stale,
                    avgDifficulty, estimatedRate, Math.toIntExact(connected), latestShareAt(state.shares), work24h));
        }
        targetMetrics.sort(Comparator.comparing(TargetMetrics::targetId));
        int workers = (int) sessions.values().stream().filter(session -> session.coin.equals(coin)).count();
        int upstreamConnections = (int) sessions.values().stream().filter(session -> session.coin.equals(coin))
                .mapToLong(session -> session.upstreams.values().stream().filter(Boolean::booleanValue).count()).sum();
        double averageDifficulty = acceptedDifficultySamples == 0 ? 0 : acceptedDifficulty / acceptedDifficultySamples;
        return new CoinMetrics(coin, listenerStatus, workers, upstreamConnections, totalSubmitted, totalAccepted, totalRejected,
                averageDifficulty, rateAvailable ? totalRate : null, now, List.copyOf(targetMetrics));
    }

    private void recordShare(PendingShare pending, boolean accepted, String reason) {
        Target target = target(pending.coin(), pending.targetId());
        if (accepted) {
            target.accepted++;
            target.acceptedDifficultySum += pending.difficulty();
        } else {
            target.rejected++;
            if (reason != null && reason.contains("stale")) target.stale++;
        }
        double factor = difficultyUnits.getOrDefault(pending.coin(), 0d);
        double work = accepted && pending.difficulty() > 0 && factor > 0 ? pending.difficulty() * factor : 0;
        target.shares.addLast(new ShareSample(pending.at(), pending.difficulty(), accepted, work));
        pruneShares(target.shares, Instant.now().minus(HISTORY));
        event(accepted ? "SUCCESS" : "WARN", pending.coin(), accepted
                ? "Share accepted · " + pending.targetId()
                : "Share rejected · " + pending.targetId() + (reason == null ? "" : " · " + reason));
    }

    private Target target(String coin, String targetId) {
        return targets.computeIfAbsent(coin, ignored -> new LinkedHashMap<>()).computeIfAbsent(targetId, ignored -> new Target());
    }

    private static String pendingKey(String sessionId, String targetId, String rpcId) {
        return sessionId + "|" + targetId + "|" + rpcId;
    }

    private static double difficulty(JsonNode message, String method) {
        JsonNode params = message.path("params");
        if ("mining.set_difficulty".equals(method) && params.isArray() && params.size() > 0)
            return numeric(params.get(0));
        for (JsonNode candidate : List.of(params.path("difficulty"), params.path("target_difficulty"),
                message.path("result").path("difficulty"), message.path("result").path("job").path("difficulty"))) {
            double value = numeric(candidate);
            if (value > 0) return value;
        }
        return 0;
    }

    private static double numeric(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        if (node.isNumber()) return node.asDouble();
        if (node.isTextual()) try { return Double.parseDouble(node.asText()); } catch (NumberFormatException ignored) { }
        return 0;
    }

    private static boolean accepted(JsonNode response) {
        JsonNode error = response.get("error");
        if (error != null && !error.isNull() && !(error.isBoolean() && !error.asBoolean())) return false;
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) return false;
        if (result.isBoolean()) return result.asBoolean();
        if (result.isTextual()) return "ok".equalsIgnoreCase(result.asText()) || "accepted".equalsIgnoreCase(result.asText());
        if (result.isObject()) {
            String status = result.path("status").asText("");
            if (!status.isBlank()) return "ok".equalsIgnoreCase(status) || "accepted".equalsIgnoreCase(status);
            return result.path("accepted").asBoolean(false);
        }
        return false;
    }

    private static String rejectionKind(JsonNode response) {
        JsonNode error = response.path("error");
        String description = error.isArray() && error.size() > 1 ? error.get(1).asText("") : error.asText("");
        if (description.isBlank()) description = response.path("result").path("status").asText("");
        String lower = description.toLowerCase();
        if (lower.contains("stale")) return "stale";
        if (lower.contains("low difficulty") || lower.contains("low diff")) return "low difficulty";
        if (lower.contains("duplicate")) return "duplicate";
        return description.isBlank() ? "rejected" : safeText(description);
    }

    private static String latestShareAt(ArrayDeque<ShareSample> shares) {
        return shares.stream().filter(ShareSample::accepted).map(sample -> sample.at().toString()).reduce((a, b) -> b).orElse(null);
    }

    private static void prune(ArrayDeque<Instant> values, Instant threshold) {
        while (!values.isEmpty() && values.peekFirst().isBefore(threshold)) values.removeFirst();
    }

    private static void pruneShares(ArrayDeque<ShareSample> values, Instant threshold) {
        while (!values.isEmpty() && values.peekFirst().at().isBefore(threshold)) values.removeFirst();
    }

    private static String safePool(String value) {
        try {
            URI uri = URI.create(value.contains("://") ? value : "stratum+tcp://" + value);
            return uri.getHost() == null ? null : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception ignored) { return null; }
    }

    private static String safeText(String value) {
        String cleaned = value.replaceAll("(?i)(password|pass|token|wallet|login|authorization|credential)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]")
                .replaceAll("[\\r\\n\\t]", " ");
        return cleaned.substring(0, Math.min(240, cleaned.length()));
    }

    private static final class Session {
        final String id;
        final String coin;
        final Instant connectedAt;
        final Map<String, Boolean> upstreams = new HashMap<>();
        final Map<String, Double> difficulties = new HashMap<>();
        String userPool;
        String currentTargetId = FeeManager.USER_TARGET_ID;
        Session(String id, String coin, Instant connectedAt) { this.id = id; this.coin = coin; this.connectedAt = connectedAt; }
    }

    private static final class Target {
        final ArrayDeque<Instant> jobs = new ArrayDeque<>();
        final ArrayDeque<ShareSample> shares = new ArrayDeque<>();
        long submitted, accepted, rejected, stale;
        double acceptedDifficultySum;
    }

    private record PendingShare(String coin, String targetId, double difficulty, Instant at) { }
    private record ShareSample(Instant at, double difficulty, boolean accepted, double workHashes) { }
    public record ConsoleEvent(Instant at, String level, String coin, String message) { }
    public record TargetMetrics(String targetId, String label, String pool, Boolean house,
                               double configuredFeePercent, double routedJobPercent, long routedJobs24h,
                               long submittedShares, long acceptedShares, long rejectedShares, long staleShares,
                               double averageAcceptedDifficulty, Double estimatedHashrateHps,
                               int connectedWorkers, String lastAcceptedShareAt, double acceptedWorkHashes24h) { }
    public record CoinMetrics(String coin, String listenerStatus, int connectedWorkers, int upstreamConnections,
                              long submittedShares, long acceptedShares, long rejectedShares,
                              double averageAcceptedShareDifficulty, Double estimatedHashrateHps,
                              Instant sampledAt, List<TargetMetrics> targets) { }
}
