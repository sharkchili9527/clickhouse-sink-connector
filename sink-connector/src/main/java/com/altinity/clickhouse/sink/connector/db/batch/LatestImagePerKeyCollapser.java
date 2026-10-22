package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Struct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps only the last change event per primary key within one batch, for
 * ReplacingMergeTree targets.
 *
 * <p>Every event of one MySQL transaction carries the same GTID and commit
 * timestamp, so all of them get the same {@code _version}. When a batch holds
 * several images of one row -- an INSERT followed by UPDATEs in the same
 * transaction, the normal "insert, then fill in the generated columns" pattern
 * -- and those images have different column sets, they are written by different
 * INSERT statements. Which statement runs last is decided by HashMap iteration
 * order, not by binlog order, and with equal versions ReplacingMergeTree keeps
 * whichever row was written last. Measured on SIT (stat_qa.tb_campaign): an
 * intermediate image won in 843 rows, leaving gen_id/search_text NULL and
 * total_time 0 while MySQL had the real values.</p>
 *
 * <p>For ReplacingMergeTree only the final image of a key matters, so dropping
 * the earlier ones within the batch removes the race without changing the end
 * state. Kept unchanged:</p>
 * <ul>
 *   <li>records without a key (keyless tables have nothing to collapse on);</li>
 *   <li>every key that has an UPDATE moving the row to a different sorting key
 *       anywhere in the segment -- each such UPDATE writes a tombstone at the old
 *       position that must not be lost;</li>
 *   <li>TRUNCATE records, which also split the batch into segments that are
 *       collapsed independently, so no image crosses a TRUNCATE.</li>
 * </ul>
 *
 * <p>The input list is never modified.</p>
 */
public final class LatestImagePerKeyCollapser {

    private LatestImagePerKeyCollapser() {
    }

    /**
     * @param records            the batch, in binlog order.
     * @param sortingKeyColumns  the target table's sorting-key columns, may be
     *                           null or empty when unknown.
     * @return a new list holding the records to write, in their original order.
     */
    public static List<ClickHouseStruct> collapse(List<ClickHouseStruct> records,
                                                  List<String> sortingKeyColumns) {
        if (records == null || records.size() < 2) {
            return records;
        }
        List<ClickHouseStruct> result = new ArrayList<>(records.size());
        List<ClickHouseStruct> segment = new ArrayList<>();
        for (ClickHouseStruct record : records) {
            if (record != null && isTruncate(record)) {
                collapseSegment(segment, sortingKeyColumns, result);
                segment.clear();
                result.add(record);
            } else {
                segment.add(record);
            }
        }
        collapseSegment(segment, sortingKeyColumns, result);
        return result;
    }

    private static void collapseSegment(List<ClickHouseStruct> segment,
                                        List<String> sortingKeyColumns,
                                        List<ClickHouseStruct> out) {
        Map<String, Integer> lastIndexByKey = new HashMap<>();
        Set<String> relocatingKeys = new HashSet<>();
        for (int i = 0; i < segment.size(); i++) {
            ClickHouseStruct record = segment.get(i);
            String key = keyOf(record);
            if (key == null) {
                continue;
            }
            lastIndexByKey.put(key, i);
            if (relocatesSortingKey(record, sortingKeyColumns)) {
                relocatingKeys.add(key);
            }
        }
        for (int i = 0; i < segment.size(); i++) {
            ClickHouseStruct record = segment.get(i);
            String key = keyOf(record);
            if (key == null || relocatingKeys.contains(key)
                    || lastIndexByKey.get(key) == i) {
                out.add(record);
            }
        }
    }

    private static String keyOf(ClickHouseStruct record) {
        if (record == null || record.getCdcOperation() == null) {
            return null;
        }
        return record.getKey();
    }

    private static boolean isTruncate(ClickHouseStruct record) {
        return record.getCdcOperation() != null
                && record.getCdcOperation().getOperation().equalsIgnoreCase(
                        ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation());
    }

    /**
     * Same comparison as {@code PreparedStatementExecutor.updateRelocatesSortingKey}:
     * only sorting-key columns present in both images are compared.
     */
    static boolean relocatesSortingKey(ClickHouseStruct record, List<String> sortingKeyColumns) {
        if (sortingKeyColumns == null || sortingKeyColumns.isEmpty()) {
            return false;
        }
        Struct before = record.getBeforeStruct();
        Struct after = record.getAfterStruct();
        if (before == null || after == null) {
            return false;
        }
        for (String keyColumn : sortingKeyColumns) {
            if (before.schema().field(keyColumn) == null || after.schema().field(keyColumn) == null) {
                continue;
            }
            if (!Objects.equals(before.get(keyColumn), after.get(keyColumn))) {
                return true;
            }
        }
        return false;
    }
}
