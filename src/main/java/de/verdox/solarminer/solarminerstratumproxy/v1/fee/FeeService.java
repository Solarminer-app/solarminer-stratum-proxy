package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String BACKEND_URL = "https://fee.solarminer.app/api/fees";
    private static final long TARGET_CACHE_MS = 60_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FeeManager feeManager;

    /**
     * On-demand, per-referral target cache serving the node's {@code ?referral=}
     * reads. Kept separate from {@link FeeManager}'s routing state so an on-demand
     * lookup for an arbitrary referral never clobbers the targets the proxy uses
     * to roll jobs for the configured referral.
     */
    private final Map<String, CachedTargets> referralTargets = new ConcurrentHashMap<>();

    /**
     * The referral code whose fee split this proxy instance enforces. Settable via
     * {@code solarminer.fee.referral} (env: SOLARMINER_FEE_REFERRAL); defaults to
     * {@code "solarminer"} (the house dev fee only). A node that was bought with a
     * referral code should be configured with that code so the referrer share is
     * routed under their worker — fee-backend resolves unknown/blank codes to
     * {@code solarminer} anyway.
     */
    private final String configuredReferral;

    public FeeService(FeeManager feeManager,
                      @Value("${solarminer.fee.referral:solarminer}") String configuredReferral) {
        this.feeManager = feeManager;
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
        fetchAndUpdateFees("btc", configuredReferral);
        fetchAndUpdateFees("bitcoin", configuredReferral);
        log.debug("Fetched fees from solarminer backend");
    }

    public void fetchAndUpdateFees(String coin, String referral) {
        try {
            String url = String.format("%s?coin=%s&referral=%s", BACKEND_URL, coin, referral != null ? referral : "");
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
            String url = String.format("%s?coin=%s&referral=%s", BACKEND_URL, c, referral == null ? "" : referral);
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