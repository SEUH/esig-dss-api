package io.github.seuh.esig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Detects wall-clock jumps while this JVM is running. Absolute time sync is managed by the host OS. */
@Component
public class SystemTimeMonitor {
    private static final Logger log = LoggerFactory.getLogger(SystemTimeMonitor.class);
    private static final long JUMP_THRESHOLD_MILLIS = 5_000;
    private volatile Snapshot snapshot;

    public SystemTimeMonitor() {
        snapshot = new Snapshot(Instant.now(), 0, null, null);
    }

    @Scheduled(fixedDelayString = "${esig.time.check-interval:PT1M}",
               initialDelayString = "${esig.time.check-interval:PT1M}")
    public synchronized void checkSystemTime() {
        Instant now = Instant.now();
        long monotonicNow = System.nanoTime();
        Snapshot previous = snapshot;
        long elapsedWallMillis = Duration.between(previous.checkedAt(), now).toMillis();
        long elapsedMonotonicMillis = Duration.ofNanos(monotonicNow - previous.monotonicNanos()).toMillis();
        long adjustmentMillis = elapsedWallMillis - elapsedMonotonicMillis;
        long count = previous.clockJumpCount();
        Instant lastClockJump = previous.lastClockJump();
        Long lastAdjustmentMillis = previous.lastAdjustmentMillis();
        if (Math.abs(adjustmentMillis) >= JUMP_THRESHOLD_MILLIS) {
            count++;
            lastClockJump = now;
            lastAdjustmentMillis = adjustmentMillis;
            log.warn("System wall clock changed relative to monotonic time: adjustmentMs={}", adjustmentMillis);
        }
        snapshot = new Snapshot(now, monotonicNow, count, lastClockJump, lastAdjustmentMillis);
    }

    public TimeStatus status() {
        Snapshot current = snapshot;
        return new TimeStatus(Instant.now(), current.checkedAt(), current.clockJumpCount(),
                current.lastClockJump(), current.lastAdjustmentMillis(),
                "JVM system clock; synchronize the operating-system clock with NTS-capable software on the host");
    }

    public record TimeStatus(Instant currentUtc, Instant lastCheckUtc, long clockJumpCount,
                             Instant lastClockJumpUtc, Long lastAdjustmentMillis, String clockSource) {}

    private record Snapshot(Instant checkedAt, long monotonicNanos, long clockJumpCount,
                            Instant lastClockJump, Long lastAdjustmentMillis) {
        private Snapshot(Instant checkedAt, long clockJumpCount, Instant lastClockJump, Long lastAdjustmentMillis) {
            this(checkedAt, System.nanoTime(), clockJumpCount, lastClockJump, lastAdjustmentMillis);
        }
    }
}
