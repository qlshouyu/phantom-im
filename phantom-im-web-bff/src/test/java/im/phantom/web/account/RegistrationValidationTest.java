package im.phantom.web.account;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsBoundaryValues() {
        assertThat(validator.validate(new RegistrationRequest("昵", "abcd", "12345678", "12345678"))).isEmpty();
        assertThat(validator.validate(new RegistrationRequest("名".repeat(64), "a".repeat(32),
                "密".repeat(64), "密".repeat(64)))).isEmpty();
    }

    @Test
    void reportsEveryInvalidField() {
        RegistrationRequest request = new RegistrationRequest("", "bad-name", "short", "different");
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("nickname", "loginName", "password");
    }
}
