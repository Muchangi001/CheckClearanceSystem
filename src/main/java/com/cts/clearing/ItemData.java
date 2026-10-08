package com.cts.clearing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.cts.cheque.Cheque;
import com.cts.cheque.ChequeImage;

/**
 * The canonical form of a presented item: what gets hashed and signed.
 * Any change to a field or an image after signing breaks verification.
 */
final class ItemData {

	private ItemData() {
	}

	static String canonical(Cheque c, List<ChequeImage> images) {
		StringBuilder sb = new StringBuilder()
			.append(c.getId()).append('|')
			.append(c.getSerial()).append('|')
			.append(c.getMicrCode()).append('|')
			.append(c.getAccountNo()).append('|')
			.append(c.getTxCode()).append('|')
			.append(c.getAmountPaise()).append('|')
			.append(c.getChequeDate()).append('|')
			.append(c.getPayeeName()).append('|')
			.append(c.getDepositorAccount());
		for (ChequeImage image : images) {
			sb.append('|').append(image.getSide()).append(':').append(image.getSha256());
		}
		return sb.toString();
	}

	static String sha256(String data) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
