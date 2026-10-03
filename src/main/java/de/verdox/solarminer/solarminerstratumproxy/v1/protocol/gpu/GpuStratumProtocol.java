package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.JobOrigin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SRBMiner / 2Miners JSON-RPC family. RVN and ETC retain separate registered
 * protocol instances because their subscribe, target and notify shapes differ.
 * No production listener is configured until real submit and fee-switch tests pass.
 */
abstract class GpuStratumProtocol implements MiningProtocol {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong jobSequence = new AtomicLong();
    private final AtomicLong requestSequence = new AtomicLong(1_000_000);
    private final Map<String, JobOrigin> jobs = boundedMap();
    private final Map<String, PendingSubmit> pending = boundedMap();
    private final Map<String, String> latestJobs = new HashMap<>();
    private final Map<String, String> currentTarget = new HashMap<>();
    private final Map<String, String> extranonces = new HashMap<>();
    private final Set<String> authorized = new HashSet<>();

    private record PendingSubmit(JsonNode minerId) { }

    private static <K, V> Map<K, V> boundedMap() {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>() {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > 4096; }
        });
    }

    abstract String coin();
    abstract boolean validWallet(String wallet);
    abstract String targetMethod();

    @Override
    public String interceptMessageFromMiner(String raw, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null || !"mining.authorize".equals(method(message))) return raw;
        JsonNode params = message.path("params");
        if (!params.isArray() || params.size() < 2 || !params.get(0).isTextual()) return raw;
        String user = params.get(0).asText();
        int marker = user.indexOf(".sm1.");
        if (marker < 0) return raw;
        String wallet = user.substring(0, marker);
        String[] encoded = user.substring(marker + 5).split("\\.", 2);
        if (!validWallet(wallet) || encoded.length != 2 || !encoded[0].matches("[A-Za-z0-9_-]{1,256}")
                || !encoded[1].matches("[A-Za-z0-9_-]{1,32}")) {
            context.disconnect();
            return raw;
        }
        String pool;
        try { pool = new String(Base64.getUrlDecoder().decode(encoded[0]), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { context.disconnect(); return raw; }
        if (!validPool(pool)) { context.disconnect(); return raw; }
        String worker = wallet + "." + encoded[1];
        context.setDynamicRouting(pool, worker, params.get(1).asText());
        ((ArrayNode) params).set(0, mapper.valueToTree(worker));
        return message.toString();
    }

    @Override
    public void handleMessageFromMiner(String raw, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null) { context.disconnect(); return; }
        String method = method(message);
        if (!context.isConnectedToUpstream()) {
            if ("mining.authorize".equals(method)) context.connectToTargetPool(coin());
            return;
        }
        if (!"mining.submit".equals(method)) {
            context.sendToUpstream(FeeManager.USER_TARGET_ID, raw);
            return;
        }
        JsonNode params = message.path("params");
        if (!params.isArray() || params.size() < 3 || !params.get(1).isTextual()
                || !message.hasNonNull("id")) {
            reject(message, context, "Invalid submit");
            return;
        }
        JobOrigin origin = jobs.get(params.get(1).asText());
        if (origin == null || !ready(origin.targetId())) {
            reject(message, context, "Unknown or stale job");
            return;
        }
        String worker = context.getWorkerForTarget(origin.targetId());
        if (worker == null || worker.isBlank()) {
            reject(message, context, "Target worker unavailable");
            return;
        }
        ((ArrayNode) params).set(0, mapper.valueToTree(worker));
        ((ArrayNode) params).set(1, mapper.valueToTree(origin.originalJobId()));
        long upstreamId = requestSequence.incrementAndGet();
        pending.put(origin.targetId() + ":" + upstreamId, new PendingSubmit(message.path("id").deepCopy()));
        message.put("id", upstreamId);
        context.sendToUpstream(origin.targetId(), message.toString());
    }

    @Override
    public void handleMessageFromPool(String raw, String targetId, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null) return;
        String method = method(message);
        if ("client.reconnect".equals(method)) {
            // A pool must not redirect the miner around the SolarMiner proxy.
            // Reopen the whole session; MinerSession's single-upstream reconnect
            // path does not replay this dialect's subscribe/authorize handshake.
            context.disconnect();
            return;
        }
        if ("mining.notify".equals(method)) {
            JsonNode params = message.path("params");
            if (!params.isArray() || params.isEmpty() || !params.get(0).isTextual()) return;
            String original = params.get(0).asText();
            String proxyId = "sm-gpu-" + jobSequence.incrementAndGet();
            jobs.put(proxyId, new JobOrigin(targetId, original));
            ((ArrayNode) params).set(0, mapper.valueToTree(proxyId));
            latestJobs.put(targetId, message.toString());
            if (FeeManager.USER_TARGET_ID.equals(targetId)) {
                String selected = context.rollNextJobTarget();
                if (!ready(selected) || !latestJobs.containsKey(selected)) selected = FeeManager.USER_TARGET_ID;
                if (!selected.equals(context.getCurrentTargetId())) {
                    context.setCurrentTargetId(selected);
                    return;
                }
            }
            if (targetId.equals(context.getCurrentTargetId()) && ready(targetId)) context.sendToMiner(message.toString());
            return;
        }
        if (targetMethod().equals(method) || "mining.set_extranonce".equals(method)) {
            if (targetMethod().equals(method)) currentTarget.put(targetId, raw);
            else if (message.path("params").isArray() && !message.path("params").isEmpty())
                extranonces.put(targetId, message.path("params").get(0).asText());
            if (targetId.equals(context.getCurrentTargetId())) context.sendToMiner(raw);
            return;
        }
        JsonNode id = message.path("id");
        if (!id.isMissingNode() && !id.isNull()) {
            PendingSubmit submit = pending.remove(targetId + ":" + id.asText());
            if (submit != null) {
                message.set("id", submit.minerId());
                context.sendToMiner(message.toString());
                return;
            }
            if (id.isIntegralNumber() && id.asLong() == 1 && message.path("result").isArray()) {
                JsonNode result = message.path("result");
                JsonNode nonce = "ravencoin".equals(coin()) ? result.path(0) : result.path(1);
                if (nonce.isTextual()) extranonces.put(targetId, nonce.asText());
            }
            if (id.isIntegralNumber() && id.asLong() == 2) {
                if (message.path("result").asBoolean(false) && message.path("error").isNull()) authorized.add(targetId);
                else {
                    authorized.remove(targetId);
                    context.disconnect();
                    return;
                }
            }
            // A reply carrying one of our rewritten submit IDs is meaningful only
            // when the matching origin target owns the pending request.
            if (id.isIntegralNumber() && id.asLong() > 1_000_000) return;
            if (FeeManager.USER_TARGET_ID.equals(targetId)) context.sendToMiner(raw);
            return;
        }
        if (targetId.equals(context.getCurrentTargetId())) context.sendToMiner(raw);
    }

    @Override
    public void onTargetChanged(String targetId, ProxyContext context) {
        if (!ready(targetId)) return;
        String target = currentTarget.get(targetId);
        if (target != null) context.sendToMiner(target);
        String nonce = extranonces.get(targetId);
        if (nonce != null) {
            ObjectNode message = mapper.createObjectNode();
            message.putNull("id");
            message.put("method", "mining.set_extranonce");
            message.putArray("params").add(nonce);
            context.sendToMiner(message.toString());
        }
        String job = latestJobs.get(targetId);
        if (job != null) context.sendToMiner(job);
    }

    @Override
    public String translateMessageForUpstream(String raw, String targetId, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null || !"mining.authorize".equals(method(message))) return raw;
        JsonNode params = message.path("params");
        String worker = context.getWorkerForTarget(targetId);
        if (!params.isArray() || params.size() < 2 || worker == null || worker.isBlank()) return raw;
        ((ArrayNode) params).set(0, mapper.valueToTree(worker));
        ((ArrayNode) params).set(1, mapper.valueToTree(context.getPasswordForTarget(targetId) == null
                ? "x" : context.getPasswordForTarget(targetId)));
        return message.toString();
    }

    private boolean ready(String targetId) {
        return authorized.contains(targetId) && currentTarget.containsKey(targetId) && extranonces.containsKey(targetId);
    }

    private boolean validPool(String raw) {
        try {
            URI uri = URI.create(raw.contains("://") ? raw : "stratum+tcp://" + raw);
            return ("stratum+tcp".equals(uri.getScheme()) || "stratum+ssl".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getHost().matches("[A-Za-z0-9.-]+")
                    && uri.getPort() > 0 && uri.getPort() <= 65535
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    private void reject(ObjectNode request, ProxyContext context, String reason) {
        ObjectNode response = mapper.createObjectNode();
        response.set("id", request.path("id").deepCopy());
        response.put("result", false);
        response.putArray("error").add(21).add(reason).addNull();
        context.sendToMiner(response.toString());
    }

    private ObjectNode parse(String raw) {
        try { JsonNode node = mapper.readTree(raw); return node instanceof ObjectNode object ? object : null; }
        catch (Exception e) { return null; }
    }

    private static String method(ObjectNode node) { return node.path("method").asText(""); }
}
