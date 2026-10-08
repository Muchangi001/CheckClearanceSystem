package com.cts.web;

import java.util.Map;

import com.cts.cheque.ValidationException;

import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.ModelAndView;

@RestControllerAdvice
class Errors {

	@ExceptionHandler(ValidationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	Object validation(ValidationException ex, jakarta.servlet.http.HttpServletRequest request) {
		return respond(request, ex.getMessage());
	}

	/** Two people acted on the same item at once; the second loses cleanly. */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	Object conflict(jakarta.servlet.http.HttpServletRequest request) {
		return respond(request, "Someone else changed this item at the same moment. Reload and try again.");
	}

	private Object respond(jakarta.servlet.http.HttpServletRequest request, String message) {
		if (request.getRequestURI().startsWith("/api/")) {
			return Map.of("error", message);
		}
		return new ModelAndView("error", Map.of("message", message));
	}

}
