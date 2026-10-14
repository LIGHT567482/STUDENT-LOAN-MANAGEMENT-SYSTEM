# KIU Student Loan Management System

Offline-first desktop application for the KIU finance office. The application uses
Java 25 (the latest LTS release), JavaFX, and an embedded SQLite database. The
same source runs on Windows, macOS, and Linux when built on the target operating
system; it does not require a terminal or network connection at runtime.
The desktop interface and its styling are implemented in Java with JavaFX;
there is no HTML, JavaScript, or external CSS stylesheet. KIU branding and the
supplied logo and login-background image are packaged as image resources.
Validation messages explain why an action cannot continue.
Reports can be exported as CSV or formatted PDF. Loan-product changes are
restricted to administrator accounts.
The dashboard provides currency-specific portfolio and repayment indicators,
application trends, collection composition, product allocation, and
disbursement-versus-collection charts.

## Run from source

Install JDK 25 and Maven on Windows, macOS, or Linux, then run:

```sh
mvn clean javafx:run
```

The first launch asks you to create the first administrator account in the graphical
setup window. Database files are stored in `~/.kiu-loans/`.

## Build

Create a native application image on the target operating system with:

```sh
mvn -Pdesktop-installer clean verify
```

To create a native installer instead, set the matching `jpackage` type:

```sh
# Windows (WiX Toolset required)
mvn -Pdesktop-installer -Djpackage.type=msi clean verify

# macOS (build on macOS)
mvn -Pdesktop-installer -Djpackage.type=dmg clean verify

# Linux (build on Linux)
mvn -Pdesktop-installer -Djpackage.type=deb clean verify
```

Native packages are written under `target/installer/` and bundle the Java runtime.
Build each installer on its target operating system. Android is not supported by
the JavaFX desktop client; native Android support is a separate future project.

## Roles

The application provides Administrator, Officer, Finance, and Auditor roles. New
installations have no default credentials; the first user is created in the GUI.

Only administrators can add, edit, or deactivate loan products. Only officers can
add, edit, archive, or restore student records; archiving preserves the student
and financial history. Other roles can view student records.

Loan applications require scanned PDF copies of the stamped guarantor letter,
staff ID, academic records, and applicant signature, plus a JPG/PNG passport
photo. The officer must attest that the guarantor signed the letter and that it
bears a stamp from the Finance Director or a School/College/Faculty Dean. The
applicant reviews and accepts the configured product terms and proposed
repayment schedule before submission. The application form can render a
multi-page draft agreement preview before submission. The generated PDF includes
applicant, student, guarantor, and requested-loan details; a dedicated page of
proposed terms and conditions; the proposed repayment schedule; and a final
signatory page. Submitted agreements also include the supplied applicant
signature scan. The terms are clearly marked as a working draft pending
institutional and legal review; they are not approved legal wording. Final
repayment dates are set at disbursement. Electronic signing and email delivery
are not enabled in this proof of concept.

Original application files and generated agreements are retained in the
restricted `~/.kiu-loans/application-evidence/` folder, with references in the
database. Encrypted backups include this evidence alongside the database, and
restore validates that referenced evidence is present.
# STUDENT-LOAN-MANAGEMENT-SYSTEM
