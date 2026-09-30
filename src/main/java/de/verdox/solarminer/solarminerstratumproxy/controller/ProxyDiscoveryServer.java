package de.verdox.solarminer.solarminerstratumproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Answers small, versioned UDP broadcasts so PC-Agents can locate this proxy on their LAN. */
@Component
public class ProxyDiscoveryServer {
    public static final String REQUEST = "SOLARMINER_PROXY_DISCOVER_V1";
    private static final Logger LOGGER = Logger.getLogger(ProxyDiscoveryServer.class.getName());
    private final ObjectMapper mapper;
    private final ProxyProperties proxyProperties;
    private final int apiPort;
    private final int discoveryPort;
    private final boolean enabled;
    private volatile DatagramSocket socket;

    public ProxyDiscoveryServer(ObjectMapper mapper, ProxyProperties proxyProperties,
            @Value("${server.port:8090}") int apiPort,
            @Value("${proxy.discovery.port:8091}") int discoveryPort,
            @Value("${proxy.discovery.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.proxyProperties = proxyProperties;
        this.apiPort = apiPort;
        this.discoveryPort = discoveryPort;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        Thread.ofVirtual().name("solarminer-proxy-discovery").start(this::listen);
    }

    private void listen() {
        try (DatagramSocket server = new DatagramSocket(null)) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(discoveryPort));
            socket = server;
            byte[] buffer = new byte[128];
            LOGGER.info("LAN proxy discovery listening on UDP " + discoveryPort);
            while (!server.isClosed()) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                server.receive(request);
                if (!REQUEST.equals(new String(request.getData(), request.getOffset(), request.getLength(),
                        StandardCharsets.US_ASCII))) continue;
                byte[] response = mapper.writeValueAsBytes(discoveryResponse());
                if (response.length > 1024) continue;
                server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
            }
        } catch (SocketException e) {
            if (socket == null || !socket.isClosed()) LOGGER.log(Level.WARNING,
                    "LAN proxy discovery socket could not be opened", e);
        } catch (IOException e) {
            if (socket == null || !socket.isClosed()) LOGGER.log(Level.WARNING,
                    "LAN proxy discovery stopped unexpectedly", e);
        } finally {
            socket = null;
        }
    }

    private DiscoveryResponse discoveryResponse() {
        Map<String, Integer> coinPorts = new LinkedHashMap<>();
        proxyProperties.getCoins().forEach((coin, config) -> {
            if (config != null && config.getPort() > 0 && config.getPort() <= 65535)
                coinPorts.put(coin, config.getPort());
        });
        return new DiscoveryResponse("solarminer-stratum-proxy", 1, apiPort, coinPorts);
    }

    @PreDestroy
    public void stop() {
        DatagramSocket current = socket;
        if (current != null) current.close();
    }

    public record DiscoveryResponse(String service, int protocolVersion, int apiPort, Map<String, Integer> coins) { }
}
