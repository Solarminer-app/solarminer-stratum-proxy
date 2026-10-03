package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GpuStratumProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ravencoinKeepsTargetAndSubmitWithTheOriginPool() throws Exception {
        exercise(new de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu.RavencoinStratumProtocol(), "ravencoin",
                "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG", "mining.set_target",
                "[\"00000000ffff0000000000000000000000000000000000000000000000000000\"]",
                "[\"065bbf80\",\"49\"]", "[\"055bbf80\",\"49\"]",
                "[\"job\",\"header\",\"seed\",\"target\",true,4564346,\"1b079cef\"]");
    }

    @Test
    void etcKeepsDifficultyAndSubmitWithTheOriginPool() throws Exception {
        exercise(new EthereumClassicStratumProtocol(), "ethereumclassic",
                "0xa6e43E5D497ce1f4d28b4270630E97308eDA8b3e", "mining.set_difficulty",
                "[1.999969]", "[[\"mining.notify\",\"session\",\"EthereumStratum/1.0.0\"],\"36f4\"]",
                "[[\"mining.notify\",\"fee-session\",\"EthereumStratum/1.0.0\"],\"41a2\"]",
                "[\"job\",\"seed\",\"header\",true]");
    }

    private void exercise(GpuStratumProtocol protocol, String coin, String wallet, String targetMethod,
                          String targetParams, String userSubscribe, String feeSubscribe, String notifyParams) throws Exception {
        FakeContext context = new FakeContext(protocol, wallet);
        String pool = coin.equals("ravencoin") ? "rvn.2miners.com:6060" : "etc.2miners.com:1010";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(pool.getBytes(StandardCharsets.UTF_8));
        String login = "{\"id\":2,\"method\":\"mining.authorize\",\"params\":[\"" + wallet
                + ".sm1." + encoded + ".rig\",\"x\"]}";
        String rewritten = protocol.interceptMessageFromMiner(login, context);
        assertEquals(pool, context.pool);
        assertEquals(wallet + ".rig", json(rewritten).path("params").path(0).asText());
        String houseLogin = protocol.translateMessageForUpstream(rewritten, "HOUSE", context);
        assertEquals(context.houseWorker, json(houseLogin).path("params").path(0).asText());

        context.connected = true;
        protocol.handleMessageFromPool("{\"id\":1,\"result\":" + userSubscribe + ",\"error\":null}", FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":true,\"error\":null}", FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool(event(targetMethod, targetParams), FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool(event("mining.notify", notifyParams.replace("job", "user-job")), FeeManager.USER_TARGET_ID, context);

        protocol.handleMessageFromPool("{\"id\":1,\"result\":" + feeSubscribe + ",\"error\":null}", "HOUSE", context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":true,\"error\":null}", "HOUSE", context);
        protocol.handleMessageFromPool(event(targetMethod, targetParams), "HOUSE", context);
        protocol.handleMessageFromPool(event("mining.notify", notifyParams.replace("job", "house-job")), "HOUSE", context);
        context.nextTarget = "HOUSE";
        protocol.handleMessageFromPool(event("mining.notify", notifyParams.replace("job", "user-job-2")), FeeManager.USER_TARGET_ID, context);

        assertEquals("HOUSE", context.currentTarget);
        assertEquals(targetMethod, json(context.toMiner.get(context.toMiner.size() - 3)).path("method").asText());
        assertEquals("mining.set_extranonce", json(context.toMiner.get(context.toMiner.size() - 2)).path("method").asText());
        JsonNode selectedJob = json(context.toMiner.getLast());
        String proxyJobId = selectedJob.path("params").path(0).asText();
        assertNotEquals("house-job", proxyJobId);

        protocol.handleMessageFromMiner("{\"id\":77,\"method\":\"mining.submit\",\"params\":[\""
                + wallet + ".rig\",\"" + proxyJobId + "\",\"001122\"]}", context);
        JsonNode forwarded = json(context.toPools.get("HOUSE").getLast());
        assertEquals(context.houseWorker, forwarded.path("params").path(0).asText());
        assertEquals("house-job", forwarded.path("params").path(1).asText());
        assertEquals("001122", forwarded.path("params").path(2).asText());
        long upstreamId = forwarded.path("id").asLong();
        assertNotEquals(77, upstreamId);
        int before = context.toMiner.size();
        protocol.handleMessageFromPool("{\"id\":" + upstreamId + ",\"result\":true,\"error\":null}", FeeManager.USER_TARGET_ID, context);
        assertEquals(before, context.toMiner.size());
        protocol.handleMessageFromPool("{\"id\":" + upstreamId + ",\"result\":true,\"error\":null}", "HOUSE", context);
        assertEquals(77, json(context.toMiner.getLast()).path("id").asInt());
    }

    @Test
    void refusesUnknownShare() throws Exception {
        GpuStratumProtocol protocol = new RavencoinStratumProtocol();
        FakeContext context = new FakeContext(protocol, "Rwallet");
        context.connected = true;
        protocol.handleMessageFromMiner("{\"id\":9,\"method\":\"mining.submit\",\"params\":[\"worker\",\"unknown\",\"nonce\"]}", context);
        assertTrue(context.toPools.isEmpty());
        assertFalse(json(context.toMiner.getLast()).path("result").asBoolean());
    }

    @Test
    void disconnectsWhenFeeLoginFailsOrPoolRequestsDirectReconnect() {
        GpuStratumProtocol protocol = new EthereumClassicStratumProtocol();
        FakeContext context = new FakeContext(protocol, "0xa6e43E5D497ce1f4d28b4270630E97308eDA8b3e");
        context.connected = true;
        protocol.handleMessageFromPool("{\"id\":2,\"result\":false,\"error\":[20,\"invalid\",null]}", "HOUSE", context);
        assertFalse(context.connected);
        context.connected = true;
        protocol.handleMessageFromPool("{\"id\":null,\"method\":\"client.reconnect\",\"params\":[\"other.example\",1234]}", FeeManager.USER_TARGET_ID, context);
        assertFalse(context.connected);
    }

    private static String event(String method, String params) {
        return "{\"id\":null,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private static class FakeContext implements ProxyContext {
        private final GpuStratumProtocol protocol;
        private final String houseWorker;
        private final List<String> toMiner = new ArrayList<>();
        private final Map<String, List<String>> toPools = new HashMap<>();
        private String pool;
        private String worker;
        private String password;
        private boolean connected;
        private String currentTarget = FeeManager.USER_TARGET_ID;
        private String nextTarget = FeeManager.USER_TARGET_ID;

        FakeContext(GpuStratumProtocol protocol, String wallet) {
            this.protocol = protocol;
            this.houseWorker = wallet + ".solarminer";
        }
        public void sendToMiner(String message) { toMiner.add(message); }
        public void broadcastToUpstreams(String message) { }
        public void sendToUpstream(String targetId, String message) { toPools.computeIfAbsent(targetId, unused -> new ArrayList<>()).add(message); }
        public void connectToTargetPool(String workerName) { connected = true; }
        public void disconnect() { connected = false; }
        public boolean isConnectedToUpstream() { return connected; }
        public String getCurrentTargetId() { return currentTarget; }
        public void setCurrentTargetId(String targetId) {
            if (!currentTarget.equals(targetId)) {
                currentTarget = targetId;
                protocol.onTargetChanged(targetId, this);
            }
        }
        public String rollNextJobTarget() { return nextTarget; }
        public String getWorkerForTarget(String targetId) { return "HOUSE".equals(targetId) ? houseWorker : worker; }
        public String getPasswordForTarget(String targetId) { return "HOUSE".equals(targetId) ? "x" : password; }
        public void reconnectToTarget(String targetId, String address) { }
        public void setDynamicRouting(String pool, String worker, String password) {
            this.pool = pool;
            this.worker = worker;
            this.password = password;
        }
    }
}
