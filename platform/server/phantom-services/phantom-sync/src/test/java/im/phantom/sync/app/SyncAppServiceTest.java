package im.phantom.sync.app;

import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncReq;
import im.phantom.proto.v1.SyncResp;
import im.phantom.sync.infra.UserTimelineReadDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncAppServiceTest {

    @Mock
    private UserTimelineReadDao dao;

    private SyncAppService service;

    @BeforeEach
    void setUp() {
        service = new SyncAppService(dao);
    }

    @Test
    void returnsBatchAndMarksHasMoreWhenLatestSequenceIsNotReached() {
        when(dao.fetch("u_1", 10L, 2)).thenReturn(List.of(event(11L), event(12L)));
        when(dao.maxUserSeq("u_1")).thenReturn(13L);

        SyncResp resp = service.sync("u_1", SyncReq.newBuilder().setSinceUserSeq(10L).setLimit(2).build());

        assertThat(resp.getEventsList()).extracting(SyncEvent::getUserSeq).containsExactly(11L, 12L);
        assertThat(resp.getMaxUserSeq()).isEqualTo(13L);
        assertThat(resp.getHasMore()).isTrue();
    }

    @Test
    void emptyWhenClientAlreadyAtLatestSequence() {
        when(dao.fetch("u_1", 13L, 200)).thenReturn(List.of());
        when(dao.maxUserSeq("u_1")).thenReturn(13L);

        SyncResp resp = service.sync("u_1", SyncReq.newBuilder().setSinceUserSeq(13L).build());

        assertThat(resp.getEventsList()).isEmpty();
        assertThat(resp.getHasMore()).isFalse();
    }

    @Test
    void capsOversizedLimitAtFiveHundred() {
        when(dao.fetch("u_1", 0L, 500)).thenReturn(List.of());
        when(dao.maxUserSeq("u_1")).thenReturn(0L);

        SyncResp resp = service.sync("u_1", SyncReq.newBuilder().setSinceUserSeq(0L).setLimit(9999).build());

        assertThat(resp.getHasMore()).isFalse();
        verify(dao).fetch("u_1", 0L, 500);
    }

    private SyncEvent event(long seq) {
        return SyncEvent.newBuilder().setUserSeq(seq).build();
    }
}
