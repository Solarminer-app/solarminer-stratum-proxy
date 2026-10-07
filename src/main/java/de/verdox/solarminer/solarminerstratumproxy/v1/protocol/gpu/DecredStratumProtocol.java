package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/** Decred BLAKE3 pool dialect used by the documented SRBMiner/Suprnova route. */
@Component("decredStratumProtocol")
@Scope("prototype")
public class DecredStratumProtocol extends GpuStratumProtocol {

    @Override String coin() { return "decred"; }
    @Override boolean validWallet(String wallet) {
        // Mainnet Decred P2PKH and P2SH addresses use Base58Check and begin D.
        return wallet != null && wallet.matches("D[1-9A-HJ-NP-Za-km-z]{24,50}");
    }
    @Override String targetMethod() { return "mining.set_difficulty"; }

}
