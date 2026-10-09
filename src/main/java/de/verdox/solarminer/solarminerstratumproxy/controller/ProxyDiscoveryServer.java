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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Publishes versioned LAN heartbeats and still answers the legacy discovery query. */
@Component
public class ProxyDiscoveryServer {
    public static final String REQUEST = "SOLARMINER_PROXY_DISCOVER_V1";
    private static final Logger LOGGER = Logger.getLogger(ProxyDiscoveryServer.class.getName());
    private final ObjectMapper mapper;
    private final ProxyProperties proxyProperties;
    private final int apiPort;
    private final int discoveryPort;
    private final int heartbeatPort;
    private final long heartbeatIntervalMs;
    private final boolean enabled;
    private volatile DatagramSocket requestSocket;
    private volatile DatagramSocket heartbeatSocket;

    public ProxyDiscoveryServer(ObjectMapper mapper, ProxyProperties proxyProperties,
            @Value("${server.port:8090}") int apiPort,
            @Value("${proxy.discovery.port:8091}") int discoveryPort,
            @Value("${proxy.discovery.heartbeat-port:8092}") int heartbeatPort,
            @Value("${proxy.discovery.heartbeat-interval-ms:1000}") long heartbeatIntervalMs,
            @Value("${proxy.discovery.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.proxyProperties = proxyProperties;
        this.apiPort = apiPort;
        this.discoveryPort = discoveryPort;
        this.heartbeatPort = heartbeatPort;
        this.heartbeatIntervalMs = Math.max(250, heartbeatIntervalMs);
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        Thread.ofVirtual().name("solarminer-proxy-discovery").start(this::listen);
        Thread.ofVirtual().name("solarminer-proxy-heartbeat").start(this::publishHeartbeats);
    }

    private void listen() {
        try (DatagramSocket server = new DatagramSocket(null)) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(discoveryPort));
            requestSocket = server;
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
            if (requestSocket == null || !requestSocket.isClosed()) LOGGER.log(Level.WARNING,
                    "LAN proxy discovery socket could not be opened", e);
        } catch (IOException e) {
            if (requestSocket == null || !requestSocket.isClosed()) LOGGER.log(Level.WARNING,
                    "LAN proxy discovery stopped unexpectedly", e);
        } finally {
            requestSocket = null;
        }
    }

    private void publishHeartbeats() {
        try (DatagramSocket sender = new DatagramSocket()) {
            sender.setBroadcast(true);
            heartbeatSocket = sender;
            LOGGER.info("LAN proxy heartbeats publishing on UDP " + heartbeatPort);
            while (!sender.isClosed()) {
                byte[] payload = mapper.writeValueAsBytes(discoveryResponse());
                for (InetAddress broadcast : broadcastAddresses()) {
                    try {
                        sender.send(new DatagramPacket(payload, payload.length, broadcast, heartbeatPort));
                    } catch (IOException exception) {
                        LOGGER.log(Level.FINE, "Could not publish proxy heartbeat to " + broadcast, exception);
                    }
                }
                Thread.sleep(heartbeatIntervalMs);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            if (heartbeatSocket == null || !heartbeatSocket.isClosed())
                LOGGER.log(Level.WARNING, "LAN proxy heartbeat stopped unexpectedly", exception);
        } finally {
            heartbeatSocket = null;
        }
    }

    static Set<InetAddress> broadcastAddresses() throws SocketException, java.net.UnknownHostException {
        Set<InetAddress> broadcasts = new LinkedHashSet<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            String name = network.getName().toLowerCase(Locale.ROOT);
            if (!network.isUp() || network.isLoopback() || network.isVirtual()
                    || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                    || name.startsWith("tailscale") || name.startsWith("wsl")) continue;
            network.getInterfaceAddresses().stream()
                    .filter(address -> address.getAddress() instanceof Inet4Address && address.getBroadcast() != null)
                    .map(java.net.InterfaceAddress::getBroadcast).forEach(broadcasts::add);
        }
        broadcasts.add(InetAddress.getByName("255.255.255.255"));
        return broadcasts;
    }

    DiscoveryResponse discoveryResponse() {
        Map<String, Integer> coinPorts = new LinkedHashMap<>();
        proxyProperties.getCoins().forEach((coin, config) -> {
            if (config != null && config.getPort() > 0 && config.getPort() <= 65535)
                coinPorts.put(coin, config.getPort());
        });
        return new DiscoveryResponse("solarminer-stratum-proxy", 1, apiPort, coinPorts);
    }

    @PreDestroy
    public void stop() {
        DatagramSocket currentRequest = requestSocket;
        if (currentRequest != null) currentRequest.close();
        DatagramSocket currentHeartbeat = heartbeatSocket;
        if (currentHeartbeat != null) currentHeartbeat.close();
    }

    public record DiscoveryResponse(String service, int protocolVersion, int apiPort, Map<String, Integer> coins) { }
}
