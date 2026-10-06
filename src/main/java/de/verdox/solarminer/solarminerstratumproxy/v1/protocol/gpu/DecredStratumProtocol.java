package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Decred BLAKE3 pool dialect used by the documented SRBMiner/Suprnova route. */
@Component("decredStratumProtocol")
@Scope("prototype")
public class DecredStratumProtocol extends GpuStratumProtocol {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override String coin() { return "decred"; }
    @Override boolean validWallet(String wallet) {
        // Mainnet Decred P2PKH and P2SH addresses use Base58Check and begin D.
        return wallet != null && wallet.matches("D[1-9A-HJ-NP-Za-km-z]{24,50}");
    }
    @Override String targetMethod() { return "mining.set_difficulty"; }

    @Override
    protected ArrayNode provisionalSubscription() {
        // Haste uses subscription IDs, extranonce1, and extranonce2 byte length.
        ArrayNode result = mapper.createArrayNode();
        ArrayNode subscriptions = result.addArray();
        subscriptions.addArray().add("mining.set_difficulty").add("1");
        subscriptions.addArray().add("mining.notify").add("solarminer-decred");
        result.add("000000000000000000000000");
        result.add(12);
        return result;
    }
}
