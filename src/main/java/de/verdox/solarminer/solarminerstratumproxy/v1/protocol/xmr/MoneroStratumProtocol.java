package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.xmr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.JobOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component("moneroStratumProtocol")
@Scope("prototype")
public class MoneroStratumProtocol implements MiningProtocol {
    private static final Logger log = LoggerFactory.getLogger(MoneroStratumProtocol.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, JobOrigin> jobRegistry = Collections.synchronizedMap(
            new LinkedHashMap<>(1000, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JobOrigin> eldest) {
                    return size() > 1000;
                }
            }
    );

    private final Map<String, String> cachedJobs = Collections.synchronizedMap(
            new LinkedHashMap<>(1000, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 1000;
                }
            }
    );
    private final AtomicLong internalJobCounter = new AtomicLong(0);

    @Override
    public String interceptMessageFromMiner(String rawMessage, ProxyContext context) {
        JsonNode node = decodeMessage(rawMessage);
        if (node == null) return rawMessage;

        if ("login".equals(node.has("method") ? node.get("method").asText() : "")) {
            JsonNode params = node.get("params");
            if (params != null && params.has("login")) {
                String rawLogin = params.get("login").asText();

                if (rawLogin.contains(";")) {
                    String[] parts = rawLogin.split(";");
                    if (parts.length >= 3) {
                        context.setDynamicRouting(parts[0], parts[1], parts[2]);

                        ObjectNode objNode = (ObjectNode) node;
                        ObjectNode paramNode = (ObjectNode) objNode.get("params");
                        paramNode.put("login", parts[1]);
                        paramNode.put("pass", parts[2]);

                        return objNode.toString();
                    }
                }
            }
        }
        return rawMessage;
    }

    @Override
    public void handleMessageFromMiner(String rawMessage, ProxyContext context) {
        JsonNode node = decodeMessage(rawMessage);
        if (node == null) return;
        String method = node.has("method") ? node.get("method").asText() : "";

        if ("login".equals(method)) {
            context.connectToTargetPool(node.get("params").get("login").asText());
            return;
        }

        if ("submit".equals(method)) {
            try {
                JsonNode params = node.get("params");
                String proxyJobId = params.get("job_id").asText();
                JobOrigin origin = jobRegistry.get(proxyJobId);

                if (origin != null) {
                    ((ObjectNode) params).put("job_id", origin.originalJobId());
                    context.sendToUpstream(origin.targetId(), rawMessage);
                }
            } catch (Exception e) {
                log.error("Fehler beim Submit-Umschreiben", e);
            }
            return;
        }

        if ("keepalived".equals(method)) {
            context.broadcastToUpstreams(rawMessage);
            return;
        }

        context.sendToUpstream(context.getCurrentTargetId(), rawMessage);
    }

    @Override
    public void handleMessageFromPool(String rawMessage, String targetId, ProxyContext context) {
        JsonNode node = decodeMessage(rawMessage);
        if (node == null) return;
        String method = node.has("method") ? node.get("method").asText() : "";

        if ("job".equals(method)) {
            try {
                JsonNode params = node.get("params");
                String originalJobId = params.get("job_id").asText();
                String proxyJobId = "xmr-" + internalJobCounter.incrementAndGet();

                jobRegistry.put(proxyJobId, new JobOrigin(targetId, originalJobId));
                ((ObjectNode) params).put("job_id", proxyJobId);

                cachedJobs.put(targetId, rawMessage);

                if (FeeManager.USER_TARGET_ID.equals(targetId)) {
                    String nextTarget = context.rollNextJobTarget();
                    if (!nextTarget.equals(context.getCurrentTargetId())) {
                        context.setCurrentTargetId(nextTarget);
                        return;
                    }
                }

                if (targetId.equals(context.getCurrentTargetId())) {
                    context.sendToMiner(rawMessage);
                }
            } catch (Exception e) {
                log.error("Fehler beim Job-Forwarding", e);
            }
            return;
        }

        if (targetId.equals(context.getCurrentTargetId())) {
            context.sendToMiner(rawMessage);
        }
    }

    @Override
    public void onTargetChanged(String newTargetId, ProxyContext context) {
        String latestJob = cachedJobs.get(newTargetId);
        if (latestJob != null) {
            context.sendToMiner(latestJob);
        }
    }

    @Override
    public String translateMessageForUpstream(String rawMessage, String targetId, ProxyContext context) {
        JsonNode node = decodeMessage(rawMessage);
        if (node != null && node.has("method") && "login".equals(node.get("method").asText())) {
            try {
                ObjectNode objNode = (ObjectNode) node;
                if (objNode.has("params")) {
                    ObjectNode params = (ObjectNode) objNode.get("params");
                    String worker = context.getWorkerForTarget(targetId);
                    String pass = context.getPasswordForTarget(targetId);

                    if (worker != null) params.put("login", worker);
                    if (pass != null) params.put("pass", pass);
                }
                return mapper.writeValueAsString(objNode);
            } catch (Exception e) {
                log.error("Fehler beim Übersetzen des Login-Pakets", e);
            }
        }
        return rawMessage;
    }

    private JsonNode decodeMessage(String rawJson) {
        try {
            return mapper.readTree(rawJson);
        } catch (Exception e) {
            return null;
        }
    }
}
