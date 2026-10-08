package com.cts.cheque;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HexFormat;

import com.cts.CtsProperties;
import com.cts.audit.AuditService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Outward capture at the presenting bank: scan, key, validate, and hold or queue. */
@Service
public class CaptureService {

	private static final long MAX_AMOUNT_PAISE = 99_999_999_999_99L; // 13-digit MICR amount field

	private final ChequeRepository cheques;
	private final ChequeImageRepository images;
	private final AuditService audit;
	private final JdbcClient jdbc;
	private final CtsProperties props;

	public CaptureService(ChequeRepository cheques, ChequeImageRepository images, AuditService audit, JdbcClient jdbc,
			CtsProperties props) {
		this.cheques = cheques;
		this.images = images;
		this.audit = audit;
		this.jdbc = jdbc;
		this.props = props;
	}

	public record CaptureRequest(String serial, String micrCode, String accountNo, String txCode, String amount,
			LocalDate chequeDate, String payeeName, String depositorAccount) {
	}

	@Transactional
	public Cheque capture(CaptureRequest req, MultipartFile front, MultipartFile back, String maker) {
		MicrLine micr = new MicrLine(req.serial(), req.micrCode(), req.accountNo(), req.txCode());
		long amount = parseRupees(req.amount());
		if (req.chequeDate() == null) {
			throw new ValidationException("Cheque date is required");
		}
		String payee = req.payeeName() == null ? "" : req.payeeName().trim();
		if (payee.isEmpty()) {
			throw new ValidationException("Payee name is required");
		}
		requireDepositAccount(req.depositorAccount());
		Image frontImage = readImage(front, "Front");
		Image backImage = readImage(back, "Back");

		Cheque cheque = new Cheque(micr, amount, req.chequeDate(), payee, req.depositorAccount(), maker);
		String rejection = rejectionReason(cheque);
		if (rejection != null) {
			cheque.reject(rejection);
		}
		else if (amount >= props.approvalThresholdPaise()) {
			cheque.hold("High value: needs checker approval");
		}
		else {
			cheque.ready();
		}
		cheques.saveAndFlush(cheque);
		images.save(new ChequeImage(cheque.getId(), "FRONT", frontImage.contentType(), frontImage.sha256(), frontImage.data()));
		images.save(new ChequeImage(cheque.getId(), "BACK", backImage.contentType(), backImage.sha256(), backImage.data()));
		audit.record(cheque.getId(), maker, "CAPTURED", micr + " for " + amount + " paise");
		if (cheque.getStatus() == ChequeStatus.REJECTED) {
			audit.record(cheque.getId(), "SYSTEM", "REJECTED", rejection);
		}
		else if (cheque.getStatus() == ChequeStatus.PENDING_APPROVAL) {
			audit.record(cheque.getId(), "SYSTEM", "HELD", cheque.getStatusReason());
		}
		return cheque;
	}

	/** Rules that stop an item before it ever reaches the clearing house. */
	private String rejectionReason(Cheque cheque) {
		LocalDate today = LocalDate.now(props.zoneId());
		if (cheque.getChequeDate().isAfter(today)) {
			return "Post-dated: payable from " + cheque.getChequeDate();
		}
		if (cheque.getChequeDate().isBefore(today.minusMonths(3))) {
			return "Stale: cheques are valid for 3 months from their date";
		}
		boolean knownBank = jdbc.sql("select count(*) from bank where code = ? and not is_presenting")
			.param(cheque.draweeBankCode())
			.query(Integer.class)
			.single() > 0;
		if (!knownBank) {
			return "Unknown drawee bank code " + cheque.draweeBankCode();
		}
		if (cheques.existsByDedupeKeyAndStatusNotIn(cheque.getDedupeKey(),
				EnumSet.of(ChequeStatus.REJECTED, ChequeStatus.RETURNED))) {
			return "Duplicate: this instrument is already in clearing";
		}
		return null;
	}

	private void requireDepositAccount(String accountNo) {
		boolean exists = accountNo != null && jdbc.sql("select count(*) from deposit_account where account_no = ?")
			.param(accountNo)
			.query(Integer.class)
			.single() > 0;
		if (!exists) {
			throw new ValidationException("Unknown depositor account");
		}
	}

	public static long parseRupees(String value) {
		if (value == null || value.isBlank()) {
			throw new ValidationException("Amount is required");
		}
		String clean = value.replace(",", "").replace("₹", "").trim();
		if (!clean.matches("\\d+(\\.\\d{1,2})?")) {
			throw new ValidationException("Amount must be rupees with at most 2 decimals");
		}
		String[] parts = clean.split("\\.");
		String paise = parts.length == 2 ? (parts[1] + "0").substring(0, 2) : "00";
		long amount;
		try {
			amount = Math.multiplyExact(Long.parseLong(parts[0]), 100) + Long.parseLong(paise);
		}
		catch (ArithmeticException | NumberFormatException ex) {
			throw new ValidationException("Amount is too large");
		}
		if (amount <= 0 || amount > MAX_AMOUNT_PAISE) {
			throw new ValidationException("Amount must be between ₹0.01 and the MICR field's 13-digit limit");
		}
		return amount;
	}

	private record Image(String contentType, String sha256, byte[] data) {
	}

	private static Image readImage(MultipartFile file, String side) {
		if (file == null || file.isEmpty()) {
			throw new ValidationException(side + " image is required");
		}
		String type = file.getContentType();
		if (type == null || !type.startsWith("image/")) {
			throw new ValidationException(side + " image must be an image file");
		}
		try {
			byte[] data = file.getBytes();
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
			return new Image(type, hash, data);
		}
		catch (IOException | NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Could not read " + side + " image", ex);
		}
	}

}
