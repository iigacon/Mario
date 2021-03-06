package com.nhb.common.encrypt.aes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;

import com.nhb.common.encrypt.exception.VerificationFailureException;
import com.nhb.common.encrypt.utils.EncryptionUtils;

public final class AESEncryptor {

	private static final int KEY_BYTE_SIZE = 16;
	private static final int IV_SIZE = 16;

	private static final String AES = "AES";
	private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
	private static final byte[] ENCRYPTION_INFO = System
			.getProperty("encryption.aes.encryptionInfo", "NHBCommonEncryption|KeyForEncryption").getBytes();
	private static final byte[] AUTHENTICATION_INFO = System
			.getProperty("encryption.aes.authenticationInfo", "NHBCommonEncryption|KeyForAuthentication").getBytes();

	private static byte[] decodeBase64(String base64Data) {
		return Base64.decodeBase64(base64Data);
	}

	private static String encodeBase64(byte[] bytes) {
		return Base64.encodeBase64String(bytes);
	}

	private static IvParameterSpec randomIV() {
		return new IvParameterSpec(EncryptionUtils.randomBytes(KEY_BYTE_SIZE));
	}

	public static final AESEncryptor newInstance() {
		return new AESEncryptor();
	}

	public static SecretKey deriveKeyFromPassword(byte[] password) {
		try {
			return new SecretKeySpec(EncryptionUtils.hkdf(password, KEY_BYTE_SIZE, ENCRYPTION_INFO, null), AES);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public static byte[] generatePassword() {
		return EncryptionUtils.randomBytes(KEY_BYTE_SIZE);
	}

	public static String generatePasswordBase64() {
		return encodeBase64(generatePassword());
	}

	private final Cipher encryptionCipher;
	private final Cipher decryptionCipher;
	private byte[] password;

	private AESEncryptor() {
		try {
			this.encryptionCipher = Cipher.getInstance(TRANSFORMATION);
			this.decryptionCipher = Cipher.getInstance(TRANSFORMATION);
		} catch (Exception ex) {
			throw new RuntimeException("Create ciphers error", ex);
		}
	}

	public SecretKey getSecretKey() {
		return deriveKeyFromPassword(this.getPassword());
	}

	public String getPasswordAsString() {
		return new String(this.getPassword());
	}

	public String getPasswordBase64() {
		return encodeBase64(this.getPassword());
	}

	public byte[] getPassword() {
		return password;
	}

	public void setPassword(byte[] password) {
		this.password = password;
	}

	public void setPassword(String password) {
		this.password = password.getBytes();
	}

	public void setPasswordBase64(String base64Password) {
		this.password = decodeBase64(base64Password);
	}

	/***************************************************************************/
	/*
	 * -------------------------------- ENCRYPTION -----------------------------
	 */
	/***************************************************************************/
	private synchronized byte[] plainEncrypt(byte[] data, SecretKey secretKey, IvParameterSpec iv) {
		try {
			encryptionCipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);
			return encryptionCipher.doFinal(data);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public byte[] encrypt(byte[] data) {
		try {
			// Generate a sub-key for encryption
			byte[] eKey = EncryptionUtils.hkdf(this.getPassword(), KEY_BYTE_SIZE, ENCRYPTION_INFO, null);

			// Generate a random initialization vector.
			IvParameterSpec iv = randomIV();
			byte[] encryptedData = plainEncrypt(data, new SecretKeySpec(eKey, AES), iv);

			// combine iv and encrypted data into one block, use to create auth
			// data
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			outputStream.write(iv.getIV());
			outputStream.write(encryptedData);

			// Generate a sub-key for authentication and apply the HMAC.
			byte[] aKey = EncryptionUtils.hkdf(password, KEY_BYTE_SIZE, AUTHENTICATION_INFO, null);
			byte[] auth = EncryptionUtils.hashHmacSHA256(outputStream.toByteArray(), aKey);

			outputStream = new ByteArrayOutputStream();
			outputStream.write(auth);
			outputStream.write(iv.getIV());
			outputStream.write(encryptedData);
			return outputStream.toByteArray();

		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public String encryptToBase64(byte[] data) {
		return encodeBase64(encrypt(data));
	}

	public synchronized byte[] encryptSimple(byte[] data) {
		try {
			System.out.println("------- start encryption -------");
			long startTime = System.currentTimeMillis();
			IvParameterSpec iv = randomIV();
			System.out.println("Create iv time: " + (System.currentTimeMillis() - startTime) + "ms");
			startTime = System.currentTimeMillis();
			encryptionCipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), iv);
			System.out.println("Init cipher time: " + (System.currentTimeMillis() - startTime) + "ms");
			startTime = System.currentTimeMillis();
			byte[] encryptedData = encryptionCipher.doFinal(data);
			System.out.println("Encryption time: " + (System.currentTimeMillis() - startTime) + "ms");
			startTime = System.currentTimeMillis();
			ByteArrayOutputStream out = null;
			try {
				out = new ByteArrayOutputStream();
				out.write(iv.getIV());
				out.write(encryptedData);
				return out.toByteArray();
			} finally {
				System.out.println("Create output time: " + (System.currentTimeMillis() - startTime) + "ms");
				if (out != null) {
					out.close();
				}
				System.out.println("--------------------------------");
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public String encryptSimpleToBase64(byte[] data) {
		return encodeBase64(encryptSimple(data));
	}

	/***************************************************************************/
	/*
	 * -------------------------------- DECRYPTION -----------------------------
	 */
	/***************************************************************************/
	private synchronized byte[] plainDecrypt(byte[] data, SecretKey secretKey, IvParameterSpec iv) {
		try {
			decryptionCipher.init(Cipher.DECRYPT_MODE, secretKey, iv);
			return decryptionCipher.doFinal(data);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public byte[] decrypt(byte[] data) {
		try {
			// Extract the HMAC from the front of the ciphertext.
			ByteArrayInputStream stream = new ByteArrayInputStream(data);
			byte[] hmac = new byte[EncryptionUtils.BLOCKSIZE];
			stream.read(hmac);

			byte[] cipherText = new byte[stream.available()];
			stream.read(cipherText);

			// Regenerate the same authentication sub-key.
			byte[] authKey = EncryptionUtils.hkdf(this.getPassword(), KEY_BYTE_SIZE, AUTHENTICATION_INFO, null);

			if (EncryptionUtils.verifyHMAC(hmac, cipherText, authKey)) {
				stream = new ByteArrayInputStream(cipherText);

				// Regenerate the same encryption sub-key.
				byte[] encryptionKey = EncryptionUtils.hkdf(this.getPassword(), KEY_BYTE_SIZE, ENCRYPTION_INFO, null);

				byte[] iv = new byte[IV_SIZE];
				stream.read(iv);

				byte[] _data = new byte[stream.available()];
				stream.read(_data);

				return plainDecrypt(_data, new SecretKeySpec(encryptionKey, AES), new IvParameterSpec(iv));
			} else {
				throw new VerificationFailureException();
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public byte[] decryptFromBase64(String base64Data) {
		return decrypt(decodeBase64(base64Data));
	}

	public synchronized byte[] decryptSimple(byte[] data) {
		try {
			ByteArrayInputStream in = null;
			try {
				in = new ByteArrayInputStream(data);

				byte[] iv = new byte[KEY_BYTE_SIZE];
				in.read(iv);

				byte[] encryptedData = new byte[in.available()];
				in.read(encryptedData);

				decryptionCipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new IvParameterSpec(iv));
				return decryptionCipher.doFinal(encryptedData);
			} finally {
				if (in != null) {
					in.close();
				}
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public byte[] decryptSimpleFromBase64(String base64Data) {
		return decryptSimple(decodeBase64(base64Data));
	}
}
