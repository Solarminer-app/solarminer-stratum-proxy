package de.verdox.solarminer.solarminerstratumproxy.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight identity check for the PC-Agent's managed child process. */
@RestController
public class ProxyHealthController {
    private final String instanceId;

    public ProxyHealthController(@Value("${proxy.health-instance-id:}") String instanceId) {
        this.instanceId = instanceId;
    }

    @GetMapping("/api/health")
    public Health health() {
        return new Health("solarminer-stratum-proxy", instanceId);
    }

    public record Health(String service, String instanceId) { }
}
