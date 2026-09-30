package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.pearl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.JobOrigin;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/** Pearl JSON-RPC dialect: named job/submit parameters and large opaque proofs. */
@Component("pearlStratumProtocol")
@Scope("prototype")
public class PearlStratumProtocol implements MiningProtocol {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong jobSequence = new AtomicLong();
    private final AtomicLong requestSequence = new AtomicLong(1_000_000);
    private final Map<String, JobOrigin> jobs = boundedMap(4096);
    private final Map<String, PendingSubmit> pendingSubmits = boundedMap(4096);
    private final Map<String, String> latestJobs = new HashMap<>();
    private final Set<String> authorizedTargets = new HashSet<>();
    private JsonNode authorizeId;

    private record PendingSubmit(String targetId, JsonNode minerId) { }

    private static <K, V> Map<K, V> boundedMap(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>() {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > max; }
        });
    }

    @Override
    public String interceptMessageFromMiner(String rawMessage, ProxyContext context) {
        ObjectNode message = parse(rawMessage);
        if (message == null || !"mining.authorize".equals(method(message))) return rawMessage;
        JsonNode credential = credential(message.path("params"));
        if (credential == null || !credential.isTextual()) return rawMessage;
        String[] parts = credential.asText().split(";", 3);
        if (parts.length == 3 && validPool(parts[0]) && parts[1].startsWith("prl1")) {
            context.setDynamicRouting(parts[0], parts[1], parts[2]);
            setCredential(message.path("params"), parts[1], parts[2]);
        } else {
            JsonNode params = message.path("params");
            JsonNode worker = params.path("worker");
            if (!credential.asText().startsWith("prl1") || !worker.isTextual()) return rawMessage;
            String[] encoded = worker.asText().split("\\.", 3);
            if (encoded.length != 3 || !"sm1".equals(encoded[0])
                    || encoded[1].length() > 256 || !encoded[1].matches("[A-Za-z0-9_-]+")
                    || !encoded[2].matches("[A-Za-z0-9_-]{1,32}")) return rawMessage;
            String pool;
            try { pool = new String(Base64.getUrlDecoder().decode(encoded[1]), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { return rawMessage; }
            if (!validPool(pool)) return rawMessage;
            context.setDynamicRouting(pool, credential.asText() + "/" + encoded[2], null);
            setCredential(params, credential.asText() + "/" + encoded[2], null);
        }
        authorizeId = message.path("id").deepCopy();
        return message.toString();
    }

    @Override
    public void handleMessageFromMiner(String rawMessage, ProxyContext context) {
        ObjectNode message = parse(rawMessage);
        if (message == null) { context.disconnect(); return; }
        String method = method(message);
        if (!context.isConnectedToUpstream()) {
            if ("mining.authorize".equals(method)) context.connectToTargetPool("pearl");
            return;
        }
        if (!"mining.submit".equals(method)) {
            context.sendToUpstream(FeeManager.USER_TARGET_ID, rawMessage);
            return;
        }
        JsonNode params = message.path("params");
        JsonNode jobId = params.path("job_id");
        if (!message.path("id").isIntegralNumber() || !params.isObject() || !jobId.isTextual()
                || !params.path("plain_proof").isTextual()) {
            reject(message, context, 20, "invalid Pearl submit");
            return;
        }
        JobOrigin origin = jobs.get(jobId.asText());
        if (origin == null || !authorizedTargets.contains(origin.targetId())) {
            reject(message, context, 21, "stale job");
            return;
        }
        ((ObjectNode) params).put("job_id", origin.originalJobId());
        long upstreamId = requestSequence.incrementAndGet();
        pendingSubmits.put(origin.targetId() + ":" + upstreamId,
                new PendingSubmit(origin.targetId(), message.path("id").deepCopy()));
        message.put("id", upstreamId);
        context.sendToUpstream(origin.targetId(), message.toString());
    }

    @Override
    public void handleMessageFromPool(String rawMessage, String targetId, ProxyContext context) {
        ObjectNode message = parse(rawMessage);
        if (message == null) return;
        String method = method(message);
        if ("mining.notify".equals(method)) {
            // A pool may notify before its authorize acknowledgement. Cache the job,
            // but expose fee work only after that upstream is authorized.
            JsonNode params = message.path("params");
            JsonNode jobId = params.path("job_id");
            if (!params.isObject() || !jobId.isTextual() || jobId.asText().isBlank()) return;
            String proxyId = "sm-prl-" + jobSequence.incrementAndGet();
            jobs.put(proxyId, new JobOrigin(targetId, jobId.asText()));
            ((ObjectNode) params).put("job_id", proxyId);
            latestJobs.put(targetId, message.toString());
            if (FeeManager.USER_TARGET_ID.equals(targetId) && authorizedTargets.contains(targetId)) {
                String selected = context.rollNextJobTarget();
                if (!authorizedTargets.contains(selected) || !latestJobs.containsKey(selected))
                    selected = FeeManager.USER_TARGET_ID;
                boolean changed = !selected.equals(context.getCurrentTargetId());
                context.setCurrentTargetId(selected);
                if (changed && selected.equals(targetId)) return;
            }
            if (targetId.equals(context.getCurrentTargetId())
                    && (FeeManager.USER_TARGET_ID.equals(targetId) || authorizedTargets.contains(targetId)))
                context.sendToMiner(message.toString());
            return;
        }
        JsonNode id = message.path("id");
        if (!id.isMissingNode() && !id.isNull()) {
            PendingSubmit pending = pendingSubmits.remove(targetId + ":" + id.asText());
            if (pending != null) {
                message.set("id", pending.minerId());
                context.sendToMiner(message.toString());
                return;
            }
            if (authorizeId != null && authorizeId.equals(id)) {
                if (message.path("result").asBoolean(false) && message.path("error").isNull())
                    authorizedTargets.add(targetId);
                else if (!FeeManager.USER_TARGET_ID.equals(targetId)) {
                    context.disconnect();
                    return;
                }
                if (FeeManager.USER_TARGET_ID.equals(targetId)) context.sendToMiner(rawMessage);
                return;
            }
            if (!FeeManager.USER_TARGET_ID.equals(targetId) || (id.isIntegralNumber() && id.asLong() > 1_000_000))
                return;
        }
        if (targetId.equals(context.getCurrentTargetId())) context.sendToMiner(rawMessage);
    }

    @Override
    public void onTargetChanged(String newTargetId, ProxyContext context) {
        if (authorizedTargets.contains(newTargetId)) {
            String job = latestJobs.get(newTargetId);
            if (job != null) context.sendToMiner(job);
        }
    }

    @Override
    public String translateMessageForUpstream(String rawMessage, String targetId, ProxyContext context) {
        ObjectNode message = parse(rawMessage);
        if (message == null || !"mining.authorize".equals(method(message))) return rawMessage;
        String targetWorker = context.getWorkerForTarget(targetId);
        if (targetWorker == null || targetWorker.isBlank()) return rawMessage;
        setCredential(message.path("params"), targetWorker, context.getPasswordForTarget(targetId));
        return message.toString();
    }

    private JsonNode credential(JsonNode params) {
        if (params.isArray() && !params.isEmpty()) return params.get(0);
        if (params.isObject()) {
            for (String key : new String[]{"wallet", "login", "user"})
                if (params.has(key)) return params.get(key);
        }
        return null;
    }

    private void setCredential(JsonNode params, String target, String password) {
        if (params.isArray()) {
            ArrayNode values = (ArrayNode) params;
            if (!values.isEmpty()) values.set(0, mapper.getNodeFactory().textNode(target));
            if (password != null && values.size() > 1) values.set(1, mapper.getNodeFactory().textNode(password));
        } else if (params.isObject()) {
            ObjectNode values = (ObjectNode) params;
            String key = values.has("wallet") ? "wallet" : values.has("login") ? "login" : "user";
            boolean combined = !values.has("worker") && target.contains("/");
            if (!combined && target.contains("/")) {
                String[] walletAndWorker = target.split("/", 2);
                values.put(key, walletAndWorker[0]);
                values.put("worker", walletAndWorker[1]);
            } else values.put(key, target);
            if (password != null && (values.has("password") || values.has("pass")))
                values.put(values.has("password") ? "password" : "pass", password);
        }
    }

    private void reject(ObjectNode request, ProxyContext context, int code, String reason) {
        ObjectNode response = mapper.createObjectNode();
        response.set("id", request.path("id").deepCopy());
        response.putNull("result");
        response.putObject("error").put("code", code).put("msg", reason);
        context.sendToMiner(response.toString());
    }

    private ObjectNode parse(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            return node instanceof ObjectNode object ? object : null;
        } catch (Exception ignored) { return null; }
    }

    private static String method(JsonNode message) { return message.path("method").asText(""); }

    private static boolean validPool(String value) {
        try {
            URI uri = URI.create(value);
            return ("stratum+tcp".equals(uri.getScheme()) || "stratum+ssl".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                    && uri.getUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty());
        } catch (IllegalArgumentException ignored) { return false; }
    }
}
