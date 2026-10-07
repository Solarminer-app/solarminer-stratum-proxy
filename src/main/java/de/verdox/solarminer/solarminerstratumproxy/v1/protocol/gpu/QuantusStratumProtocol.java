package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.JobOrigin;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Kryptex QTC login/job/submit dialect observed on port 7049, not node QUIC. */
@Component("quantusStratumProtocol")
@Scope("prototype")
public class QuantusStratumProtocol implements MiningProtocol {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> sessions = new HashMap<>();
    private final Map<String, ObjectNode> latest = new HashMap<>();
    private final Map<String, JobOrigin> jobs = bounded();
    private final Map<String, JsonNode> pending = bounded();
    private final Map<String, Integer> reconnects = new HashMap<>();
    private final Map<String, String> requests = bounded();
    private final Set<String> restarting = new HashSet<>();
    private JsonNode loginId;
    private long sequence;
    private long requestSequence = 1_000_000;

    private static <T> Map<String, T> bounded() {
        return new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, T> eldest) { return size() > 4096; }
        };
    }

    @Override public boolean isHandshakeMessage(String raw) {
        ObjectNode message = parse(raw);
        return message != null && "login".equals(message.path("method").asText());
    }

    @Override public String interceptMessageFromMiner(String raw, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null) return raw;
        String method = message.path("method").asText();
        if (message.hasNonNull("id")) requests.put(message.path("id").toString(), method);
        if (!"solarminer.route".equals(method) && !"login".equals(method)) return raw;
        try {
            if ("solarminer.route".equals(method)) {
                JsonNode params = message.path("params");
                GpuRouteEnvelope.apply(params.path(0).asText(), params.path(1).asText("x"), this::validWallet, context);
                return null;
            }
            if (!message.hasNonNull("id") || !message.path("params").isObject()) throw new IllegalArgumentException();
            ObjectNode params = (ObjectNode) message.path("params");
            String worker = GpuRouteEnvelope.apply(params.path("login").asText(), params.path("pass").asText("x"), this::validWallet, context);
            params.put("login", worker);
            loginId = message.path("id").deepCopy();
            return message.toString();
        } catch (IllegalArgumentException invalid) {
            context.recordProtocolError("Invalid Quantus login or route envelope");
            context.disconnect();
            return null;
        }
    }

    private boolean validWallet(String wallet) { return wallet.matches("qz[1-9A-HJ-NP-Za-km-z]{38,58}"); }

    @Override public void handleMessageFromMiner(String raw, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null) { context.disconnect(); return; }
        String method = message.path("method").asText();
        if (!context.isConnectedToUpstream()) {
            if ("login".equals(method)) context.connectToTargetPool("quantus");
            else { context.recordProtocolError("Quantus requires the login Stratum dialect"); context.disconnect(); }
            return;
        }
        if (!"submit".equals(method)) { context.broadcastToUpstreams(raw); return; }
        JsonNode params = message.path("params");
        JobOrigin origin = jobs.get(params.path("job_id").asText());
        if (!params.isObject() || !message.hasNonNull("id") || origin == null || !sessions.containsKey(origin.targetId())) {
            context.recordLocalReject("Unknown or stale Quantus job");
            ObjectNode error = mapper.createObjectNode().put("jsonrpc", "2.0");
            error.set("id", message.path("id"));
            error.putNull("result");
            error.putObject("error").put("code", -1).put("message", "Unknown or stale Quantus job");
            context.sendToMiner(error.toString());
            return;
        }
        ((ObjectNode) params).put("job_id", origin.originalJobId());
        ((ObjectNode) params).put("id", sessions.get(origin.targetId()));
        long id;
        do { id = ++requestSequence; } while (requests.containsKey(Long.toString(id)));
        pending.put(targetKey(origin.targetId(), mapper.valueToTree(id)), message.path("id").deepCopy());
        message.put("id", id);
        context.sendToUpstream(origin.targetId(), message.toString());
    }

    @Override public void handleMessageFromPool(String raw, String targetId, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null) return;
        String method = message.path("method").asText();
        if ("client.reconnect".equals(method)) {
            if (reconnects.merge(targetId, 1, Integer::sum) > 3) { context.recordProtocolError("Quantus reconnect limit exceeded"); context.disconnect(); return; }
            sessions.remove(targetId);
            restarting.add(targetId);
            latest.remove(targetId);
            jobs.entrySet().removeIf(entry -> entry.getValue().targetId().equals(targetId));
            context.reconnectToTarget(targetId, null);
            return;
        }
        if (message.hasNonNull("id")) {
            JsonNode id = message.path("id");
            JsonNode minerId = pending.remove(targetKey(targetId, id));
            if (minerId != null) { message.set("id", minerId); context.sendToMiner(message.toString()); return; }
            if (loginId != null && loginId.equals(id)) {
                JsonNode result = message.path("result");
                if (!result.path("id").isTextual() || !"OK".equals(result.path("status").asText()) || message.hasNonNull("error")) {
                    context.recordProtocolError("Quantus upstream login failed for " + targetId);
                    context.disconnect();
                    return;
                }
                sessions.put(targetId, result.path("id").asText());
                if (result.path("job") instanceof ObjectNode job) cache(job, targetId, true);
                if (restarting.remove(targetId)) {
                    if (targetId.equals(context.getCurrentTargetId())) onTargetChanged(targetId, context);
                    return;
                }
                if (FeeManager.USER_TARGET_ID.equals(targetId)) {
                    if (latest.containsKey(targetId)) ((ObjectNode) result).set("job", latest.get(targetId).path("params").path("job").deepCopy());
                    context.sendToMiner(message.toString());
                } else if (targetId.equals(context.getCurrentTargetId())) onTargetChanged(targetId, context);
                return;
            }
            if ((!id.isIntegralNumber() || id.asLong() <= 1_000_000 || requests.containsKey(id.toString()))
                    && FeeManager.USER_TARGET_ID.equals(targetId)) context.sendToMiner(raw);
            return;
        }
        if ("job".equals(method) && message.path("params") instanceof ObjectNode params) {
            ObjectNode job = params.path("job") instanceof ObjectNode wrapped ? wrapped : params;
            cache(job, targetId, params.path("clean_jobs").asBoolean(true));
            if (FeeManager.USER_TARGET_ID.equals(targetId)) {
                String selected = context.rollNextJobTarget();
                if (!sessions.containsKey(selected) || !latest.containsKey(selected)) selected = FeeManager.USER_TARGET_ID;
                if (!selected.equals(context.getCurrentTargetId())) { context.setCurrentTargetId(selected); return; }
            }
            if (targetId.equals(context.getCurrentTargetId())) onTargetChanged(targetId, context);
        }
    }

    private void cache(ObjectNode job, String targetId, boolean cleanJobs) {
        if (!job.path("job_id").isTextual()) return;
        String id = Long.toHexString(++sequence);
        jobs.put(id, new JobOrigin(targetId, job.path("job_id").asText()));
        ObjectNode copy = job.deepCopy().put("job_id", id);
        ObjectNode event = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "job");
        event.putObject("params").put("clean_jobs", cleanJobs).set("job", copy);
        latest.put(targetId, event);
    }

    @Override public void onTargetChanged(String targetId, ProxyContext context) {
        if (sessions.containsKey(targetId) && latest.containsKey(targetId)) context.sendToMiner(latest.get(targetId).toString());
    }

    @Override public String translateMessageForUpstream(String raw, String targetId, ProxyContext context) {
        ObjectNode message = parse(raw);
        if (message == null || !message.path("params").isObject()) return raw;
        ObjectNode params = (ObjectNode) message.path("params");
        if ("login".equals(message.path("method").asText())) {
            params.put("login", context.getWorkerForTarget(targetId));
            params.put("pass", context.getPasswordForTarget(targetId) == null ? "x" : context.getPasswordForTarget(targetId));
        } else if (params.has("id") && sessions.containsKey(targetId)) params.put("id", sessions.get(targetId));
        return message.toString();
    }

    private static String targetKey(String targetId, JsonNode id) { return targetId + ":" + id; }
    private ObjectNode parse(String raw) {
        try { JsonNode node = mapper.readTree(raw); return node instanceof ObjectNode object ? object : null; }
        catch (Exception malformed) { return null; }
    }
}
