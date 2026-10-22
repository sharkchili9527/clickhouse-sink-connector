package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LatestImagePerKeyCollapserTest {

    private static final Schema KEY_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA).build();
    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("bucket", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static ClickHouseStruct ev(long offset, ClickHouseConverter.CDC_OPERATION op,
                                       Integer keyId, String beforeBucket, String afterBucket) {
        Struct key = keyId == null ? null : new Struct(KEY_SCHEMA).put("id", keyId);
        int id = keyId == null ? -1 : keyId;
        Struct before = beforeBucket == null ? null : new Struct(ROW_SCHEMA).put("id", id).put("bucket", beforeBucket);
        Struct after = afterBucket == null ? null : new Struct(ROW_SCHEMA).put("id", id).put("bucket", afterBucket);
        return new ClickHouseStruct(offset, "t", key, 0, 0L, before, after, new HashMap<>(), op);
    }

    private static List<Long> offsets(List<ClickHouseStruct> l) {
        List<Long> o = new ArrayList<>();
        for (ClickHouseStruct s : l) {
            o.add(s.getKafkaOffset());
        }
        return o;
    }

    private static final ClickHouseConverter.CDC_OPERATION C = ClickHouseConverter.CDC_OPERATION.CREATE;
    private static final ClickHouseConverter.CDC_OPERATION U = ClickHouseConverter.CDC_OPERATION.UPDATE;
    private static final ClickHouseConverter.CDC_OPERATION D = ClickHouseConverter.CDC_OPERATION.DELETE;
    private static final ClickHouseConverter.CDC_OPERATION T = ClickHouseConverter.CDC_OPERATION.TRUNCATE;

    @Test
    void keepsOnlyTheLastImagePerKeyInOriginalOrder() {
        List<ClickHouseStruct> in = Arrays.asList(
                ev(1, C, 1, null, "a"), ev(2, C, 2, null, "a"),
                ev(3, U, 1, "a", "a"), ev(4, U, 2, "a", "a"), ev(5, U, 1, "a", "a"));
        assertEquals(Arrays.asList(4L, 5L), offsets(LatestImagePerKeyCollapser.collapse(in, null)));
    }

    @Test
    void aTrailingDeleteIsKeptAndWins() {
        List<ClickHouseStruct> in = Arrays.asList(ev(1, C, 1, null, "a"), ev(2, D, 1, "a", null));
        assertEquals(Collections.singletonList(2L), offsets(LatestImagePerKeyCollapser.collapse(in, null)));
    }

    @Test
    void reinsertAfterDeleteKeepsOnlyTheReinsert() {
        List<ClickHouseStruct> in = Arrays.asList(
                ev(1, C, 1, null, "a"), ev(2, D, 1, "a", null), ev(3, C, 1, null, "b"));
        assertEquals(Collections.singletonList(3L), offsets(LatestImagePerKeyCollapser.collapse(in, null)));
    }

    @Test
    void noImageCrossesATruncate() {
        List<ClickHouseStruct> in = Arrays.asList(
                ev(1, C, 1, null, "a"), ev(2, U, 1, "a", "b"),
                ev(3, T, null, null, null),
                ev(4, C, 1, null, "c"), ev(5, U, 1, "c", "d"));
        assertEquals(Arrays.asList(2L, 3L, 5L), offsets(LatestImagePerKeyCollapser.collapse(in, null)));
    }

    @Test
    void recordsWithoutAKeyAreNeverDropped() {
        List<ClickHouseStruct> in = Arrays.asList(ev(1, C, null, null, "a"), ev(2, C, null, null, "a"));
        assertEquals(Arrays.asList(1L, 2L), offsets(LatestImagePerKeyCollapser.collapse(in, null)));
    }

    /** An UPDATE that moves the row on the sorting key leaves that key's events untouched. */
    @Test
    void keysWithASortingKeyRelocationAreLeftAlone() {
        List<String> sortingKey = Arrays.asList("bucket", "id");
        List<ClickHouseStruct> in = Arrays.asList(
                ev(1, C, 1, null, "a"), ev(2, U, 1, "a", "b"), ev(3, U, 1, "b", "b"),
                ev(4, C, 2, null, "a"), ev(5, U, 2, "a", "a"));
        assertEquals(Arrays.asList(1L, 2L, 3L, 5L),
                offsets(LatestImagePerKeyCollapser.collapse(in, sortingKey)));
    }

    @Test
    void inputListIsNotModified() {
        List<ClickHouseStruct> in = new ArrayList<>(Arrays.asList(ev(1, C, 1, null, "a"), ev(2, U, 1, "a", "b")));
        LatestImagePerKeyCollapser.collapse(in, null);
        assertEquals(2, in.size());
    }
}
