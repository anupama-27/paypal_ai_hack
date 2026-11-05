package com.cashflowai.domain;

import java.security.SecureRandom;
import java.util.Base64;

/** Unguessable tokens for customer links and agent access keys. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    public static String random() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
