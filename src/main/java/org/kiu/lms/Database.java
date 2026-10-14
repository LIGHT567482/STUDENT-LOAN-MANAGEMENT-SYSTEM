package org.kiu.lms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class Database {
    private final Path file;
    private final String url;

    public Database() throws IOException, SQLException {
        Path directory = Path.of(System.getProperty("user.home"), ".kiu-loans");
        Files.createDirectories(directory);
        file = directory.resolve("loans.db");
        url = "jdbc:sqlite:" + file;
        initialize();
    }

    public Path file() {
        return file;
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY, username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        display_name TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN
                            ('ADMINISTRATOR','OFFICER','FINANCE','AUDITOR')),
                        salt BLOB NOT NULL, password_hash BLOB NOT NULL, active INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS students (
                        id INTEGER PRIMARY KEY, student_id TEXT NOT NULL UNIQUE, full_name TEXT NOT NULL,
                        faculty TEXT NOT NULL, year_of_study INTEGER NOT NULL, phone TEXT, email TEXT,
                        guarantor_name TEXT NOT NULL, guarantor_phone TEXT NOT NULL, guarantor_relation TEXT,
                        photo_path TEXT,
                        active INTEGER NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            ensureColumn(connection, "students", "active", "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(connection, "students", "photo_path", "TEXT");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS loan_products (
                        id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, currency TEXT NOT NULL CHECK(currency IN ('UGX','USD')),
                        minimum_amount INTEGER NOT NULL CHECK(minimum_amount > 0),
                        maximum_amount INTEGER NOT NULL CHECK(maximum_amount >= minimum_amount),
                        annual_rate REAL NOT NULL CHECK(annual_rate >= 0), max_term_months INTEGER NOT NULL CHECK(max_term_months > 0),
                        monthly_penalty_rate REAL NOT NULL CHECK(monthly_penalty_rate >= 0), active INTEGER NOT NULL DEFAULT 1
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS loan_applications (
                        id INTEGER PRIMARY KEY, student_id INTEGER NOT NULL REFERENCES students(id),
                        product_id INTEGER NOT NULL REFERENCES loan_products(id), currency TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK(amount > 0), term_months INTEGER NOT NULL CHECK(term_months > 0),
                        purpose TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('SUBMITTED','STALLED','APPROVED','REJECTED','DISBURSED')),
                        creator_id INTEGER NOT NULL REFERENCES users(id), decider_id INTEGER REFERENCES users(id),
                        decision_note TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        decided_at TEXT, disbursed_at TEXT
                    )""");
            ensureStalledStatus(connection);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS application_evidence (
                        id INTEGER PRIMARY KEY, application_id INTEGER NOT NULL REFERENCES loan_applications(id),
                        evidence_type TEXT NOT NULL, stored_path TEXT NOT NULL, original_name TEXT NOT NULL,
                        uploaded_by INTEGER NOT NULL REFERENCES users(id),
                        uploaded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS loans (
                        id INTEGER PRIMARY KEY, application_id INTEGER NOT NULL UNIQUE REFERENCES loan_applications(id),
                        student_id INTEGER NOT NULL REFERENCES students(id), product_id INTEGER NOT NULL REFERENCES loan_products(id),
                        currency TEXT NOT NULL, principal INTEGER NOT NULL, annual_rate REAL NOT NULL,
                        penalty_rate REAL NOT NULL, term_months INTEGER NOT NULL, disbursed_on TEXT NOT NULL,
                        status TEXT NOT NULL CHECK(status IN ('ACTIVE','CLOSED'))
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS installments (
                        id INTEGER PRIMARY KEY, loan_id INTEGER NOT NULL REFERENCES loans(id), number INTEGER NOT NULL,
                        due_date TEXT NOT NULL, principal_due INTEGER NOT NULL, interest_due INTEGER NOT NULL,
                        principal_paid INTEGER NOT NULL DEFAULT 0, interest_paid INTEGER NOT NULL DEFAULT 0,
                        penalty_accrued INTEGER NOT NULL DEFAULT 0, penalty_paid INTEGER NOT NULL DEFAULT 0,
                        penalty_through TEXT, status TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK(status IN ('PENDING','PARTIAL','PAID')),
                        UNIQUE(loan_id, number)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS payments (
                        id INTEGER PRIMARY KEY, loan_id INTEGER NOT NULL REFERENCES loans(id),
                        receipt_number TEXT NOT NULL UNIQUE, amount INTEGER NOT NULL, principal_part INTEGER NOT NULL,
                        interest_part INTEGER NOT NULL, penalty_part INTEGER NOT NULL,
                        paid_on TEXT NOT NULL, receiver_id INTEGER NOT NULL REFERENCES users(id),
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
                        currency TEXT NOT NULL CHECK(currency IN ('UGX','USD')), UNIQUE(code,currency)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS journal_entries (
                        id INTEGER PRIMARY KEY, memo TEXT NOT NULL, reference TEXT NOT NULL,
                        currency TEXT NOT NULL, posted_by INTEGER NOT NULL REFERENCES users(id),
                        posted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS journal_lines (
                        id INTEGER PRIMARY KEY, entry_id INTEGER NOT NULL REFERENCES journal_entries(id),
                        account_id INTEGER NOT NULL REFERENCES accounts(id), debit INTEGER NOT NULL DEFAULT 0 CHECK(debit >= 0),
                        credit INTEGER NOT NULL DEFAULT 0 CHECK(credit >= 0), CHECK(NOT (debit > 0 AND credit > 0))
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY, occurred_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        user_id INTEGER REFERENCES users(id), action TEXT NOT NULL, entity TEXT NOT NULL,
                        entity_id TEXT NOT NULL, detail TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, device_id TEXT NOT NULL,
                        entity TEXT NOT NULL, entity_id TEXT NOT NULL, operation TEXT NOT NULL,
                        payload TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, synced_at TEXT
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settings (
                        key TEXT PRIMARY KEY, value TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_students_name ON students(full_name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_applications_status ON loan_applications(status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_installments_due ON installments(due_date,status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_payments_date ON payments(paid_on)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(id DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_evidence_application ON application_evidence(application_id)");
            try (var setting = connection.prepareStatement(
                    "INSERT OR IGNORE INTO settings(key,value) VALUES('device_id',?)")) {
                setting.setString(1, UUID.randomUUID().toString());
                setting.executeUpdate();
            }

            for (String currency : new String[]{"UGX", "USD"}) {
                seedAccount(connection, "1000", "Cash & Bank", "ASSET", currency);
                seedAccount(connection, "1100", "Student Loans Receivable", "ASSET", currency);
                seedAccount(connection, "3000", "Loan Fund Capital", "EQUITY", currency);
                seedAccount(connection, "4000", "Interest Income", "INCOME", currency);
                seedAccount(connection, "4100", "Penalty Income", "INCOME", currency);
            }
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             var columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void ensureStalledStatus(Connection connection) throws SQLException {
        String createSql;
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='table' AND name='loan_applications'")) {
            if (!result.next()) {
                return;
            }
            createSql = result.getString(1);
        }
        if (createSql != null && createSql.contains("STALLED")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate("ALTER TABLE loan_applications RENAME TO loan_applications_old");
            statement.executeUpdate("""
                    CREATE TABLE loan_applications (
                        id INTEGER PRIMARY KEY, student_id INTEGER NOT NULL REFERENCES students(id),
                        product_id INTEGER NOT NULL REFERENCES loan_products(id), currency TEXT NOT NULL,
                        amount INTEGER NOT NULL CHECK(amount > 0), term_months INTEGER NOT NULL CHECK(term_months > 0),
                        purpose TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('SUBMITTED','STALLED','APPROVED','REJECTED','DISBURSED')),
                        creator_id INTEGER NOT NULL REFERENCES users(id), decider_id INTEGER REFERENCES users(id),
                        decision_note TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        decided_at TEXT, disbursed_at TEXT
                    )""");
            statement.executeUpdate("""
                    INSERT INTO loan_applications (id,student_id,product_id,currency,amount,term_months,purpose,status,
                        creator_id,decider_id,decision_note,created_at,decided_at,disbursed_at)
                    SELECT id,student_id,product_id,currency,amount,term_months,purpose,status,
                        creator_id,decider_id,decision_note,created_at,decided_at,disbursed_at
                    FROM loan_applications_old""");
            statement.executeUpdate("DROP TABLE loan_applications_old");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_applications_status ON loan_applications(status)");
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    private void seedAccount(Connection connection, String code, String name, String type, String currency)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO accounts(code,name,type,currency) VALUES(?,?,?,?)")) {
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, type);
            statement.setString(4, currency);
            statement.executeUpdate();
        }
    }
}
