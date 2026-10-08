package com.cts.clearing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.cts.CtsProperties;
import com.cts.audit.AuditService;
import com.cts.cheque.Cheque;
import com.cts.cheque.ChequeImageRepository;
import com.cts.cheque.ChequeRepository;
import com.cts.cheque.ChequeStatus;
import com.cts.cheque.ValidationException;
import com.cts.ledger.LedgerService;
import com.cts.security.SigningService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The drawee (paying) bank, simulated. On receipt it runs the checks a core
 * banking system would and returns anything that fails with an NPCI reason
 * code. Items that pass wait for a human to compare the signature with the
 * specimen and confirm; if nobody answers by the item's expiry, it is deemed
 * approved and paid regardless.
 */
@Service
public class DraweeService {

	private final ChequeRepository cheques;
	private final ChequeImageRepository images;
	private final SigningService signing;
	private final LedgerService ledger;
	private final AuditService audit;
	private final JdbcClient jdbc;
	private final CtsProperties props;

	public DraweeService(ChequeRepository cheques, ChequeImageRepository images, SigningService signing,
			LedgerService ledger, AuditService audit, JdbcClient jdbc, CtsProperties props) {
		this.cheques = cheques;
		this.images = images;
		this.signing = signing;
		this.ledger = ledger;
		this.audit = audit;
		this.jdbc = jdbc;
		this.props = props;
	}

	record Account(long id, String holderName, long balancePaise, String status) {
	}

	record PositivePay(String serial, java.time.LocalDate chequeDate, String payeeName, long amountPaise) {
	}

	/** Inward receipt: automatic checks. Anything that fails is returned immediately. */
	void receive(Cheque cheque) {
		String data = ItemData.canonical(cheque, images.findByChequeIdOrderBySideDesc(cheque.getId()));
		if (!signing.verify(data, cheque.getSignature(), cheque.getSigningKeyId())) {
			returnItem(cheque, "83", "Signature on item data did not verify", "DRAWEE-RULES");
			return;
		}
		Optional<Account> account = account(cheque);
		if (account.isEmpty()) {
			returnItem(cheque, "88", "No such account at drawee", "DRAWEE-RULES");
			return;
		}
		if ("CLOSED".equals(account.get().status())) {
			returnItem(cheque, "88", "Account closed", "DRAWEE-RULES");
			return;
		}
		if (stopped(account.get(), cheque)) {
			returnItem(cheque, "20", "Drawer stopped cheque " + cheque.getSerial(), "DRAWEE-RULES");
			return;
		}
		String ppsFailure = positivePayFailure(account.get(), cheque);
		if (ppsFailure != null) {
			returnItem(cheque, "88", ppsFailure, "DRAWEE-RULES");
			return;
		}
		if (account.get().balancePaise() < cheque.getAmountPaise()) {
			returnItem(cheque, "01", "Balance below cheque amount", "DRAWEE-RULES");
			return;
		}
		audit.record(cheque.getId(), "DRAWEE-RULES", "CHECKS_PASSED",
				"Signature, account, stop-payment, Positive Pay and funds checks passed; awaiting confirmation");
	}

	@Transactional
	public void confirm(long id, String user) {
		Cheque cheque = presented(id);
		Account account = account(cheque).orElseThrow(() -> new ValidationException("Drawer account not found"));
		if (account.balancePaise() < cheque.getAmountPaise()) {
			throw new ValidationException("Insufficient funds now: return it with code 01 instead");
		}
		pay(cheque, account, user, false);
	}

	@Transactional
	public void returnManually(long id, String code, String reason, String user) {
		Cheque cheque = presented(id);
		boolean known = jdbc.sql("select count(*) from return_reason where code = ?").param(code).query(Integer.class)
			.single() > 0;
		if (!known) {
			throw new ValidationException("Unknown return reason code " + code);
		}
		returnItem(cheque, code, reason == null || reason.isBlank() ? null : reason.trim(), user);
	}

	/** Items nobody answered by their expiry are deemed approved and paid. */
	@Transactional
	public int deemExpired() {
		List<Cheque> expired = cheques.findByStatusAndExpiresAtBefore(ChequeStatus.PRESENTED, Instant.now());
		for (Cheque cheque : expired) {
			Account account = account(cheque).orElseThrow();
			pay(cheque, account, "SYSTEM", true);
		}
		return expired.size();
	}

	private void pay(Cheque cheque, Account account, String user, boolean deemed) {
		jdbc.sql("update drawee_account set balance_paise = balance_paise - ? where id = ?")
			.params(cheque.getAmountPaise(), account.id())
			.update();
		ledger.postPair(cheque.getId(), null, "DRAWEE_DEBIT",
				"DRAWER:" + cheque.getMicrCode() + "/" + cheque.getAccountNo(),
				"SETTLEMENT:" + cheque.draweeBankCode(), cheque.getAmountPaise());
		cheque.confirm(user, deemed, Instant.now());
		audit.record(cheque.getId(), user, deemed ? "DEEMED_APPROVED" : "CONFIRMED",
				deemed ? "No response by expiry; paid under deemed approval" : null);
	}

	private void returnItem(Cheque cheque, String code, String reason, String user) {
		cheque.returnItem(code, reason, user, Instant.now());
		audit.record(cheque.getId(), user, "RETURNED", code + (reason == null ? "" : " - " + reason));
	}

	private Cheque presented(long id) {
		Cheque cheque = cheques.findById(id).orElseThrow(() -> new ValidationException("No cheque " + id));
		if (cheque.getStatus() != ChequeStatus.PRESENTED) {
			throw new ValidationException("Cheque " + id + " is " + cheque.getStatus() + ", not awaiting the drawee");
		}
		return cheque;
	}

	private Optional<Account> account(Cheque cheque) {
		return jdbc.sql("""
				select id, holder_name, balance_paise, status from drawee_account
				where micr_code = ? and account_no = ?""")
			.params(cheque.getMicrCode(), cheque.getAccountNo())
			.query(Account.class)
			.optional();
	}

	private boolean stopped(Account account, Cheque cheque) {
		return jdbc.sql("select count(*) from stop_payment where account_id = ? and serial = ?")
			.params(account.id(), cheque.getSerial())
			.query(Integer.class)
			.single() > 0;
	}

	/**
	 * At or above ₹50,000 a registered Positive Pay entry must match exactly.
	 * At or above the bank's mandatory threshold, having none is itself a return.
	 */
	private String positivePayFailure(Account account, Cheque cheque) {
		if (cheque.getAmountPaise() < props.ppsThresholdPaise()) {
			return null;
		}
		Optional<PositivePay> pps = jdbc.sql("""
				select serial, cheque_date, payee_name, amount_paise from positive_pay
				where account_id = ? and serial = ?""")
			.params(account.id(), cheque.getSerial())
			.query(PositivePay.class)
			.optional();
		if (pps.isEmpty()) {
			return cheque.getAmountPaise() >= props.ppsMandatoryPaise()
					? "Positive Pay registration required at this amount" : null;
		}
		PositivePay p = pps.get();
		if (p.amountPaise() != cheque.getAmountPaise()) {
			return "Positive Pay mismatch: amount";
		}
		if (!p.chequeDate().equals(cheque.getChequeDate())) {
			return "Positive Pay mismatch: date";
		}
		if (!p.payeeName().equalsIgnoreCase(cheque.getPayeeName())) {
			return "Positive Pay mismatch: payee";
		}
		return null;
	}

}
