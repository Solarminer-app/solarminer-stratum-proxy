package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeeService {
    private static final Logger log = LoggerFactory.getLogger(FeeService.class);
    private static final String DEFAULT_BACKEND_URL = "https://fee.solarminer.app/api/fees";
    private static final long TARGET_CACHE_MS = 60_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FeeManager feeManager;
    private final ProxyProperties proxyProperties;
    private final String backendUrl;

    /**
     * On-demand, per-referral target cache serving the node's {@code ?referral=}
     * reads. Kept separate from {@link FeeManager}'s routing state so an on-demand
     * lookup for an arbitrary referral never clobbers the targets the proxy uses
     * to roll jobs for the configured referral.
     */
    private final Map<String, CachedTargets> referralTargets = new ConcurrentHashMap<>();

    /**
     * The referral code whose fee split this proxy instance enforces for job
     * routing (stratum-routed miners). Initialized from
     * {@code solarminer.fee.referral} (env: SOLARMINER_FEE_REFERRAL); defaults to
     * {@code "solarminer"} (the house dev fee only). Changed at runtime via
     * {@link #setReferral(String)} — the node's core pushes the site's saved
     * referral here so the referrer share is routed under their worker for
     * proxy-routed miners (fee-backend resolves unknown/blank codes to
     * {@code solarminer} anyway).
     */
    private volatile String configuredReferral;
    public FeeService(FeeManager feeManager,
                      ProxyProperties proxyProperties,
                      @Value("${solarminer.fee.referral:solarminer}") String configuredReferral,
                      @Value("${solarminer.fee.backend-url:" + DEFAULT_BACKEND_URL + "}") String backendUrl) {
        this.feeManager = feeManager;
        this.proxyProperties = proxyProperties;
        this.backendUrl = backendUrl;
        this.objectMapper = new ObjectMapper();
        this.configuredReferral = (configuredReferral == null || configuredReferral.isBlank())
                ? "solarminer" : configuredReferral.trim();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedRateString = "${solarminer.fee.refresh-ms:60000}")
    public void scheduledFetch() {
        fetchConfiguredCoinFees(configuredReferral);
        log.debug("Fetched fees from solarminer backend");
    }

    private void fetchConfiguredCoinFees(String referral) {
        proxyProperties.getCoins().keySet().forEach(coin -> fetchAndUpdateFees(coin, referral));
    }

    public void fetchAndUpdateFees(String coin, String referral) {
        try {
            String url = String.format("%s?coin=%s&referral=%s", backendUrl, coin, referral != null ? referral : "");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Could not communicate with backend to fetch fees. Status: {}", response.statusCode());
                return;
            }

            String rawJsonBody = response.body();

            FeeResponse feeResponse = objectMapper.readValue(rawJsonBody, FeeResponse.class);
            feeManager.updateTargets(coin, new ArrayList<>(feeResponse.targets()));
        } catch (Exception e) {
            log.error("Backend not reachable: {}", e.getMessage());
        }
    }

    /**
     * Change the referral this proxy enforces for job routing, at runtime. Blank
     * resets to the house fee ({@code solarminer}). If it actually changed, the
     * targets are re-fetched immediately so the next rolled job already uses the
     * new split (no wait for the 60 s poll). Called by the node's core whenever
     * the site's saved referral changes.
     */
    public synchronized void setReferral(String referral) {
        String normalized = (referral == null || referral.isBlank()) ? "solarminer" : referral.trim();
        if (normalized.equalsIgnoreCase(configuredReferral)) {
            return;
        }
        configuredReferral = normalized;
        log.info("Referral for fee routing changed to: {}", normalized);
        fetchConfiguredCoinFees(normalized);
    }

    /**
     * The fee targets the node reads for a given referral
     * ({@code GET /api/v1/fees/{coin}/targets?referral=<code>}). For the
     * <b>configured</b> referral this serves the live routing state (already
     * polled); for any other referral it fetches the targets on demand from the
     * backend, cached briefly, without touching the routing state. fee-backend
     * resolves an unknown code to the house fee, so the response always reflects a
     * real, routable fee split.
     */
    public List<FeeTarget> targetsFor(String coin, String referral) {
        String c = coin == null ? "" : coin.toLowerCase(Locale.ROOT);
        if (configuredReferral.equalsIgnoreCase(referral == null ? "" : referral)) {
            return feeManager.getFeeTargets(c);
        }
        String key = c + ":" + (referral == null ? "" : referral.trim().toLowerCase(Locale.ROOT));
        CachedTargets cached = referralTargets.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt() < TARGET_CACHE_MS) {
            return cached.targets();
        }
        try {
            String url = String.format("%s?coin=%s&referral=%s", backendUrl, c, referral == null ? "" : referral);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Could not fetch fee targets for referral. Status: {}", response.statusCode());
                return cached != null ? cached.targets() : List.of();
            }
            FeeResponse feeResponse = objectMapper.readValue(response.body(), FeeResponse.class);
            List<FeeTarget> targets = List.copyOf(feeResponse.targets());
            referralTargets.put(key, new CachedTargets(targets, now));
            return targets;
        } catch (Exception e) {
            log.error("Could not fetch fee targets for referral: {}", e.getMessage());
            return cached != null ? cached.targets() : List.of();
        }
    }

    private record CachedTargets(List<FeeTarget> targets, long loadedAt) {
    }
}
