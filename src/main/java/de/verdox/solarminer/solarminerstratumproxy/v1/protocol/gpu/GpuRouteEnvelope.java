package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu;

import de.verdox.solarminer.solarminerstratumproxy.v1.ProxyContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Predicate;

/**
 * PC-Agent route envelope, independent of the upstream coin's wire dialect.
 */
final class GpuRouteEnvelope {
    static String apply(String login, String password, Predicate<String> walletCheck, ProxyContext context) {
        int marker = login.indexOf(".sm1.");
        if (marker < 0) throw new IllegalArgumentException("Missing GPU route envelope");
        String wallet = login.substring(0, marker);
        String[] fields = login.substring(marker + 5).split("\\.", 2);
        if (!walletCheck.test(wallet) || fields.length != 2 || !fields[0].matches("[A-Za-z0-9_-]{1,256}")
                || !fields[1].matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid GPU route envelope");
        String pool = new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
        URI uri = URI.create(pool.contains("://") ? pool : "stratum+tcp://" + pool);
        if (!("stratum+tcp".equals(uri.getScheme()) || "stratum+ssl".equals(uri.getScheme()))
                || uri.getHost() == null || !uri.getHost().matches("[A-Za-z0-9.-]+")
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
            throw new IllegalArgumentException("Invalid GPU upstream route");
        String worker = wallet + (uri.getHost().endsWith(".kryptex.network") ? "/" : ".") + fields[1];
        String existing = context.getWorkerForTarget("USER");
        if (existing != null && !existing.equals(worker))
            throw new IllegalArgumentException("GPU route already configured");
        context.setDynamicRouting(pool, worker, password);
        return worker;
    }
}
