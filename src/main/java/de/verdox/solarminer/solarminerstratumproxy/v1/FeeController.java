package de.verdox.solarminer.solarminerstratumproxy.v1;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/fees")
public class FeeController {
    private final FeeManager feeManager;

    public FeeController(FeeManager feeManager) {
        this.feeManager = feeManager;
    }

    @GetMapping("/{coin}/targets")
    public List<FeeTarget> getCoinTargets(@PathVariable String coin) {
        return feeManager.getFeeTargets(coin);
    }
}
