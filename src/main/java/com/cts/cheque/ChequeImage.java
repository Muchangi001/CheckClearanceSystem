package com.cts.cheque;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Stored in Postgres for the MVP so images survive container restarts.
 * Production: object storage, with only the reference and hash kept here.
 */
@Entity
public class ChequeImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long chequeId;
	private String side;
	private String contentType;
	private String sha256;
	private byte[] data;

	protected ChequeImage() {
	}

	public ChequeImage(Long chequeId, String side, String contentType, String sha256, byte[] data) {
		this.chequeId = chequeId;
		this.side = side;
		this.contentType = contentType;
		this.sha256 = sha256;
		this.data = data;
	}

	public String getSide() { return side; }
	public String getContentType() { return contentType; }
	public String getSha256() { return sha256; }
	public byte[] getData() { return data; }

}
