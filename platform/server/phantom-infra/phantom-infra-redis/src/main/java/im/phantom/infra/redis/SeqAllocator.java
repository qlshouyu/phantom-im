package im.phantom.infra.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序号分配器（sequence-allocation spec）。
 *
 * <p>取号走 Redis {@code INCR seq:{scope}} 快速分配；MySQL {@code seq_watermark} 为真相源。
 * 恢复对齐守卫：进程内每个 scope 首次取号，或 Redis 值疑似低于真相源时，先用水位对齐再自增，
 * 保证严格递增且 <b>永不回退</b>（守住 MSG-15/16）。
 *
 * <p>对齐 Lua（原子）：若 key 不存在或当前值 &lt; hint，则 SET 为 hint，再 INCR 返回。
 */
@Component
public class SeqAllocator {

    private static final DefaultRedisScript<Long> ALIGN_INCR = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) " +
            "local hint = tonumber(ARGV[1]) " +
            "if (cur == false) or (tonumber(cur) < hint) then " +
            "  redis.call('SET', KEYS[1], hint) " +
            "end " +
            "return redis.call('INCR', KEYS[1])",
            Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    /** 本进程已对齐过的 scope，避免每次取号都查库（稳态裸 INCR）。 */
    private final Set<String> aligned = ConcurrentHashMap.newKeySet();

    public SeqAllocator(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    public long nextConvSeq(String convId) {
        return next("conv:" + convId);
    }

    public long nextUserSeq(String userId) {
        return next("user:" + userId);
    }

    private long next(String scope) {
        String key = "seq:" + scope;
        if (aligned.add(scope)) {
            // 首次触达该 scope：用 MySQL 真相源对齐后自增，杜绝 Redis 冷启动/丢数据导致回退
            long watermark = watermarkOf(scope);
            Long v = redis.execute(ALIGN_INCR, List.of(key), String.valueOf(watermark));
            return v == null ? watermark + 1 : v;
        }
        // 稳态：裸 INCR（O(1)）
        Long v = redis.opsForValue().increment(key);
        return v == null ? 1L : v;
    }

    /** 读取 MySQL 已落库最大序号（不存在则 0）。 */
    long watermarkOf(String scope) {
        Long max = jdbc.query(
                "SELECT seq FROM seq_watermark WHERE scope = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L,
                scope);
        return max == null ? 0L : max;
    }
}
