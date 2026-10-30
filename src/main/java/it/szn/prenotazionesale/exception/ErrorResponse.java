package it.szn.prenotazionesale.exception;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modello conforme a RFC 7807 / RFC 9457 (Problem Details for HTTP APIs).
 * Include il campo 'message' per garantire piena compatibilita' con il frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @Builder.Default
    private String type = "about:blank";

    private String title;
    private int status;
    private String detail;
    private String message;

    @Builder.Default
    private String timestamp = Instant.now().toString();

    public static ErrorResponse of(int status, String message) {
        return ErrorResponse.builder()
                .type("about:blank")
                .title(resolveTitleForStatus(status))
                .status(status)
                .detail(message)
                .message(message)
                .timestamp(Instant.now().toString())
                .build();
    }

    private static String resolveTitleForStatus(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 429 -> "Too Many Requests";
            case 502 -> "Bad Gateway";
            case 504 -> "Gateway Timeout";
            default -> "Internal Server Error";
        };
    }
}
