package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end reproduction of the SIT stat_qa.tb_campaign inconsistency on
 * ClickHouse 22.8 with GTID on, so every event of a transaction shares one
 * _version exactly as on SIT.
 *
 * <p>Three workloads, each compared row by row (all columns) between MySQL and
 * ClickHouse FINAL:</p>
 * <ol>
 *   <li>one transaction per row: INSERT with gen_id/search_text NULL and
 *       total_time 0, UPDATE gen_id/search_text, UPDATE total_time -- the
 *       application's "create campaign" path;</li>
 *   <li>a separate-transaction UPDATE filling NULL columns;</li>
 *   <li>a separate-transaction UPDATE clearing columns to NULL.</li>
 * </ol>
 */
@Testcontainers
@DisplayName("UPDATE filling or clearing NULL columns must reach ClickHouse unchanged (CK 22.8, GTID)")
public class UpdateNullFillSameTransactionIT {

    private static final int ROWS = 60;

    private MySQLContainer<?> mySqlContainer;
    private ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withCommand("--gtid-mode=ON", "--enforce-gtid-consistency=ON")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));
        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:22.8")
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @AfterEach
    public void stopContainers() {
        if (mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
    }

    @Test
    public void everyRowMatchesMySqlAfterNullFillAndNullClearUpdates() throws Exception {
        Injector injector = Guice.createInjector(new AppInjector());
        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        // Same NULL handling as dist/config.yml.template: without these two,
        // ClickHouse turns a NULL bound into a DEFAULT 0 column back into 0.
        props.setProperty("non.default.value", "true");
        props.setProperty("clickhouse.jdbc.settings",
                "input_format_null_as_default=0,allow_experimental_object_type=1,insert_allow_materialized_columns=1");

        ClickHouseDebeziumEmbeddedApplication app = new ClickHouseDebeziumEmbeddedApplication();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                app.start(injector.getInstance(DebeziumRecordParserService.class), props, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(25000);

        Connection my = ITCommon.connectToMySQL(mySqlContainer);
        try (Statement st = my.createStatement()) {
            st.execute("CREATE TABLE tb_campaign (id INT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(64) NOT NULL,"
                    + " gen_id VARCHAR(64) NULL, total_time INT NULL DEFAULT 0, search_text VARCHAR(128) NULL,"
                    + " remark VARCHAR(64) NULL, deleted TINYINT NULL DEFAULT 0)");
        }
        Thread.sleep(5000);

        // 1) create-campaign path: INSERT + two UPDATEs in ONE transaction.
        my.setAutoCommit(false);
        try (Statement st = my.createStatement()) {
            for (int i = 0; i < ROWS; i++) {
                st.execute("INSERT INTO tb_campaign (title, total_time) VALUES ('tx-" + i + "', 0)");
                st.execute("UPDATE tb_campaign SET gen_id = CONCAT('G-', id), search_text = CONCAT('s-', id)"
                        + " WHERE id = LAST_INSERT_ID()");
                st.execute("UPDATE tb_campaign SET total_time = 1200 WHERE id = LAST_INSERT_ID()");
                my.commit();
            }
        }
        my.setAutoCommit(true);

        // 2) separate transactions: INSERT, then later UPDATE filling the NULLs.
        try (Statement st = my.createStatement()) {
            for (int i = 0; i < ROWS; i++) {
                st.execute("INSERT INTO tb_campaign (title, total_time, remark) VALUES ('sep-" + i + "', 0, 'note')");
            }
            st.execute("UPDATE tb_campaign SET gen_id = CONCAT('G-', id), search_text = CONCAT('s-', id),"
                    + " total_time = 600 WHERE title LIKE 'sep-%'");
            // 3) clear to NULL: total_time has DEFAULT 0, so dropping the column
            //    from the INSERT instead of binding NULL would show up as 0.
            st.execute("UPDATE tb_campaign SET total_time = NULL, remark = NULL, deleted = NULL"
                    + " WHERE title LIKE 'sep-%' AND MOD(id, 2) = 0");
        }

        Map<Integer, String> mysqlRows = rows(my,
                "SELECT id, title, gen_id, total_time, search_text, remark, deleted FROM tb_campaign");
        assertEquals(ROWS * 2, mysqlRows.size());

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        Map<Integer, String> ckRows = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            Thread.sleep(3000);
            try {
                ckRows = rows(writer.getConnection(), "SELECT id, title, gen_id, total_time, search_text, remark,"
                        + " deleted FROM employees.tb_campaign FINAL");
            } catch (Exception e) {
                continue; // table not auto-created yet
            }
            if (mysqlRows.equals(ckRows)) {
                break;
            }
        }

        int mismatched = 0;
        StringBuilder sample = new StringBuilder();
        for (Map.Entry<Integer, String> e : mysqlRows.entrySet()) {
            String ck = ckRows == null ? null : ckRows.get(e.getKey());
            if (!e.getValue().equals(ck)) {
                if (mismatched++ < 5) {
                    sample.append("\n  mysql=").append(e.getValue()).append("\n  ck   =").append(ck);
                }
            }
        }
        // FINAL can still come out right by luck: with tied _version the winner
        // is whichever INSERT ran last, i.e. HashMap order of the INSERT texts.
        // On SIT the stripped copy ran last; here it may not. So also require
        // that no key ever had two DIFFERENT rows written at the same _version.
        long tiedConflicts = 0;
        try (ResultSet rs = writer.getConnection().createStatement().executeQuery(
                "SELECT count() FROM (SELECT id, _version FROM employees.tb_campaign GROUP BY id, _version"
                        + " HAVING uniqExact(tuple(title, gen_id, total_time, search_text, remark, deleted)) > 1)")) {
            if (rs.next()) {
                tiedConflicts = rs.getLong(1);
            }
        }
        app.getDebeziumEventCapture().engine.close();
        executorService.shutdown();
        HikariDbSource.close();
        my.close();

        assertEquals(0, mismatched, mismatched + " of " + mysqlRows.size()
                + " rows differ between MySQL and ClickHouse FINAL:" + sample);
        assertEquals(0, tiedConflicts, tiedConflicts
                + " (id, _version) pairs were written with conflicting contents");
    }

    private static Map<Integer, String> rows(Connection conn, String sql) throws Exception {
        Map<Integer, String> out = new TreeMap<>();
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    sb.append(i == 1 ? "" : "|").append(v == null ? "NULL" : v.toString());
                }
                out.put(rs.getInt(1), sb.toString());
            }
        }
        return out;
    }
}
