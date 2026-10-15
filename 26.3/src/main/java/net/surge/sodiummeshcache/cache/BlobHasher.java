package net.surge.sodiummeshcache.cache;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content hash for the deduplicated blob store. Unlike {@link ContentHasher}
 * (a cache-validity key, where a collision only ever causes an extra miss),
 * a collision here would mean two genuinely different vertex buffers get
 * treated as the same blob and one of them silently serves the wrong
 * geometry to whichever sections reference it - a real correctness bug, not
 * just a missed optimization. SHA-256 (truncated to 128 bits for a shorter
 * filename) makes that practically impossible for this use case.
 */
public final class BlobHasher {
    private static final int TRUNCATED_BYTES = 16; // 128 bits

    private BlobHasher() {
    }

    public static String hash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] full = digest.digest(data);

            StringBuilder hex = new StringBuilder(TRUNCATED_BYTES * 2);
            for (int i = 0; i < TRUNCATED_BYTES; i++) {
                hex.append(String.format("%02x", full[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM implementation.
            throw new AssertionError(e);
        }
    }
}
