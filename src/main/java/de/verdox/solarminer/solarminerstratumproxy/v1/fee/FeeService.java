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
    /**
     * The fee tier this proxy resolves from fee-backend: {@code node} (default,
     * full dev fee incl. energy routing) or {@code proxy} (reduced house share
     * for proxy-only PC-Agent setups). Initialized from
     * {@code solarminer.fee.tier} (env: SOLARMINER_FEE_TIER); the PC-Agent and a
     * controlling SolarMiner Node push the effective tier at runtime via
     * {@link #setTier(String)} — every flip re-fetches immediately so the next
     * rolled job uses the new split.
     */
    private volatile String tier;
    public FeeService(FeeManager feeManager,
                      ProxyProperties proxyProperties,
                      @Value("${solarminer.fee.referral:solarminer}") String configuredReferral,
                      @Value("${solarminer.fee.tier:node}") String tier,
                      @Value("${solarminer.fee.backend-url:" + DEFAULT_BACKEND_URL + "}") String backendUrl) {
        this.feeManager = feeManager;
        this.proxyProperties = proxyProperties;
        this.backendUrl = backendUrl;
        this.objectMapper = new ObjectMapper();
        this.configuredReferral = (configuredReferral == null || configuredReferral.isBlank())
                ? "solarminer" : configuredReferral.trim();
        this.tier = "proxy".equalsIgnoreCase(tier) ? "proxy" : "node";

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
            String url = String.format("%s?coin=%s&referral=%s&tier=%s",
                    backendUrl, coin, referral != null ? referral : "", tier);
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
     * Change the fee tier this proxy resolves for job routing, at runtime.
     * {@code proxy} requests the reduced house share (proxy-only, no Node energy
     * routing); anything else resolves to the full {@code node} fee. Every actual
     * flip re-fetches immediately, so the very next rolled job uses the new split
     * — this is the forcing mechanism: a Node that starts steering or reading the
     * agent/proxy pushes {@code node} and the higher fee applies instantly, and
     * the PC-Agent reverts to {@code proxy} when Node control is switched off.
     */
    public synchronized void setTier(String requestedTier) {
        String normalized = "proxy".equalsIgnoreCase(requestedTier) ? "proxy" : "node";
        if (normalized.equalsIgnoreCase(tier)) {
            return;
        }
        tier = normalized;
        referralTargets.clear();
        log.info("Fee tier changed to: {}", normalized);
        fetchConfiguredCoinFees(configuredReferral);
    }

    public String getTier() {
        return tier;
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
        return targetsFor(coin, referral, null);
    }

    public List<FeeTarget> targetsFor(String coin, String referral, String tierOverride) {
        String effectiveTier = tierOverride == null || tierOverride.isBlank()
                ? tier
                : ("proxy".equalsIgnoreCase(tierOverride) ? "proxy" : "node");
        String c = coin == null ? "" : coin.toLowerCase(Locale.ROOT);
        if (configuredReferral.equalsIgnoreCase(referral == null ? "" : referral) && effectiveTier.equals(tier)) {
            return feeManager.getFeeTargets(c);
        }
        String key = c + ":" + (referral == null ? "" : referral.trim().toLowerCase(Locale.ROOT))
                + ":" + effectiveTier;
        CachedTargets cached = referralTargets.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt() < TARGET_CACHE_MS) {
            return cached.targets();
        }
        try {
            String url = String.format("%s?coin=%s&referral=%s&tier=%s",
                    backendUrl, c, referral == null ? "" : referral, effectiveTier);
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
