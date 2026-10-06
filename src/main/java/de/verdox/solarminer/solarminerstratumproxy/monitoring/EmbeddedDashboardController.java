package de.verdox.solarminer.solarminerstratumproxy.monitoring;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** Dedicated asset namespace used when proxy and PC-Agent share one classpath. */
@RestController
public class EmbeddedDashboardController {
    private static final Map<String, MediaType> ASSETS = Map.of(
            "index.html", MediaType.TEXT_HTML,
            "styles.css", MediaType.valueOf("text/css"),
            "app.js", MediaType.valueOf("application/javascript"));

    @GetMapping({"/embedded-dashboard", "/embedded-dashboard/"})
    public ResponseEntity<byte[]> index() { return asset("index.html"); }

    @GetMapping({"/embedded-dashboard/styles.css", "/embedded-dashboard/app.js"})
    public ResponseEntity<byte[]> assetPath(jakarta.servlet.http.HttpServletRequest request) {
        String name = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        return asset(name);
    }

    private static ResponseEntity<byte[]> asset(String name) {
        MediaType type = ASSETS.get(name);
        if (type == null) return ResponseEntity.notFound().build();
        try {
            byte[] body = new ClassPathResource("static/proxy-dashboard/" + name).getInputStream().readAllBytes();
            return ResponseEntity.ok().contentType(type).body(body);
        } catch (IOException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
