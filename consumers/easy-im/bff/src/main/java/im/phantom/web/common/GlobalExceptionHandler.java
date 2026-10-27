package im.phantom.web.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> apiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status())
                .body(ApiResponse.error(ex.code(), ex.getMessage(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> validation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(toSnakeCase(fieldError.getField()), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiResponse<>(
                1001, "注册字段不合法", fields, requestId(request)));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> sessionUnavailable(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(9002, "会话存储暂不可用", requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(1002, "服务暂时不可用", requestId(request)));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? null : value.toString();
    }

    private static String toSnakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
