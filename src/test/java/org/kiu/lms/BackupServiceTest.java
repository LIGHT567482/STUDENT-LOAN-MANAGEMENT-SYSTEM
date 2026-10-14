package org.kiu.lms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {
    @TempDir
    Path temporaryDirectory;

    private String oldUserHome;
    private LmsService service;
    private User admin;
    private User officer;

    @BeforeEach
    void setUp() throws Exception {
        oldUserHome = System.getProperty("user.home");
        System.setProperty("user.home", temporaryDirectory.resolve("profile").toString());
        service = new LmsService(new Database());
        admin = service.createFirstAdministrator("Office Admin", "admin", "adminpass1".toCharArray());
        service.createUser(admin, "Loan Officer", "officer", Role.OFFICER, "officerpass1".toCharArray());
        officer = service.login("officer", "officerpass1".toCharArray());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldUserHome);
    }

    @Test
    void encryptsBackupAndRestoresOnlyWithTheCorrectPassphrase() throws Exception {
        service.saveStudent(officer, null, "KIU-100", "Saved Student", "Arts", 1,
                "", "", "Guarantor", "0700000000", "", studentPhoto("p100"));
        Path backup = temporaryDirectory.resolve("office.kiubak");
        BackupService backupService = new BackupService(service);
        backupService.create(admin, backup, "safe-passphrase".toCharArray());
        byte[] encrypted = Files.readAllBytes(backup);
        assertEquals("KIUMBK01", new String(encrypted, 0, 8));

        service.saveStudent(officer, null, "KIU-101", "Later Student", "Arts", 2,
                "", "", "Guarantor", "0700000001", "", studentPhoto("p101"));
        assertEquals(2, service.students().size());
        assertThrows(java.security.GeneralSecurityException.class,
                () -> backupService.restore(admin, backup, "wrong-passphrase".toCharArray()));
        assertEquals(2, service.students().size());

        Path previous = backupService.restore(admin, backup, "safe-passphrase".toCharArray());
        assertTrue(Files.exists(previous));
        assertEquals(1, service.students().size());
        assertEquals("KIU-100", service.students().getFirst().get("studentId"));
    }

    @Test
    void encryptedBackupIncludesApplicationEvidence() throws Exception {
        service.saveStudent(officer, null, "KIU-200", "Evidence Student", "Law", 2,
                "", "", "Guarantor", "0700000000", "", studentPhoto("p200"));
        long studentId = ((Number) service.query("SELECT id FROM students WHERE student_id='KIU-200'")
                .getFirst().get("id")).longValue();
        service.saveProduct(admin, null, "Backup product", "UGX", 100_000, 1_000_000, 12, 12, 0);
        long productId = ((Number) service.query("SELECT id FROM loan_products WHERE name='Backup product'")
                .getFirst().get("id")).longValue();
        Path guarantor = pdf("guarantor.pdf");
        Path staff = pdf("staff.pdf");
        Path academic = pdf("academic.pdf");
        Path signature = pdf("signature.pdf");
        Path passport = temporaryDirectory.resolve("passport.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", passport.toFile());
        long applicationId = service.submitApplication(officer, studentId, productId, 200_000, 2, "Tuition",
                guarantor, "Finance Director", true, true, staff, List.of(academic), passport, signature, true);
        Path backup = temporaryDirectory.resolve("evidence.kiubak");
        BackupService backupService = new BackupService(service);
        backupService.create(admin, backup, "safe-passphrase".toCharArray());

        Path evidenceRoot = service.database().file().getParent().resolve("application-evidence");
        Path movedEvidence = temporaryDirectory.resolve("held-evidence");
        Files.move(evidenceRoot, movedEvidence);
        assertTrue(service.applicationEvidence(applicationId).size() == 6);

        backupService.restore(admin, backup, "safe-passphrase".toCharArray());

        assertEquals(6, service.applicationEvidence(applicationId).size());
        for (var row : service.applicationEvidence(applicationId)) {
            assertTrue(Files.isRegularFile(service.resolveApplicationEvidence(row)));
        }
    }

    private Path pdf(String name) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
        return path;
    }

    private Path studentPhoto(String prefix) throws Exception {
        Path photo = temporaryDirectory.resolve(prefix + "-photo.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
        return photo;
    }
}
