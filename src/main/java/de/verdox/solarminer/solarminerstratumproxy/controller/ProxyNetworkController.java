package de.verdox.solarminer.solarminerstratumproxy.controller;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/network")
public class ProxyNetworkController {
    private static final Logger LOGGER = Logger.getLogger(ProxyNetworkController.class.getName());

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        LOGGER.info("Stratum V1 Proxy is exposed to: " + findLocalIP());
    }

    @GetMapping("/ip")
    public String getLocalIp() {
        return findLocalIP();
    }

    private static String findLocalIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                String name = networkInterface.getName().toLowerCase();
                if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") || name.startsWith("tailscale")) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Could not find ip of this proxy server: " + e.getMessage());
        }
        return "127.0.0.1";
    }
}