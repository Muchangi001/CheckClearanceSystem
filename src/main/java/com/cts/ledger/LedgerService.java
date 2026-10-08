package com.cts.ledger;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Double-entry postings for a cheque's money movement.
 *
 * <pre>
 * confirm (drawee's books):     Dr DRAWER:&lt;micr&gt;/&lt;acct&gt;        Cr SETTLEMENT:&lt;drawee bank&gt;
 * settle  (presenting's books): Dr SETTLEMENT:&lt;presenting bank&gt;  Cr CUSTOMER:&lt;depositor&gt;
 * </pre>
 *
 * Each pair balances on its own, and across banks the SETTLEMENT accounts net
 * to zero: that net is what moves between banks' accounts at RBI.
 * Posting is idempotent: (cheque_id, entry_type) is unique, so a replay posts nothing.
 */
@Service
public class LedgerService {

	private final JdbcClient jdbc;

	public LedgerService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return false if this pair was already posted */
	public boolean postPair(long chequeId, Long batchId, String type, String debitAccount, String creditAccount,
			long amountPaise) {
		// ON CONFLICT rather than catching the duplicate-key error: in Postgres a
		// failed statement aborts the whole transaction.
		if (insert(chequeId, batchId, type + "_DR", debitAccount, amountPaise, 0) == 0) {
			return false;
		}
		insert(chequeId, batchId, type + "_CR", creditAccount, 0, amountPaise);
		return true;
	}

	private int insert(long chequeId, Long batchId, String type, String account, long debit, long credit) {
		return jdbc.sql("""
				insert into ledger_entry (cheque_id, settlement_batch_id, entry_type, account, debit_paise, credit_paise)
				values (?, ?, ?, ?, ?, ?)
				on conflict (cheque_id, entry_type) do nothing""")
			.params(chequeId, batchId, type, account, debit, credit)
			.update();
	}

	public List<Entry> recent() {
		return jdbc.sql("""
				select id, cheque_id, settlement_batch_id, entry_type, account, debit_paise, credit_paise, posted_at
				from ledger_entry order by id desc limit 200""")
			.query(Entry.class)
			.list();
	}

	public Totals totals() {
		return jdbc.sql("select coalesce(sum(debit_paise), 0) as debits, coalesce(sum(credit_paise), 0) as credits from ledger_entry")
			.query(Totals.class)
			.single();
	}

	public List<Balance> balances() {
		return jdbc.sql("""
				select account, sum(debit_paise) as debits, sum(credit_paise) as credits
				from ledger_entry group by account order by account""")
			.query(Balance.class)
			.list();
	}

	public record Entry(long id, long chequeId, Long settlementBatchId, String entryType, String account,
			long debitPaise, long creditPaise, OffsetDateTime postedAt) {
	}

	public record Totals(long debits, long credits) {
		public boolean balanced() {
			return debits == credits;
		}
	}

	public record Balance(String account, long debits, long credits) {
		public long net() {
			return debits - credits;
		}
	}

}
