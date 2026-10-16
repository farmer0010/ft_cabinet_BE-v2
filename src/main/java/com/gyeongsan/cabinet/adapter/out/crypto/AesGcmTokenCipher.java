package com.gyeongsan.cabinet.adapter.out.crypto;

import com.gyeongsan.cabinet.domain.kakaonotify.model.TokenDecryptionException;
import com.gyeongsan.cabinet.domain.kakaonotify.port.out.TokenCipherPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AesGcmTokenCipher implements TokenCipherPort {

    private static final String PREFIX = "v1.";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmTokenCipher(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("암호화 키는 base64 로 인코딩한 32바이트 값이어야 합니다.");
        }
        if (raw.length != 32) {
            Arrays.fill(raw, (byte) 0);
            throw new IllegalArgumentException("암호화 키는 base64 로 인코딩한 32바이트 값이어야 합니다.");
        }
        this.key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
    }

    @Override
    public String encrypt(String plaintext, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("토큰 암호화에 실패했습니다.", e);
        }
    }

    @Override
    public String decrypt(String ciphertext, String context) {
        try {
            if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
                throw new IllegalArgumentException("알 수 없는 암호문 형식");
            }
            byte[] in = Base64.getUrlDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (in.length <= IV_BYTES) {
                throw new IllegalArgumentException("암호문이 너무 짧음");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(in, 0, IV_BYTES)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TokenDecryptionException("토큰을 복호화할 수 없습니다(키가 바뀌었거나 값이 손상됨).", e);
        }
    }
}
