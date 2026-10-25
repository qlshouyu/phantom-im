package im.phantom.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 本地风控配置。 */
@ConfigurationProperties(prefix = "phantom.risk")
public class RiskProperties {

    private List<String> blockedKeywords = List.of("违禁词");
    private boolean strictMode;
    private String adminToken = "phantom-local-admin-token";

    public List<String> getBlockedKeywords() {
        return blockedKeywords;
    }

    public void setBlockedKeywords(List<String> blockedKeywords) {
        this.blockedKeywords = blockedKeywords == null ? List.of() : List.copyOf(blockedKeywords);
    }

    public boolean isStrictMode() {
        return strictMode;
    }

    public void setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
    }

    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
}
