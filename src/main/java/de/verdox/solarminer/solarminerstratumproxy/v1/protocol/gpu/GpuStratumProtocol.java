package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.JobOrigin;

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
 * The embedded agent exposes local listeners; production rollout still needs real
 * submit and fee-switch verification.
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
    private final Map<String, String> requests = boundedMap();
    private final Map<String, ArrayNode> nonceParams = new HashMap<>();
    private boolean routePrepared;
    private boolean extranonceSubscribed;
    private ArrayNode minerNonceParameters;
    private boolean minerJsonRpc2;
    private final Set<String> restarting = new HashSet<>();
    private final Map<String, Integer> reconnects = new HashMap<>();

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
    public boolean isHandshakeMessage(String raw) {
        ObjectNode message = parse(raw);
        return message != null && Set.of("mining.subscribe", "mining.authorize",
                "mining.configure").contains(method(message));
    }

    @Override
    public String interceptMessageFromMiner(String raw, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null) return raw;
        if ("2.0".equals(message.path("jsonrpc").asText())) minerJsonRpc2 = true;
        if (message.hasNonNull("id")) requests.put(message.path("id").toString(), method(message));
        if ("mining.extranonce.subscribe".equals(method(message))) {
            // The proxy owns nonce changes across fee pools. Some upstreams reject
            // this optional extension or close the socket when asked about it.
            extranonceSubscribed = true;
            if (message.hasNonNull("id")) {
                ObjectNode response = mapper.createObjectNode();
                if (message.has("jsonrpc")) response.set("jsonrpc", message.path("jsonrpc"));
                response.set("id", message.path("id").deepCopy());
                response.put("result", true);
                response.putNull("error");
                context.sendToMiner(response.toString());
            }
            return null;
        }
        boolean route = "solarminer.route".equals(method(message));
        if (!route && !"mining.authorize".equals(method(message))) return raw;
        JsonNode params = message.path("params");
        if (!params.isArray() || params.size() < 2 || !params.get(0).isTextual()) {
            if (route) { context.recordProtocolError("Invalid route preamble"); context.disconnect(); return null; }
            return raw;
        }
        String user = params.get(0).asText();
        int marker = user.indexOf(".sm1.");
        if (marker < 0) {
            if (route) { context.recordProtocolError("Invalid route preamble"); context.disconnect(); return null; }
            return raw;
        }
        String worker;
        try { worker = GpuRouteEnvelope.apply(user, params.get(1).asText(), this::validWallet, context); }
        catch (IllegalArgumentException invalid) {
            context.recordProtocolError("Invalid GPU route envelope"); context.disconnect(); return null;
        }
        routePrepared = true;
        if (route) return null; // Internal envelope is never sent to a mining pool.
        ((ArrayNode) params).set(0, mapper.valueToTree(worker));
        return message.toString();
    }

    @Override
    public void handleMessageFromMiner(String raw, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null) { context.disconnect(); return; }
        String method = method(message);
        if (message.hasNonNull("id")) requests.put(message.path("id").toString(), method);
        if (!context.isConnectedToUpstream()) {
            if (routePrepared) context.connectToTargetPool(coin());
            else if ("mining.subscribe".equals(method)) {
                reject(message, context, "GPU route preamble required; update PC-Agent and proxy together");
                context.disconnect();
            }
            return;
        }
        if (!"mining.submit".equals(method)) {
            context.broadcastToUpstreams(raw);
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
        long upstreamId;
        do { upstreamId = requestSequence.incrementAndGet(); } while (requests.containsKey(Long.toString(upstreamId)));
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
            // Ignore redirect destinations; only reopen the configured route.
            if (reconnects.merge(targetId, 1, Integer::sum) > 3) {
                context.recordProtocolError("Upstream reconnect limit exceeded for " + targetId);
                context.disconnect();
                return;
            }
            restarting.add(targetId);
            authorized.remove(targetId);
            currentTarget.remove(targetId);
            extranonces.remove(targetId);
            nonceParams.remove(targetId);
            latestJobs.remove(targetId);
            synchronized (jobs) { jobs.entrySet().removeIf(entry -> entry.getValue().targetId().equals(targetId)); }
            context.reconnectToTarget(targetId, null);
            return;
        }
        if ("mining.notify".equals(method)) {
            JsonNode params = message.path("params");
            if (!params.isArray() || params.isEmpty() || !params.get(0).isTextual()) return;
            String original = params.get(0).asText();
            // Some raw KAWPOW pools carry the complete target only in notify.
            if ("ravencoin".equals(coin()) && params.size() > 3 && params.get(3).isTextual()
                    && params.get(3).asText().matches("(?:0x)?[A-Fa-f0-9]{64}")) {
                ObjectNode target = mapper.createObjectNode().put("method", "mining.set_target");
                target.putNull("id");
                target.putArray("params").add(params.get(3).asText());
                if (minerJsonRpc2) target.put("jsonrpc", "2.0");
                currentTarget.put(targetId, target.toString());
            }
            String proxyId = Long.toHexString(jobSequence.incrementAndGet());
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
            else if (message.path("params").isArray() && !message.path("params").isEmpty()) {
                extranonces.put(targetId, message.path("params").get(0).asText());
                nonceParams.put(targetId, ((ArrayNode) message.path("params")).deepCopy());
            }
            if (targetId.equals(context.getCurrentTargetId())) {
                if ("mining.set_extranonce".equals(method) && !extranonceSubscribed) {
                    if (nonceParams.get(targetId).equals(minerNonceParameters)) return;
                    context.recordProtocolError("Pool changed extranonce without miner capability");
                    context.disconnect();
                    return;
                }
                if ("mining.set_extranonce".equals(method)) minerNonceParameters = nonceParams.get(targetId);
                context.sendToMiner(raw);
            }
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
            String requestMethod = requests.get(id.toString());
            if ("mining.extranonce.subscribe".equals(requestMethod)) return;
            if ("mining.subscribe".equals(requestMethod)) {
                JsonNode result = message.path("result");
                JsonNode nonce = "ravencoin".equals(coin())
                        ? (result.path(0).isTextual() ? result.path(0) : result.path(1))
                        : result.path(1);
                if (!result.isArray() || !nonce.isTextual() || message.hasNonNull("error")) {
                    context.recordProtocolError("Upstream subscription failed for " + targetId);
                    if (FeeManager.USER_TARGET_ID.equals(targetId)) context.sendToMiner(raw);
                    context.disconnect();
                    return;
                }
                if (nonce.isTextual()) {
                    extranonces.put(targetId, nonce.asText());
                    ArrayNode params = mapper.createArrayNode().add(nonce.asText());
                    if (result.size() > 2 && result.path(2).canConvertToInt()) params.add(result.path(2).asInt());
                    nonceParams.put(targetId, params);
                    if (FeeManager.USER_TARGET_ID.equals(targetId) && !restarting.contains(targetId)) minerNonceParameters = params.deepCopy();
                }
            }
            if ("mining.authorize".equals(requestMethod)) {
                if (message.path("result").asBoolean(false) && (message.path("error").isNull() || !message.has("error"))) authorized.add(targetId);
                else {
                    authorized.remove(targetId);
                    context.recordProtocolError("Upstream authorization failed for " + targetId);
                    context.disconnect();
                    return;
                }
            }
            // A reply carrying one of our rewritten submit IDs is meaningful only
            // when the matching origin target owns the pending request.
            if (id.isIntegralNumber() && id.asLong() > 1_000_000 && requestMethod == null) return;
            if (restarting.contains(targetId)) {
                if ("mining.authorize".equals(requestMethod) && authorized.contains(targetId)) {
                    restarting.remove(targetId);
                    if (targetId.equals(context.getCurrentTargetId())) onTargetChanged(targetId, context);
                }
                return;
            }
            if (FeeManager.USER_TARGET_ID.equals(targetId))
                context.sendToMiner(raw);
            if ("mining.authorize".equals(requestMethod) && targetId.equals(context.getCurrentTargetId()) && ready(targetId)) {
                String job = latestJobs.get(targetId);
                if (job != null) context.sendToMiner(job);
            }
            return;
        }
        if (targetId.equals(context.getCurrentTargetId())) context.sendToMiner(raw);
    }

    @Override
    public void onTargetChanged(String targetId, ProxyContext context) {
        if (!ready(targetId)) return;
        String target = currentTarget.get(targetId);
        if (target != null) context.sendToMiner(target);
        else if ("ethereumclassic".equals(coin())) {
            ObjectNode difficulty = mapper.createObjectNode().put("method", "mining.set_difficulty");
            difficulty.putNull("id");
            difficulty.putArray("params").add(1);
            if (minerJsonRpc2) difficulty.put("jsonrpc", "2.0");
            context.sendToMiner(difficulty.toString());
        }
        String nonce = extranonces.get(targetId);
        if (nonce != null && !nonceParams.get(targetId).equals(minerNonceParameters)) {
            if (!extranonceSubscribed) {
                context.recordProtocolError("Fee switch requires mining.extranonce.subscribe");
                context.disconnect();
                return;
            }
            ObjectNode message = mapper.createObjectNode();
            message.putNull("id");
            message.put("method", "mining.set_extranonce");
            if (minerJsonRpc2) message.put("jsonrpc", "2.0");
            message.set("params", nonceParams.get(targetId));
            context.sendToMiner(message.toString());
            minerNonceParameters = nonceParams.get(targetId).deepCopy();
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
        return authorized.contains(targetId) && extranonces.containsKey(targetId)
                && (currentTarget.containsKey(targetId) || "ethereumclassic".equals(coin()));
    }

    private void reject(ObjectNode request, ProxyContext context, String reason) {
        context.recordLocalReject(reason);
        ObjectNode response = mapper.createObjectNode();
        if (request.has("jsonrpc")) response.set("jsonrpc", request.path("jsonrpc"));
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
