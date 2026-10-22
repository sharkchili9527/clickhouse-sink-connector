package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end (grouping -> binding -> execution order) reproduction of the SIT
 * stat_qa.tb_campaign inconsistency: 843 rows had gen_id/search_text NULL
 * and total_time 0 in ClickHouse while MySQL had the real values.
 *
 * <p>The application creates a campaign in one transaction -- INSERT with the
 * generated columns still empty, then UPDATE gen_id/search_text, then UPDATE
 * total_time -- so all three events share one _version. The connector wrote
 * them through INSERT statements with different column lists, executed in
 * HashMap order, and ReplacingMergeTree kept the last-written row of the tie:
 * an intermediate image, or an after image stripped of the columns the UPDATE
 * had just filled in.</p>
 *
 * <p>The ClickHouse side is simulated: every executed row is recorded in
 * execution order, and {@link #finalRow} applies the ReplacingMergeTree rule
 * (highest _version wins, ties go to the row written last).</p>
 */
class UpdateNullFillColumnLossTest {

    private static final Schema KEY_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA).build();

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("title", Schema.STRING_SCHEMA)
            .field("gen_id", Schema.OPTIONAL_STRING_SCHEMA)
            .field("total_time", Schema.OPTIONAL_INT32_SCHEMA)
            .field("search_text", Schema.OPTIONAL_STRING_SCHEMA)
            .field("remark", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("title", "String");
        m.put("gen_id", "Nullable(String)");
        m.put("total_time", "Nullable(Int32)");
        m.put("search_text", "Nullable(String)");
        m.put("remark", "Nullable(String)");
        m.put("_sign", "Int8");
        m.put("_version", "UInt64");
        return m;
    }

    /** One executed row: the INSERT it went through and its bound values. */
    static final class WrittenRow {
        final String query;
        final Map<String, Object> values;

        WrittenRow(String query, Map<String, Object> values) {
            this.query = query;
            this.values = values;
        }
    }

    // ---------------------------------------------------------------- builders

    private static Struct row(int id, String gen, Integer total, String search, String remark) {
        return new Struct(ROW_SCHEMA).put("id", id).put("title", "t" + id)
                .put("gen_id", gen).put("total_time", total)
                .put("search_text", search).put("remark", remark);
    }

    private static ClickHouseStruct event(long offset, ClickHouseConverter.CDC_OPERATION op,
                                          Struct before, Struct after, long version) {
        Struct image = after != null ? after : before;
        Struct key = new Struct(KEY_SCHEMA).put("id", image.get("id"));
        ClickHouseStruct s = new ClickHouseStruct(offset, "sit.db_camp.tb_campaign", key, 0,
                System.currentTimeMillis(), before, after, new HashMap<>(), op);
        s.setVersion(version);
        return s;
    }

    private static ClickHouseStruct insert(long offset, Struct after, long version) {
        return event(offset, ClickHouseConverter.CDC_OPERATION.CREATE, null, after, version);
    }

    private static ClickHouseStruct update(long offset, Struct before, Struct after, long version) {
        return event(offset, ClickHouseConverter.CDC_OPERATION.UPDATE, before, after, version);
    }

    // ------------------------------------------------------- pipeline + "ClickHouse"

    /** Runs grouping and execution exactly as ClickHouseBatchRunnable does. */
    private static List<WrittenRow> write(List<ClickHouseStruct> records,
                                          DBMetadata.TABLE_ENGINE engine) throws Exception {
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        Map<String, String> cols = columns();
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> grouped = new HashMap<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(records, grouped, new HashMap<>(),
                config, "tb_campaign", "stat_qa", null, cols, "_sign", null,
                engine, Collections.singletonList("id"));

        List<WrittenRow> written = new ArrayList<>();
        PreparedStatementExecutor executor = new PreparedStatementExecutor("_sign", false, null,
                "_version", "stat_qa", ZoneId.of("UTC"), () -> Collections.singletonList("id"));
        executor.addToPreparedStatementBatch("topic", grouped, new BlockMetaData(), config,
                recordingConnection(written), "tb_campaign", cols, engine);
        return written;
    }

    /** ReplacingMergeTree FINAL: highest _version wins, a tie goes to the row written last. */
    private static Map<String, Object> finalRow(List<WrittenRow> written, int id) {
        Map<String, Object> winner = null;
        long best = Long.MIN_VALUE;
        for (WrittenRow r : written) {
            if (!Integer.valueOf(id).equals(r.values.get("id"))) {
                continue;
            }
            long v = ((Number) r.values.get("_version")).longValue();
            if (v >= best) {
                best = v;
                winner = new HashMap<>(r.values);
                // A column absent from this INSERT gets its DEFAULT. For these
                // Nullable columns that is NULL, except total_time DEFAULT '0'.
                for (String c : columns().keySet()) {
                    if (!r.query.contains("`" + c + "`")) {
                        winner.put(c, "total_time".equals(c) ? 0 : null);
                    }
                }
            }
        }
        return winner;
    }

    private static Connection recordingConnection(List<WrittenRow> written) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        return recordingStatement((String) args[0], written);
                    }
                    return defaultReturn(proxy, method.getName(), args, method.getReturnType());
                });
    }

    private static PreparedStatement recordingStatement(String sql, List<WrittenRow> written) {
        List<String> names = new ArrayList<>();
        int open = sql.indexOf('(');
        int close = sql.indexOf(')', open);
        for (String c : sql.substring(open + 1, close).split(",")) {
            names.add(c.replace("`", "").trim());
        }
        Map<Integer, Object> params = new HashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    String m = method.getName();
                    if (m.startsWith("set") && args != null && args.length >= 2
                            && args[0] instanceof Integer) {
                        params.put((Integer) args[0], "setNull".equals(m) ? null : args[1]);
                        return null;
                    }
                    if ("addBatch".equals(m)) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (int i = 0; i < names.size(); i++) {
                            assertTrue(params.containsKey(i + 1),
                                    "placeholder " + (i + 1) + " (" + names.get(i) + ") left unbound in " + sql);
                            values.put(names.get(i), params.get(i + 1));
                        }
                        written.add(new WrittenRow(sql, values));
                        return null;
                    }
                    if ("executeBatch".equals(m)) {
                        return new int[0];
                    }
                    return defaultReturn(proxy, m, args, method.getReturnType());
                });
    }

    private static Object defaultReturn(Object proxy, String name, Object[] args, Class<?> type) {
        if ("toString".equals(name)) return "recording";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == args[0];
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    // ------------------------------------------------------------------- tests

    /**
     * The production pattern: INSERT, UPDATE gen_id/search_text, UPDATE
     * total_time, all in one transaction (one _version). ClickHouse must end up
     * with the final MySQL row.
     */
    @Test
    void sameTransactionInsertThenTwoUpdatesEndsInFinalImage() throws Exception {
        long v = 123456789L;
        Struct inserted = row(1001, null, 0, null, null);
        Struct afterGen = row(1001, "G-1001", 0, "s-1001", null);
        Struct afterTotal = row(1001, "G-1001", 1200, "s-1001", null);
        List<ClickHouseStruct> batch = Arrays.asList(
                insert(1, inserted, v),
                update(2, inserted, afterGen, v),
                update(3, afterGen, afterTotal, v));

        Map<String, Object> ck = finalRow(write(batch, DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE), 1001);

        assertEquals("G-1001", ck.get("gen_id"));
        assertEquals("s-1001", ck.get("search_text"));
        assertEquals(1200, ck.get("total_time"));
    }

    /**
     * Same scenario, independent of execution order: no row other than the
     * final image may be written at the winning _version. Which of several
     * tied INSERTs runs last is decided by HashMap iteration order -- in this
     * test JVM the final image happens to run last, on SIT the stripped copy
     * did -- so asserting only the winner would pass by luck.
     */
    @Test
    void sameTransactionWritesNoCompetingRowAtTheSameVersion() throws Exception {
        long v = 123456789L;
        Struct inserted = row(1001, null, 0, null, null);
        Struct afterGen = row(1001, "G-1001", 0, "s-1001", null);
        Struct afterTotal = row(1001, "G-1001", 1200, "s-1001", null);
        List<WrittenRow> written = write(Arrays.asList(
                insert(1, inserted, v),
                update(2, inserted, afterGen, v),
                update(3, afterGen, afterTotal, v)),
                DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE);

        assertEquals(1, written.size(), "only the final image may reach ClickHouse at this _version");
        assertEquals(1200, written.get(0).values.get("total_time"));
        assertEquals("G-1001", written.get(0).values.get("gen_id"));
    }

    /**
     * A lone UPDATE that fills NULL columns must be written once, with those
     * columns -- never also as a copy that lacks them.
     */
    @Test
    void updateFillingNullColumnsIsWrittenOnceWithAllColumns() throws Exception {
        long v = 99L;
        Struct before = row(7, null, 0, null, null);
        Struct after = row(7, "G7", 1200, "s7", null);

        List<WrittenRow> written = write(Collections.singletonList(update(1, before, after, v)),
                DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE);

        assertEquals(1, written.size(), "an UPDATE must produce exactly one row");
        assertTrue(written.get(0).query.contains("`gen_id`"));
        assertTrue(written.get(0).query.contains("`search_text`"));
        Map<String, Object> ck = finalRow(written, 7);
        assertEquals("G7", ck.get("gen_id"));
        assertEquals("s7", ck.get("search_text"));
        assertEquals(1200, ck.get("total_time"));
    }

    /**
     * The opposite direction: an UPDATE clearing a column to NULL must write an
     * explicit NULL, not leave the column out (which would apply the DEFAULT --
     * 0 for total_time).
     */
    @Test
    void updateClearingColumnToNullBindsExplicitNull() throws Exception {
        Struct before = row(8, "G8", 1200, "s8", "note");
        Struct after = row(8, "G8", null, "s8", null);

        List<WrittenRow> written = write(Collections.singletonList(update(1, before, after, 5L)),
                DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);

        assertEquals(1, written.size());
        WrittenRow r = written.get(0);
        assertTrue(r.query.contains("`total_time`") && r.query.contains("`remark`"));
        assertNull(r.values.get("total_time"));
        assertNull(r.values.get("remark"));
        assertEquals("G8", r.values.get("gen_id"));
    }

    /** Collapsing is per key: other rows in the batch are all written, in order. */
    @Test
    void otherKeysInTheBatchAreUntouched() throws Exception {
        long v = 10L;
        Struct a0 = row(1, null, 0, null, null);
        Struct a1 = row(1, "G1", 1200, "s1", null);
        Struct b0 = row(2, "G2", 600, "s2", null);
        List<ClickHouseStruct> batch = Arrays.asList(
                insert(1, a0, v), insert(2, b0, v + 1), update(3, a0, a1, v));

        List<WrittenRow> written = write(batch, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);

        assertEquals(2, written.size());
        assertEquals("G1", finalRow(written, 1).get("gen_id"));
        assertEquals(600, finalRow(written, 2).get("total_time"));
    }

    /**
     * Not a ReplacingMergeTree target (engine unknown): the batch is not
     * collapsed, every event is still written.
     */
    @Test
    void nonReplacingMergeTreeTargetIsNotCollapsed() {
        long v = 10L;
        Struct a0 = row(1, null, 0, null, null);
        Struct a1 = row(1, "G1", 1200, "s1", null);
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> grouped = new HashMap<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                Arrays.asList(insert(1, a0, v), update(2, a0, a1, v)), grouped, new HashMap<>(),
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "tb_campaign", "stat_qa",
                null, columns(), "_sign", null, null, Collections.singletonList("id"));
        int n = 0;
        for (List<ClickHouseStruct> l : grouped.values()) {
            n += l.size();
        }
        assertEquals(2, n);
    }
}
