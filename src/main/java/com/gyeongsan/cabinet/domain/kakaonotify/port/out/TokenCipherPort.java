package com.gyeongsan.cabinet.domain.kakaonotify.port.out;

public interface TokenCipherPort {

    String encrypt(String plaintext, String context);

    String decrypt(String ciphertext, String context);
}
