package com.cts.cheque;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
public class Cheque {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String serial;
	private String micrCode;
	private String accountNo;
	private String txCode;
	private long amountPaise;
	private LocalDate chequeDate;
	private String payeeName;
	private String depositorAccount;

	@Enumerated(EnumType.STRING)
	private ChequeStatus status;
	private String statusReason;
	private String returnCode;
	private boolean deemed;

	private String dedupeKey;
	private String dataHash;
	private byte[] signature;
	private String signingKeyId;

	private String capturedBy;
	private String approvedBy;
	private String decidedBy;

	@Column(insertable = false, updatable = false)
	private Instant capturedAt;
	private Instant presentedAt;
	private Instant expiresAt;
	private Instant decidedAt;
	private Long settlementBatchId;

	@Version
	private long version;

	protected Cheque() {
	}

	public Cheque(MicrLine micr, long amountPaise, LocalDate chequeDate, String payeeName, String depositorAccount,
			String capturedBy) {
		this.serial = micr.serial();
		this.micrCode = micr.micrCode();
		this.accountNo = micr.accountNo();
		this.txCode = micr.txCode();
		this.amountPaise = amountPaise;
		this.chequeDate = chequeDate;
		this.payeeName = payeeName;
		this.depositorAccount = depositorAccount;
		this.capturedBy = capturedBy;
		this.dedupeKey = micr.dedupeKey();
	}

	public MicrLine micr() {
		return new MicrLine(serial, micrCode, accountNo, txCode);
	}

	public String draweeBankCode() {
		return micr().bankCode();
	}

	void reject(String reason) {
		this.status = ChequeStatus.REJECTED;
		this.statusReason = reason;
	}

	void hold(String reason) {
		this.status = ChequeStatus.PENDING_APPROVAL;
		this.statusReason = reason;
	}

	void ready() {
		this.status = ChequeStatus.READY;
		this.statusReason = null;
	}

	void approve(String checker) {
		this.approvedBy = checker;
		ready();
	}

	public void present(String dataHash, byte[] signature, String keyId, Instant at, Instant expiresAt) {
		this.status = ChequeStatus.PRESENTED;
		this.dataHash = dataHash;
		this.signature = signature;
		this.signingKeyId = keyId;
		this.presentedAt = at;
		this.expiresAt = expiresAt;
	}

	public void confirm(String by, boolean deemed, Instant at) {
		this.status = ChequeStatus.CONFIRMED;
		this.decidedBy = by;
		this.deemed = deemed;
		this.decidedAt = at;
	}

	public void returnItem(String code, String reason, String by, Instant at) {
		this.status = ChequeStatus.RETURNED;
		this.returnCode = code;
		this.statusReason = reason;
		this.decidedBy = by;
		this.decidedAt = at;
	}

	public void settle(long batchId) {
		this.status = ChequeStatus.SETTLED;
		this.settlementBatchId = batchId;
	}

	public Long getId() { return id; }
	public String getSerial() { return serial; }
	public String getMicrCode() { return micrCode; }
	public String getAccountNo() { return accountNo; }
	public String getTxCode() { return txCode; }
	public long getAmountPaise() { return amountPaise; }
	public LocalDate getChequeDate() { return chequeDate; }
	public String getPayeeName() { return payeeName; }
	public String getDepositorAccount() { return depositorAccount; }
	public ChequeStatus getStatus() { return status; }
	public String getStatusReason() { return statusReason; }
	public String getReturnCode() { return returnCode; }
	public boolean isDeemed() { return deemed; }
	public String getDedupeKey() { return dedupeKey; }
	public String getDataHash() { return dataHash; }
	public byte[] getSignature() { return signature; }
	public String getSigningKeyId() { return signingKeyId; }
	public String getCapturedBy() { return capturedBy; }
	public String getApprovedBy() { return approvedBy; }
	public String getDecidedBy() { return decidedBy; }
	public Instant getCapturedAt() { return capturedAt; }
	public Instant getPresentedAt() { return presentedAt; }
	public Instant getExpiresAt() { return expiresAt; }
	public Instant getDecidedAt() { return decidedAt; }
	public Long getSettlementBatchId() { return settlementBatchId; }

}
