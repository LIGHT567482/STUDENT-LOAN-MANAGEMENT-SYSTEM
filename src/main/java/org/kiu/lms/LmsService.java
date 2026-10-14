package org.kiu.lms;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

public final class LmsService {
    private static final MathContext CALCULATION = new MathContext(18, RoundingMode.HALF_UP);
    private static final long MAX_EVIDENCE_FILE_BYTES = 15L * 1024 * 1024;
    private static final long MAX_EVIDENCE_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ACADEMIC_DOCUMENTS = 10;
    private final Database database;

    public LmsService(Database database) {
        this.database = database;
    }

    public Database database() {
        return database;
    }

    public boolean requiresFirstAdministrator() {
        return scalarLong("SELECT COUNT(*) FROM users") == 0;
    }

    public User createFirstAdministrator(String displayName, String username, char[] password) {
        try {
            validateIdentity(displayName, username, password);
            return transaction(connection -> {
                if (scalarLong(connection, "SELECT COUNT(*) FROM users") != 0) {
                    throw new IllegalStateException("An administrator account already exists.");
                }
                long id = insertUser(connection, displayName, username, Role.ADMINISTRATOR, password);
                User user = new User(id, username, displayName, Role.ADMINISTRATOR, true);
                audit(connection, user.id(), "CREATE", "USER", Long.toString(id), "Initial administrator created");
                return user;
            });
        } finally {
            clearPassword(password);
        }
    }

    public User login(String username, char[] password) {
        try {
            return transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,username,display_name,role,salt,password_hash,active FROM users WHERE username=?")) {
                statement.setString(1, username.trim());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt("active") != 1
                            || !Passwords.matches(password, result.getBytes("salt"), result.getBytes("password_hash"))) {
                        throw new IllegalArgumentException("The username or password is incorrect, or the account is inactive.");
                    }
                    User user = new User(result.getLong("id"), result.getString("username"),
                            result.getString("display_name"), Role.valueOf(result.getString("role")), true);
                    insertAudit(connection, user.id(), "LOGIN", "USER", Long.toString(user.id()), "Successful sign-in");
                    return user;
                }
            }
            });
        } finally {
            clearPassword(password);
        }
    }

    public List<Map<String, Object>> users() {
        return query("SELECT id,username,display_name AS name,role,CASE active WHEN 1 THEN 'Active' ELSE 'Inactive' END AS status "
                + "FROM users ORDER BY display_name");
    }

    public void createUser(User actor, String displayName, String username, Role role, char[] password) {
        try {
            require(actor, Role.ADMINISTRATOR);
            validateIdentity(displayName, username, password);
            if (role == null) {
                throw new IllegalArgumentException("Select a user role.");
            }
            transaction(connection -> {
                long id = insertUser(connection, displayName, username, role, password);
                audit(connection, actor.id(), "CREATE", "USER", Long.toString(id), "Created " + role + " account " + username);
                return null;
            });
        } finally {
            clearPassword(password);
        }
    }

    public void resetPassword(User actor, long userId, char[] password) {
        try {
            require(actor, Role.ADMINISTRATOR);
            if (password == null || password.length < 8) {
                throw new IllegalArgumentException("Passwords must contain at least 8 characters.");
            }
            transaction(connection -> {
                byte[] salt = Passwords.newSalt();
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET salt=?,password_hash=? WHERE id=?")) {
                    statement.setBytes(1, salt);
                    statement.setBytes(2, Passwords.hash(password, salt));
                    statement.setLong(3, userId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("The selected user no longer exists.");
                    }
                }
                audit(connection, actor.id(), "RESET_PASSWORD", "USER", Long.toString(userId), "Password reset");
                return null;
            });
        } finally {
            clearPassword(password);
        }
    }

    public void setUserActive(User actor, long userId, boolean active) {
        require(actor, Role.ADMINISTRATOR);
        if (actor.id() == userId && !active) {
            throw new IllegalArgumentException("You cannot deactivate your own account.");
        }
        transaction(connection -> {
            Map<String, Object> target = one(connection, "SELECT role,active FROM users WHERE id=?", userId);
            if (!active && "ADMINISTRATOR".equals(text(target, "role"))
                    && number(target, "active") == 1
                    && scalarLong(connection, "SELECT COUNT(*) FROM users WHERE role='ADMINISTRATOR' AND active=1") <= 1) {
                throw new IllegalArgumentException("At least one administrator account must remain active.");
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET active=? WHERE id=?")) {
                statement.setInt(1, active ? 1 : 0);
                statement.setLong(2, userId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("The selected user no longer exists.");
                }
            }
            audit(connection, actor.id(), active ? "ACTIVATE" : "DEACTIVATE", "USER",
                    Long.toString(userId), "Account " + (active ? "activated" : "deactivated"));
            return null;
        });
    }

    public List<Map<String, Object>> students() {
        return query("""
                SELECT id,student_id AS studentId,full_name AS name,faculty,year_of_study AS year,phone,email,
                       guarantor_name AS guarantor,guarantor_phone AS guarantorPhone,guarantor_relation AS relation,
                       photo_path AS photo,
                       CASE active WHEN 1 THEN 'Active' ELSE 'Archived' END AS status
                FROM students ORDER BY full_name""");
    }

    public void saveStudent(User actor, Long id, String studentId, String fullName, String faculty, int year,
                            String phone, String email, String guarantor, String guarantorPhone, String relation,
                            Path photo) {
        require(actor, Role.OFFICER);
        requireText(studentId, "Student ID");
        requireText(fullName, "Full name");
        requireText(faculty, "Faculty");
        requireText(guarantor, "Guarantor name");
        requireText(guarantorPhone, "Guarantor phone");
        if (year < 1) {
            throw new IllegalArgumentException("Year of study must be at least 1.");
        }
        if (id == null && photo == null) {
            throw new IllegalArgumentException("A passport photo is required for every new student.");
        }
        if (photo != null) {
            validateFile(photo, "IMAGE");
        }

        transaction(connection -> {
            String storedPhoto = null;
            if (photo != null) {
                storedPhoto = storeStudentPhoto(studentId.trim(), photo);
            } else if (id != null) {
                Map<String, Object> existing = one(connection, "SELECT photo_path FROM students WHERE id=?", id);
                storedPhoto = existing == null ? null : text(existing, "photo_path");
            }
            String sql = id == null
                    ? "INSERT INTO students(student_id,full_name,faculty,year_of_study,phone,email,guarantor_name,guarantor_phone,guarantor_relation,photo_path) VALUES(?,?,?,?,?,?,?,?,?,?)"
                    : "UPDATE students SET student_id=?,full_name=?,faculty=?,year_of_study=?,phone=?,email=?,guarantor_name=?,guarantor_phone=?,guarantor_relation=?,photo_path=?,updated_at=CURRENT_TIMESTAMP WHERE id=?";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindStudent(statement, studentId, fullName, faculty, year, phone, email, guarantor, guarantorPhone,
                        relation, storedPhoto);
                if (id != null) {
                    statement.setLong(11, id);
                }
                statement.executeUpdate();
                long entityId = id == null ? generatedId(statement) : id;
                audit(connection, actor.id(), id == null ? "CREATE" : "UPDATE", "STUDENT",
                        Long.toString(entityId), "Student record saved: " + studentId);
            }
            return null;
        });
    }

    private String storeStudentPhoto(String studentId, Path photo) {
        Path directory = database.file().getParent().resolve("student-photos");
        createPrivateDirectory(directory);
        String extension = photo.getFileName().toString().substring(
                photo.getFileName().toString().lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
        String fileName = studentId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                + "-" + UUID.randomUUID() + extension;
        Path destination = directory.resolve(fileName);
        try {
            Files.copy(photo, destination, StandardCopyOption.COPY_ATTRIBUTES);
            setPrivateFile(destination);
        } catch (IOException exception) {
            throw new IllegalStateException("The passport photo could not be copied into the private photo folder.", exception);
        }
        return database.file().getParent().relativize(destination).toString();
    }

    public Path resolveStudentPhoto(Map<String, Object> student) {
        String relative = text(student, "photo");
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path root = database.file().getParent().resolve("student-photos").toAbsolutePath().normalize();
        Path file = database.file().getParent().resolve(relative).toAbsolutePath().normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return file;
    }

    public void setStudentActive(User actor, long studentId, boolean active) {
        require(actor, Role.OFFICER);
        transaction(connection -> {
            Map<String, Object> student = one(connection, "SELECT student_id,active FROM students WHERE id=?", studentId);
            if (!active && number(student, "active") == 1) {
                long openApplications = scalarLong(connection, """
                        SELECT COUNT(*) FROM loan_applications WHERE student_id=? AND status IN ('SUBMITTED','APPROVED')
                        """, studentId);
                long activeLoans = scalarLong(connection,
                        "SELECT COUNT(*) FROM loans WHERE student_id=? AND status='ACTIVE'", studentId);
                if (openApplications > 0 || activeLoans > 0) {
                    throw new IllegalArgumentException(
                            "This student has an open application or active loan. Resolve it before archiving the student.");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE students SET active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                statement.setInt(1, active ? 1 : 0);
                statement.setLong(2, studentId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("The selected student no longer exists.");
                }
            }
            audit(connection, actor.id(), active ? "RESTORE" : "ARCHIVE", "STUDENT",
                    Long.toString(studentId), (active ? "Restored " : "Archived ") + text(student, "student_id"));
            return null;
        });
    }

    public List<Map<String, Object>> products() {
        return query("""
                SELECT id,name,currency,minimum_amount AS minimum,maximum_amount AS maximum,annual_rate AS annualRate,
                       max_term_months AS maxTerm,monthly_penalty_rate AS penaltyRate,
                       CASE active WHEN 1 THEN 'Active' ELSE 'Inactive' END AS status
                FROM loan_products ORDER BY name""");
    }

    public void saveProduct(User actor, Long id, String name, String currency, long minimum, long maximum,
                            double annualRate, int maxTerm, double penaltyRate) {
        require(actor, Role.ADMINISTRATOR);
        requireText(name, "Product name");
        if (!List.of("UGX", "USD").contains(currency) || minimum <= 0 || maximum < minimum
                || !Double.isFinite(annualRate) || annualRate < 0 || maxTerm < 1
                || !Double.isFinite(penaltyRate) || penaltyRate < 0) {
            throw new IllegalArgumentException("Enter a valid currency, amount range, non-negative rates, and a positive term.");
        }
        transaction(connection -> {
            String sql = id == null
                    ? "INSERT INTO loan_products(name,currency,minimum_amount,maximum_amount,annual_rate,max_term_months,monthly_penalty_rate) VALUES(?,?,?,?,?,?,?)"
                    : "UPDATE loan_products SET name=?,currency=?,minimum_amount=?,maximum_amount=?,annual_rate=?,max_term_months=?,monthly_penalty_rate=? WHERE id=?";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, name.trim());
                statement.setString(2, currency);
                statement.setLong(3, minimum);
                statement.setLong(4, maximum);
                statement.setDouble(5, annualRate);
                statement.setInt(6, maxTerm);
                statement.setDouble(7, penaltyRate);
                if (id != null) {
                    statement.setLong(8, id);
                }
                statement.executeUpdate();
                long entityId = id == null ? generatedId(statement) : id;
                audit(connection, actor.id(), id == null ? "CREATE" : "UPDATE", "LOAN_PRODUCT",
                        Long.toString(entityId), "Product saved: " + name.trim());
            }
            return null;
        });
    }

    public void setProductActive(User actor, long productId, boolean active) {
        require(actor, Role.ADMINISTRATOR);
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE loan_products SET active=? WHERE id=?")) {
                statement.setInt(1, active ? 1 : 0);
                statement.setLong(2, productId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("The selected product no longer exists.");
                }
            }
            audit(connection, actor.id(), active ? "ACTIVATE" : "DEACTIVATE", "LOAN_PRODUCT",
                    Long.toString(productId), "Loan product " + (active ? "activated" : "deactivated"));
            return null;
        });
    }

    public void deleteProduct(User actor, long productId) {
        require(actor, Role.ADMINISTRATOR);
        transaction(connection -> {
            try (PreparedStatement referenced = connection.prepareStatement(
                    "SELECT COUNT(*) FROM loan_applications WHERE product_id=?")) {
                referenced.setLong(1, productId);
                try (var result = referenced.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) {
                        throw new IllegalArgumentException(
                                "This product is referenced by one or more applications and cannot be deleted.");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM loan_products WHERE id=?")) {
                statement.setLong(1, productId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("The selected product no longer exists.");
                }
            }
            audit(connection, actor.id(), "DELETE", "LOAN_PRODUCT",
                    Long.toString(productId), "Loan product deleted");
            return null;
        });
    }

    public List<Map<String, Object>> applications() {
        return query("""
                SELECT a.id,a.creator_id AS creatorId,s.student_id AS studentId,s.full_name AS student,p.name AS product,a.currency,a.amount,
                       a.term_months AS term,a.purpose,a.status,u.display_name AS creator,
                       COALESCE(d.display_name,'') AS decider,COALESCE(a.decision_note,'') AS note,a.created_at AS created
                FROM loan_applications a JOIN students s ON s.id=a.student_id
                JOIN loan_products p ON p.id=a.product_id JOIN users u ON u.id=a.creator_id
                LEFT JOIN users d ON d.id=a.decider_id ORDER BY a.id DESC""");
    }

    public long submitApplication(User actor, long studentId, long productId, long amount, int term, String purpose,
                                  Path guarantorLetter, String stampAuthority, boolean stampConfirmed,
                                  boolean guarantorSignatureConfirmed,
                                  Path staffId, List<Path> academicDocuments, Path passportPhoto,
                                  Path applicantSignature, boolean termsAccepted) {
        require(actor, Role.ADMINISTRATOR, Role.OFFICER);
        requireText(purpose, "Purpose");
        requireText(stampAuthority, "Guarantor letter stamp authority");
        if (!List.of("Finance Director", "School/College/Faculty Dean").contains(stampAuthority)) {
            throw new IllegalArgumentException("Choose whether the guarantor letter was stamped by the Finance Director or a School/College/Faculty Dean.");
        }
        if (!stampConfirmed) {
            throw new IllegalArgumentException("Confirm that the guarantor letter bears the selected official stamp.");
        }
        if (!guarantorSignatureConfirmed) {
            throw new IllegalArgumentException("Confirm that the guarantor has signed the guarantor letter.");
        }
        if (!termsAccepted) {
            throw new IllegalArgumentException("The applicant must review and accept the loan terms before the application can be submitted.");
        }
        validateEvidenceFiles(guarantorLetter, staffId, academicDocuments, passportPhoto, applicantSignature);

        Path[] applicationDirectory = new Path[1];
        try {
            return transaction(connection -> {
            Map<String, Object> student = one(connection, """
                    SELECT active,full_name,student_id,faculty,year_of_study,phone,email,guarantor_name,
                           guarantor_phone,guarantor_relation FROM students WHERE id=?""", studentId);
            if (number(student, "active") != 1) {
                throw new IllegalArgumentException("Archived students cannot submit a loan application. Ask an officer to restore the student record.");
            }
            Map<String, Object> product = one(connection, """
                    SELECT name,currency,minimum_amount,maximum_amount,max_term_months,annual_rate,
                           monthly_penalty_rate,active FROM loan_products WHERE id=?""",
                    productId);
            if (number(product, "active") != 1) {
                throw new IllegalArgumentException("Only active loan products accept applications.");
            }
            if (amount < number(product, "minimum_amount") || amount > number(product, "maximum_amount")) {
                throw new IllegalArgumentException("The amount is outside this product's allowed range.");
            }
            if (term < 1 || term > number(product, "max_term_months")) {
                throw new IllegalArgumentException("The term must be within the product's maximum term.");
            }
            if (scalarLong(connection, """
                    SELECT (SELECT COUNT(*) FROM loan_applications WHERE student_id=? AND status IN ('SUBMITTED','APPROVED'))
                         + (SELECT COUNT(*) FROM loans WHERE student_id=? AND status='ACTIVE')""", studentId, studentId) > 0) {
                throw new IllegalArgumentException("This student already has an open application or active loan.");
            }
            long applicationId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO loan_applications(student_id,product_id,currency,amount,term_months,purpose,status,creator_id)
                    VALUES(?,?,?,?,?,?,'SUBMITTED',?)""", Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, studentId);
                statement.setLong(2, productId);
                statement.setString(3, text(product, "currency"));
                statement.setLong(4, amount);
                statement.setInt(5, term);
                statement.setString(6, purpose.trim());
                statement.setLong(7, actor.id());
                statement.executeUpdate();
                applicationId = generatedId(statement);
            }
            Path evidenceRoot = evidenceDirectory();
            applicationDirectory[0] = evidenceRoot.resolve(Long.toString(applicationId));
            createPrivateDirectory(applicationDirectory[0]);
            saveEvidence(connection, applicationId, actor, "GUARANTOR_LETTER", guarantorLetter,
                    applicationDirectory[0]);
            saveEvidence(connection, applicationId, actor, "STAFF_ID", staffId, applicationDirectory[0]);
            for (Path academicDocument : academicDocuments) {
                saveEvidence(connection, applicationId, actor, "ACADEMIC_DOCUMENT",
                        academicDocument, applicationDirectory[0]);
            }
            saveEvidence(connection, applicationId, actor, "PASSPORT_PHOTO", passportPhoto,
                    applicationDirectory[0]);
            Path storedSignature = saveEvidence(connection, applicationId, actor, "APPLICANT_SIGNATURE",
                    applicantSignature, applicationDirectory[0]);

            List<Map<String, Object>> installments = repaymentPreview(amount, decimal(product, "annual_rate"),
                    term, LocalDate.now());
            Path agreement = applicationDirectory[0].resolve("loan-agreement.pdf");
            LoanAgreementPdf.write(agreement, new LoanAgreementPdf.Details("Application #" + applicationId,
                    text(student, "full_name"), text(student, "student_id"), text(student, "faculty"),
                    Math.toIntExact(number(student, "year_of_study")), text(student, "phone"),
                    text(student, "email"), text(student, "guarantor_name"), text(student, "guarantor_phone"),
                    text(student, "guarantor_relation"), text(product, "name"), text(product, "currency"),
                    amount, decimal(product, "annual_rate"), decimal(product, "monthly_penalty_rate"),
                    term, purpose.trim(), stampAuthority, LocalDate.now(), false), installments, storedSignature);
            setPrivateFile(agreement);
            insertEvidence(connection, applicationId, actor, "LOAN_AGREEMENT", agreement,
                    "loan-agreement.pdf");
            audit(connection, actor.id(), "SUBMIT", "APPLICATION", Long.toString(applicationId),
                    "Loan application submitted with required evidence and signed terms");
            return applicationId;
            });
        } catch (RuntimeException exception) {
            if (applicationDirectory[0] != null) {
                try {
                    deleteEvidenceDirectory(applicationDirectory[0]);
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            throw exception;
        }
    }

    public List<Map<String, Object>> applicationEvidence(long applicationId) {
        return query("""
                SELECT evidence_type AS type,original_name AS name,stored_path AS path,uploaded_at AS uploaded
                FROM application_evidence WHERE application_id=? ORDER BY id""", applicationId);
    }

    public Path resolveApplicationEvidence(Map<String, Object> evidence) {
        Path root = database.file().getParent().resolve("application-evidence").toAbsolutePath().normalize();
        Path file = database.file().getParent().resolve(text(evidence, "path")).toAbsolutePath().normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("This evidence file is missing or is outside the private evidence folder.");
        }
        return file;
    }

    private void validateEvidenceFiles(Path guarantorLetter, Path staffId, List<Path> academicDocuments,
                                       Path passportPhoto, Path applicantSignature) {
        if (academicDocuments == null || academicDocuments.isEmpty()) {
            throw new IllegalArgumentException("Attach at least one academic document.");
        }
        if (academicDocuments.size() > MAX_ACADEMIC_DOCUMENTS) {
            throw new IllegalArgumentException("Attach no more than " + MAX_ACADEMIC_DOCUMENTS + " academic documents.");
        }
        List<Path> pdfs = new ArrayList<>(Arrays.asList(guarantorLetter, staffId, applicantSignature));
        pdfs.addAll(academicDocuments);
        long total = 0;
        for (Path path : pdfs) {
            total += validateFile(path, "PDF");
        }
        total += validateFile(passportPhoto, "IMAGE");
        if (total > MAX_EVIDENCE_TOTAL_BYTES) {
            throw new IllegalArgumentException("The selected evidence is larger than 100 MB. Choose smaller scans.");
        }
    }

    private long validateFile(Path path, String type) {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Select every required evidence file before submitting the application.");
        }
        try {
            long size = Files.size(path);
            if (size == 0 || size > MAX_EVIDENCE_FILE_BYTES) {
                throw new IllegalArgumentException("Each evidence file must be smaller than 15 MB and cannot be empty.");
            }
            String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            byte[] header;
            try (var input = Files.newInputStream(path)) {
                header = input.readNBytes(8);
            }
            if ("PDF".equals(type)) {
                if (!fileName.endsWith(".pdf") || header.length < 5
                        || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
                        || header[3] != 'F' || header[4] != '-') {
                    throw new IllegalArgumentException("Upload scanned PDF files for the guarantor letter, staff ID, academic records, and applicant signature.");
                }
                try (PDDocument document = Loader.loadPDF(path.toFile())) {
                    if (document.getNumberOfPages() == 0) {
                        throw new IllegalArgumentException("The scanned PDF has no pages. Choose a complete scan.");
                    }
                } catch (IOException exception) {
                    throw new IllegalArgumentException("The selected PDF is damaged or cannot be opened. Choose a complete scan.", exception);
                }
            } else if (!validImage(fileName, header)) {
                throw new IllegalArgumentException("Upload the passport photo as a JPG or PNG image.");
            }
            return size;
        } catch (IOException exception) {
            throw new IllegalArgumentException("The selected evidence file could not be read. Choose a readable file and try again.", exception);
        }
    }

    private boolean validImage(String fileName, byte[] header) {
        boolean png = fileName.endsWith(".png") && header.length >= 8
                && (header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0d && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a;
        boolean jpeg = fileName.endsWith(".jpg") || fileName.endsWith(".jpeg");
        return png || (jpeg && header.length >= 3 && (header[0] & 0xff) == 0xff
                && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff);
    }

    private Path evidenceDirectory() {
        Path directory = database.file().getParent().resolve("application-evidence");
        createPrivateDirectory(directory);
        return directory;
    }

    private void createPrivateDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            try {
                Files.setPosixFilePermissions(directory, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
            }
        } catch (IOException exception) {
            throw new IllegalStateException("The private application evidence folder could not be created.", exception);
        }
    }

    private Path saveEvidence(Connection connection, long applicationId, User actor, String type,
                              Path source, Path directory) throws SQLException {
        String extension = source.getFileName().toString().substring(
                source.getFileName().toString().lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
        String fileName = type.toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "-"
                + UUID.randomUUID() + extension;
        Path destination = directory.resolve(fileName);
        try {
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            setPrivateFile(destination);
        } catch (IOException exception) {
            throw new IllegalStateException("Evidence could not be copied into the private application folder.", exception);
        }
        insertEvidence(connection, applicationId, actor, type, destination, source.getFileName().toString());
        return destination;
    }

    private void insertEvidence(Connection connection, long applicationId, User actor, String type,
                                Path file, String originalName) throws SQLException {
        String storedPath = database.file().getParent().relativize(file).toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_evidence(application_id,evidence_type,stored_path,original_name,uploaded_by)
                VALUES(?,?,?,?,?)""")) {
            statement.setLong(1, applicationId);
            statement.setString(2, type);
            statement.setString(3, storedPath);
            statement.setString(4, originalName);
            statement.setLong(5, actor.id());
            statement.executeUpdate();
        }
    }

    private void setPrivateFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        } catch (IOException exception) {
            throw new IllegalStateException("The evidence file permissions could not be secured.", exception);
        }
    }

    private void deleteEvidenceDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("The incomplete application evidence could not be cleaned up.", exception);
        }
    }

    public List<Map<String, Object>> repaymentPreview(long principal, double annualRate, int term, LocalDate startDate) {
        if (principal <= 0 || !Double.isFinite(annualRate) || annualRate < 0 || term < 1 || startDate == null) {
            throw new IllegalArgumentException("Enter a valid amount, interest rate, term, and start date.");
        }
        BigDecimal balance = BigDecimal.valueOf(principal);
        BigDecimal monthlyRate = BigDecimal.valueOf(annualRate).divide(BigDecimal.valueOf(1200), CALCULATION);
        BigDecimal payment = monthlyRate.signum() == 0
                ? balance.divide(BigDecimal.valueOf(term), 0, RoundingMode.HALF_UP)
                : balance.multiply(monthlyRate).divide(BigDecimal.ONE.subtract(
                        BigDecimal.ONE.add(monthlyRate).pow(-term, CALCULATION), CALCULATION), CALCULATION);
        List<Map<String, Object>> schedule = new ArrayList<>(term);
        for (int month = 1; month <= term; month++) {
            long opening = balance.setScale(0, RoundingMode.HALF_UP).longValueExact();
            long interest = balance.multiply(monthlyRate).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long principalDue = month == term ? opening
                    : Math.min(opening, payment.setScale(0, RoundingMode.HALF_UP).longValueExact() - interest);
            balance = balance.subtract(BigDecimal.valueOf(principalDue));
            Map<String, Object> installment = new LinkedHashMap<>();
            installment.put("number", month);
            installment.put("due", startDate.plusMonths(month));
            installment.put("principal", principalDue);
            installment.put("interest", interest);
            installment.put("total", principalDue + interest);
            schedule.add(installment);
        }
        return schedule;
    }

    public void decideApplication(User actor, long applicationId, boolean approved, String note) {
        require(actor, Role.ADMINISTRATOR, Role.OFFICER);
        transaction(connection -> {
            Map<String, Object> application = one(connection,
                    "SELECT status,creator_id FROM loan_applications WHERE id=?", applicationId);
            String status = text(application, "status");
            if (!"SUBMITTED".equals(status) && !"STALLED".equals(status)) {
                throw new IllegalArgumentException("Only submitted or stalled applications can be decided.");
            }
            if (approved && scalarLong(connection,
                    "SELECT COUNT(*) FROM application_evidence WHERE application_id=?", applicationId) == 0) {
                throw new IllegalArgumentException(
                        "An application cannot be approved without evidence. Stall it and attach the required documents first.");
            }
            if (actor.role() != Role.ADMINISTRATOR && number(application, "creator_id") == actor.id()) {
                throw new IllegalArgumentException("A loan officer cannot decide their own application.");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE loan_applications SET status=?,decider_id=?,decision_note=?,decided_at=CURRENT_TIMESTAMP WHERE id=?""")) {
                statement.setString(1, approved ? "APPROVED" : "REJECTED");
                statement.setLong(2, actor.id());
                statement.setString(3, note == null ? "" : note.trim());
                statement.setLong(4, applicationId);
                statement.executeUpdate();
            }
            audit(connection, actor.id(), approved ? "APPROVE" : "REJECT", "APPLICATION",
                    Long.toString(applicationId), note == null ? "" : note.trim());
            return null;
        });
    }

    public void stallApplication(User actor, long applicationId, String note) {
        require(actor, Role.ADMINISTRATOR, Role.OFFICER);
        transaction(connection -> {
            Map<String, Object> application = one(connection,
                    "SELECT status,creator_id FROM loan_applications WHERE id=?", applicationId);
            String status = text(application, "status");
            if (!"SUBMITTED".equals(status) && !"STALLED".equals(status)) {
                throw new IllegalArgumentException("Only submitted or stalled applications can be stalled.");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE loan_applications SET status='STALLED',decider_id=?,decision_note=?,decided_at=CURRENT_TIMESTAMP WHERE id=?""")) {
                statement.setLong(1, actor.id());
                statement.setString(2, note == null ? "" : note.trim());
                statement.setLong(3, applicationId);
                statement.executeUpdate();
            }
            audit(connection, actor.id(), "STALL", "APPLICATION",
                    Long.toString(applicationId), note == null ? "" : note.trim());
            return null;
        });
    }

    public long applicationEvidenceCount(long applicationId) {
        return scalarLong("SELECT COUNT(*) FROM application_evidence WHERE application_id=?", applicationId);
    }

    public void addApplicationEvidence(User actor, long applicationId, Path source, String evidenceType) {
        require(actor, Role.ADMINISTRATOR, Role.OFFICER);
        requireText(evidenceType, "Evidence type");
        String upper = evidenceType.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        if (!List.of("GUARANTOR_LETTER", "STAFF_ID", "ACADEMIC_DOCUMENT", "PASSPORT_PHOTO",
                "APPLICANT_SIGNATURE", "LOAN_AGREEMENT", "OTHER").contains(upper)) {
            throw new IllegalArgumentException("Choose a valid evidence type.");
        }
        boolean image = "PASSPORT_PHOTO".equals(upper);
        validateFile(source, image ? "IMAGE" : "PDF");
        transaction(connection -> {
            Map<String, Object> application = one(connection,
                    "SELECT status FROM loan_applications WHERE id=?", applicationId);
            String status = text(application, "status");
            if (!"SUBMITTED".equals(status) && !"STALLED".equals(status)) {
                throw new IllegalArgumentException("Evidence can only be added to submitted or stalled applications.");
            }
            Path directory = evidenceDirectory().resolve(Long.toString(applicationId));
            createPrivateDirectory(directory);
            saveEvidence(connection, applicationId, actor, upper, source, directory);
            audit(connection, actor.id(), "ADD_EVIDENCE", "APPLICATION",
                    Long.toString(applicationId), "Added " + upper.toLowerCase(java.util.Locale.ROOT) + " evidence");
            return null;
        });
    }

    public List<Map<String, Object>> loans(User actor) {
        accrueOverdue(actor, LocalDate.now());
        return query("""
                SELECT l.id,s.student_id AS studentId,s.full_name AS student,l.currency,l.principal,l.term_months AS term,
                       l.disbursed_on AS disbursed,l.status,
                       COALESCE((SELECT SUM(i.principal_due-i.principal_paid) FROM installments i WHERE i.loan_id=l.id),0) AS outstanding,
                       COALESCE((SELECT SUM(i.penalty_accrued-i.penalty_paid) FROM installments i WHERE i.loan_id=l.id),0) AS penalty,
                       COALESCE((SELECT COUNT(*) FROM installments i WHERE i.loan_id=l.id AND i.due_date<date('now') AND i.status<>'PAID'),0) AS overdue
                FROM loans l JOIN students s ON s.id=l.student_id ORDER BY l.id DESC""");
    }

    public List<Map<String, Object>> installments(long loanId) {
        return query("""
                SELECT number,due_date AS due,principal_due AS principal,interest_due AS interest,
                       principal_paid AS principalPaid,interest_paid AS interestPaid,
                       penalty_accrued-penalty_paid AS penalty,status
                FROM installments WHERE loan_id=? ORDER BY number""", loanId);
    }

    public void disburse(User actor, long applicationId, LocalDate date) {
        require(actor, Role.ADMINISTRATOR, Role.FINANCE);
        if (date == null) {
            throw new IllegalArgumentException("Choose a disbursement date.");
        }
        transaction(connection -> {
            accrueOverdue(connection, actor, LocalDate.now());
            Map<String, Object> application = one(connection, """
                    SELECT a.*,p.annual_rate,p.monthly_penalty_rate FROM loan_applications a
                    JOIN loan_products p ON p.id=a.product_id WHERE a.id=?""", applicationId);
            if (!"APPROVED".equals(text(application, "status"))) {
                throw new IllegalArgumentException("Only approved applications can be disbursed.");
            }
            String currency = text(application, "currency");
            long principal = number(application, "amount");
            long cash = accountBalance(connection, "1000", currency);
            if (cash < principal) {
                throw new IllegalArgumentException("Insufficient " + currency + " cash. Add funds before disbursement.");
            }
            long loanId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO loans(application_id,student_id,product_id,currency,principal,annual_rate,penalty_rate,
                                      term_months,disbursed_on,status)
                    VALUES(?,?,?,?,?,?,?,?,?,'ACTIVE')""", Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, number(application, "id"));
                statement.setLong(2, number(application, "student_id"));
                statement.setLong(3, number(application, "product_id"));
                statement.setString(4, currency);
                statement.setLong(5, principal);
                statement.setDouble(6, decimal(application, "annual_rate"));
                statement.setDouble(7, decimal(application, "monthly_penalty_rate"));
                statement.setInt(8, (int) number(application, "term_months"));
                statement.setString(9, date.toString());
                statement.executeUpdate();
                loanId = generatedId(statement);
            }
            createSchedule(connection, loanId, principal, decimal(application, "annual_rate"),
                    (int) number(application, "term_months"), date, currency);
            journal(connection, actor, currency, "Loan disbursement", "LOAN-" + loanId,
                    "1100", principal, "1000", principal);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE loan_applications SET status='DISBURSED',disbursed_at=CURRENT_TIMESTAMP WHERE id=?""")) {
                statement.setLong(1, applicationId);
                statement.executeUpdate();
            }
            audit(connection, actor.id(), "DISBURSE", "LOAN", Long.toString(loanId),
                    "Disbursed application " + applicationId + " on " + date);
            return null;
        });
    }

    public void addFunds(User actor, String currency, long amount, String note) {
        require(actor, Role.ADMINISTRATOR, Role.FINANCE);
        if (amount <= 0 || !List.of("UGX", "USD").contains(currency)) {
            throw new IllegalArgumentException("Enter a positive amount in UGX or USD.");
        }
        transaction(connection -> {
            String reference = "FUND-" + UUID.randomUUID();
            journal(connection, actor, currency, note == null || note.isBlank() ? "Loan fund top-up" : note.trim(),
                    reference, "1000", amount, "3000", amount);
            audit(connection, actor.id(), "ADD_FUNDS", "JOURNAL", reference,
                    "Added " + Money.format(amount, currency) + " to the loan pool");
            return null;
        });
    }

    public String recordPayment(User actor, long loanId, long amount, LocalDate date) {
        require(actor, Role.ADMINISTRATOR, Role.FINANCE);
        if (amount <= 0) {
            throw new IllegalArgumentException("Payment must be greater than zero.");
        }
        if (date == null) {
            throw new IllegalArgumentException("Choose a payment date.");
        }
        return transaction(connection -> {
            accrueOverdue(connection, actor, LocalDate.now());
            Map<String, Object> loan = one(connection, "SELECT currency,status FROM loans WHERE id=?", loanId);
            if (!"ACTIVE".equals(text(loan, "status"))) {
                throw new IllegalArgumentException("This loan is already closed.");
            }
            String currency = text(loan, "currency");
            List<Map<String, Object>> installments = query(connection, """
                    SELECT id,principal_due,interest_due,principal_paid,interest_paid,penalty_accrued,penalty_paid,status
                    FROM installments WHERE loan_id=? ORDER BY number""", loanId);
            long totalOwed = 0;
            for (Map<String, Object> installment : installments) {
                totalOwed += number(installment, "principal_due") - number(installment, "principal_paid")
                        + number(installment, "interest_due") - number(installment, "interest_paid")
                        + number(installment, "penalty_accrued") - number(installment, "penalty_paid");
            }
            if (amount > totalOwed) {
                throw new IllegalArgumentException("Payment exceeds the total outstanding amount of "
                        + Money.format(totalOwed, currency) + ".");
            }
            long remaining = amount;
            long principalPart = 0;
            long interestPart = 0;
            long penaltyPart = 0;
            for (Map<String, Object> installment : installments) {
                if (remaining == 0) {
                    break;
                }
                long penaltyDue = number(installment, "penalty_accrued") - number(installment, "penalty_paid");
                long interestDue = number(installment, "interest_due") - number(installment, "interest_paid");
                long principalDue = number(installment, "principal_due") - number(installment, "principal_paid");
                long penaltyPaid = Math.min(remaining, penaltyDue);
                remaining -= penaltyPaid;
                long interestPaid = Math.min(remaining, interestDue);
                remaining -= interestPaid;
                long principalPaid = Math.min(remaining, principalDue);
                remaining -= principalPaid;
                penaltyPart += penaltyPaid;
                interestPart += interestPaid;
                principalPart += principalPaid;
                if (penaltyPaid + interestPaid + principalPaid > 0) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE installments SET penalty_paid=penalty_paid+?,interest_paid=interest_paid+?,
                            principal_paid=principal_paid+?,status=CASE
                              WHEN principal_paid+? >= principal_due AND interest_paid+? >= interest_due
                               AND penalty_paid+? >= penalty_accrued THEN 'PAID' ELSE 'PARTIAL' END
                            WHERE id=?""")) {
                        statement.setLong(1, penaltyPaid);
                        statement.setLong(2, interestPaid);
                        statement.setLong(3, principalPaid);
                        statement.setLong(4, principalPaid);
                        statement.setLong(5, interestPaid);
                        statement.setLong(6, penaltyPaid);
                        statement.setLong(7, number(installment, "id"));
                        statement.executeUpdate();
                    }
                }
            }
            String receipt = nextReceipt(connection, date);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO payments(loan_id,receipt_number,amount,principal_part,interest_part,penalty_part,paid_on,receiver_id)
                    VALUES(?,?,?,?,?,?,?,?)""")) {
                statement.setLong(1, loanId);
                statement.setString(2, receipt);
                statement.setLong(3, amount);
                statement.setLong(4, principalPart);
                statement.setLong(5, interestPart);
                statement.setLong(6, penaltyPart);
                statement.setString(7, date.toString());
                statement.setLong(8, actor.id());
                statement.executeUpdate();
            }
            journalRepayment(connection, actor, currency, loanId, amount, principalPart, interestPart, penaltyPart);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE loans SET status=CASE WHEN NOT EXISTS
                      (SELECT 1 FROM installments WHERE loan_id=? AND status<>'PAID') THEN 'CLOSED' ELSE 'ACTIVE' END
                    WHERE id=?""")) {
                statement.setLong(1, loanId);
                statement.setLong(2, loanId);
                statement.executeUpdate();
            }
            audit(connection, actor.id(), "PAYMENT", "PAYMENT", receipt,
                    "Repayment " + Money.format(amount, currency) + " received");
            return receipt;
        });
    }

    public List<Map<String, Object>> ledger(String currency) {
        return query("""
                SELECT a.code,a.name,a.currency,
                       COALESCE(SUM(jl.debit),0) AS debits,COALESCE(SUM(jl.credit),0) AS credits,
                       COALESCE(SUM(jl.debit-jl.credit),0) AS balance
                FROM accounts a LEFT JOIN journal_lines jl ON jl.account_id=a.id
                WHERE a.currency=? GROUP BY a.id ORDER BY a.code""", currency);
    }

    public List<Map<String, Object>> journalEntries(String currency) {
        return query("""
                SELECT e.id,e.posted_at AS date,e.memo,e.reference,e.currency,u.display_name AS postedBy,
                       a.code AS account,jl.debit,jl.credit
                FROM journal_entries e JOIN users u ON u.id=e.posted_by
                JOIN journal_lines jl ON jl.entry_id=e.id JOIN accounts a ON a.id=jl.account_id
                WHERE e.currency=? ORDER BY e.id DESC,jl.id""", currency);
    }

    public List<Map<String, Object>> payments() {
        return query("""
                SELECT p.receipt_number AS receipt,p.paid_on AS date,s.student_id AS studentId,s.full_name AS student,
                       l.currency,p.amount,p.principal_part AS principal,p.interest_part AS interest,
                       p.penalty_part AS penalty,u.display_name AS receivedBy
                FROM payments p JOIN loans l ON l.id=p.loan_id JOIN students s ON s.id=l.student_id
                JOIN users u ON u.id=p.receiver_id ORDER BY p.id DESC""");
    }

    public Map<String, Long> dashboard(User actor, String currency) {
        accrueOverdue(actor, LocalDate.now());
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("students", scalarLong("SELECT COUNT(*) FROM students"));
        stats.put("activeLoans", scalarLong("SELECT COUNT(*) FROM loans WHERE status='ACTIVE'"));
        stats.put("awaiting", scalarLong("SELECT COUNT(*) FROM loan_applications WHERE status='SUBMITTED'"));
        stats.put("overdue", scalarLong("SELECT COUNT(*) FROM installments WHERE due_date<date('now') AND status<>'PAID'"));
        stats.put("outstanding", scalarLong("""
                SELECT COALESCE(SUM(i.principal_due-i.principal_paid),0) FROM installments i
                JOIN loans l ON l.id=i.loan_id WHERE l.currency=? AND l.status='ACTIVE'""", currency));
        stats.put("collected", scalarLong("SELECT COALESCE(SUM(amount),0) FROM payments WHERE loan_id IN (SELECT id FROM loans WHERE currency=?)", currency));
        stats.put("disbursed", scalarLong("SELECT COALESCE(SUM(principal),0) FROM loans WHERE currency=?", currency));
        stats.put("pendingRequested", scalarLong("""
                SELECT COALESCE(SUM(amount),0) FROM loan_applications WHERE currency=? AND status='SUBMITTED'""",
                currency));
        stats.put("dueSoon", scalarLong("""
                SELECT COUNT(*) FROM installments i JOIN loans l ON l.id=i.loan_id
                WHERE l.currency=? AND l.status='ACTIVE' AND i.status<>'PAID'
                  AND i.due_date>=date('now') AND i.due_date<date('now','+30 days')""", currency));
        stats.put("cash", accountBalance("1000", currency));
        return stats;
    }

    public List<Map<String, Object>> installmentHealth(String currency) {
        return query("""
                SELECT CASE WHEN i.status='PAID' THEN 'Paid'
                            WHEN i.due_date<date('now') THEN 'Overdue' ELSE 'Upcoming' END AS category,
                       COUNT(*) AS total
                FROM installments i JOIN loans l ON l.id=i.loan_id
                WHERE l.currency=? GROUP BY category ORDER BY category""", currency);
    }

    public List<Map<String, Object>> applicationStatusCounts(String currency) {
        return query("""
                SELECT status,COUNT(*) AS total FROM loan_applications WHERE currency=?
                GROUP BY status ORDER BY status""", currency);
    }

    public List<Map<String, Object>> monthlyApplications(String currency) {
        return query("""
                WITH months AS (
                  SELECT date('now','start of month','-5 months','+'||n||' months') AS month
                  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5)
                )
                SELECT substr(months.month,1,7) AS month,
                       COALESCE((SELECT COUNT(*) FROM loan_applications a
                         WHERE a.currency=? AND substr(a.created_at,1,7)=substr(months.month,1,7)),0) AS applications
                FROM months ORDER BY months.month""", currency);
    }

    public List<Map<String, Object>> monthlyActivity(String currency) {
        return query("""
                WITH months AS (
                  SELECT date('now','start of month','-5 months','+'||n||' months') AS month
                  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5)
                )
                SELECT substr(months.month,1,7) AS month,
                  COALESCE((SELECT SUM(l.principal) FROM loans l WHERE l.currency=? AND substr(l.disbursed_on,1,7)=substr(months.month,1,7)),0) AS disbursed,
                  COALESCE((SELECT SUM(p.amount) FROM payments p JOIN loans l ON l.id=p.loan_id
                    WHERE l.currency=? AND substr(p.paid_on,1,7)=substr(months.month,1,7)),0) AS collected
                FROM months ORDER BY months.month""", currency, currency);
    }

    public List<Map<String, Object>> monthlyCollectionComposition(String currency) {
        return query("""
                WITH months AS (
                  SELECT date('now','start of month','-5 months','+'||n||' months') AS month
                  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5)
                )
                SELECT substr(months.month,1,7) AS month,
                  COALESCE((SELECT SUM(p.principal_part) FROM payments p JOIN loans l ON l.id=p.loan_id
                    WHERE l.currency=? AND substr(p.paid_on,1,7)=substr(months.month,1,7)),0) AS principal,
                  COALESCE((SELECT SUM(p.interest_part) FROM payments p JOIN loans l ON l.id=p.loan_id
                    WHERE l.currency=? AND substr(p.paid_on,1,7)=substr(months.month,1,7)),0) AS interest,
                  COALESCE((SELECT SUM(p.penalty_part) FROM payments p JOIN loans l ON l.id=p.loan_id
                    WHERE l.currency=? AND substr(p.paid_on,1,7)=substr(months.month,1,7)),0) AS penalty
                FROM months ORDER BY months.month""", currency, currency, currency);
    }

    public List<Map<String, Object>> portfolioByProduct(String currency) {
        return query("""
                SELECT p.name AS product,COUNT(l.id) AS loans,COALESCE(SUM(l.principal),0) AS principal
                FROM loans l JOIN loan_products p ON p.id=l.product_id
                WHERE l.currency=? GROUP BY p.id ORDER BY principal DESC,p.name""", currency);
    }

    public List<Map<String, Object>> report(String name) {
        return switch (name) {
            case "Portfolio summary" -> query("""
                    SELECT l.currency,l.status,COUNT(*) AS loans,SUM(l.principal) AS principal,
                           SUM(COALESCE(i.remaining,0)) AS outstanding
                    FROM loans l LEFT JOIN (SELECT loan_id,SUM(principal_due-principal_paid) AS remaining
                    FROM installments GROUP BY loan_id) i ON i.loan_id=l.id GROUP BY l.currency,l.status ORDER BY l.currency,l.status""");
            case "Overdue installments" -> query("""
                    SELECT s.student_id AS studentId,s.full_name AS student,s.phone,s.guarantor_name AS guarantor,
                           s.guarantor_phone AS guarantorPhone,l.currency,i.number AS installment,i.due_date AS due,
                           i.principal_due-i.principal_paid AS principal,i.interest_due-i.interest_paid AS interest,
                           i.penalty_accrued-i.penalty_paid AS penalty
                    FROM installments i JOIN loans l ON l.id=i.loan_id JOIN students s ON s.id=l.student_id
                    WHERE i.due_date<date('now') AND i.status<>'PAID' ORDER BY i.due_date""");
            case "Collections by month" -> query("""
                    SELECT substr(p.paid_on,1,7) AS month,l.currency,SUM(p.amount) AS collected,
                           SUM(p.principal_part) AS principal,SUM(p.interest_part) AS interest,SUM(p.penalty_part) AS penalty
                    FROM payments p JOIN loans l ON l.id=p.loan_id GROUP BY month,l.currency ORDER BY month DESC""");
            case "Disbursements by faculty" -> query("""
                    SELECT s.faculty,l.currency,COUNT(*) AS loans,SUM(l.principal) AS disbursed
                    FROM loans l JOIN students s ON s.id=l.student_id GROUP BY s.faculty,l.currency ORDER BY s.faculty""");
            case "Applications by status" -> query("""
                    SELECT status,currency,COUNT(*) AS applications,SUM(amount) AS requested
                    FROM loan_applications GROUP BY status,currency ORDER BY status,currency""");
            default -> throw new IllegalArgumentException("Choose a report from the list.");
        };
    }

    public List<Map<String, Object>> auditLog() {
        return query("""
                SELECT a.occurred_at AS time,COALESCE(u.username,'system') AS user,a.action,a.entity,
                       a.entity_id AS entityId,a.detail
                FROM audit_log a LEFT JOIN users u ON u.id=a.user_id ORDER BY a.id DESC LIMIT 2000""");
    }

    public long unsyncedCount() {
        return scalarLong("SELECT COUNT(*) FROM sync_outbox WHERE synced_at IS NULL");
    }

    public String deviceId() {
        return scalarString("SELECT value FROM settings WHERE key='device_id'");
    }

    public void recordBackupEvent(User actor, String fileName) {
        require(actor, Role.ADMINISTRATOR);
        transaction(connection -> {
            audit(connection, actor.id(), "BACKUP", "DATABASE", database.file().toString(),
                    "Encrypted backup created: " + fileName);
            return null;
        });
    }

    public void recordRestoreEvent(User actor, String fileName) {
        require(actor, Role.ADMINISTRATOR);
        transaction(connection -> {
            Long actorId = scalarLong(connection, "SELECT COUNT(*) FROM users WHERE id=?", actor.id()) > 0
                    ? actor.id() : null;
            String detail = "Database restored from " + fileName;
            insertAudit(connection, actorId, "RESTORE", "DATABASE", database.file().toString(), detail);
            insertOutbox(connection, "DATABASE", database.file().toString(), "RESTORE", detail);
            return null;
        });
    }

    public List<Map<String, Object>> query(String sql, Object... values) {
        return transaction(connection -> query(connection, sql, values));
    }

    public void accrueOverdue(User actor, LocalDate throughDate) {
        transaction(connection -> {
            accrueOverdue(connection, actor, throughDate);
            return null;
        });
    }

    private void accrueOverdue(Connection connection, User actor, LocalDate throughDate) throws SQLException {
        List<Map<String, Object>> overdue = query(connection, """
                SELECT i.id,i.due_date,i.principal_due-i.principal_paid AS unpaid_principal,
                       i.interest_due-i.interest_paid AS unpaid_interest,i.penalty_through,
                       l.penalty_rate,l.currency
                FROM installments i JOIN loans l ON l.id=i.loan_id
                WHERE i.status<>'PAID' AND i.due_date<?""", throughDate.toString());
        for (Map<String, Object> installment : overdue) {
            LocalDate dueDate = LocalDate.parse(text(installment, "due_date"));
            String previousText = text(installment, "penalty_through");
            LocalDate lastAccrued = previousText == null ? dueDate : LocalDate.parse(previousText);
            long days = Math.max(0, ChronoUnit.DAYS.between(lastAccrued, throughDate));
            long base = number(installment, "unpaid_principal") + number(installment, "unpaid_interest");
            if (days == 0 || base == 0) {
                continue;
            }
            long penalty = BigDecimal.valueOf(base)
                    .multiply(BigDecimal.valueOf(decimal(installment, "penalty_rate")))
                    .multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(3000), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE installments SET penalty_accrued=penalty_accrued+?,penalty_through=?,
                      status=CASE WHEN status='PENDING' THEN 'PARTIAL' ELSE status END WHERE id=?""")) {
                statement.setLong(1, penalty);
                statement.setString(2, throughDate.toString());
                statement.setLong(3, number(installment, "id"));
                statement.executeUpdate();
            }
            audit(connection, actor.id(), "ACCRUE_PENALTY", "INSTALLMENT", text(installment, "id"),
                    "Accrued " + Money.format(penalty, text(installment, "currency")));
        }
    }

    private void createSchedule(Connection connection, long loanId, long principal, double annualRate, int term,
                                LocalDate disbursed, String currency) throws SQLException {
        for (Map<String, Object> installment : repaymentPreview(principal, annualRate, term, disbursed)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO installments(loan_id,number,due_date,principal_due,interest_due,status)
                    VALUES(?,?,?,?,?,'PENDING')""")) {
                statement.setLong(1, loanId);
                statement.setInt(2, (int) number(installment, "number"));
                statement.setString(3, text(installment, "due"));
                statement.setLong(4, number(installment, "principal"));
                statement.setLong(5, number(installment, "interest"));
                statement.executeUpdate();
            }
        }
        long scheduledPrincipal = repaymentPreview(principal, annualRate, term, disbursed).stream()
                .mapToLong(installment -> number(installment, "principal")).sum();
        if (scheduledPrincipal != principal) {
            throw new IllegalStateException("Loan schedule failed to reconcile " + Money.format(
                    principal - scheduledPrincipal, currency) + ".");
        }
    }

    private void journal(Connection connection, User actor, String currency, String memo, String reference,
                         String debitCode, long debit, String creditCode, long credit) throws SQLException {
        if (debit <= 0 || debit != credit) {
            throw new IllegalArgumentException("Journal entries must have equal, positive debits and credits.");
        }
        long entryId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entries(memo,reference,currency,posted_by) VALUES(?,?,?,?)""",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, memo);
            statement.setString(2, reference);
            statement.setString(3, currency);
            statement.setLong(4, actor.id());
            statement.executeUpdate();
            entryId = generatedId(statement);
        }
        addJournalLine(connection, entryId, debitCode, currency, debit, 0);
        addJournalLine(connection, entryId, creditCode, currency, 0, credit);
        audit(connection, actor.id(), "POST", "JOURNAL", Long.toString(entryId), memo + " (" + reference + ")");
    }

    private void journalRepayment(Connection connection, User actor, String currency, long loanId, long amount,
                                  long principal, long interest, long penalty) throws SQLException {
        if (principal + interest + penalty != amount) {
            throw new IllegalStateException("Repayment allocation does not balance.");
        }
        long entryId;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_entries(memo,reference,currency,posted_by)
                VALUES('Loan repayment',?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "LOAN-" + loanId);
            statement.setString(2, currency);
            statement.setLong(3, actor.id());
            statement.executeUpdate();
            entryId = generatedId(statement);
        }
        addJournalLine(connection, entryId, "1000", currency, amount, 0);
        if (principal > 0) {
            addJournalLine(connection, entryId, "1100", currency, 0, principal);
        }
        if (interest > 0) {
            addJournalLine(connection, entryId, "4000", currency, 0, interest);
        }
        if (penalty > 0) {
            addJournalLine(connection, entryId, "4100", currency, 0, penalty);
        }
        audit(connection, actor.id(), "POST", "JOURNAL", Long.toString(entryId), "Loan repayment " + loanId);
    }

    private void addJournalLine(Connection connection, long entryId, String code, String currency,
                                long debit, long credit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_lines(entry_id,account_id,debit,credit)
                SELECT ?,id,?,? FROM accounts WHERE code=? AND currency=?""")) {
            statement.setLong(1, entryId);
            statement.setLong(2, debit);
            statement.setLong(3, credit);
            statement.setString(4, code);
            statement.setString(5, currency);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Missing ledger account " + code + " for " + currency + ".");
            }
        }
    }

    private long accountBalance(Connection connection, String code, String currency) throws SQLException {
        return scalarLong(connection, """
                SELECT COALESCE(SUM(jl.debit-jl.credit),0) FROM accounts a LEFT JOIN journal_lines jl ON jl.account_id=a.id
                WHERE a.code=? AND a.currency=?""", code, currency);
    }

    private long accountBalance(String code, String currency) {
        return scalarLong("""
                SELECT COALESCE(SUM(jl.debit-jl.credit),0) FROM accounts a LEFT JOIN journal_lines jl ON jl.account_id=a.id
                WHERE a.code=? AND a.currency=?""", code, currency);
    }

    private String nextReceipt(Connection connection, LocalDate date) throws SQLException {
        String prefix = "RCP-" + date.toString().replace("-", "") + "-";
        long next = scalarLong(connection, "SELECT COUNT(*) FROM payments WHERE receipt_number LIKE ?",
                prefix + "%") + 1;
        return prefix + String.format("%05d", next);
    }

    private long insertUser(Connection connection, String displayName, String username, Role role, char[] password)
            throws SQLException {
        byte[] salt = Passwords.newSalt();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users(username,display_name,role,salt,password_hash) VALUES(?,?,?,?,?)""",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username.trim());
            statement.setString(2, displayName.trim());
            statement.setString(3, role.name());
            statement.setBytes(4, salt);
            statement.setBytes(5, Passwords.hash(password, salt));
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private void validateIdentity(String displayName, String username, char[] password) {
        requireText(displayName, "Display name");
        requireText(username, "Username");
        if (password == null || password.length < 8) {
            throw new IllegalArgumentException("Passwords must contain at least 8 characters.");
        }
    }

    private void bindStudent(PreparedStatement statement, String studentId, String name, String faculty, int year,
                             String phone, String email, String guarantor, String guarantorPhone, String relation,
                             String photoPath) throws SQLException {
        statement.setString(1, studentId.trim());
        statement.setString(2, name.trim());
        statement.setString(3, faculty.trim());
        statement.setInt(4, year);
        statement.setString(5, emptyToNull(phone));
        statement.setString(6, emptyToNull(email));
        statement.setString(7, guarantor.trim());
        statement.setString(8, guarantorPhone.trim());
        statement.setString(9, emptyToNull(relation));
        statement.setString(10, emptyToNull(photoPath));
    }

    private void audit(Connection connection, long userId, String action, String entity, String entityId, String detail)
            throws SQLException {
        insertAudit(connection, userId, action, entity, entityId, detail);
        insertOutbox(connection, entity, entityId, action, detail);
    }

    private void insertOutbox(Connection connection, String entity, String entityId, String action, String detail)
            throws SQLException {
        String deviceId = scalarString(connection, "SELECT value FROM settings WHERE key='device_id'");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("entity", entity);
        payload.put("entityId", entityId);
        payload.put("detail", detail);
        payload.put("record", entitySnapshot(connection, entity, entityId));
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sync_outbox(uuid,device_id,entity,entity_id,operation,payload)
                VALUES(?,?,?,?,?,?)""")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, deviceId);
            statement.setString(3, entity);
            statement.setString(4, entityId);
            statement.setString(5, action);
            statement.setString(6, toJson(payload));
            statement.executeUpdate();
        }
    }

    private Object entitySnapshot(Connection connection, String entity, String entityId) throws SQLException {
        return switch (entity) {
            case "USER" -> query(connection, """
                    SELECT id,username,display_name AS displayName,role,active,created_at AS createdAt
                    FROM users WHERE id=?""", Long.parseLong(entityId)).stream().findFirst().orElse(null);
            case "STUDENT" -> query(connection, """
                    SELECT id,student_id AS studentId,full_name AS fullName,faculty,year_of_study AS yearOfStudy,
                           phone,email,guarantor_name AS guarantorName,guarantor_phone AS guarantorPhone,
                           guarantor_relation AS guarantorRelation FROM students WHERE id=?""",
                    Long.parseLong(entityId)).stream().findFirst().orElse(null);
            case "LOAN_PRODUCT" -> query(connection, """
                    SELECT id,name,currency,minimum_amount AS minimumAmount,maximum_amount AS maximumAmount,
                           annual_rate AS annualRate,max_term_months AS maximumTerm,
                           monthly_penalty_rate AS monthlyPenaltyRate,active
                    FROM loan_products WHERE id=?""", Long.parseLong(entityId)).stream().findFirst().orElse(null);
            case "APPLICATION" -> query(connection, "SELECT * FROM loan_applications WHERE id=?",
                    Long.parseLong(entityId)).stream().findFirst().orElse(null);
            case "LOAN" -> query(connection, "SELECT * FROM loans WHERE id=?",
                    Long.parseLong(entityId)).stream().findFirst().orElse(null);
            case "INSTALLMENT" -> query(connection, "SELECT * FROM installments WHERE id=?",
                    Long.parseLong(entityId)).stream().findFirst().orElse(null);
            case "PAYMENT" -> query(connection, "SELECT * FROM payments WHERE receipt_number=?",
                    entityId).stream().findFirst().orElse(null);
            case "JOURNAL" -> {
                String predicate = entityId.chars().allMatch(Character::isDigit) ? "e.id=?" : "e.reference=?";
                Object identifier = predicate.equals("e.id=?") ? Long.parseLong(entityId) : entityId;
                String sql = """
                        SELECT e.id,e.memo,e.reference,e.currency,e.posted_by AS postedBy,e.posted_at AS postedAt,
                               a.code AS accountCode,a.name AS accountName,jl.debit,jl.credit
                        FROM journal_entries e JOIN journal_lines jl ON jl.entry_id=e.id
                        JOIN accounts a ON a.id=jl.account_id WHERE %s ORDER BY jl.id
                        """.formatted(predicate);
                yield query(connection, sql, identifier);
            }
            default -> Map.of("reference", entityId);
        };
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return "\"" + jsonEscape(string) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry ->
                            toJson(entry.getKey().toString()) + ":" + toJson(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> values) {
            List<String> serialized = new ArrayList<>();
            for (Object item : values) {
                serialized.add(toJson(item));
            }
            return "[" + String.join(",", serialized) + "]";
        }
        return toJson(value.toString());
    }

    private void insertAudit(Connection connection, Long userId, String action, String entity, String entityId,
                             String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_log(user_id,action,entity,entity_id,detail) VALUES(?,?,?,?,?)""")) {
            if (userId == null) {
                statement.setNull(1, java.sql.Types.INTEGER);
            } else {
                statement.setLong(1, userId);
            }
            statement.setString(2, action);
            statement.setString(3, entity);
            statement.setString(4, entityId);
            statement.setString(5, detail);
            statement.executeUpdate();
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("The database operation could not be completed.", exception);
            }
        } catch (SQLException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("UNIQUE constraint failed")) {
                throw new IllegalArgumentException("That value already exists. Use a unique ID or name.");
            }
            throw new IllegalStateException("The database operation could not be completed.", exception);
        }
    }

    private List<Map<String, Object>> query(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                var metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        row.put(metadata.getColumnLabel(column), result.getObject(column));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private Map<String, Object> one(Connection connection, String sql, Object... values) throws SQLException {
        List<Map<String, Object>> result = query(connection, sql, values);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("The selected record no longer exists.");
        }
        return result.getFirst();
    }

    private long scalarLong(String sql, Object... values) {
        return transaction(connection -> scalarLong(connection, sql, values));
    }

    private long scalarLong(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private String scalarString(String sql) {
        return transaction(connection -> scalarString(connection, sql));
    }

    private String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : "";
        }
    }

    private long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("The database did not return a new record ID.");
            }
            return keys.getLong(1);
        }
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private void require(User user, Role... roles) {
        for (Role role : roles) {
            if (user.role() == role) {
                return;
            }
        }
        throw new SecurityException("Your role is not permitted to perform this action.");
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static double decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).doubleValue();
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private void clearPassword(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }
}
