package com.vastu.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Saves uploaded files to a configured directory on disk.
 *
 * Directory structure:
 *   {base-dir}/
 *     {yyyy}/{MM}/{dd}/
 *       {uuid}.{ext}
 *
 * Swap this class for an S3-backed implementation by implementing the same
 * interface – everything else stays the same.
 *
 * Security hardening:
 *  • Allowed MIME types whitelist (rejects unknown types server-side)
 *  • Filename is replaced with a random UUID (no path traversal possible)
 *  • Extension is taken from the original filename only if it's on the whitelist
 */
@Slf4j
@Service
public class FileStorageService {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "heic", "heif", "pdf");

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "image/heic", "image/heif", "application/pdf"
    );

    @Value("${app.storage.base-dir:./uploads}")
    private String baseDir;

    @Value("${app.storage.max-file-size-bytes:10485760}")
    private long maxFileSizeBytes;

    @PostConstruct
    public void init() throws IOException {
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Files.createDirectories(base);
        log.info("[FileStorage] Storage root: {}", base);
    }

    /**
     * Validates and persists a MultipartFile.
     *
     * @param file      the uploaded file
     * @param fieldName used in error messages only
     * @return relative path string (stored in DB), e.g. "2025/06/15/abc123.jpg"
     * @throws IllegalArgumentException on validation failure
     * @throws IOException              on I/O failure
     */
    public String store(MultipartFile file, String fieldName) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(fieldName + ": file is empty or missing");
        }

        // Size check (server-side guard in addition to frontend validation)
        if (file.getSize() > maxFileSizeBytes) {
            throw new IllegalArgumentException(fieldName + ": file exceeds maximum allowed size of "
                    + (maxFileSizeBytes / 1_048_576) + " MB");
        }

        // MIME type check
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(fieldName + ": unsupported file type '" + contentType + "'");
        }

        // Extension check
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = FilenameUtils.getExtension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            // Fall back to deriving extension from MIME type
            ext = mimeToExt(contentType);
        }

        // Build date-sharded path
        LocalDate today = LocalDate.now();
        String relativePath = String.format("%d/%02d/%02d/%s.%s",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID(), ext);

        Path destination = Path.of(baseDir).toAbsolutePath().normalize()
                .resolve(relativePath).normalize();

        // Safety: ensure resolved path is still inside baseDir
        if (!destination.startsWith(Path.of(baseDir).toAbsolutePath().normalize())) {
            throw new SecurityException("Path traversal attempt detected for field: " + fieldName);
        }

        Files.createDirectories(destination.getParent());
        Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

        log.info("[FileStorage] Stored {} ({} KB) → {}", fieldName,
                file.getSize() / 1024, relativePath);
        return relativePath;
    }

    /**
     * Resolves a relative path (from DB) to an absolute Path on disk.
     * Used when sending files to Telegram.
     */
    public Path resolve(String relativePath) {
        return Path.of(baseDir).toAbsolutePath().normalize()
                .resolve(relativePath).normalize();
    }

    /**
     * Resolves multiple relative paths, filtering out nulls.
     */
    public List<Path> resolveAll(String... relativePaths) {
        return java.util.Arrays.stream(relativePaths)
                .filter(p -> p != null && !p.isBlank())
                .map(this::resolve)
                .filter(Files::exists)
                .toList();
    }

    // ── Helpers ────────────────────────────────────────────────

    private String mimeToExt(String mime) {
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png"               -> "png";
            case "image/webp"              -> "webp";
            case "image/heic"              -> "heic";
            case "image/heif"              -> "heif";
            case "application/pdf"         -> "pdf";
            default                        -> "bin";
        };
    }
}
