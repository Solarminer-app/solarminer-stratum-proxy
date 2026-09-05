package de.verdox.solarminer.solarminerstratumproxy.v1;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeService;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
                                          @RequestParam(name = "referral", required = false) String referral) {
        return feeService.targetsFor(coin, referral);
    }
}
