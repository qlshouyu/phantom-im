package im.phantom.web.account;

public enum AccountStatus {
    PROVISIONING,
    ACTIVE,
    PROVISIONING_FAILED,
    DISABLED;

    public boolean canLogin() {
        return this == ACTIVE;
    }
}
