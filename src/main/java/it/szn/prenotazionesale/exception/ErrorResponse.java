package it.szn.prenotazionesale.exception;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ErrorResponse {
	
	private LocalDateTime timestamp;
	private int status;
	private String message;

	public static ErrorResponse of(int status, String message) {
		return new ErrorResponse(LocalDateTime.now(), status, message);
	}
}
