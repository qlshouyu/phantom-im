package im.phantom.web.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "登录名不能为空") String loginName,
        @NotBlank(message = "密码不能为空") String password) {
}
