package im.phantom.web.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(min = 1, max = 64, message = "昵称长度必须为 1 至 64 个字符")
        String nickname,
        @NotBlank(message = "登录名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "登录名必须为 4 至 32 位字母、数字或下划线")
        String loginName,
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度必须为 8 至 64 个字符")
        String password,
        @NotBlank(message = "确认密码不能为空")
        String passwordConfirm) {
}
