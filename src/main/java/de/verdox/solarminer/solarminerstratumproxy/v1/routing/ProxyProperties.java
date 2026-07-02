package de.verdox.solarminer.solarminerstratumproxy.v1.routing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {
    private Map<String, CoinConfig> coins = new HashMap<>();

    @Setter
    @Getter
    public static class CoinConfig {
        private int port;
    }
}
