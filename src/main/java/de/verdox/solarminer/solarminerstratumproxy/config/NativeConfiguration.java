package de.verdox.solarminer.solarminerstratumproxy.config;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeResponse;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

@Configuration
@RegisterReflectionForBinding(FeeResponse.class)
public class NativeConfiguration {

}
