package im.phantom.common.time;

import java.time.Clock;
import java.util.Objects;

/** 基于注入 Clock 的系统时间实现。 */
public final class SystemTimeProvider implements TimeProvider {

    private final Clock clock;

    public SystemTimeProvider() {
        this(Clock.systemUTC());
    }

    public SystemTimeProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public long nowMillis() {
        return clock.millis();
    }
}
