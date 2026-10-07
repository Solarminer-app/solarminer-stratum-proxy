package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("ethereumclassicStratumProtocol")
@Scope("prototype")
public class EthereumClassicStratumProtocol extends GpuStratumProtocol {
    @Override String coin() { return "ethereumclassic"; }
    @Override boolean validWallet(String wallet) { return wallet.matches("0x[0-9a-fA-F]{40}"); }
    @Override String targetMethod() { return "mining.set_difficulty"; }
}
