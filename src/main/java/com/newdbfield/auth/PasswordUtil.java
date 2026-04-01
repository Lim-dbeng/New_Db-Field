package com.newdbfield.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 비밀번호 암호화 유틸리티
 * 
 * BCrypt 사용을 권장하지만, 의존성 없이 사용할 수 있는 SHA-256 + Salt 방식도 제공
 * 
 * BCrypt 사용 시:
 * 1. pom.xml에 의존성 추가:
 *    <dependency>
 *        <groupId>org.mindrot</groupId>
 *        <artifactId>jbcrypt</artifactId>
 *        <version>0.4</version>
 *    </dependency>
 * 
 * 2. BCrypt 사용 예시:
 *    String hashed = BCrypt.hashpw(password, BCrypt.gensalt());
 *    boolean valid = BCrypt.checkpw(password, hashed);
 */
public class PasswordUtil {
	
	private static final String ALGORITHM = "SHA-256";
	private static final int SALT_LENGTH = 16;
	
	/**
	 * 비밀번호 해싱 (SHA-256 + Salt)
	 * @param password 평문 비밀번호
	 * @return 해시된 비밀번호 (salt:hash 형식)
	 */
	public static String hashPassword(String password) {
		try {
			// Salt 생성
			SecureRandom random = new SecureRandom();
			byte[] salt = new byte[SALT_LENGTH];
			random.nextBytes(salt);
			
			// 비밀번호 + Salt 해싱
			MessageDigest md = MessageDigest.getInstance(ALGORITHM);
			md.update(salt);
			byte[] hash = md.digest(password.getBytes("UTF-8"));
			
			// Salt와 Hash를 Base64로 인코딩하여 결합
			String saltBase64 = Base64.getEncoder().encodeToString(salt);
			String hashBase64 = Base64.getEncoder().encodeToString(hash);
			
			return saltBase64 + ":" + hashBase64;
		} catch (Exception e) {
			throw new RuntimeException("비밀번호 해싱 실패", e);
		}
	}
	
	/**
	 * 비밀번호 검증
	 * @param password 평문 비밀번호
	 * @param hashedPassword 해시된 비밀번호 (salt:hash 형식)
	 * @return 검증 성공 여부
	 */
	public static boolean verifyPassword(String password, String hashedPassword) {
		try {
			if (hashedPassword == null || !hashedPassword.contains(":")) {
				return false;
			}
			
			// Salt와 Hash 분리
			String[] parts = hashedPassword.split(":", 2);
			if (parts.length != 2) {
				return false;
			}
			
			byte[] salt = Base64.getDecoder().decode(parts[0]);
			byte[] storedHash = Base64.getDecoder().decode(parts[1]);
			
			// 입력된 비밀번호 해싱
			MessageDigest md = MessageDigest.getInstance(ALGORITHM);
			md.update(salt);
			byte[] computedHash = md.digest(password.getBytes("UTF-8"));
			
			// 해시 비교 (타이밍 공격 방지를 위해 MessageDigest.isEqual 사용 권장)
			return MessageDigest.isEqual(storedHash, computedHash);
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * 기존 평문 비밀번호를 해시로 변환 (마이그레이션용)
	 * @param plainPassword 평문 비밀번호
	 * @return 해시된 비밀번호
	 */
	public static String migratePassword(String plainPassword) {
		return hashPassword(plainPassword);
	}
	
	/**
	 * 비밀번호가 이미 해시된 형식인지 확인
	 * @param password 비밀번호
	 * @return 해시된 형식이면 true
	 */
	public static boolean isHashed(String password) {
		return password != null && password.contains(":") && password.split(":").length == 2;
	}
}

