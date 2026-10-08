package com.cts.clearing;

import java.time.OffsetDateTime;
import java.util.List;

import com.cts.audit.AuditService;
import com.cts.cheque.Cheque;
import com.cts.cheque.ChequeRepository;
import com.cts.cheque.ChequeStatus;
import com.cts.ledger.LedgerService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement on realisation: confirmed items settle in hourly cycles, and the
 * presenting bank credits its customer. Each run is one batch; the net
 * position per bank is what moves between their RBI accounts.
 */
@Service
public class SettlementService {

	private final ChequeRepository cheques;
	private final LedgerService ledger;
	private final AuditService audit;
	private final JdbcClient jdbc;

	public SettlementService(ChequeRepository cheques, LedgerService ledger, AuditService audit, JdbcClient jdbc) {
		this.cheques = cheques;
		this.ledger = ledger;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	/** @return the batch id, or null if nothing was confirmed */
	@Transactional
	public Long settle(String user) {
		List<Cheque> confirmed = cheques.findByStatusOrderByIdAsc(ChequeStatus.CONFIRMED);
		if (confirmed.isEmpty()) {
			return null;
		}
		long total = confirmed.stream().mapToLong(Cheque::getAmountPaise).sum();
		long batchId = jdbc.sql("""
				insert into settlement_batch (item_count, total_paise, triggered_by) values (?, ?, ?) returning id""")
			.params(confirmed.size(), total, user)
			.query(Long.class)
			.single();
		String presenting = jdbc.sql("select code from bank where is_presenting").query(String.class).single();
		for (Cheque cheque : confirmed) {
			boolean posted = ledger.postPair(cheque.getId(), batchId, "PAYEE_CREDIT", "SETTLEMENT:" + presenting,
					"CUSTOMER:" + cheque.getDepositorAccount(), cheque.getAmountPaise());
			if (posted) {
				jdbc.sql("update deposit_account set balance_paise = balance_paise + ? where account_no = ?")
					.params(cheque.getAmountPaise(), cheque.getDepositorAccount())
					.update();
			}
			cheque.settle(batchId);
			audit.record(cheque.getId(), user, "SETTLED", "Batch " + batchId + "; payee credited");
		}
		return batchId;
	}

	public record Batch(long id, OffsetDateTime settledAt, int itemCount, long totalPaise, String triggeredBy) {
	}

	/** Positive: the bank pays into settlement. Negative: it receives. */
	public record Position(String bankCode, String bankName, long netPaise) {
	}

	public List<Batch> batches() {
		return jdbc.sql("select id, settled_at, item_count, total_paise, triggered_by from settlement_batch order by id desc limit 50")
			.query(Batch.class)
			.list();
	}

	public List<Position> positions(long batchId) {
		return jdbc.sql("""
				select b.code as bank_code, b.name as bank_name,
				       coalesce(sum(l.credit_paise - l.debit_paise), 0) as net_paise
				from ledger_entry l
				join cheque c on c.id = l.cheque_id
				join bank b on l.account = 'SETTLEMENT:' || b.code
				where c.settlement_batch_id = ?
				group by b.code, b.name
				order by b.code""")
			.param(batchId)
			.query(Position.class)
			.list();
	}

}
