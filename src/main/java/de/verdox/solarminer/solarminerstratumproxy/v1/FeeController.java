package de.verdox.solarminer.solarminerstratumproxy.v1;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeService;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/fees")
public class FeeController {
    private final FeeService feeService;

    public FeeController(FeeService feeService) {
        this.feeService = feeService;
    }

    @GetMapping("/{coin}/targets")
    public List<FeeTarget> getCoinTargets(@PathVariable String coin,
                                          @RequestParam(name = "referral", required = false) String referral,
                                          @RequestParam(name = "tier", required = false) String tier) {
        return feeService.targetsFor(coin, referral, tier);
    }

    /**
     * Set the referral this proxy enforces for job routing (stratum-routed
     * miners), at runtime. The node's core posts the site's saved referral here so
     * the dev-fee split is routed to the referrer's worker. Blank resets to house.
     */
    @PostMapping("/referral")
    public void setReferral(@RequestBody(required = false) ReferralRequest request) {
        feeService.setReferral(request == null ? null : request.referral());
    }

    /**
     * Set the fee tier this proxy resolves for job routing (stratum-routed
     * miners), at runtime. {@code proxy} = reduced house share (PC-Agent without
     * a SolarMiner Node); anything else = full node fee. The PC-Agent pushes the
     * effective tier whenever Node control is switched on or off, and a Node
     * forces {@code node} whenever it starts steering or reading the proxy.
     */
    @PostMapping("/tier")
    public void setTier(@RequestBody(required = false) TierRequest request) {
        feeService.setTier(request == null ? null : request.tier());
    }

    public record ReferralRequest(String referral) {
    }

    public record TierRequest(String tier) {
    }
}
