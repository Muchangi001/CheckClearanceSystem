package com.cts.cheque;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The MICR code line on an Indian CTS-2010 cheque:
 * cheque serial (6) · MICR code (9: city 3 + bank 3 + branch 3) · account (6) · transaction code (2).
 * The amount is not part of it: the presenting bank keys it in.
 */
public record MicrLine(String serial, String micrCode, String accountNo, String txCode) {

	private static final Pattern SIX = Pattern.compile("\\d{6}");
	private static final Pattern NINE = Pattern.compile("\\d{9}");
	private static final Pattern TWO = Pattern.compile("\\d{2}");

	public MicrLine {
		serial = strip(serial);
		micrCode = strip(micrCode);
		accountNo = strip(accountNo);
		txCode = strip(txCode);
		require(SIX, serial, "Cheque serial must be 6 digits");
		require(NINE, micrCode, "MICR code must be 9 digits");
		require(SIX, accountNo, "Account field must be 6 digits");
		require(TWO, txCode, "Transaction code must be 2 digits");
	}

	public String cityCode() {
		return micrCode.substring(0, 3);
	}

	public String bankCode() {
		return micrCode.substring(3, 6);
	}

	public String branchCode() {
		return micrCode.substring(6, 9);
	}

	/**
	 * Identity of the physical instrument. The amount is deliberately left out:
	 * the same leaf presented again with an altered amount is still a duplicate.
	 */
	public String dedupeKey() {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest((micrCode + "|" + accountNo + "|" + serial).getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public String toString() {
		return "⑈" + serial + "⑈ " + micrCode + "⑆ " + accountNo + "⑈ " + txCode;
	}

	private static String strip(String value) {
		return value == null ? "" : value.replaceAll("\\s", "");
	}

	private static void require(Pattern pattern, String value, String message) {
		if (!pattern.matcher(value).matches()) {
			throw new ValidationException(message);
		}
	}

}
