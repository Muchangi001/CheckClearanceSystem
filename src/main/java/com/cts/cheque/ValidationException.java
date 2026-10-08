package com.cts.cheque;

/** A request that breaks a rule. Shown to the user; never a system fault. */
public class ValidationException extends RuntimeException {

	public ValidationException(String message) {
		super(message);
	}

}
