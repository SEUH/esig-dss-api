package io.github.seuh.esig;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;

@RestController
public class StatusController {
    private final TrustLists trustLists;
    private final SystemTimeMonitor timeMonitor;

    public StatusController(TrustLists trustLists, SystemTimeMonitor timeMonitor) {
        this.trustLists = trustLists;
        this.timeMonitor = timeMonitor;
    }

    @GetMapping("/api/status")
    public ResponseEntity<OperationalStatus> status() {
        TrustLists.TrustListStatus trust = trustLists.status();
        OperationalStatus result = new OperationalStatus(trust.ready() ? "UP" : "DEGRADED",
                Instant.now(), timeMonitor.status(),
                new RuntimeStatus(Runtime.version().toString(), ManagementFactory.getRuntimeMXBean().getUptime()),
                trust);
        return ResponseEntity.status(trust.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }

    public record OperationalStatus(String status, Instant checkedAtUtc,
                                    SystemTimeMonitor.TimeStatus systemTime,
                                    RuntimeStatus runtime, TrustLists.TrustListStatus trustLists) {}

    public record RuntimeStatus(String javaVersion, long uptimeMillis) {}
}
