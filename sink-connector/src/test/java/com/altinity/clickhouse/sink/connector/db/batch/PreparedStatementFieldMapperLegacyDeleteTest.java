package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreparedStatementFieldMapperLegacyDeleteTest {

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    @Test
    void deleteBindsNegativeOneToLegacySignColumn() throws Exception {
        Map<Integer, Integer> boundIntegers = new HashMap<>();
        PreparedStatement statement = recordingStatement(boundIntegers);

        Struct before = new Struct(ROW_SCHEMA).put("id", 42);
        ClickHouseStruct record = new ClickHouseStruct(
                10L,
                "mysql.server.table",
                null,
                0,
                System.currentTimeMillis(),
                before,
                null,
                new HashMap<>(),
                ClickHouseConverter.CDC_OPERATION.DELETE);
        record.setVersion(123L);

        Map<String, Integer> columnIndexes = new LinkedHashMap<>();
        columnIndexes.put("id", 1);
        columnIndexes.put("_sign", 2);
        columnIndexes.put("_version", 3);

        Map<String, String> columnTypes = new LinkedHashMap<>();
        columnTypes.put("id", "Int32");
        columnTypes.put("_sign", "Int8");
        columnTypes.put("_version", "UInt64");

        PreparedStatementFieldMapper mapper = new PreparedStatementFieldMapper(
                "_sign",
                false,
                null,
                "_version",
                "test_db",
                ZoneId.of("UTC"));
        ClickHouseSinkConnectorConfig config =
                new ClickHouseSinkConnectorConfig(new HashMap<>());

        mapper.insertPreparedStatement(
                columnIndexes,
                statement,
                record.getBeforeModifiedFields(),
                record,
                before,
                true,
                config,
                columnTypes,
                DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE,
                "table");

        assertEquals(-1, boundIntegers.get(2));
    }

    private static PreparedStatement recordingStatement(
            Map<Integer, Integer> boundIntegers) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setInt".equals(method.getName())) {
                        boundIntegers.put((Integer) args[0], (Integer) args[1]);
                    }
                    if ("setNull".equals(method.getName())) {
                        boundIntegers.remove((Integer) args[0]);
                    }
                    if ("toString".equals(method.getName())) {
                        return "RecordingPreparedStatement";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }
}
