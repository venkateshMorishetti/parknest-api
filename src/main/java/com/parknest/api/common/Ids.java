package com.parknest.api.common;

import java.security.SecureRandom;

/**
 * IDs like "usr_01JC8X4M2QX3Y5Z7A9B1C3D5E7": a type prefix and a ULID (sortable by creation time, 26 characters).
 */
public final class Ids {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String newId(String prefix) {
        return prefix + "_" + ulid();
    }

    static String ulid() {
        char[] out = new char[26];
        long time = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = CROCKFORD[RANDOM.nextInt(32)];
        }
        return new String(out);
    }
}
