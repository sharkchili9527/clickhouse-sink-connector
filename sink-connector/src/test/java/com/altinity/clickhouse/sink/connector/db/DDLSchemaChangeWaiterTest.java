package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DDLSchemaChangeWaiter}.
 * Tests the DDL parsing and column name extraction logic.
 */
class DDLSchemaChangeWaiterTest {

    @Test
    void testExtractAddColumnNames() {
        String ddl = "ALTER TABLE `test_db`.`test_table` ADD COLUMN `new_col1` String, ADD COLUMN `new_col2` Int32";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(2, columns.size());
        assertTrue(columns.contains("new_col1"));
        assertTrue(columns.contains("new_col2"));
    }

    @Test
    void testExtractDropColumnNames() {
        String ddl = "ALTER TABLE `test_db`.`test_table` DROP COLUMN `old_col`";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("DROP\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(1, columns.size());
        assertEquals("old_col", columns.get(0));
    }

    @Test
    void testExtractUnquotedColumnNames() {
        String ddl = "ALTER TABLE test_db.test_table ADD COLUMN new_col String";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(1, columns.size());
        assertEquals("new_col", columns.get(0));
    }

    @Test
    void testExtractNoMatchReturnsEmpty() {
        String ddl = "ALTER TABLE test_db.test_table MODIFY COLUMN col1 String";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertTrue(columns.isEmpty());
    }

    @Test
    void testExtractMultipleAddColumns() {
        String ddl = "ALTER TABLE `db`.`tbl` ADD COLUMN `a` Int8, ADD COLUMN `b` String, ADD COLUMN `c` Float64";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(3, columns.size());
        assertEquals("a", columns.get(0));
        assertEquals("b", columns.get(1));
        assertEquals("c", columns.get(2));
    }

    @Test
    void testWaitForSchemaVisibilityNullInputs() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
        // Should not throw on null connection or DDL
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, "ALTER TABLE db.tbl ADD COLUMN x Int32"));
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, null));
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, ""));
    }

    @Test
    void testWaitForSchemaVisibilityNonAlterTable() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
        // Non-ALTER TABLE DDL should return immediately (no table matcher match)
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, "CREATE TABLE db.tbl (id Int32) ENGINE = MergeTree()"));
    }

    @Test
    void testConstructorDefaults() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter();
        assertEquals(30_000, DDLSchemaChangeWaiter.DEFAULT_TIMEOUT_MS);
        assertEquals(100, DDLSchemaChangeWaiter.DEFAULT_POLL_INTERVAL_MS);
    }

    @Test
    void testCaseInsensitiveExtraction() {
        String ddl = "alter table `DB`.`TBL` add column `MixedCase` String";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                DDLSchemaChangeWaiter.ADD_COLUMN_PATTERN);
        assertEquals(1, columns.size());
        assertEquals("MixedCase", columns.get(0));
    }

    // ---------------------------------------------------------------------
    // Quoted identifiers. A backtick-quoted name may contain spaces, commas
    // and reserved words, and `` is an escaped backtick. Getting any of these
    // wrong makes the waiter poll a column that cannot exist, which costs the
    // full 30s timeout on every ADD (measured 31.9s) and makes DROP return
    // without ever confirming the real column is gone.
    // ---------------------------------------------------------------------

    @Test
    void addColumnWithSpaceInQuotedName() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl "
                        + "ADD COLUMN IF NOT EXISTS `space col` Nullable(Int32)",
                DDLSchemaChangeWaiter.ADD_COLUMN_PATTERN);
        assertEquals(List.of("space col"), columns);
    }

    @Test
    void dropColumnWithSpaceInQuotedName() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl DROP COLUMN IF EXISTS `space col`",
                DDLSchemaChangeWaiter.DROP_COLUMN_PATTERN);
        assertEquals(List.of("space col"), columns);
    }

    @Test
    void addColumnMixesQuotedAndBareNames() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl "
                        + "ADD COLUMN IF NOT EXISTS normal_col Int32, "
                        + "ADD COLUMN IF NOT EXISTS `space col` String, "
                        + "ADD COLUMN IF NOT EXISTS CamelCase Int64",
                DDLSchemaChangeWaiter.ADD_COLUMN_PATTERN);
        assertEquals(List.of("normal_col", "space col", "CamelCase"), columns);
    }

    @Test
    void addColumnAcceptsReservedWordAndPunctuation() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl "
                        + "ADD COLUMN `select` Int32, "
                        + "ADD COLUMN `if` Int32, "
                        + "ADD COLUMN `a-b` Int32, "
                        + "ADD COLUMN `a,b` Int32",
                DDLSchemaChangeWaiter.ADD_COLUMN_PATTERN);
        assertEquals(List.of("select", "if", "a-b", "a,b"), columns);
    }

    @Test
    void addColumnUnescapesDoubledBacktick() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl ADD COLUMN `a``b` Int32",
                DDLSchemaChangeWaiter.ADD_COLUMN_PATTERN);
        assertEquals(List.of("a`b"), columns);
    }

    @Test
    void bareNameStopsAtWhitespaceAndComma() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl add column a Int64,add column b DateTime64(3)",
                DDLSchemaChangeWaiter.ADD_COLUMN_PATTERN);
        assertEquals(List.of("a", "b"), columns);
    }

    @Test
    void ifNotExistsIsNotMistakenForTheColumn() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl ADD COLUMN IF NOT EXISTS ship_spec Nullable(String)",
                DDLSchemaChangeWaiter.ADD_COLUMN_PATTERN);
        assertEquals(List.of("ship_spec"), columns);
    }

    @Test
    void dropColumnMixesQuotedAndBareNames() {
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(
                "ALTER TABLE db.tbl "
                        + "DROP COLUMN a5_x, DROP COLUMN `space col`, DROP COLUMN a3_x",
                DDLSchemaChangeWaiter.DROP_COLUMN_PATTERN);
        assertEquals(List.of("a5_x", "space col", "a3_x"), columns);
    }
}
