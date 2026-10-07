package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
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
    void routePreambleAllowsRealSubscriptionWithoutFabricatedNonce() throws Exception {
        for (GpuStratumProtocol protocol : List.of(new RavencoinStratumProtocol(), new EthereumClassicStratumProtocol())) {
            FakeContext context = new FakeContext(protocol, "worker");
            String wallet = switch (protocol.coin()) {
                case "ravencoin" -> "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG";
                case "quantus" -> "qz" + "A".repeat(44);
                default -> "0xa6e43E5D497ce1f4d28b4270630E97308eDA8b3e";
            };
            String pool = Base64.getUrlEncoder().withoutPadding().encodeToString("stratum+tcp://pool.example:7049".getBytes(StandardCharsets.UTF_8));
            assertNull(protocol.interceptMessageFromMiner("{\"method\":\"solarminer.route\",\"params\":[\"" + wallet + ".sm1." + pool + ".rig\",\"x\"]}", context));
            protocol.handleMessageFromMiner("{\"id\":1,\"method\":\"mining.subscribe\",\"params\":[\"SRBMiner-MULTI/3.7.0\"]}", context);
            assertTrue(context.toMiner.isEmpty());
            assertTrue(context.connected);
            String realReply = protocol.coin().equals("ravencoin")
                    ? "{\"id\":1,\"result\":[null,\"605132\"],\"error\":null}"
                    : "{\"id\":1,\"result\":[[\"mining.notify\",\"session\",\"EthereumStratum/1.0.0\"],\"36f4\"],\"error\":null}";
            protocol.handleMessageFromPool(realReply, FeeManager.USER_TARGET_ID, context);
            assertEquals(List.of(realReply), context.toMiner);
        }
    }

    @Test
    void ravencoinKeepsTargetAndSubmitWithTheOriginPool() throws Exception {
        exercise(new de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu.RavencoinStratumProtocol(), "ravencoin",
                "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG", "mining.set_target",
                "[\"00000000ffff0000000000000000000000000000000000000000000000000000\"]",
                "[\"065bbf80\",\"49\"]", "[\"055bbf80\",\"49\"]",
                "[\"job\",\"header\",\"seed\",\"target\",true,4564346,\"1b079cef\"]");
    }

    @Test
    void ravencoinKryptexSubscribeWithNullFirstFieldStillForwardsJobs() throws Exception {
        GpuStratumProtocol protocol = new RavencoinStratumProtocol();
        FakeContext context = new FakeContext(protocol, "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG");
        context.connected = true;
        handshake(protocol, context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":[null,\"605132\"],\"error\":null}",
                FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":true,\"error\":null}",
                FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool(event("mining.set_target", "[\"00000000ffff0000000000000000000000000000000000000000000000000000\"]"),
                FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool(event("mining.notify", "[\"job\",\"header\",\"seed\",\"target\",true,4564346,\"1b079cef\"]"),
                FeeManager.USER_TARGET_ID, context);
        assertEquals("mining.notify", json(context.toMiner.getLast()).path("method").asText());
    }

    @Test
    void etcKeepsDifficultyAndSubmitWithTheOriginPool() throws Exception {
        exercise(new EthereumClassicStratumProtocol(), "ethereumclassic",
                "0xa6e43E5D497ce1f4d28b4270630E97308eDA8b3e", "mining.set_difficulty",
                "[1.999969]", "[[\"mining.notify\",\"session\",\"EthereumStratum/1.0.0\"],\"36f4\"]",
                "[[\"mining.notify\",\"fee-session\",\"EthereumStratum/1.0.0\"],\"41a2\"]",
                "[\"job\",\"seed\",\"header\",true]");
    }

    @Test
    void kryptexUsesSlashSeparatedWalletAndWorkerForBothGpuCoins() throws Exception {
        for (GpuStratumProtocol protocol : List.of(new RavencoinStratumProtocol(), new EthereumClassicStratumProtocol())) {
            String wallet = protocol.coin().equals("ravencoin")
                    ? "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG"
                    : "0xa6e43E5D497ce1f4d28b4270630E97308eDA8b3e";
            String pool = protocol.coin().equals("ravencoin")
                    ? "stratum+tcp://rvn.kryptex.network:7031" : "stratum+tcp://etc.kryptex.network:7033";
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(pool.getBytes(StandardCharsets.UTF_8));
            FakeContext context = new FakeContext(protocol, wallet);
            String login = "{\"id\":2,\"method\":\"mining.authorize\",\"params\":[\""
                    + wallet + ".sm1." + encoded + ".rig\",\"x\"]}";
            JsonNode rewritten = json(protocol.interceptMessageFromMiner(login, context));
            assertEquals(wallet + "/rig", rewritten.path("params").path(0).asText());
            assertEquals(pool, context.pool);
        }
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
        handshake(protocol, context);
        assertNull(protocol.interceptMessageFromMiner("{\"id\":8,\"method\":\"mining.extranonce.subscribe\",\"params\":[]}", context));
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
    void failsClosedOnFeeLoginButReopensConfiguredRouteOnPoolReconnect() {
        GpuStratumProtocol protocol = new EthereumClassicStratumProtocol();
        FakeContext context = new FakeContext(protocol, "0xa6e43E5D497ce1f4d28b4270630E97308eDA8b3e");
        context.connected = true;
        handshake(protocol, context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":false,\"error\":[20,\"invalid\",null]}", "HOUSE", context);
        assertFalse(context.connected);
        context.connected = true;
        protocol.handleMessageFromPool("{\"id\":null,\"method\":\"client.reconnect\",\"params\":[\"other.example\",1234]}", FeeManager.USER_TARGET_ID, context);
        assertTrue(context.connected);
        assertEquals(FeeManager.USER_TARGET_ID, context.reconnected);
    }

    private static void handshake(GpuStratumProtocol protocol, FakeContext context) {
        protocol.handleMessageFromMiner("{\"id\":1,\"method\":\"mining.subscribe\",\"params\":[]}", context);
        protocol.handleMessageFromMiner("{\"id\":2,\"method\":\"mining.authorize\",\"params\":[\"worker\",\"x\"]}", context);
    }

    @Test
    void arbitraryStringIdsAndOptionalFailureAreNotAuthorizationFailure() throws Exception {
        GpuStratumProtocol protocol = new EthereumClassicStratumProtocol();
        FakeContext context = new FakeContext(protocol, "wallet");
        context.connected = true;
        protocol.handleMessageFromMiner("{\"id\":\"sub\",\"method\":\"mining.subscribe\",\"params\":[]}", context);
        int forwardedBeforeExtension = context.toPools.values().stream().mapToInt(List::size).sum();
        assertNull(protocol.interceptMessageFromMiner("{\"id\":2,\"method\":\"mining.extranonce.subscribe\",\"params\":[]}", context));
        assertEquals(forwardedBeforeExtension, context.toPools.values().stream().mapToInt(List::size).sum(), "The proxy must negotiate its nonce capability locally");
        assertTrue(json(context.toMiner.getLast()).path("result").asBoolean());
        protocol.handleMessageFromMiner("{\"id\":\"auth\",\"method\":\"mining.authorize\",\"params\":[\"wallet\",\"x\"]}", context);
        protocol.handleMessageFromPool("{\"id\":\"sub\",\"result\":[[],\"abcd\"],\"error\":null}", "USER", context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":false,\"error\":null}", "USER", context);
        assertTrue(context.connected);
        protocol.handleMessageFromPool("{\"id\":\"auth\",\"result\":true,\"error\":null}", "USER", context);
        protocol.handleMessageFromPool(event("mining.notify", "[\"abcdef\",\"seed\",\"header\",true]"), "USER", context);
        assertEquals("mining.notify", json(context.toMiner.getLast()).path("method").asText());
        assertTrue(json(context.toMiner.getLast()).path("params").path(0).asText().matches("[0-9a-f]+"));
    }

    @Test
    void rejectsLegacySubscribeInsteadOfInventingExtranonce() throws Exception {
        GpuStratumProtocol protocol = new RavencoinStratumProtocol();
        FakeContext context = new FakeContext(protocol, "wallet");
        protocol.handleMessageFromMiner("{\"id\":42,\"method\":\"mining.subscribe\",\"params\":[]}", context);
        assertFalse(context.connected);
        assertFalse(json(context.toMiner.getLast()).path("result").asBoolean());
        assertEquals(42, json(context.toMiner.getLast()).path("id").asInt());
    }

    private static String event(String method, String params) {
        return "{\"id\":null,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    @Test
    void quantusObservedLoginJobAndNamedSubmitKeepTheirOriginAndSessionToken() throws Exception {
        var protocol = new QuantusStratumProtocol();
        String wallet = "qz" + "A".repeat(44);
        var context = new FakeContext(protocol, wallet);
        String route = Base64.getUrlEncoder().withoutPadding().encodeToString("stratum+tcp://qtc.kryptex.network:7049".getBytes(StandardCharsets.UTF_8));
        String login = protocol.interceptMessageFromMiner("{\"id\":\"login-17\",\"jsonrpc\":\"2.0\",\"method\":\"login\",\"params\":{\"login\":\"" + wallet + ".sm1." + route + ".rig\",\"pass\":\"x\",\"agent\":\"SRBMiner\"}}", context);
        assertEquals(wallet + "/rig", json(login).path("params").path("login").asText());
        assertTrue(protocol.isHandshakeMessage(login));
        protocol.handleMessageFromMiner(login, context);
        assertTrue(context.connected);
        assertEquals(context.houseWorker, json(protocol.translateMessageForUpstream(login, "HOUSE", context)).path("params").path("login").asText());
        String userJob = "{\"job_id\":\"0ae6900e_18253611008\",\"difficulty\":18253611008,\"extranonce\":\"160478ab\",\"mining_hash\":\"" + "6".repeat(64) + "\",\"target\":\"" + "3c".repeat(64) + "\"}";
        protocol.handleMessageFromPool("{\"id\":\"login-17\",\"error\":null,\"result\":{\"id\":\"user-token\",\"status\":\"OK\",\"job\":" + userJob + "}}", "USER", context);
        assertEquals("160478ab", json(context.toMiner.getLast()).path("result").path("job").path("extranonce").asText());
        String houseJob = userJob.replace("0ae6900e_18253611008", "fee-job").replace("160478ab", "deadbeef");
        protocol.handleMessageFromPool("{\"id\":\"login-17\",\"error\":null,\"result\":{\"id\":\"fee-token\",\"status\":\"OK\",\"job\":" + houseJob + "}}", "HOUSE", context);
        context.nextTarget = "HOUSE";
        protocol.handleMessageFromPool(event("job", userJob), "USER", context);
        JsonNode selected = json(context.toMiner.getLast());
        assertEquals("job", selected.path("method").asText());
        assertTrue(selected.path("params").path("clean_jobs").asBoolean());
        assertEquals("deadbeef", selected.path("params").path("job").path("extranonce").asText());
        String proxyJob = selected.path("params").path("job").path("job_id").asText();
        protocol.handleMessageFromMiner("{\"id\":\"share-9\",\"method\":\"submit\",\"params\":{\"id\":\"user-token\",\"job_id\":\"" + proxyJob + "\",\"nonce\":\"1122\",\"result\":\"opaque-proof\"}}", context);
        JsonNode forwarded = json(context.toPools.get("HOUSE").getLast());
        assertEquals("fee-token", forwarded.path("params").path("id").asText());
        assertEquals("fee-job", forwarded.path("params").path("job_id").asText());
        assertEquals("opaque-proof", forwarded.path("params").path("result").asText());
        long upstreamId = forwarded.path("id").asLong();
        int before = context.toMiner.size();
        protocol.handleMessageFromPool("{\"id\":" + upstreamId + ",\"result\":{\"status\":\"OK\"},\"error\":null}", "USER", context);
        assertEquals(before, context.toMiner.size());
        protocol.handleMessageFromPool("{\"id\":" + upstreamId + ",\"result\":{\"status\":\"OK\"},\"error\":null}", "HOUSE", context);
        assertEquals("share-9", json(context.toMiner.getLast()).path("id").asText());
        context.nextTarget = "USER";
        protocol.handleMessageFromPool(event("job", "{\"clean_jobs\":false,\"job\":" + userJob + "}"), "USER", context);
        JsonNode wrapped = json(context.toMiner.getLast());
        assertFalse(wrapped.path("params").path("clean_jobs").asBoolean(true));
        assertTrue(wrapped.path("params").path("job").path("job_id").isTextual());
    }

    @Test
    void extranonceSizeChangesAreNegotiatedAndPreserved() throws Exception {
        GpuStratumProtocol protocol = new DecredStratumProtocol();
        var context = new FakeContext(protocol, "wallet");
        context.connected = true;
        handshake(protocol, context);
        assertNull(protocol.interceptMessageFromMiner("{\"id\":3,\"method\":\"mining.extranonce.subscribe\",\"params\":[]}", context));
        protocol.handleMessageFromPool("{\"id\":1,\"result\":[[],\"abcd\",12],\"error\":null}", "USER", context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":[[],\"abcd\",8],\"error\":null}", "HOUSE", context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":true,\"error\":null}", "HOUSE", context);
        protocol.handleMessageFromPool(event("mining.set_difficulty", "[4]"), "HOUSE", context);
        protocol.onTargetChanged("HOUSE", context);
        var update = json(context.toMiner.getLast());
        assertEquals("mining.set_extranonce", update.path("method").asText());
        assertEquals(8, update.path("params").path(1).asInt());
        assertTrue(context.connected);
    }

    @Test
    void changingNonceWithoutCapabilityFailsClosedAndCannotLeakFeeJob() {
        GpuStratumProtocol protocol = new EthereumClassicStratumProtocol();
        var context = new FakeContext(protocol, "wallet");
        context.connected = true;
        handshake(protocol, context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":[[],\"abcd\"],\"error\":null}", "USER", context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":[[],\"def0\"],\"error\":null}", "HOUSE", context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":true,\"error\":null}", "HOUSE", context);
        protocol.handleMessageFromPool(event("mining.notify", "[\"fee-job\",\"seed\",\"header\",true]"), "HOUSE", context);
        protocol.onTargetChanged("HOUSE", context);
        assertFalse(context.connected);
        assertTrue(context.toMiner.stream().noneMatch(message -> message.contains("\"method\":\"mining.notify\"")));
    }

    @Test
    void ravencoinCanUseTargetEmbeddedInNotifyWithoutSeparateSetTarget() throws Exception {
        GpuStratumProtocol protocol = new RavencoinStratumProtocol();
        var context = new FakeContext(protocol, "wallet");
        context.connected = true;
        handshake(protocol, context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":[null,\"abcd\"],\"error\":null}", "USER", context);
        protocol.handleMessageFromPool("{\"id\":2,\"result\":true,\"error\":null}", "USER", context);
        protocol.handleMessageFromPool(event("mining.notify", "[\"job\",\"header\",\"seed\",\"" + "f".repeat(64) + "\",true,42,\"bits\"]"), "USER", context);
        assertEquals("mining.notify", json(context.toMiner.getLast()).path("method").asText());
    }

    @Test
    void upstreamSubscribeFailureIsNotSilentFeeBypass() {
        GpuStratumProtocol protocol = new EthereumClassicStratumProtocol();
        var context = new FakeContext(protocol, "wallet");
        context.connected = true;
        handshake(protocol, context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":false,\"error\":[20,\"unsupported\",null]}", "HOUSE", context);
        assertFalse(context.connected);
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private static class FakeContext implements ProxyContext {
        private final MiningProtocol protocol;
        private final String houseWorker;
        private final List<String> toMiner = new ArrayList<>();
        private final Map<String, List<String>> toPools = new HashMap<>();
        private String pool;
        private String worker;
        private String password;
        private boolean connected;
        private String currentTarget = FeeManager.USER_TARGET_ID;
        private String nextTarget = FeeManager.USER_TARGET_ID;
        private String reconnected;

        FakeContext(MiningProtocol protocol, String wallet) {
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
        public void reconnectToTarget(String targetId, String address) { reconnected = targetId; }
        public void setDynamicRouting(String pool, String worker, String password) {
            this.pool = pool;
            this.worker = worker;
            this.password = password;
        }
    }
}
