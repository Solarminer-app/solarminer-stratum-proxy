package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** QTC Kryptex-style JSON-RPC Stratum adapter; Quantus' native WS/QUIC pool is not this protocol. */
@Component("quantusStratumProtocol")
@Scope("prototype")
public class QuantusStratumProtocol extends GpuStratumProtocol {
    @Override String coin() { return "quantus"; }
    @Override boolean validWallet(String wallet) {
        return wallet != null && wallet.matches("qz[1-9A-HJ-NP-Za-km-z]{38,58}");
    }
    @Override String targetMethod() { return "mining.set_difficulty"; }
}
