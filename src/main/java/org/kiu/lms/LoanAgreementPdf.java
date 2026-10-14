package org.kiu.lms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.Loader;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class LoanAgreementPdf {
    private static final float MARGIN = 48;
    private static final float LINE_HEIGHT = 15;
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    public record Details(String reference, String studentName, String studentId, String faculty, int yearOfStudy,
                          String studentPhone, String studentEmail, String guarantorName, String guarantorPhone,
                          String guarantorRelation, String productName, String currency, long principal,
                          double annualRate, double monthlyPenaltyRate, int term, String purpose,
                          String stampAuthority, LocalDate preparedOn, boolean draftPreview) {
    }

    private LoanAgreementPdf() {
    }

    public static void write(Path path, Details details, List<Map<String, Object>> installments,
                             Path applicantSignature) throws IOException {
        try (PDDocument document = new PDDocument()) {
            long estimatedTotal = installments.stream().mapToLong(installment ->
                    ((Number) installment.get("principal")).longValue()
                            + ((Number) installment.get("interest")).longValue()).sum();
            writeLoanDetailsPage(document, details, estimatedTotal);
            writeDraftTermsPage(document);
            for (int start = 0; start < installments.size(); start += 25) {
                PDPage schedulePage = new PDPage(PDRectangle.LETTER);
                document.addPage(schedulePage);
                float scheduleY = schedulePage.getMediaBox().getHeight() - MARGIN;
                try (PDPageContentStream stream = new PDPageContentStream(document, schedulePage)) {
                    scheduleY = heading(stream, schedulePage, "PROPOSED REPAYMENT SCHEDULE", scheduleY);
                    scheduleY = paragraph(stream, schedulePage, "This schedule is an estimate based on the selected product. Final due dates are confirmed at disbursement.",
                            scheduleY, false);
                    int end = Math.min(start + 25, installments.size());
                    for (int index = start; index < end; index++) {
                        Map<String, Object> installment = installments.get(index);
                        long principalDue = ((Number) installment.get("principal")).longValue();
                        long interestDue = ((Number) installment.get("interest")).longValue();
                        scheduleY = paragraph(stream, schedulePage, "Installment " + installment.get("number") + " - "
                                + installment.get("due") + ": principal "
                                + Money.format(principalDue, details.currency())
                                + ", interest " + Money.format(interestDue, details.currency()) + ", total "
                                + Money.format(principalDue + interestDue, details.currency()), scheduleY, false);
                    }
                }
            }
            writeSignatoryPage(document, details, applicantSignature);
            document.save(path.toFile());
        }
    }

    private static void writeLoanDetailsPage(PDDocument document, Details details, long estimatedTotal)
            throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        float y = page.getMediaBox().getHeight() - MARGIN;
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            y = heading(stream, page, Branding.loginBrand(), y);
            y = paragraph(stream, page, "LOAN APPLICATION AND AGREEMENT DETAILS", y, true);
            y = paragraph(stream, page, details.draftPreview()
                    ? "DRAFT PREVIEW - NOT SUBMITTED OR INSTITUTION-APPROVED"
                    : "Application reference: " + details.reference(), y, true);
            y = paragraph(stream, page, "Prepared: " + details.preparedOn(), y, false);
            y = paragraph(stream, page, "Applicant: " + details.studentName(), y, false);
            y = paragraph(stream, page, "Student ID: " + details.studentId(), y, false);
            y = paragraph(stream, page, "Faculty / year of study: " + details.faculty() + " / "
                    + details.yearOfStudy(), y, false);
            y = paragraph(stream, page, "Applicant phone / email: " + present(details.studentPhone())
                    + " / " + present(details.studentEmail()), y, false);
            y = paragraph(stream, page, "Guarantor: " + details.guarantorName(), y, false);
            y = paragraph(stream, page, "Guarantor phone / relationship: " + details.guarantorPhone()
                    + " / " + present(details.guarantorRelation()), y, false);
            y = paragraph(stream, page, "Loan product: " + details.productName(), y, false);
            y = paragraph(stream, page, "Requested principal: "
                    + Money.format(details.principal(), details.currency()), y, false);
            y = paragraph(stream, page, "Purpose: " + details.purpose(), y, false);
            y = paragraph(stream, page, "Annual interest rate: " + details.annualRate() + "%", y, false);
            y = paragraph(stream, page, "Configured monthly late-payment rate: "
                    + details.monthlyPenaltyRate() + "% (only as configured for this product).", y, false);
            y = paragraph(stream, page, "Requested repayment term: " + details.term() + " months", y, false);
            y = paragraph(stream, page, "Estimated total repayment: "
                    + Money.format(estimatedTotal, details.currency()), y, true);
            y = paragraph(stream, page, "Guarantor letter stamp authority attested as: "
                    + details.stampAuthority() + ".", y, false);
            y = paragraph(stream, page, "The estimated schedule is a proposal. Final amounts and dates are confirmed only if the application is approved and the loan is disbursed.",
                    y, false);
            paragraph(stream, page, "A full-page draft of the proposed terms and conditions follows. The final page is reserved for signatures.",
                    y, false);
        }
    }

    private static void writeDraftTermsPage(PDDocument document) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        float y = page.getMediaBox().getHeight() - MARGIN;
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            y = heading(stream, page, "DRAFT TERMS AND CONDITIONS", y);
            y = paragraph(stream, page, "WORKING DRAFT ONLY - These proposed terms are for review and discussion. They are not institution-approved legal wording and do not create a loan or guarantee obligation before formal approval and disbursement.",
                    y, true);
            String[] terms = {
                    "1. Application and approval. The applicant's request is subject to review, verification, and written approval by the institution. Submission or preview of this document is not a promise to lend.",
                    "2. Applicant information. The applicant is responsible for providing complete and accurate identity, contact, academic, financial, and supporting-document information, and for promptly correcting any material error.",
                    "3. Amount and disbursement. The amount shown on the details page is requested principal, not a confirmation of funds advanced. Any approved amount and disbursement date must be confirmed in the final approval and loan records.",
                    "4. Interest and repayment estimate. The displayed rate and schedule are based on the selected product and requested term. Actual balances, installment amounts, and due dates must be confirmed in the final disbursement schedule.",
                    "5. Repayment. If a loan is approved and disbursed, the borrower is expected to make payments by the due dates in the final schedule using a payment channel identified by the institution, and to retain the resulting receipts.",
                    "6. Payment allocation and balance. Payments are recorded and allocated under the institution's approved accounting and loan-servicing procedures. The institution should provide a statement of the balance on request.",
                    "7. Late payment. Any late-payment charge may apply only where permitted, disclosed for the selected product, and consistent with applicable law and approved institutional policy. This draft does not introduce any additional fee.",
                    "8. Early or additional payments. The borrower should contact the institution for a current settlement or balance statement before making an early settlement or an additional payment, so that the payment can be correctly recorded.",
                    "9. Guarantor documentation. The guarantor named in the application and any signed guarantor letter are supporting records. The existence, scope, and enforceability of a guarantee depend on separate signed documentation and applicable law; this draft does not enlarge that obligation.",
                    "10. Changes in contact details. The borrower and guarantor should keep the contact information supplied with the application current and notify the institution promptly of any change.",
                    "11. Statements and discrepancies. The borrower should review payment receipts and account statements and promptly notify the institution of a suspected posting error, attaching the relevant receipt or supporting record.",
                    "12. Missed payments and recovery. Any response to missed payments, including notices or recovery steps, must follow applicable law and formally approved institutional procedures. No unlisted remedy is created by this draft.",
                    "13. Records and personal information. Application, loan, guarantor, and payment records are retained and accessed for legitimate administration, audit, and legal purposes under applicable law and institutional policy.",
                    "14. Amendments and questions. A change to approved loan terms is effective only when recorded through the institution's authorized written process. Questions or disputes should first be raised with the designated loan office.",
                    "15. Review and acceptance. Before signing a final agreement, the applicant should review the approved principal, rate, schedule, and applicable institutional terms, and may request clarification of any provision.",
                    "16. Governing requirements. Any final agreement remains subject to applicable law and the institution's duly approved policies. Where this working draft conflicts with either, the applicable law and approved final documents prevail."
            };
            for (String term : terms) {
                y = paragraph(stream, page, term, y, false);
            }
            paragraph(stream, page, "DRAFT - Pending institutional and legal review.", y - 2, true);
        }
    }

    private static void writeSignatoryPage(PDDocument document, Details details, Path applicantSignature)
            throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        float y = page.getMediaBox().getHeight() - MARGIN;
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            y = heading(stream, page, "SIGNATORIES AND ACKNOWLEDGMENT", y);
            y = paragraph(stream, page, details.draftPreview()
                    ? "DRAFT PREVIEW - Signatures below are intentionally left blank."
                    : "Application reference: " + details.reference(), y, true);
            y = paragraph(stream, page, "The applicant confirms that they have reviewed the loan details and the draft terms presented with this application. Any final obligation is subject to formal institutional approval and the final disbursement documents.",
                    y, false);
            y -= 16;
            y = signatureBlock(stream, page, "Applicant / borrower", details.studentName(), y);
            y = signatureBlock(stream, page, "Guarantor acknowledgment", details.guarantorName(), y);
            y = signatureBlock(stream, page, "Authorized institutional representative", "Name and title", y);
            y = signatureBlock(stream, page, "Witness (if required)", "Name", y);
            y = paragraph(stream, page, "Guarantor letter stamp authority attested as: "
                    + details.stampAuthority() + ".", y, false);
            paragraph(stream, page, "The signed guarantor letter is retained separately with the application evidence. Electronic signing and email delivery are not enabled in this local proof of concept.",
                    y, false);
            if (applicantSignature != null) {
                y = paragraph(stream, page, "Applicant signature scan supplied with application:", y - 6, true);
                drawApplicantSignature(document, stream, page, applicantSignature, y);
            } else {
                paragraph(stream, page, "Applicant signature scan: not supplied for this preview.", y - 6, false);
            }
        }
    }

    private static float signatureBlock(PDPageContentStream stream, PDPage page, String role, String name, float y)
            throws IOException {
        y = paragraph(stream, page, role + ": " + name, y, true);
        stream.setStrokingColor(new Color(70, 90, 75));
        stream.moveTo(MARGIN, y - 18);
        stream.lineTo(page.getMediaBox().getWidth() - MARGIN, y - 18);
        stream.stroke();
        return paragraph(stream, page, "Signature: ____________________________________    Date: __________________",
                y - 34, false) - 18;
    }

    private static void drawApplicantSignature(PDDocument document, PDPageContentStream stream, PDPage page,
                                               Path applicantSignature, float y) throws IOException {
        try (PDDocument signatureDocument = Loader.loadPDF(applicantSignature.toFile())) {
            if (signatureDocument.getNumberOfPages() == 0) {
                throw new IOException("The applicant signature PDF contains no pages.");
            }
            BufferedImage signatureImage = new PDFRenderer(signatureDocument)
                    .renderImage(0, 1.2f, ImageType.RGB);
            PDImageXObject signature = LosslessFactory.createFromImage(document, signatureImage);
            float maxWidth = page.getMediaBox().getWidth() - 2 * MARGIN;
            float maxHeight = 80;
            float scale = Math.min(maxWidth / signature.getWidth(), maxHeight / signature.getHeight());
            stream.drawImage(signature, MARGIN, y - signature.getHeight() * scale,
                    signature.getWidth() * scale, signature.getHeight() * scale);
        }
    }

    private static String present(String value) {
        return value == null || value.isBlank() ? "Not provided" : value;
    }

    private static float heading(PDPageContentStream stream, PDPage page, String text, float y) throws IOException {
        stream.setNonStrokingColor(new Color(24, 115, 67));
        stream.beginText();
        stream.setFont(BOLD, 19);
        stream.newLineAtOffset(MARGIN, y);
        stream.showText(text);
        stream.endText();
        return y - 30;
    }

    private static float paragraph(PDPageContentStream stream, PDPage page, String text, float y, boolean bold)
            throws IOException {
        float fontSize = bold ? 10 : 9;
        PDType1Font font = bold ? BOLD : REGULAR;
        float maxWidth = page.getMediaBox().getWidth() - 2 * MARGIN;
        for (String line : wrap(text, font, fontSize, maxWidth)) {
            if (y < MARGIN) {
                throw new IOException("The agreement terms are too long to fit on one page.");
            }
            stream.beginText();
            stream.setNonStrokingColor(new Color(35, 48, 39));
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(line);
            stream.endText();
            y -= LINE_HEIGHT;
        }
        return y - 2;
    }

    private static List<String> wrap(String text, PDType1Font font, float fontSize, float maxWidth)
            throws IOException {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.replace('\n', ' ').split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.getStringWidth(candidate) / 1000 * fontSize > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                if (!line.isEmpty()) {
                    line.append(' ');
                }
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
