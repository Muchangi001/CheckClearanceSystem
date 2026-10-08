package com.cts.cheque;

import com.cts.audit.AuditService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maker-checker on held items. The person who captured an item can never approve it. */
@Service
public class ApprovalService {

	private final ChequeRepository cheques;
	private final AuditService audit;

	public ApprovalService(ChequeRepository cheques, AuditService audit) {
		this.cheques = cheques;
		this.audit = audit;
	}

	@Transactional
	public void approve(long id, String checker) {
		Cheque cheque = pending(id, checker);
		cheque.approve(checker);
		audit.record(id, checker, "APPROVED", null);
	}

	@Transactional
	public void reject(long id, String checker, String reason) {
		Cheque cheque = pending(id, checker);
		String why = reason == null || reason.isBlank() ? "Rejected by checker" : reason.trim();
		cheque.reject(why);
		audit.record(id, checker, "REJECTED", why);
	}

	private Cheque pending(long id, String checker) {
		Cheque cheque = cheques.findById(id).orElseThrow(() -> new ValidationException("No cheque " + id));
		if (cheque.getStatus() != ChequeStatus.PENDING_APPROVAL) {
			throw new ValidationException("Cheque " + id + " is " + cheque.getStatus() + ", not awaiting approval");
		}
		if (cheque.getCapturedBy().equals(checker)) {
			throw new ValidationException("Maker-checker: you captured this cheque, so someone else must decide it");
		}
		return cheque;
	}

}
