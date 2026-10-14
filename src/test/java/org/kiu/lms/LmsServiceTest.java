package org.kiu.lms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LmsServiceTest {
    @TempDir
    Path temporaryDirectory;

    private String oldUserHome;
    private LmsService service;

    @BeforeEach
    void setUp() throws Exception {
        oldUserHome = System.getProperty("user.home");
        System.setProperty("user.home", temporaryDirectory.toString());
        service = new LmsService(new Database());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldUserHome);
    }

    @Test
    void completesApprovalDisbursementRepaymentAndBalancedLedger() throws Exception {
        User admin = service.createFirstAdministrator("Office Admin", "admin", "adminpass1".toCharArray());
        service.createUser(admin, "Loan Officer", "officer", Role.OFFICER, "officerpass1".toCharArray());
        service.createUser(admin, "Finance Clerk", "finance", Role.FINANCE, "financepass1".toCharArray());
        User officer = service.login("officer", "officerpass1".toCharArray());
        User finance = service.login("finance", "financepass1".toCharArray());

        service.saveStudent(officer, null, "KIU-001", "Amina Kato", "Business", 2,
                "0700000000", "amina@example.test", "John Kato", "0711111111", "Parent", studentPhoto("k001"));
        String studentPayload = service.query("""
                SELECT payload FROM sync_outbox WHERE entity='STUDENT' ORDER BY id DESC LIMIT 1""")
                .getFirst().get("payload").toString();
        String userPayload = service.query("""
                SELECT payload FROM sync_outbox WHERE entity='USER' ORDER BY id LIMIT 1""")
                .getFirst().get("payload").toString();
        assertTrue(studentPayload.contains("Amina Kato"));
        assertTrue(studentPayload.contains("guarantorPhone"));
        assertFalse(userPayload.contains("password_hash"));
        long studentId = ((Number) service.query("SELECT id FROM students WHERE student_id='KIU-001'")
                .getFirst().get("id")).longValue();
        service.saveProduct(admin, null, "Standard UGX", "UGX", 100_000, 10_000_000, 12, 12, 1);
        long productId = ((Number) service.query("SELECT id FROM loan_products WHERE name='Standard UGX'")
                .getFirst().get("id")).longValue();

        service.addFunds(finance, "UGX", 5_000_000, "Opening pool");
        Evidence evidence = evidenceFiles("first");
        long submittedApplicationId = service.submitApplication(officer, studentId, productId, 1_000_000, 2,
                "Tuition", evidence.guarantorLetter(), "Finance Director", true, true, evidence.staffId(),
                evidence.academic(), evidence.passportPhoto(), evidence.signature(), true);
        long applicationId = ((Number) service.query("SELECT id FROM loan_applications")
                .getFirst().get("id")).longValue();
        assertEquals(submittedApplicationId, applicationId);
        assertThrows(IllegalArgumentException.class,
                () -> service.decideApplication(officer, applicationId, true, "Self approval"));
        service.decideApplication(admin, applicationId, true, "Approved");
        service.disburse(finance, applicationId, LocalDate.now());

        long loanId = ((Number) service.query("SELECT id FROM loans").getFirst().get("id")).longValue();
        List<Map<String, Object>> schedule = service.installments(loanId);
        assertEquals(2, schedule.size());
        long principalScheduled = schedule.stream().mapToLong(row -> ((Number) row.get("principal")).longValue()).sum();
        assertEquals(1_000_000, principalScheduled);
        long totalScheduled = schedule.stream().mapToLong(row ->
                ((Number) row.get("principal")).longValue() + ((Number) row.get("interest")).longValue()).sum();

        String receipt = service.recordPayment(finance, loanId, totalScheduled, LocalDate.now());
        assertTrue(receipt.matches("RCP-\\d{8}-\\d{5}"));
        assertEquals("CLOSED", service.query("SELECT status FROM loans WHERE id=?", loanId).getFirst().get("status"));
        assertEquals("DISBURSED", service.query("SELECT status FROM loan_applications WHERE id=?", applicationId)
                .getFirst().get("status"));
        assertEquals(0L, ((Number) service.query("""
                SELECT SUM(debits)-SUM(credits) AS imbalance FROM
                (SELECT SUM(jl.debit) AS debits,SUM(jl.credit) AS credits FROM journal_lines jl)""")
                .getFirst().get("imbalance")).longValue());
        assertTrue(service.auditLog().size() >= 8);
        assertTrue(service.unsyncedCount() >= service.auditLog().size() - 2);
        Map<String, Long> dashboard = service.dashboard(admin, "UGX");
        assertEquals(5_000_000 + totalScheduled - 1_000_000, dashboard.get("cash"));
        assertEquals(1_000_000L, dashboard.get("disbursed"));
        assertEquals(totalScheduled, dashboard.get("collected"));
        assertEquals(0L, dashboard.get("pendingRequested"));
        assertTrue(service.monthlyApplications("UGX").stream()
                .anyMatch(row -> ((Number) row.get("applications")).longValue() == 1));
        assertEquals(6, service.monthlyCollectionComposition("UGX").size());
        assertTrue(service.installmentHealth("UGX").stream()
                .anyMatch(row -> "Paid".equals(row.get("category")) && ((Number) row.get("total")).longValue() == 2));
        assertEquals("Standard UGX", service.portfolioByProduct("UGX").getFirst().get("product"));
    }

    @Test
    void rejectsOverpaymentAndAccruesPenaltyOnlyOncePerDay() throws Exception {
        User admin = service.createFirstAdministrator("Office Admin", "admin", "adminpass1".toCharArray());
        service.createUser(admin, "Finance Clerk", "finance", Role.FINANCE, "financepass1".toCharArray());
        User finance = service.login("finance", "financepass1".toCharArray());
        service.createUser(admin, "Loan Officer", "officer", Role.OFFICER, "officerpass1".toCharArray());
        User officer = service.login("officer", "officerpass1".toCharArray());
        service.saveStudent(officer, null, "KIU-002", "Brian Okello", "Science", 1,
                "", "", "Mary Okello", "0722222222", "Parent", studentPhoto("k002"));
        long studentId = ((Number) service.query("SELECT id FROM students WHERE student_id='KIU-002'")
                .getFirst().get("id")).longValue();
        service.saveProduct(admin, null, "Penalty test", "USD", 10_000, 100_000, 0, 1, 3);
        long productId = ((Number) service.query("SELECT id FROM loan_products WHERE name='Penalty test'")
                .getFirst().get("id")).longValue();
        service.addFunds(finance, "USD", 100_000, "Opening USD pool");
        Evidence evidence = evidenceFiles("second");
        service.submitApplication(officer, studentId, productId, 50_000, 1, "Books",
                evidence.guarantorLetter(), "School/College/Faculty Dean", true, true, evidence.staffId(),
                evidence.academic(), evidence.passportPhoto(), evidence.signature(), true);
        long applicationId = ((Number) service.query("SELECT id FROM loan_applications")
                .getFirst().get("id")).longValue();
        service.decideApplication(admin, applicationId, true, "Approved");
        LocalDate overdueDisbursement = LocalDate.now().minusMonths(2);
        service.disburse(finance, applicationId, overdueDisbursement);
        long loanId = ((Number) service.query("SELECT id FROM loans").getFirst().get("id")).longValue();

        service.accrueOverdue(finance, LocalDate.now());
        long penaltyOnce = ((Number) service.installments(loanId).getFirst().get("penalty")).longValue();
        assertTrue(penaltyOnce > 0);
        service.accrueOverdue(finance, LocalDate.now());
        long penaltyTwice = ((Number) service.installments(loanId).getFirst().get("penalty")).longValue();
        assertEquals(penaltyOnce, penaltyTwice);

        assertThrows(IllegalArgumentException.class,
                () -> service.recordPayment(finance, loanId, 99_999_999, LocalDate.now()));
        assertFalse(service.payments().stream().anyMatch(row -> "KIU-002".equals(row.get("studentId"))));
    }

    @Test
    void onlyAdministratorsCanAddOrDeactivateLoanProducts() {
        User admin = service.createFirstAdministrator("Office Admin", "admin", "adminpass1".toCharArray());
        service.createUser(admin, "Loan Officer", "officer", Role.OFFICER, "officerpass1".toCharArray());
        service.createUser(admin, "Finance Clerk", "finance", Role.FINANCE, "financepass1".toCharArray());
        User officer = service.login("officer", "officerpass1".toCharArray());
        User finance = service.login("finance", "financepass1".toCharArray());
        service.saveProduct(admin, null, "Standard UGX", "UGX", 100_000, 10_000_000, 12, 12, 1);
        long productId = ((Number) service.query(
                "SELECT id FROM loan_products WHERE name='Standard UGX'").getFirst().get("id")).longValue();

        assertThrows(SecurityException.class,
                () -> service.saveProduct(officer, null, "Officer product", "UGX", 100_000, 1_000_000, 0, 6, 0));
        assertThrows(SecurityException.class, () -> service.setProductActive(finance, productId, false));
        assertEquals("Active", service.products().getFirst().get("status"));
    }

    @Test
    void studentManagementIsOfficerOnlyAndArchivingPreservesStudentHistory() throws Exception {
        User admin = service.createFirstAdministrator("Office Admin", "admin", "adminpass1".toCharArray());
        service.createUser(admin, "Loan Officer", "officer", Role.OFFICER, "officerpass1".toCharArray());
        User officer = service.login("officer", "officerpass1".toCharArray());
        assertThrows(SecurityException.class, () -> service.saveStudent(admin, null, "KIU-003", "Rose Nambi",
                "Education", 1, "", "", "Guarantor", "0700000000", "", studentPhoto("k003a")));
        service.saveStudent(officer, null, "KIU-003", "Rose Nambi", "Education", 1,
                "", "", "Guarantor", "0700000000", "", studentPhoto("k003"));
        long studentId = ((Number) service.query("SELECT id FROM students WHERE student_id='KIU-003'")
                .getFirst().get("id")).longValue();

        service.setStudentActive(officer, studentId, false);
        assertEquals("Archived", service.students().getFirst().get("status"));
        assertEquals(1, service.query("SELECT COUNT(*) AS total FROM students WHERE id=?", studentId)
                .getFirst().get("total"));
        service.setStudentActive(officer, studentId, true);
        assertEquals("Active", service.students().getFirst().get("status"));
    }

    @Test
    void applicationRequiresAllEvidenceAndCreatesPrivateSignedAgreementPdf() throws Exception {
        User admin = service.createFirstAdministrator("Office Admin", "admin", "adminpass1".toCharArray());
        service.createUser(admin, "Loan Officer", "officer", Role.OFFICER, "officerpass1".toCharArray());
        User officer = service.login("officer", "officerpass1".toCharArray());
        service.saveStudent(officer, null, "KIU-004", "Jane Akello", "Business", 3,
                "", "", "Mary Akello", "0700000000", "Parent", studentPhoto("k004"));
        long studentId = ((Number) service.query("SELECT id FROM students WHERE student_id='KIU-004'")
                .getFirst().get("id")).longValue();
        service.saveProduct(admin, null, "Evidence product", "UGX", 100_000, 2_000_000, 12, 12, 0.5);
        long productId = ((Number) service.query("SELECT id FROM loan_products WHERE name='Evidence product'")
                .getFirst().get("id")).longValue();
        Evidence evidence = evidenceFiles("complete");

        assertThrows(IllegalArgumentException.class, () -> service.submitApplication(officer, studentId, productId,
                500_000, 6, "Tuition", evidence.guarantorLetter(), "Finance Director", true, true,
                evidence.staffId(), evidence.academic(), evidence.passportPhoto(), evidence.signature(), false));
        long applicationId = service.submitApplication(officer, studentId, productId, 500_000, 6, "Tuition",
                evidence.guarantorLetter(), "Finance Director", true, true, evidence.staffId(), evidence.academic(),
                evidence.passportPhoto(), evidence.signature(), true);

        List<Map<String, Object>> storedEvidence = service.applicationEvidence(applicationId);
        assertEquals(6, storedEvidence.size());
        Map<String, Object> agreementRow = storedEvidence.stream()
                .filter(row -> "LOAN_AGREEMENT".equals(row.get("type"))).findFirst().orElseThrow();
        Path agreement = service.resolveApplicationEvidence(agreementRow);
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(agreement.toFile())) {
            assertEquals(4, document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            String contents = stripper.getText(document).replaceAll("\\s+", " ");
            assertTrue(contents.contains("500,000"));
            assertTrue(contents.contains("Finance Director"));
            assertTrue(contents.contains("Jane Akello"));
            assertTrue(contents.contains("KIU-004"));
            assertTrue(contents.contains("Application reference: Application #"));
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            String termsPage = stripper.getText(document);
            assertTrue(termsPage.contains("DRAFT TERMS AND CONDITIONS"));
            assertTrue(termsPage.contains("16. Governing requirements"));
            stripper.setStartPage(3);
            stripper.setEndPage(3);
            assertTrue(stripper.getText(document).contains("PROPOSED REPAYMENT SCHEDULE"));
            stripper.setStartPage(4);
            stripper.setEndPage(4);
            assertTrue(stripper.getText(document).contains("SIGNATORIES AND ACKNOWLEDGMENT"));
            assertTrue(contents.contains("PROPOSED REPAYMENT SCHEDULE"));
            assertTrue(contents.contains("Electronic signing and email delivery are not enabled"));
        }
        Path storedOriginal = service.resolveApplicationEvidence(storedEvidence.stream()
                .filter(row -> "GUARANTOR_LETTER".equals(row.get("type"))).findFirst().orElseThrow());
        assertTrue(Files.isRegularFile(storedOriginal));
        assertTrue(Files.getPosixFilePermissions(storedOriginal).stream()
                .allMatch(permission -> permission.name().startsWith("OWNER_")));
        assertTrue(service.repaymentPreview(500_000, 12, 6, LocalDate.now()).stream()
                .mapToLong(row -> ((Number) row.get("principal")).longValue()).sum() == 500_000);
    }

    private Evidence evidenceFiles(String prefix) throws Exception {
        Path guarantor = pdf(prefix + "-guarantor.pdf");
        Path staffId = pdf(prefix + "-staff.pdf");
        Path academic = pdf(prefix + "-academic.pdf");
        Path signature = pdf(prefix + "-signature.pdf");
        Path passport = temporaryDirectory.resolve(prefix + "-passport.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", passport.toFile());
        return new Evidence(guarantor, staffId, List.of(academic), passport, signature);
    }

    private Path studentPhoto(String prefix) throws Exception {
        Path photo = temporaryDirectory.resolve(prefix + "-photo.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
        return photo;
    }

    private Path pdf(String name) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
        return path;
    }

    private record Evidence(Path guarantorLetter, Path staffId, List<Path> academic,
                            Path passportPhoto, Path signature) {
    }
}
