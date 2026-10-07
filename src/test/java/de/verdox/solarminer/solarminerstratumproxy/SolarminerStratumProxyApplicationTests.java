package de.verdox.solarminer.solarminerstratumproxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"server.port=0", "proxy.bind-address=127.0.0.1", "proxy.discovery.enabled=false",
        "proxy.coins.bitcoin.port=0", "proxy.coins.monero.port=0", "proxy.coins.pearl.port=0",
        "proxy.coins.ravencoin.port=0", "proxy.coins.ethereumclassic.port=0", "proxy.coins.decred.port=0",
        "proxy.coins.quantus.port=0", "solarminer.fee.backend-url=http://127.0.0.1:9/api/fees"})
class SolarminerStratumProxyApplicationTests {

    @Test
    void contextLoads() {
    }

}
