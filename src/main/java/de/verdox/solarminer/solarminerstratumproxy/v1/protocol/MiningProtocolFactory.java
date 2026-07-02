package de.verdox.solarminer.solarminerstratumproxy.v1.protocol;

import de.verdox.solarminer.solarminerstratumproxy.v1.MiningProtocol;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class MiningProtocolFactory {

    private final ApplicationContext context;

    public MiningProtocolFactory(ApplicationContext context) {
        this.context = context;
    }

    public MiningProtocol getProtocol(String coinName) {
        String beanName = coinName.toLowerCase() + "StratumProtocol";

        if (context.containsBean(beanName)) {
            return (MiningProtocol) context.getBean(beanName);
        }

        throw new IllegalArgumentException("No protocol algorithm found for coin: " + coinName);
    }
}
