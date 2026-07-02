package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.btc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.JobOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component("bitcoinStratumProtocol")
@Scope("prototype")
public class BitcoinStratumProtocol implements MiningProtocol {
    private static final Logger log = LoggerFactory.getLogger(BitcoinStratumProtocol.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<String, ExtranonceState> extranonceStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> cachedNotifies = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, JobOrigin> jobRegistry = new ConcurrentHashMap<>();
    private final AtomicLong internalJobCounter = new AtomicLong(0);

    @Override
    public String interceptMessageFromMiner(String rawMessage, ProxyContext context) {
        JsonNode node = decodeMessage(rawMessage);
        if (node == null) return rawMessage;

        String method = node.has("method") ? node.get("method").asText() : "";

        if (BitcoinStratumMethods.MINING_AUTHORIZE.equals(method)) {
            JsonNode params = node.get("params");
            if (params != null && params.isArray() && params.size() > 0) {
                String rawUserParam = params.get(0).asText();

                if (rawUserParam.contains(";")) {
                    String[] parts = rawUserParam.split(";");
                    if (parts.length >= 3) {
                        context.setDynamicRouting(parts[0], parts[1], parts[2]);
                        ArrayNode arrayParams = (ArrayNode) params;
                        arrayParams.set(0, mapper.valueToTree(parts[1]));
                        if (arrayParams.size() > 1) {
                            arrayParams.set(1, mapper.valueToTree(parts[2]));
                        } else {
                            arrayParams.add(parts[2]);
                        }

                        return node.toString();
                    }
                }
            }
        }
        else if (BitcoinStratumMethods.MINING_SUBMIT.equals(method)) {
            JsonNode params = node.get("params");
            String workerName = params.get(0).asText();
            if (workerName.contains(";")) {
                String[] parts = workerName.split(";");
                if (parts.length >= 2) {
                    ((ArrayNode) params).set(0, mapper.valueToTree(parts[1]));
                    return node.toString();
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

        if (!context.isConnectedToUpstream()) {

            if (BitcoinStratumMethods.MINING_CONFIGURE.equals(method)) {
                long id = node.has("id") ? node.get("id").asLong() : 0;
                String dummyConfigure = String.format("{\"id\": %d, \"result\": {\"version-rolling\": true, \"version-rolling.mask\": \"1fffe000\"}, \"error\": null}", id);
                context.sendToMiner(dummyConfigure);
                return;
            }
            else if (BitcoinStratumMethods.MINING_SUBSCRIBE.equals(method)) {
                long id = node.has("id") ? node.get("id").asLong() : 1;
                context.sendToMiner(String.format("{\"id\": %d, \"result\": [[[\"mining.set_difficulty\", \"1\"], [\"mining.notify\", \"1\"]], \"00000000\", 4], \"error\": null}", id));
                return;
            }
            if (BitcoinStratumMethods.MINING_AUTHORIZE.equals(method) && node.has("params") && node.get("params").isArray()) {
                String rawUserParam = node.get("params").get(0).asText();
                context.connectToTargetPool(rawUserParam);
            }
            return;
        }

        if (BitcoinStratumMethods.MINING_SUBMIT.equals(method)) {
            try {
                String internalId = node.get("params").get(1).asText();
                JobOrigin origin = jobRegistry.get(internalId);

                if (origin != null) {
                    ObjectNode objNode = (ObjectNode) node;
                    ((ArrayNode) objNode.get("params")).set(1, mapper.valueToTree(origin.originalJobId()));
                    context.sendToUpstream(origin.targetId(), mapper.writeValueAsString(objNode));
                }
            } catch (Exception e) {
                log.error("Fehler beim Umschreiben des Submits", e);
            }
        } else if (BitcoinStratumMethods.MINING_CONFIGURE.equals(method) ||
                BitcoinStratumMethods.MINING_SUBSCRIBE.equals(method) ||
                BitcoinStratumMethods.MINING_EXTRANONCE_SUBSCRIBE.equals(method)) {

            context.broadcastToUpstreams(rawMessage);
        } else {
            context.sendToUpstream(FeeManager.USER_TARGET_ID, rawMessage);
        }
    }

    @Override
    public void handleMessageFromPool(String rawMessage, String targetId, ProxyContext context) {
        JsonNode node = decodeMessage(rawMessage);
        if (node == null) return;
        String method = node.has("method") ? node.get("method").asText() : "";

        if (BitcoinStratumMethods.CLIENT_RECONNECT.equals(method)) {
            try {
                JsonNode params = node.get("params");
                String newUrl = params.get(0).asText();
                int newPort = params.get(1).asInt();

                log.warn("Pool [{}] sendet Reconnect-Befehl: {}:{}", targetId, newUrl, newPort);
                context.reconnectToTarget(targetId, newUrl + ":" + newPort);

            } catch (Exception e) {
                log.error("Fehler beim Verarbeiten des Reconnect-Befehls", e);
            }
            return;
        }
        if (BitcoinStratumMethods.MINING_SET_DIFFICULTY.equals(method)) {
            if(targetId.equals(context.getCurrentTargetId()))
                context.sendToMiner(rawMessage);
            return;
        }
        if (BitcoinStratumMethods.MINING_NOTIFY.equals(method)) {
            try {
                String originalJobId = node.get("params").get(0).asText();
                String proxyJobId = "proxy-" + internalJobCounter.incrementAndGet();
                jobRegistry.put(proxyJobId, new JobOrigin(targetId, originalJobId));

                ObjectNode objNode = (ObjectNode) node;
                ((ArrayNode) objNode.get("params")).set(0, mapper.valueToTree(proxyJobId));
                String rewritten = mapper.writeValueAsString(objNode);

                cachedNotifies.put(targetId, rewritten);

                if (FeeManager.USER_TARGET_ID.equals(targetId)) {
                    String nextTarget = context.rollNextJobTarget();
                    if (!nextTarget.equals(context.getCurrentTargetId())) {
                        context.setCurrentTargetId(nextTarget);
                        return;
                    }
                }

                if (targetId.equals(context.getCurrentTargetId())) {
                    context.sendToMiner(rewritten);
                }
            } catch (Exception ignored) {
            }
            return;
        }

        if (BitcoinStratumMethods.MINING_SET_EXTRANONCE.equals(method)) {
            try {
                ExtranonceState state = new ExtranonceState(node.get("params").get(0).asText(), node.get("params").get(1).asInt());
                extranonceStates.put(targetId, state);
                if (targetId.equals(context.getCurrentTargetId())) context.sendToMiner(rawMessage);
            } catch (Exception ignored) {
            }
            return;
        }

        if (node.has("id") && !node.get("id").isNull() && node.has("result")) {
            JsonNode result = node.get("result");

            if (result.isArray() && result.size() >= 3) {
                try {
                    ExtranonceState state = new ExtranonceState(result.get(1).asText(), result.get(2).asInt());
                    extranonceStates.put(targetId, state);

                    if (targetId.equals(context.getCurrentTargetId())) {
                        ObjectNode setExtranonce = mapper.createObjectNode();
                        setExtranonce.putNull("id");
                        setExtranonce.put("method", BitcoinStratumMethods.MINING_SET_EXTRANONCE);
                        ArrayNode params = setExtranonce.putArray("params");
                        params.add(state.extranonce1());
                        params.add(state.extranonce2Size());
                        context.sendToMiner(mapper.writeValueAsString(setExtranonce));
                    }
                } catch (Exception ignored) {}
                return;
            }

            if (result.isObject() && result.has("version-rolling")) {
                return;
            }
            if (targetId.equals(context.getCurrentTargetId())) {
                context.sendToMiner(rawMessage);
            }
            return;
        }

        if (targetId.equals(context.getCurrentTargetId())) {
            context.sendToMiner(rawMessage);
        }
    }

    @Override
    public void onTargetChanged(String newTargetId, ProxyContext context) {
        ExtranonceState state = extranonceStates.get(newTargetId);
        if (state != null) {
            ObjectNode node = mapper.createObjectNode();
            node.putNull("id");
            node.put("method", BitcoinStratumMethods.MINING_SET_EXTRANONCE);
            ArrayNode params = node.putArray("params");
            params.add(state.extranonce1());
            params.add(state.extranonce2Size());
            try {
                context.sendToMiner(mapper.writeValueAsString(node));
            } catch (Exception ignored) {
            }
        }

        String latestNotify = cachedNotifies.get(newTargetId);
        if (latestNotify != null) {
            context.sendToMiner(latestNotify);
        }
    }

    @Override
    public String translateMessageForUpstream(String rawMessage, String targetId, ProxyContext context) {
        if (FeeManager.USER_TARGET_ID.equals(targetId)) return rawMessage;

        JsonNode node = decodeMessage(rawMessage);
        if (node != null && node.has("method") && BitcoinStratumMethods.MINING_AUTHORIZE.equals(node.get("method").asText())) {
            try {
                ObjectNode objNode = (ObjectNode) node;
                if (objNode.has("params") && objNode.get("params").isArray()) {
                    ArrayNode params = (ArrayNode) objNode.get("params");
                    String worker = context.getWorkerForTarget(targetId);
                    String pass = context.getPasswordForTarget(targetId);
                    if (worker != null && params.size() > 0) params.set(0, mapper.valueToTree(worker));
                    if (pass != null && params.size() > 1) params.set(1, mapper.valueToTree(pass));
                }
                return mapper.writeValueAsString(objNode);
            } catch (Exception ignored) {
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

    private record ExtranonceState(String extranonce1, int extranonce2Size) {
    }
}