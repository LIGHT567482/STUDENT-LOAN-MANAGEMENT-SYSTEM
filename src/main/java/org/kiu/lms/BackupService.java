package org.kiu.lms;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupService {
    private static final byte[] MAGIC = "KIUMBK01".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PACKAGE_MAGIC = "KIUEVD01".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int ITERATIONS = 220_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final LmsService service;

    public BackupService(LmsService service) {
        this.service = service;
    }

    public void create(User actor, Path target, char[] passphrase) throws IOException, GeneralSecurityException {
        requirePassphrase(passphrase);
        try (Connection connection = service.database().open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (java.sql.SQLException exception) {
            throw new IOException("Could not prepare the database for backup.", exception);
        }
        byte[] databaseBytes = Files.readAllBytes(service.database().file());
        byte[] packageBytes = packageBackup(databaseBytes,
                service.database().file().getParent().resolve("application-evidence"));
        byte[] salt = randomBytes(SALT_LENGTH);
        byte[] nonce = randomBytes(NONCE_LENGTH);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, packageBytes, passphrase, salt, nonce);
        ByteBuffer file = ByteBuffer.allocate(MAGIC.length + salt.length + nonce.length + encrypted.length);
        file.put(MAGIC).put(salt).put(nonce).put(encrypted);
        Path absoluteTarget = target.toAbsolutePath();
        Path temporary = absoluteTarget.resolveSibling(absoluteTarget.getFileName() + ".tmp");
        try {
            Files.write(temporary, file.array());
            replaceFile(temporary, absoluteTarget);
        } finally {
            Files.deleteIfExists(temporary);
            Arrays.fill(databaseBytes, (byte) 0);
            Arrays.fill(packageBytes, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
        service.recordBackupEvent(actor, absoluteTarget.getFileName().toString());
    }

    public Path restore(User actor, Path source, char[] passphrase)
            throws IOException, GeneralSecurityException {
        requirePassphrase(passphrase);
        byte[] file = Files.readAllBytes(source);
        int headerLength = MAGIC.length + SALT_LENGTH + NONCE_LENGTH;
        if (file.length <= headerLength || !Arrays.equals(MAGIC, Arrays.copyOf(file, MAGIC.length))) {
            throw new IOException("This is not a valid KIU encrypted backup.");
        }
        byte[] salt = Arrays.copyOfRange(file, MAGIC.length, MAGIC.length + SALT_LENGTH);
        byte[] nonce = Arrays.copyOfRange(file, MAGIC.length + SALT_LENGTH, headerLength);
        byte[] ciphertext = Arrays.copyOfRange(file, headerLength, file.length);
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, passphrase, salt, nonce);
        Path database = service.database().file();
        String suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path previous = database.resolveSibling("loans.db.pre-restore-" + suffix);
        Path temporary = database.resolveSibling("loans.db.restore.tmp");
        Path evidenceRoot = database.getParent().resolve("application-evidence");
        Path staging = database.getParent().resolve("application-evidence.restore.tmp-" + suffix);
        Path previousEvidence = database.getParent().resolve("application-evidence.pre-restore-" + suffix);
        boolean packaged = Arrays.equals(PACKAGE_MAGIC,
                Arrays.copyOf(plaintext, Math.min(PACKAGE_MAGIC.length, plaintext.length)));
        boolean legacyDatabase = plaintext.length >= SQLITE_HEADER.length
                && Arrays.equals(SQLITE_HEADER, Arrays.copyOf(plaintext, SQLITE_HEADER.length));
        if (!packaged && !legacyDatabase) {
            Arrays.fill(plaintext, (byte) 0);
            throw new IOException("The backup contents are invalid.");
        }
        boolean oldEvidenceMoved = false;
        boolean databaseReplaced = false;
        try {
            if (packaged) {
                unzipBackup(plaintext, staging);
                Path packagedDatabase = staging.resolve("loans.db");
                if (!Files.isRegularFile(packagedDatabase, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("The backup does not contain a database.");
                }
                Files.move(packagedDatabase, temporary, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.write(temporary, plaintext);
                Files.createDirectories(staging.resolve("application-evidence"));
            }
            validateDatabase(temporary);
            validateEvidenceReferences(temporary,
                    packaged ? staging.resolve("application-evidence") : staging.resolve("application-evidence"));
            try (Connection connection = service.database().open(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (java.sql.SQLException exception) {
                throw new IOException("Could not prepare the current database for restore.", exception);
            }
            Files.copy(database, previous, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(evidenceRoot, LinkOption.NOFOLLOW_LINKS)) {
                movePath(evidenceRoot, previousEvidence);
                oldEvidenceMoved = true;
            }
            replaceFile(temporary, database);
            databaseReplaced = true;
            Path stagedEvidence = staging.resolve("application-evidence");
            if (Files.exists(stagedEvidence, LinkOption.NOFOLLOW_LINKS)) {
                movePath(stagedEvidence, evidenceRoot);
            }
            try {
                new Database();
            } catch (java.sql.SQLException exception) {
                throw new IOException("The restored database could not be migrated to the current schema.", exception);
            }
            Files.deleteIfExists(database.resolveSibling("loans.db-wal"));
            Files.deleteIfExists(database.resolveSibling("loans.db-shm"));
        } catch (IOException | RuntimeException exception) {
            if (databaseReplaced && Files.exists(previous)) {
                Files.copy(previous, database, StandardCopyOption.REPLACE_EXISTING);
            }
            if (databaseReplaced && Files.exists(evidenceRoot, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(evidenceRoot);
            }
            if (oldEvidenceMoved && Files.exists(previousEvidence, LinkOption.NOFOLLOW_LINKS)) {
                movePath(previousEvidence, evidenceRoot);
            }
            throw exception;
        } finally {
            Files.deleteIfExists(temporary);
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(staging);
            }
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(file, (byte) 0);
        }
        service.recordRestoreEvent(actor, source.getFileName().toString());
        return previous;
    }

    private byte[] packageBackup(byte[] databaseBytes, Path evidenceRoot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("loans.db"));
            zip.write(databaseBytes);
            zip.closeEntry();
            if (Files.exists(evidenceRoot, LinkOption.NOFOLLOW_LINKS)) {
                try (var files = Files.walk(evidenceRoot)) {
                    for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .sorted().toList()) {
                        if (Files.isSymbolicLink(file)) {
                            throw new IOException("A symbolic link was found in the evidence folder.");
                        }
                        zip.putNextEntry(new ZipEntry("application-evidence/"
                                + evidenceRoot.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, zip);
                        zip.closeEntry();
                    }
                }
            }
            zip.finish();
        }
        ByteBuffer packageBytes = ByteBuffer.allocate(PACKAGE_MAGIC.length + bytes.size());
        packageBytes.put(PACKAGE_MAGIC).put(bytes.toByteArray());
        return packageBytes.array();
    }

    private void unzipBackup(byte[] packageBytes, Path staging) throws IOException {
        Files.createDirectories(staging.resolve("application-evidence"));
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(packageBytes,
                PACKAGE_MAGIC.length, packageBytes.length - PACKAGE_MAGIC.length))) {
            ZipEntry entry;
            int entries = 0;
            long totalBytes = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 20_000 || entry.isDirectory()) {
                    throw new IOException("The backup contains too many or unsupported entries.");
                }
                String name = entry.getName();
                if (!"loans.db".equals(name) && !name.startsWith("application-evidence/")) {
                    throw new IOException("The backup contains an unexpected file.");
                }
                Path destination = staging.resolve(name).normalize();
                if (!destination.startsWith(staging.normalize())) {
                    throw new IOException("The backup contains an unsafe file path.");
                }
                Files.createDirectories(destination.getParent());
                try (var output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > 2L * 1024 * 1024 * 1024) {
                            throw new IOException("The backup expands beyond the supported 2 GB limit.");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private void validateEvidenceReferences(Path database, Path evidenceRoot) throws IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            try (ResultSet table = statement.executeQuery("""
                    SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='application_evidence'""")) {
                if (!table.next() || table.getInt(1) == 0) {
                    return;
                }
            }
            try (ResultSet files = statement.executeQuery("SELECT stored_path FROM application_evidence")) {
                while (files.next()) {
                    Path relative = Path.of(files.getString(1).replace('\\', '/')).normalize();
                    if (relative.isAbsolute() || relative.getNameCount() < 2
                            || !"application-evidence".equals(relative.getName(0).toString())
                            || !Files.isRegularFile(evidenceRoot.resolve(relative.subpath(1, relative.getNameCount())),
                            LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("The backup is missing evidence referenced by its database.");
                    }
                }
            }
        } catch (java.sql.SQLException | java.nio.file.InvalidPathException exception) {
            throw new IOException("The backup evidence could not be validated.", exception);
        }
    }

    private void movePath(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private byte[] crypt(int mode, byte[] input, char[] passphrase, byte[] salt, byte[] nonce)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
                return cipher.doFinal(input);
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private void validateDatabase(Path candidate) throws IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + candidate);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new IOException("The backup database failed its integrity check.");
            }
            try (ResultSet tables = statement.executeQuery("""
                    SELECT COUNT(*) FROM sqlite_master WHERE type='table'
                    AND name IN ('users','students','loan_products','settings','accounts')""")) {
                if (!tables.next() || tables.getInt(1) != 5) {
                    throw new IOException("The backup does not contain the required KIU loan data.");
                }
            }
        } catch (java.sql.SQLException exception) {
            throw new IOException("The backup database could not be validated.", exception);
        }
    }

    private void replaceFile(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requirePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < 8) {
            throw new IllegalArgumentException("The backup passphrase must contain at least 8 characters.");
        }
    }
}
