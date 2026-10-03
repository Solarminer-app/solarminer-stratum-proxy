package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component("ravencoinStratumProtocol")
@Scope("prototype")
public class RavencoinStratumProtocol extends GpuStratumProtocol {
    @Override String coin() { return "ravencoin"; }
    @Override boolean validWallet(String wallet) {
        return wallet.matches("[Rr][123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{25,34}");
    }
    @Override String targetMethod() { return "mining.set_target"; }
}
