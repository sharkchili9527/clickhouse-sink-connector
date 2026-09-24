package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that waits for DDL schema changes (ALTER TABLE ADD/DROP COLUMN)
 * to become visible in ClickHouse's {@code system.columns} before returning.
 *
 * <p>This class addresses a race condition (GitHub issue #1222) where the
 * connector executes an ALTER TABLE and then immediately reads column
 * metadata. If the ALTER hasn't propagated yet, the connector builds
 * INSERT statements using a stale column list, silently dropping values
 * for newly added columns.</p>
 *
 * <p>The waiter polls {@code system.columns} in a tight loop with
 * configurable timeout and interval.</p>
 */
public class DDLSchemaChangeWaiter {

    private static final Logger log = LogManager.getLogger(DDLSchemaChangeWaiter.class);

    /** Default max wait time (ms) for a schema change to become visible. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Default polling interval (ms) between visibility checks. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 100;

    /**
     * Matches one SQL identifier, either backtick-quoted or bare, as two
     * alternative capturing groups.
     *
     * <p>The previous form was {@code `?([^`\s]+)`?}: the backticks were
     * optional but the capture still forbade whitespace, so
     * {@code ADD COLUMN `space col`} yielded {@code space}. That column never
     * appears in {@code system.columns}, so the waiter blocked for the full
     * 30s timeout on every such DDL (measured 31.9s, reproducible), and the
     * real column was never waited for. On the DROP side the same misparse is
     * worse than slow: the bogus name is absent from the start, so the waiter
     * returns immediately without confirming the real column is gone from
     * every replica.</p>
     *
     * <p>Keeping the quoted and bare cases as separate alternatives is what
     * allows the quoted branch to accept spaces -- and {@code ``} as an
     * escaped backtick -- while the bare branch still stops at whitespace or
     * a comma. Callers must therefore take the first non-null group; see
     * {@link #firstNonNullGroup(Matcher, int...)}.</p>
     */
    private static final String IDENTIFIER = "(?:`((?:``|[^`])+)`|([^\\s,]+))";

    /**
     * Regex to extract column names from ALTER TABLE ... ADD COLUMN statements.
     * Matches both backtick-quoted and unquoted column names.
     *
     * <p>The optional {@code IF NOT EXISTS} is consumed as part of the prefix,
     * not captured as the column name. Without that, the generated DDL
     * {@code ADD COLUMN IF NOT EXISTS ship_spec Nullable(String)} yields the
     * literal column {@code IF}, which never appears in
     * {@code system.columns} -- so the waiter polls a name that cannot exist
     * until it times out, and the real column is never waited for at all.</p>
     */
    static final Pattern ADD_COLUMN_PATTERN = Pattern.compile(
            "ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + IDENTIFIER,
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Regex to extract column names from ALTER TABLE ... DROP COLUMN.
     *
     * <p>Consumes the optional {@code IF EXISTS} for the same reason as
     * {@link #ADD_COLUMN_PATTERN}.</p>
     */
    static final Pattern DROP_COLUMN_PATTERN = Pattern.compile(
            "DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?" + IDENTIFIER,
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Regex to extract database.table from ALTER TABLE statements.
     *
     * <p>Both parts use {@link #IDENTIFIER}, so a quoted database or table
     * name containing spaces resolves to the real name and the waiter queries
     * the right row in {@code system.columns}. The unquoted database
     * alternative additionally excludes {@code .} so it cannot swallow the
     * separator.</p>
     */
    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(?:`((?:``|[^`])+)`|([^`\\s.]+))"
                    + "\\." + IDENTIFIER,
            Pattern.CASE_INSENSITIVE
    );

    private final long timeoutMs;
    private final long pollIntervalMs;

    /** Creates a waiter with default timeout and poll interval. */
    public DDLSchemaChangeWaiter() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Creates a waiter with custom timeout and poll interval.
     *
     * @param timeoutMs      Maximum time to wait for visibility (ms).
     * @param pollIntervalMs Time between polls (ms).
     */
    public DDLSchemaChangeWaiter(long timeoutMs, long pollIntervalMs) {
        this.timeoutMs = timeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Waits for all column additions in the given DDL to become visible
     * in {@code system.columns}. For DROP COLUMN, waits until the column
     * disappears. For DDL that cannot be parsed, applies a conservative
     * fixed delay.
     *
     * @param conn An active JDBC connection to the ClickHouse server.
     * @param ddl  The DDL statement that was just executed.
     */
    public void waitForSchemaVisibility(Connection conn, String ddl) {
        if (conn == null || ddl == null || ddl.isEmpty()) {
            return;
        }

        Matcher tableMatcher = ALTER_TABLE_PATTERN.matcher(ddl);
        if (!tableMatcher.find()) {
            return;
        }

        // Groups 1/2 are the quoted and bare database, 3/4 the table; exactly
        // one of each pair matches.
        String database = firstNonNullGroup(tableMatcher, 1, 2);
        String table = firstNonNullGroup(tableMatcher, 3, 4);
        if (database == null || table == null) {
            return;
        }

        List<String> addedColumns = extractColumnNames(ddl, ADD_COLUMN_PATTERN);
        List<String> droppedColumns = extractColumnNames(ddl, DROP_COLUMN_PATTERN);

        if (!addedColumns.isEmpty()) {
            waitForColumnsToAppear(conn, database, table, addedColumns);
        }

        if (!droppedColumns.isEmpty()) {
            waitForColumnsToDisappear(conn, database, table, droppedColumns);
        }

        if (addedColumns.isEmpty() && droppedColumns.isEmpty()) {
            log.info("DDL does not contain parseable ADD/DROP COLUMN; " +
                    "applying {}ms safety delay for: {}", pollIntervalMs * 5, ddl);
            try {
                Thread.sleep(pollIntervalMs * 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Polls {@code system.columns} until all specified columns appear.
     */
    private void waitForColumnsToAppear(Connection conn, String database,
                                        String table, List<String> columns) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> remaining = new HashSet<>(columns);

        log.info("Waiting for {} column(s) to appear in {}.{}: {}",
                columns.size(), database, table, columns);

        while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Set<String> currentColumns = queryColumnNames(conn, database, table);
                remaining.removeAll(currentColumns);

                if (remaining.isEmpty()) {
                    log.info("All added columns are now visible in {}.{}", database, table);
                    return;
                }

                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for column visibility");
                return;
            } catch (Exception e) {
                log.warn("Error polling system.columns for {}.{}: {}",
                        database, table, e.getMessage());
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!remaining.isEmpty()) {
            log.warn("Timeout ({}ms) waiting for columns to appear in {}.{}: {}. " +
                            "Proceeding anyway -- data loss may occur for these columns. "
                            + "If a name here is a SQL keyword such as IF, the DDL was "
                            + "misparsed and the real column was never waited for.",
                    timeoutMs, database, table, remaining);
        }
    }

    /**
     * Polls {@code system.columns} until all specified columns disappear.
     */
    private void waitForColumnsToDisappear(Connection conn, String database,
                                           String table, List<String> columns) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> remaining = new HashSet<>(columns);

        log.info("Waiting for {} column(s) to disappear from {}.{}: {}",
                columns.size(), database, table, columns);

        while (!remaining.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Set<String> currentColumns = queryColumnNames(conn, database, table);
                remaining.retainAll(currentColumns);

                if (remaining.isEmpty()) {
                    log.info("All dropped columns are no longer visible in {}.{}", database, table);
                    return;
                }

                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for column drop visibility");
                return;
            } catch (Exception e) {
                log.warn("Error polling system.columns for {}.{}: {}",
                        database, table, e.getMessage());
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!remaining.isEmpty()) {
            log.warn("Timeout ({}ms) waiting for columns to disappear from {}.{}: {}",
                    timeoutMs, database, table, remaining);
        }
    }

    /**
     * Queries the current column names for a table from {@code system.columns}.
     */
    private Set<String> queryColumnNames(Connection conn, String database,
                                         String table) throws Exception {
        Set<String> columns = new HashSet<>();
        String sql = String.format(
                "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s'",
                database, table);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    /**
     * Extracts column names from a DDL statement using the given pattern.
     */
    static List<String> extractColumnNames(String ddl, Pattern pattern) {
        List<String> columns = new ArrayList<>();
        Matcher matcher = pattern.matcher(ddl);
        while (matcher.find()) {
            String column = firstNonNullGroup(matcher, 1, 2);
            if (column != null) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * Returns the first of the given groups that matched, with {@code ``}
     * unescaped back to a single backtick so the result compares equal to what
     * {@code system.columns} reports.
     *
     * <p>{@link #IDENTIFIER} offers the quoted and bare forms as alternatives,
     * so only one of its two groups is ever non-null and the caller cannot just
     * read group 1.</p>
     *
     * @param matcher a matcher positioned on a successful match.
     * @param groups  candidate group indexes, in priority order.
     * @return the identifier, or null when none of the groups matched.
     */
    private static String firstNonNullGroup(Matcher matcher, int... groups) {
        for (int group : groups) {
            String value = matcher.group(group);
            if (value != null) {
                return value.replace("``", "`");
            }
        }
        return null;
    }
}
