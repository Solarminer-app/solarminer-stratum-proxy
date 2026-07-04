package de.verdox.solarminer.solarminerstratumproxy.v1.fee;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
public class FeeService {
    private static final Logger log = LoggerFactory.getLogger(FeeService.class);
    private static final String BACKEND_URL = "https://fee.solarminer.app/api/fees";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FeeManager feeManager;

    @Setter
    @Getter
    private String configuredReferral = "default";

    public FeeService(FeeManager feeManager) {
        this.feeManager = feeManager;
        this.objectMapper = new ObjectMapper();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedRate = 3600000)
    public void scheduledFetch() {
        fetchAndUpdateFees("btc", configuredReferral);
        fetchAndUpdateFees("bitcoin", configuredReferral);
        log.info("Fetched fees from solarminer backend");
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
}