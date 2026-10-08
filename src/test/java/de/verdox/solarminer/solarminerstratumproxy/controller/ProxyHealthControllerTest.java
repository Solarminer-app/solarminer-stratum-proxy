package de.verdox.solarminer.solarminerstratumproxy.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ProxyHealthControllerTest {
    @Test
    void reportsTheCurrentProcessIdentity() throws Exception {
        MockMvc api = standaloneSetup(new ProxyHealthController("current")).build();
        api.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("solarminer-stratum-proxy"))
                .andExpect(jsonPath("$.instanceId").value("current"));
    }
}
