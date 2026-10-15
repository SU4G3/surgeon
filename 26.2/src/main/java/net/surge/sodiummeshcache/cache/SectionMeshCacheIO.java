package net.surge.sodiummeshcache.cache;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Binary read/write of one section's cache entry, split into two parts for
 * content-addressed deduplication:
 *
 * <ul>
 *   <li><b>Pointer file</b> (one per section, tiny, kept per-world/per-save):
 *   the content-validity hash, occlusion data, and for each render pass its
 *   vertex segments plus a reference (blob hash) to where the actual vertex
 *   bytes live.</li>
 *   <li><b>Blob file</b> (one per distinct vertex buffer, named by its own
 *   SHA-256, stored deflate-compressed): the raw vertex bytes. This store is
 *   global - shared across every world and server this player has visited,
 *   not just the current one - because a block's mesh looks the same
 *   regardless of which save it's in. Many sections with identical geometry
 *   (flat stone layers, superflat worlds, repeated strip-mine tunnels, the
 *   same structure generated in different worlds) end up pointing at the
 *   very same blob instead of each storing their own copy.</li>
 * </ul>
 *
 * Pointer format (all big-endian):
 * <pre>
 * magic        4 bytes  "CSP1" (Chunk Surgeon Pointer v1)
 * contentHash  8 bytes
 * flags        4 bytes
 * visLen       4 bytes
 * visData      visLen * 8 bytes
 * passCount    4 bytes
 * for each pass:
 *   passIndex       4 bytes
 *   segmentsLen     4 bytes
 *   segments        segmentsLen * 4 bytes
 *   blobHash        32 bytes (64 hex chars, UTF-8)
 * </pre>
 *
 * Blob format:
 * <pre>
 * magic           4 bytes  "CSB1"
 * uncompressedLen 4 bytes
 * deflated bytes  (rest of file)
 * </pre>
 *
 * The stored {@code contentHash} is compared against the freshly computed
 * one on read, so a stale pointer is simply treated as a cache miss rather
 * than needing separate invalidation. A missing blob (evicted by the global
 * LRU budget, or disk corruption) is treated the same way - a pointer never
 * "owns" its blob, so evicting a blob out from under a pointer that still
 * references it is always safe, just a miss on next read.
 */
public final class SectionMeshCacheIO {
    private static final int POINTER_MAGIC = 0x43535031; // "CSP1"
    private static final int BLOB_MAGIC = 0x43534231; // "CSB1"

    private SectionMeshCacheIO() {
    }

    public static void write(Path pointerFile, Path blobsDir, CachedSectionMesh mesh) throws IOException {
        List<String> blobHashes = new ArrayList<>(mesh.passes.size());
        for (CachedPass pass : mesh.passes) {
            blobHashes.add(writeBlobIfAbsent(blobsDir, pass.vertexData));
        }

        Path tmp = pointerFile.resolveSibling(pointerFile.getFileName().toString() + ".tmp");
        Files.createDirectories(pointerFile.getParent());

        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(POINTER_MAGIC);
            out.writeLong(mesh.contentHash);
            out.writeInt(mesh.flags);

            out.writeInt(mesh.visibilityData.length);
            for (long v : mesh.visibilityData) {
                out.writeLong(v);
            }

            out.writeInt(mesh.passes.size());
            for (int i = 0; i < mesh.passes.size(); i++) {
                CachedPass pass = mesh.passes.get(i);

                out.writeInt(pass.passIndex);

                out.writeInt(pass.vertexSegments.length);
                for (int seg : pass.vertexSegments) {
                    out.writeInt(seg);
                }

                out.write(blobHashes.get(i).getBytes(StandardCharsets.US_ASCII));
            }
        }

        Files.move(tmp, pointerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String writeBlobIfAbsent(Path blobsDir, byte[] vertexData) throws IOException {
        // Hash is over the UNCOMPRESSED bytes: dedup must match on actual
        // content regardless of how (or whether) it's compressed on disk.
        String hash = BlobHasher.hash(vertexData);
        Path blobFile = blobsDir.resolve(hash + ".blob");

        if (Files.isRegularFile(blobFile)) {
            // Already present - just bump its LRU timestamp since this write
            // is effectively a fresh "use" of this content too.
            touch(blobFile);
            return hash;
        }

        Files.createDirectories(blobsDir);
        Path tmp = blobsDir.resolve(hash + ".blob.tmp." + Thread.currentThread().threadId());

        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(BLOB_MAGIC);
            out.writeInt(vertexData.length);

            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (var deflate = new DeflaterOutputStream(out, deflater)) {
                deflate.write(vertexData);
            } finally {
                deflater.end();
            }
        }

        // If another thread/session already wrote this exact blob between
        // our check and now, REPLACE_EXISTING just overwrites it with
        // byte-identical (re-encoded) content - harmless.
        Files.move(tmp, blobFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return hash;
    }

    private static byte[] readBlob(Path blobsDir, String hash) throws IOException {
        Path blobFile = blobsDir.resolve(hash + ".blob");
        if (!Files.isRegularFile(blobFile)) {
            return null;
        }

        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(blobFile)))) {
            if (in.readInt() != BLOB_MAGIC) {
                return null;
            }

            int uncompressedLen = in.readInt();
            byte[] result = new byte[uncompressedLen];

            Inflater inflater = new Inflater();
            try (var inflate = new InflaterInputStream(in, inflater)) {
                int off = 0;
                while (off < uncompressedLen) {
                    int n = inflate.read(result, off, uncompressedLen - off);
                    if (n < 0) {
                        return null;
                    }
                    off += n;
                }
            } finally {
                inflater.end();
            }

            touch(blobFile);
            return result;
        }
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // Not fatal - worst case this blob looks slightly staler than it
            // is for LRU eviction purposes.
        }
    }

    /**
     * Returns null if the pointer doesn't exist, is unreadable/corrupt, its
     * stored content hash doesn't match {@code expectedContentHash}, or any
     * of its referenced blobs is missing.
     */
    public static CachedSectionMesh readIfValid(Path pointerFile, Path blobsDir, long expectedContentHash) {
        if (!Files.isRegularFile(pointerFile)) {
            return null;
        }

        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(pointerFile)))) {
            if (in.readInt() != POINTER_MAGIC) {
                return null;
            }

            long storedHash = in.readLong();
            if (storedHash != expectedContentHash) {
                return null;
            }

            int flags = in.readInt();

            int visLen = in.readInt();
            long[] visibilityData = new long[visLen];
            for (int i = 0; i < visLen; i++) {
                visibilityData[i] = in.readLong();
            }

            int passCount = in.readInt();
            List<CachedPass> passes = new ArrayList<>(passCount);
            for (int i = 0; i < passCount; i++) {
                int passIndex = in.readInt();

                int segLen = in.readInt();
                int[] segments = new int[segLen];
                for (int j = 0; j < segLen; j++) {
                    segments[j] = in.readInt();
                }

                byte[] hashBytes = new byte[32];
                in.readFully(hashBytes);
                String blobHash = new String(hashBytes, StandardCharsets.US_ASCII);

                byte[] vertexData = readBlob(blobsDir, blobHash);
                if (vertexData == null) {
                    return null;
                }

                passes.add(new CachedPass(passIndex, segments, vertexData));
            }

            return new CachedSectionMesh(storedHash, flags, visibilityData, passes);
        } catch (IOException | RuntimeException e) {
            // Corrupt/truncated pointer (e.g. crash mid-write before the
            // rename landed): treat as a miss, not a hard failure.
            return null;
        }
    }
}
