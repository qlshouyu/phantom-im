package im.phantom.infra.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeqAllocatorTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ValueOperations<String, String> valueOps;

    private SeqAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new SeqAllocator(redis, jdbc) {
            @Override
            long watermarkOf(String scope) {
                return 1005L;
            }
        };
    }

    @Test
    void firstAllocationAlignsRedisToMysqlWatermarkThenIncrements() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1006L);

        long seq = allocator.nextConvSeq("si_a__b");

        assertThat(seq).isEqualTo(1006L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object> hint = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of("seq:conv:si_a__b")), hint.capture());
        assertThat(hint.getValue()).isEqualTo("1005");
    }

    @Test
    void subsequentAllocationUsesPlainIncrementWithoutReadingMysqlAgain() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1006L);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("seq:conv:si_a__b")).thenReturn(1007L);

        assertThat(allocator.nextConvSeq("si_a__b")).isEqualTo(1006L);
        assertThat(allocator.nextConvSeq("si_a__b")).isEqualTo(1007L);

        verify(redis).execute(any(DefaultRedisScript.class), anyList(), any());
        verify(valueOps).increment("seq:conv:si_a__b");
    }

    @Test
    void userScopesAreIndependentFromConversationScopes() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(501L);

        assertThat(allocator.nextUserSeq("u_1")).isEqualTo(501L);

        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of("seq:user:u_1")), eq("1005"));
        verify(redis, never()).opsForValue();
    }
}
