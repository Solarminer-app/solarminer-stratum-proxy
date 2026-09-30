package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.pearl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PearlStratumProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void routesObjectProofToOriginAndRestoresMinerRequestId() throws Exception {
        de.verdox.solarminer.solarminerstratumproxy.v1.protocol.pearl.PearlStratumProtocol protocol = new PearlStratumProtocol();
        FakeContext context = new FakeContext(protocol);
        String login = "{\"id\":1,\"method\":\"mining.authorize\",\"params\":{\"wallet\":\"stratum+ssl://prl.kryptex.network:8048;prl1user/rig;x\"}}";
        String rewritten = protocol.interceptMessageFromMiner(login, context);
        assertEquals("stratum+ssl://prl.kryptex.network:8048", context.pool);
        assertEquals("prl1user/rig", json(rewritten).path("params").path("wallet").asText());
        String feeLogin = protocol.translateMessageForUpstream(rewritten, "HOUSE", context);
        assertEquals("prl1house/solarminer", json(feeLogin).path("params").path("wallet").asText());

        context.connected = true;
        protocol.handleMessageFromPool(notify("user-job"), FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":true,\"error\":null}", FeeManager.USER_TARGET_ID, context);
        protocol.handleMessageFromPool(notify("house-job"), "HOUSE", context);
        protocol.handleMessageFromPool("{\"id\":1,\"result\":true,\"error\":null}", "HOUSE", context);
        context.nextTarget = "HOUSE";
        protocol.handleMessageFromPool(notify("user-job-2"), FeeManager.USER_TARGET_ID, context);

        JsonNode feeJob = json(context.toMiner.getLast());
        assertEquals("mining.notify", feeJob.path("method").asText());
        String internalId = feeJob.path("params").path("job_id").asText();
        protocol.handleMessageFromMiner("{\"id\":77,\"method\":\"mining.submit\",\"params\":{\"job_id\":\"" + internalId + "\",\"plain_proof\":\"AQID\"}}", context);
        JsonNode upstream = json(context.toPools.get("HOUSE").getLast());
        assertEquals("house-job", upstream.path("params").path("job_id").asText());
        assertEquals("AQID", upstream.path("params").path("plain_proof").asText());
        long proxyRequestId = upstream.path("id").asLong();
        assertNotEquals(77, proxyRequestId);

        int before = context.toMiner.size();
        protocol.handleMessageFromPool("{\"id\":" + proxyRequestId + ",\"result\":true,\"error\":null}", FeeManager.USER_TARGET_ID, context);
        assertEquals(before, context.toMiner.size());
        protocol.handleMessageFromPool("{\"id\":" + proxyRequestId + ",\"result\":true,\"error\":null}", "HOUSE", context);
        assertEquals(77, json(context.toMiner.getLast()).path("id").asInt());
    }

    @Test
    void rejectsUnknownJobWithoutSendingProofToAnyPool() throws Exception {
        PearlStratumProtocol protocol = new PearlStratumProtocol();
        FakeContext context = new FakeContext(protocol);
        context.connected = true;
        protocol.handleMessageFromMiner("{\"id\":5,\"method\":\"mining.submit\",\"params\":{\"job_id\":\"missing\",\"plain_proof\":\"AQID\"}}", context);
        assertTrue(context.toPools.isEmpty());
        assertEquals(21, json(context.toMiner.getLast()).path("error").path("code").asInt());
    }

    @Test
    void keepsNamedWalletAndWorkerSeparateForObjectDialect() throws Exception {
        PearlStratumProtocol protocol = new PearlStratumProtocol();
        FakeContext context = new FakeContext(protocol);
        String login = "{\"id\":3,\"method\":\"mining.authorize\",\"params\":{\"wallet\":\"stratum+ssl://prl.suprnova.cc:3374;prl1user/rig;x\",\"worker\":\"rig\"}}";
        String rewritten = protocol.interceptMessageFromMiner(login, context);
        assertEquals("prl1user", json(rewritten).path("params").path("wallet").asText());
        assertEquals("rig", json(rewritten).path("params").path("worker").asText());
        String fee = protocol.translateMessageForUpstream(rewritten, "HOUSE", context);
        assertEquals("prl1house", json(fee).path("params").path("wallet").asText());
        assertEquals("solarminer", json(fee).path("params").path("worker").asText());
    }

    @Test
    void decodesObservedSrbMinerLoginBeforeForwardingToPools() throws Exception {
        PearlStratumProtocol protocol = new PearlStratumProtocol();
        FakeContext context = new FakeContext(protocol);
        String pool = "stratum+ssl://prl.kryptex.network:8048";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(pool.getBytes(StandardCharsets.UTF_8));
        String login = "{\"id\":1,\"method\":\"mining.authorize\",\"params\":{\"wallet\":\"prl1user\","
                + "\"worker\":\"sm1." + encoded + ".rig\",\"agent\":\"SRBMiner-MULTI/3.7.0\",\"type\":\"v2\"}}";
        String rewritten = protocol.interceptMessageFromMiner(login, context);
        assertEquals(pool, context.pool);
        assertEquals("prl1user", json(rewritten).path("params").path("wallet").asText());
        assertEquals("rig", json(rewritten).path("params").path("worker").asText());
        assertFalse(json(rewritten).path("params").has("pass"));
        String fee = protocol.translateMessageForUpstream(rewritten, "HOUSE", context);
        assertEquals("prl1house", json(fee).path("params").path("wallet").asText());
        assertEquals("solarminer", json(fee).path("params").path("worker").asText());
    }

    @Test
    void rejectsFeePoolAuthorizationInsteadOfMiningWithoutFee() {
        PearlStratumProtocol protocol = new PearlStratumProtocol();
        FakeContext context = new FakeContext(protocol);
        protocol.interceptMessageFromMiner("{\"id\":1,\"method\":\"mining.authorize\",\"params\":[\"stratum+ssl://prl.kryptex.network:8048;prl1user/rig;x\"]}", context);
        context.connected = true;
        protocol.handleMessageFromPool("{\"id\":1,\"result\":false,\"error\":{\"code\":25}}", "HOUSE", context);
        assertFalse(context.connected);
    }

    private String notify(String jobId) {
        return "{\"id\":null,\"method\":\"mining.notify\",\"params\":{\"job_id\":\"" + jobId + "\",\"target\":\"ff\"}}";
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private static class FakeContext implements ProxyContext {
        private final PearlStratumProtocol protocol;
        private final List<String> toMiner = new ArrayList<>();
        private final Map<String, List<String>> toPools = new HashMap<>();
        private String pool;
        private String worker;
        private String pass;
        private boolean connected;
        private String currentTarget = FeeManager.USER_TARGET_ID;
        private String nextTarget = FeeManager.USER_TARGET_ID;

        FakeContext(PearlStratumProtocol protocol) { this.protocol = protocol; }
        public void sendToMiner(String message) { toMiner.add(message); }
        public void broadcastToUpstreams(String message) { }
        public void sendToUpstream(String targetId, String message) { toPools.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(message); }
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
        public String getWorkerForTarget(String targetId) { return "HOUSE".equals(targetId) ? "prl1house/solarminer" : worker; }
        public String getPasswordForTarget(String targetId) { return "HOUSE".equals(targetId) ? "x" : pass; }
        public void reconnectToTarget(String targetId, String address) { }
        public void setDynamicRouting(String pool, String worker, String pass) { this.pool = pool; this.worker = worker; this.pass = pass; }
    }
}
