package com.cts.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Signs presented items so the drawee can prove who sent them and that nothing
 * changed in transit (CTS's end-to-end PKI).
 *
 * The key pair is generated at boot and the private half stays in memory; only
 * the public half is stored, so items signed by an earlier boot still verify.
 * In production the private key lives in an HSM and is reached through the
 * PKCS#11 provider: the JCA calls below stay the same, only the provider changes.
 */
@Service
public class SigningService {

	private static final String ALGORITHM = "SHA256withRSA";

	private final JdbcClient jdbc;
	private final KeyPair keyPair;
	private final String keyId;
	private final ConcurrentHashMap<String, PublicKey> publicKeys = new ConcurrentHashMap<>();

	public SigningService(JdbcClient jdbc) throws GeneralSecurityException {
		this.jdbc = jdbc;
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		this.keyPair = generator.generateKeyPair();
		this.keyId = UUID.randomUUID().toString();
		jdbc.sql("insert into signing_key (key_id, algorithm, public_key) values (?, ?, ?)")
			.params(keyId, ALGORITHM, keyPair.getPublic().getEncoded())
			.update();
		publicKeys.put(keyId, keyPair.getPublic());
	}

	public String keyId() {
		return keyId;
	}

	public byte[] sign(String data) {
		try {
			Signature signer = Signature.getInstance(ALGORITHM);
			signer.initSign(keyPair.getPrivate());
			signer.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return signer.sign();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Signing failed", ex);
		}
	}

	public boolean verify(String data, byte[] signature, String signedBy) {
		if (data == null || signature == null || signedBy == null) {
			return false;
		}
		try {
			Signature verifier = Signature.getInstance(ALGORITHM);
			verifier.initVerify(publicKey(signedBy));
			verifier.update(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException | RuntimeException ex) {
			return false;
		}
	}

	private PublicKey publicKey(String id) {
		return publicKeys.computeIfAbsent(id, k -> {
			byte[] encoded = jdbc.sql("select public_key from signing_key where key_id = ?")
				.param(k)
				.query(byte[].class)
				.single();
			try {
				return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
			}
			catch (GeneralSecurityException ex) {
				throw new IllegalStateException(ex);
			}
		});
	}

}
