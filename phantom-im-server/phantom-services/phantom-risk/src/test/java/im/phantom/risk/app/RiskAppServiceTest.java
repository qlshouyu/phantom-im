package im.phantom.risk.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.risk.RiskProperties;
import im.phantom.risk.infra.JdbcRiskRepository;
import im.phantom.risk.infra.JdbcRiskRepository.ReportRow;
import im.phantom.risk.infra.JdbcRiskRepository.SanctionRow;
import im.phantom.proto.v1.SyncEvent;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskAppServiceTest {
    @Mock private JdbcRiskRepository repository;
    @Mock private RouteTableService routes;
    @Mock private GatewayDeliveryService gateway;
    @Mock private ReportEventWriter reportEvents;
    private RiskAppService risk;

    @BeforeEach
    void setUp() {
        RiskProperties properties = new RiskProperties();
        properties.setAdminToken("admin");
        risk = new RiskAppService(repository, routes, gateway, properties, new ObjectMapper());
    }

    @Test
    void banAndMuteBlockSendingWithStableCodes() {
        when(repository.userStatus("u1")).thenReturn("BANNED");
        assertThatThrownBy(() -> risk.assertCanSend("app", "u1"))
                .extracting(error -> ((BizException) error).code()).isEqualTo(ErrorCodes.ACCOUNT_BANNED);

        when(repository.userStatus("u2")).thenReturn("ACTIVE");
        when(repository.activeSanction(eq("u2"), anyLong())).thenReturn(Optional.of(
                new SanctionRow("s1", "MUTE", "", 1, null, "ACTIVE", "u2")));
        assertThatThrownBy(() -> risk.assertCanSend("app", "u2"))
                .extracting(error -> ((BizException) error).code()).isEqualTo(ErrorCodes.ACCOUNT_RESTRICTED);
    }

    @Test
    void minorModeBlocksMessagesFromNonFriends() {
        when(repository.minorMode("minor")).thenReturn(true);
        when(repository.mutualFriends("stranger", "minor")).thenReturn(false);
        assertThatThrownBy(() -> risk.assertCanReceive("app", "stranger", "minor"))
                .extracting(error -> ((BizException) error).code())
                .isEqualTo(ErrorCodes.MINOR_MODE_FORBIDDEN);
    }

    @Test
    void duplicatePendingReportReturnsOriginal() {
        ReportRow existing = new ReportRow("r1", "app", "u1", "MESSAGE", "m1", "SPAM", "", "{}",
                "PENDING", 1, 1);
        when(repository.pendingReport("u1", "MESSAGE", "m1")).thenReturn(Optional.of(existing));
        assertThat(risk.report("app", "u1", "MESSAGE", "m1", "SPAM", "")).isSameAs(existing);
        verify(repository, never()).insertReport(any());
    }

    @Test
    void banningUserUpdatesStatusAndKicksAllRoutes() {
        when(repository.userStatus("bad")).thenReturn("ACTIVE");
        when(routes.query("bad")).thenReturn(List.of(new RouteEntry("bad", 3, "d1", "c1",
                "gw", "app", 1, true)));
        SanctionRow result = risk.sanction("app", "admin", "bad", "BAN", "fraud", null);
        assertThat(result.type()).isEqualTo("BAN");
        verify(repository).updateUserStatus(eq("bad"), eq("BANNED"), anyLong());
        verify(gateway).send(eq("gw"), eq("c1"), any());
    }

    @Test
    void reportKeepsSnapshotEvenWhenTargetWasRecalled() {
        when(repository.pendingReport("u1", "MESSAGE", "m1")).thenReturn(Optional.empty());
        when(repository.messageSnapshot("m1")).thenReturn("cmVjYWxsZWQtc25hcHNob3Q=");

        risk.report("app", "u1", "MESSAGE", "m1", "ABUSE", "原因");

        ArgumentCaptor<ReportRow> inserted = ArgumentCaptor.forClass(ReportRow.class);
        verify(repository).insertReport(inserted.capture());
        assertThat(inserted.getValue().snapshotJson()).isEqualTo("cmVjYWxsZWQtc25hcHNob3Q=");
    }

    @Test
    void reportHandlingIsIdempotentAndPublishesGenericResultEvent() {
        ReportRow pending = new ReportRow("r1", "app", "u1", "MESSAGE", "m1", "SPAM", "",
                "snapshot", "PENDING", 1, 1);
        when(repository.findReport("app", "r1")).thenReturn(Optional.of(pending));
        when(repository.finishReport(eq("r1"), eq("RESOLVED"), anyLong())).thenReturn(true);
        RiskAppService withEvents = new RiskAppService(repository, routes, gateway, properties(),
                new ObjectMapper(), reportEvents);

        ReportRow handled = withEvents.handleReport("app", "admin", "r1", "RESOLVED");

        assertThat(handled.status()).isEqualTo("RESOLVED");
        ArgumentCaptor<SyncEvent> event = ArgumentCaptor.forClass(SyncEvent.class);
        verify(reportEvents).append(eq("u1"), anyString(), event.capture());
        assertThat(event.getValue().getReport().getStatus()).isEqualTo("RESOLVED");
        assertThat(event.getValue().getReport().getTemplateId()).isEqualTo("report.resolved");
        verify(repository).audit(eq("app"), eq("admin"), eq("REPORT_RESOLVED"), eq("MESSAGE"),
                eq("m1"), eq("SUCCESS"), anyString(), anyLong());
    }

    private RiskProperties properties() {
        RiskProperties properties = new RiskProperties();
        properties.setAdminToken("admin");
        return properties;
    }
}
