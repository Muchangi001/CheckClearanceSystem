package com.cts.clearing;

import java.time.Instant;
import java.util.List;

import com.cts.audit.AuditService;
import com.cts.cheque.Cheque;
import com.cts.cheque.ChequeImageRepository;
import com.cts.cheque.ChequeRepository;
import com.cts.cheque.ChequeStatus;
import com.cts.security.SigningService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outward presentment: sign each READY item and send it to the clearing house.
 * The clearing house is simulated in-process, so presenting an item also
 * delivers it to the drawee simulator's inward queue.
 */
@Service
public class PresentmentService {

	private final ChequeRepository cheques;
	private final ChequeImageRepository images;
	private final SigningService signing;
	private final ExpiryPolicy expiry;
	private final DraweeService drawee;
	private final AuditService audit;

	public PresentmentService(ChequeRepository cheques, ChequeImageRepository images, SigningService signing,
			ExpiryPolicy expiry, DraweeService drawee, AuditService audit) {
		this.cheques = cheques;
		this.images = images;
		this.signing = signing;
		this.expiry = expiry;
		this.drawee = drawee;
		this.audit = audit;
	}

	@Transactional
	public int presentReady(String user) {
		List<Cheque> ready = cheques.findByStatusOrderByIdAsc(ChequeStatus.READY);
		Instant now = Instant.now();
		for (Cheque cheque : ready) {
			String data = ItemData.canonical(cheque, images.findByChequeIdOrderBySideDesc(cheque.getId()));
			cheque.present(ItemData.sha256(data), signing.sign(data), signing.keyId(), now, expiry.expiryFor(now));
			audit.record(cheque.getId(), user, "PRESENTED", "Signed with key " + signing.keyId().substring(0, 8)
					+ ", expires " + cheque.getExpiresAt());
			drawee.receive(cheque);
		}
		return ready.size();
	}

}
