package im.phantom.message.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MsgTransactionCheckerTest {

    @Test
    void committedOnlyWhenConversationTimelineContainsMessage() {
        ConvTimelineDao dao = mock(ConvTimelineDao.class);
        MsgTransactionChecker checker = new MsgTransactionChecker(dao);
        when(dao.existsByMsgId("m_1")).thenReturn(true);
        when(dao.existsByMsgId("m_2")).thenReturn(false);

        assertThat(checker.committed("m_1")).isTrue();
        assertThat(checker.committed("m_2")).isFalse();
    }
}
