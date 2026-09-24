# MySQL → ClickHouse 22.8 同步能力实测报告

- 实测日期：2026-09-04
- 镜像：`clickhouse_debezium_embedded:ck22.8-fix-20260904-2`（linux/amd64）
- 环境：开发机 `<DEV_HOST>`（连接器）+ CK 22.8 双副本（`<CK1_HOST>` / `<CK2_HOST>`）+ MySQL 8.0.30-txsql `<MYSQL_HOST>`
- 演示表：`db_camp.tb_campaign_work_order` → `stat_qa.tb_campaign_work_order`
- 原始脚本与日志：开发机 `/root/auto_probe.sh` `/root/probe_create.sh` `/root/verify_ctas.sh` `/root/repro_system_db.sh` `/root/gen_bulk.sh` `/root/run_perf.sh` `/root/run_perf2.sh`，日志 `/root/probe_A2.log` `/root/probe_C1.log` `/root/probe_C2.log` `/root/perf.log` `/root/perf2.log`

---

## 一、CREATE TABLE 到底能不能同步

结论一句话：**建表语句本身一定会同步，数据行只在这张表进了 `table.include.list` 时才同步。**

过滤器是两套，作用范围不同：

- **DDL**（CREATE / ALTER / DROP / TRUNCATE）只看 `database.include.list`
- **DML**（INSERT / UPDATE / DELETE）只看 `table.include.list`

所以一张没进白名单的新表，在 CK 里会有完整结构（含列注释、`ON CLUSTER {cluster}`）但永远 0 行 —— 就是"幽灵空表"。`ddl_probe.sh:200` 那句注释的主语是数据行、不是建表，写得有歧义，是此前误解的来源。

### 情形 C1：表**不在** `table.include.list`

**① 同步语句**

```sql
CREATE TABLE `db_camp`.`t_demo_a` (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  biz_no VARCHAR(64) NOT NULL COMMENT '业务编号',
  name VARCHAR(128) DEFAULT NULL COMMENT '名称',
  amount DECIMAL(12,2) DEFAULT NULL COMMENT '金额',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**② MySQL 查询结果**

```
COLUMN_NAME  COLUMN_TYPE     IS_NULLABLE  COLUMN_COMMENT
id           bigint          NO           主键
biz_no       varchar(64)     NO           业务编号
name         varchar(128)    YES          名称
amount       decimal(12,2)   YES          金额
```

**③ ClickHouse 查询结果（1s 后）**

```
┌─name─────┬─type─────────────────────┬─comment──┐
│ id       │ Int64                    │ 主键     │
│ biz_no   │ String                   │ 业务编号 │
│ name     │ Nullable(String)         │ 名称     │
│ amount   │ Nullable(Decimal(12, 2)) │ 金额     │
│ _sign    │ Int8                     │          │
│ _version │ UInt64                   │          │
└──────────┴──────────────────────────┴──────────┘
```

**结论**：结构 + 列注释 + 类型映射全部正确。

紧接着插 2 行：

```sql
INSERT INTO `db_camp`.`t_demo_a` (biz_no,name,amount)
VALUES ('A-1','未订阅行1',11.11),('A-2','未订阅行2',22.22);
```

MySQL `mysql_rows = 2`，CK 等 25s 后 `ck_rows = 0`。**结论：数据不同步，符合预期。**

### 情形 C2：表**已在** `table.include.list`（先改配置重启，再建表）

同样的 CREATE TABLE 打到 `t_demo_b`，结构 1s 到位（输出同上）。然后：

```sql
INSERT INTO `db_camp`.`t_demo_b` (biz_no,name,amount)
VALUES ('B-1','已订阅行1',11.11),('B-2','已订阅行2',22.22);
```

MySQL

```
id  biz_no  name       amount
1   B-1     已订阅行1  11.11
2   B-2     已订阅行2  22.22
```

ClickHouse（1s）

```
┌─id─┬─biz_no─┬─name──────┬─amount─┐
│  1 │ B-1    │ 已订阅行1 │  11.11 │
│  2 │ B-2    │ 已订阅行2 │  22.22 │
└────┴────────┴───────────┴────────┘
```

**结论：结构和数据都对。这就是"DML 也要同步"的正确做法。**

### 情形 C4：建表在前、加白名单重启在后 —— 历史行会不会补？

实测那 2 行**确实到了**（`ck_rows = 2`），但**这不是保证，不要依赖**。机制是：offset 只在有记录真正发出时才推进，被过滤掉的表不推进 offset，所以重启后从旧位点重放 binlog，才把那 2 行捞回来。一旦这期间别的订阅表有流量把 offset 推过去了，这些行就永久丢失，而且没有任何报错。

重启后新插的行正常：

```sql
INSERT INTO `db_camp`.`t_demo_a` (biz_no,name,amount) VALUES ('A-3','重启后新行',33.33);
```

两边都是 3 行，完全一致。

> **正确顺序：先改 `table.include.list` → 重启 → 再建表写数据。** 反过来做等于赌 offset 有没有推过去。想彻底免掉这个重启动作，见下面 C5 的黑名单模式。

### 情形 C3：建表语句里带数据行 —— `CREATE TABLE ... AS SELECT` 绝对不能用

**① 同步语句**

```sql
CREATE TABLE `db_camp`.`t_demo_c` AS SELECT * FROM `db_camp`.`t_demo_b`;  -- 源表 3 行
```

**② MySQL 查询结果**

```
mysql_rows
3

Create Table: CREATE TABLE `t_demo_c` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '主键',
  `biz_no` varchar(64) NOT NULL COMMENT '业务编号',
  `name` varchar(128) DEFAULT NULL COMMENT '名称',
  `amount` decimal(12,2) DEFAULT NULL COMMENT '金额'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
```

注意：**MySQL 的 CTAS 不复制 PRIMARY KEY**，`id` 只剩 `NOT NULL DEFAULT '0'`。

**③ ClickHouse 查询结果**

```
┌─ck_rows_no_final─┐        ┌─ck_rows_with_final─┐
│                1 │        │                  1 │
└──────────────────┘        └────────────────────┘

┌─id─┬─biz_no─┬─name─┐
│  3 │ X-3    │ 行3  │
└────┴────────┴──────┘

CREATE TABLE stat_qa.t_demo_c ( ... )
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}', _version)
ORDER BY tuple()
```

**结论：MySQL 3 行 → CK 1 行，丢了 2 行，连不加 `FINAL` 都只有 1 行。**

根因在 `ORDER BY tuple()`：没有主键 → 排序键是空元组 → 整张表所有行共享同一个去重键 → ReplacingMergeTree 后台 merge 直接把 3 行塌成 `_version` 最大的那 1 行。日志里 batch 是"Records: 2"写成功的，数据是**写进去之后被 merge 吃掉的**，所以查不出任何错误。

连接器自己也报了警：

```
!!  KEYLESS TABLE -- BAD PRACTICE, FIX THIS AS SOON AS POSSIBLE  !!
  Table : stat_qa.`db_camp`.`t_demo_c`
  Problem: It declares NO PRIMARY KEY and NO non-null UNIQUE key ...
  Consequence: Rows that MySQL considers distinct collapse into one in ClickHouse ...
```

要建表带数据，拆成两步：`CREATE TABLE ...`（显式写 PRIMARY KEY）+ `INSERT INTO ... SELECT`。

### 情形 C5：黑名单模式（`table.exclude.list`）—— 新建表免重启自动同步

上面 C1/C2/C4 都是白名单模式，新表必须"改配置 + 重启"。换成黑名单可以彻底免掉这个运维负担，**已实测确认**。

配置只改这一行：

```yaml
# 原来（白名单，逐表列出）
table.include.list: 'db_base\.tb_as_product,...(20 张)'
# 换成（黑名单，只列不要的）
table.exclude.list: 'db_camp\.tb_bulk_perf,db_base\.tb_ab_unsubscribed'
```

改完重启一次，之后全程**没有再重启**。

**步骤 1：新建 t_demo_f 并插 2 行**

```sql
CREATE TABLE `db_camp`.`t_demo_f` (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  biz_no VARCHAR(64) NOT NULL COMMENT '业务编号',
  name VARCHAR(128) DEFAULT NULL COMMENT '名称',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='黑名单模式新表';

INSERT INTO `db_camp`.`t_demo_f` (biz_no,name) VALUES ('F-1','黑名单模式行1'),('F-2','黑名单模式行2');
```

MySQL

```
id  biz_no  name
1   F-1     黑名单模式行1
2   F-2     黑名单模式行2
```

ClickHouse

```
┌─name─────┬─type─────────────┬─comment──┐
│ id       │ Int64            │ 主键     │
│ biz_no   │ String           │ 业务编号 │
│ name     │ Nullable(String) │ 名称     │
│ _sign    │ Int8             │          │
│ _version │ UInt64           │          │
└──────────┴──────────────────┴──────────┘
┌─id─┬─biz_no─┬─name──────────┐
│  1 │ F-1    │ 黑名单模式行1 │
│  2 │ F-2    │ 黑名单模式行2 │
└────┴────────┴───────────────┘
```

**结论：结构 + 数据都到，零重启。**

**步骤 2：再建第二张 t_demo_g，仍然不重启**

MySQL `1 / G-1 / 第二张新表` → ClickHouse 完全一致。说明**不是"重启后那一次生效"，是持续生效**。

**步骤 3：被黑名单排除的表，DML 确实不同步**

```sql
INSERT INTO `db_camp`.`tb_bulk_perf` (biz_no,vin,name,status) VALUES ('EXCLUDED-1','vintest','被排除的行',1);
```

MySQL `mysql_rows = 1`，ClickHouse 里这张表根本不存在（`ck_table_exists = 0`）。黑名单生效。

**启动检查也过了**

```
Keyless-table check passed: every table currently has a row identity.
```

无致命报错、无 keyless 拒绝。那三个库当前共 24 张表（`db_base` 12 / `db_camp` 9 / `db_soft` 3），全都有主键，所以放开到全库捕获是安全的。

### 走黑名单的两个前提

1. **`table.include.list` 和 `table.exclude.list` 互斥**，只能留一个。换的时候要把白名单那行删掉，不是两行并存。
2. **黑名单的安全性依赖"以后新建的表都有主键"**。启动日志那条 WARN 已点明 `sql_generate_invisible_primary_key is OFF`：以后有人建了一张没主键的表，**下次重启时连接器会直接拒绝启动**。白名单模式下这张表不在名单里就无所谓，黑名单模式下它自动进入捕获范围，就成了阻塞项。要走黑名单，建议同时让 DBA 打开 `sql_generate_invisible_primary_key`（注意这会让 MySQL 拒绝 `CREATE TABLE ... PARTITION BY` 的无主键写法，是 DBA 的决定）。

### 白名单 vs 黑名单怎么选

- 那三个库里的表**基本都要** → 用黑名单，或干脆去掉 `table.include.list`（不设即等于捕获 `database.include.list` 下全部表），新表自动进入，无需重启
- 只要**明确的少数几张**、其余都不能进 CK → 保留白名单，接受"改配置 + 重启"，并严格遵守**先改配置重启、再建表写数据**的顺序


---

## 二、DML / DDL 逐句实测（14 项，全部通过）

每项只动一行或一列，端到端延迟 1~2s。

### DML-1 INSERT

```sql
INSERT INTO `db_camp`.`tb_campaign_work_order` (biz_no,name,status,deleted,amount)
VALUES ('d-0904134141','演示行',1,0,12.34);
```

```
MySQL:  d-0904134141  演示行  1  0  12.34
CK   :  │ d-0904134141 │ 演示行 │ 1 │ 0 │ 12.34 │     [1s]
```

一致。

### DML-2 UPDATE

```sql
UPDATE `db_camp`.`tb_campaign_work_order`
SET name='改过了-0904134141', amount=99.99 WHERE biz_no='d-0904134141';
```

```
MySQL:  d-0904134141  改过了-0904134141  1  0  99.99
CK   :  │ d-0904134141 │ 改过了-0904134141 │ 1 │ 0 │ 99.99 │   [2s]
```

一致。

### DML-3 UPDATE 置 NULL

```sql
UPDATE `db_camp`.`tb_campaign_work_order`
SET amount=NULL, deleted=NULL WHERE biz_no='d-0904134141';
```

```
MySQL:  d-0904134141  改过了-0904134141  1  NULL  NULL
CK   :  │ d-0904134141 │ 改过了-0904134141 │ 1 │ ᴺᵁᴸᴸ │ ᴺᵁᴸᴸ │   [1s]
```

一致 —— NULL 没被列默认值吞掉（`non.default.value: true` + `input_format_null_as_default=0` 生效）。

### DML-4 DELETE

```sql
DELETE FROM `db_camp`.`tb_campaign_work_order` WHERE biz_no='d-0904134141';
```

```
MySQL:  mysql_rows = 0
CK   :  │ d-0904134141 │ 改过了-0904134141 │ _sign=-1 │ ck_rows=1 │   [1s]
```

一致。**CK 22.8 是标记删除，行还在但 `_sign=-1`，所有查询必须带 `FINAL` 且 `WHERE _sign=1`。**

### ADD-1 单列带 DEFAULT + NOT NULL + COMMENT

```sql
ALTER TABLE `db_camp`.`tb_campaign_work_order`
  ADD COLUMN a1_0904134141 INT NOT NULL DEFAULT 7 COMMENT '单列注释-0904134141';
```

```
MySQL:  a1_...  int  NO  7  单列注释-0904134141
CK   :  │ a1_0904134141 │ Int32 │ 单列注释-0904134141 │   [1s]
```

一致。

### ADD-2 一条语句两列（逗号式）

```sql
ALTER TABLE `db_camp`.`tb_campaign_work_order`
  ADD COLUMN a2_0904134141 VARCHAR(32) NULL COMMENT '两列之一-0904134141',
  ADD COLUMN a3_0904134141 DECIMAL(9,3) NULL COMMENT '两列之二-0904134141';
```

```
MySQL:  a2_... varchar(32) YES ... / a3_... decimal(9,3) YES ...
CK   :  │ a2_... │ Nullable(String)        │ 两列之一-... │
        │ a3_... │ Nullable(Decimal(9, 3)) │ 两列之二-... │   [1s]
```

一致。**必须写这种逗号式；括号式 `ADD COLUMN (a ..., b ...)` 会卡死整个连接器，见第四节 A4。**

### ADD-3 FIRST / ADD-4 AFTER

```sql
ALTER TABLE `...` ADD COLUMN a4_0904134141 INT NULL COMMENT '放最前-0904134141' FIRST;
ALTER TABLE `...` ADD COLUMN a5_0904134141 TEXT NULL COMMENT '跟在name后-0904134141' AFTER name;
```

两条都 1s 到位，`Nullable(Int32)` / `Nullable(String)`，注释正确，**列顺序和 MySQL 一致**。

### MOD-1 类型加宽 INT → BIGINT

```sql
ALTER TABLE `...` MODIFY COLUMN a1_0904134141 BIGINT NOT NULL DEFAULT 7 COMMENT '加宽后-0904134141';
```

```
MySQL:  a1_...  bigint  NO  7  加宽后-...
CK   :  │ a1_... │ Int64 │ 加宽后-... │   [1s]
```

一致。

### MOD-2 NOT NULL → NULL

```sql
ALTER TABLE `...` MODIFY COLUMN a1_0904134141 BIGINT NULL COMMENT '改成可空-0904134141';
```

```
MySQL:  a1_...  bigint  YES  NULL  改成可空-...
CK   :  │ a1_... │ Nullable(Int64) │ 改成可空-... │   [1s]
```

一致。**反方向（NULL → NOT NULL）是致命的，见第四节 A3。**

### MOD-3 只改注释 / CHG-1 CHANGE COLUMN 改名 + 注释

```sql
ALTER TABLE `...` MODIFY COLUMN a2_0904134141 VARCHAR(32) NULL COMMENT '只改注释-0904134141';
ALTER TABLE `...` CHANGE COLUMN a2_0904134141 c2_0904134141 VARCHAR(128) NULL COMMENT '改名后-0904134141';
```

```
MySQL:  c2_0904134141  varchar(128)  YES  NULL  改名后-0904134141
CK   :  │ c2_0904134141 │ Nullable(String) │ 改名后-0904134141 │   [1s]
```

一致（旧列名 `a2_` 两边都查不到，说明是真改名不是新增）。

> **核对陷阱**：一条 MySQL ALTER 会被翻译成**多条** CK 语句，中间有 500ms 保护延迟。第一轮跑的时候在类型刚变就去查，会读到"类型已改、注释还是旧的"的中间态。日志里能看清顺序：
>
> ```
> ClickHouse DDL: ALTER TABLE stat_qa.`tb_campaign_work_order` MODIFY COLUMN a2_... Nullable(String)
> ClickHouse DDL: ALTER TABLE stat_qa.`tb_campaign_work_order` RENAME COLUMN IF EXISTS a2_... to c2_...
> ClickHouse DDL: ALTER TABLE stat_qa.`tb_campaign_work_order` COMMENT COLUMN IF EXISTS c2_... '改名后-...'
> ```
>
> **核对时等 3~5 秒让整组落完再查。**

### DRP-1 / DRP-2 DROP COLUMN 单列与多列

```sql
ALTER TABLE `...` DROP COLUMN a4_0904134141;
ALTER TABLE `...` DROP COLUMN a5_0904134141, DROP COLUMN a3_0904134141;
```

两边都查不到这些列，一致。

### TRUNCATE TABLE

```sql
TRUNCATE TABLE `db_camp`.`t_demo_b`;
```

`mysql_rows = 0` / `ck_rows = 0`，1s，一致。CK 侧执行的是 `TRUNCATE TABLE IF EXISTS ... ON CLUSTER`。

### DROP TABLE

```sql
DROP TABLE `db_camp`.`t_demo_c`;
```

`mysql_table_exists = 0` / `ck_table_exists = 0`，1s，一致。执行的是 `DROP TABLE IF EXISTS ... ON CLUSTER`，两个副本一起删。

---

## 三、150 万行全量快照实测（容器 4G）

隔离实例跑，自己的 offset / schema history 表和独立 CK 库，不干扰主任务。源表 12 列（bigint / varchar / decimal / double / datetime），MySQL 侧 125.7MB 数据 + 162.3MB 含索引。容器 `-m 3500m` + `-Xmx2g`，吞吐参数用 `dist/config.yml` 的 4G 档（`max.batch.size=10000`、`sink.connector.max.queue.size=8`、`max.queue.size.in.bytes=128MB`、`thread.pool.size=4`、`snapshot.fetch.size=10000`）。

跑了两轮，**差别只有日志级别**：

- **镜像默认（DEBUG）：2103s = 35 分 03 秒**，约 713 行/s，容器日志 **503MB**，峰值内存 1.53GiB
- **日志降到 INFO：1728s = 28 分 48 秒**，约 868 行/s，容器日志 **512KB**，峰值内存 2.10GiB

两轮数据都完全正确：

```
┌─ck_total─┬─ck_uniq_id─┐      ┌─ck_final─┐      ┌─on_disk────┬────rows─┬─parts─┐
│  1500000 │    1500000 │      │  1500000 │      │ 155.75 MiB │ 1500000 │     8 │
└──────────┴────────────┘      └──────────┘      └────────────┴─────────┴───────┘
```

Debezium 自报的导出耗时：

```
Finished exporting 1500000 records for table 'db_camp.tb_bulk_perf' (1 of 1 tables); total duration '00:26:07.528'
Snapshot ended with SnapshotResult [status=COMPLETED, ...]
```

### 两个直接可用的结论

**1. 4G 容器不是瓶颈。** 两轮峰值内存 1.53G / 2.10G，离 3.418GiB 上限都有余量。瓶颈在单行 JDBC 绑定 + 每批一个 Replicated part + 一次 ZooKeeper 事务。

**2. 关掉 DEBUG 日志白送 18%。** shaded jar 里 `sink-connector/src/main/resources/log4j2.xml` 的 `<Root level="debug">` 覆盖了 lightweight 模块那份 `info`，导致 Apache HttpClient 的 `wire` logger 把每个 INSERT 的请求体和响应体全打出来 —— 35 分钟 503MB 日志。

做法是挂一份 root=info 的 log4j2.xml：

```yaml
environment:
  JAVA_TOOL_OPTIONS: "-Xmx2g -Dlog4j2.configurationFile=/log4j2.xml"
volumes:
  - ./config.yml:/config.yml:ro
  - ./log4j2.xml:/log4j2.xml:ro
```

```xml
<Configuration status="WARN">
  <Appenders>
    <Console name="console" target="SYSTEM_OUT">
      <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %c{1} - %msg%n"/>
    </Console>
  </Appenders>
  <Loggers>
    <Logger name="com.clickhouse" level="ERROR" additivity="false"><AppenderRef ref="console"/></Logger>
    <Logger name="org.apache.http" level="WARN" additivity="false"><AppenderRef ref="console"/></Logger>
    <Root level="info" additivity="false"><AppenderRef ref="console"/></Root>
  </Loggers>
</Configuration>
```

必须用 `JAVA_TOOL_OPTIONS`，`JAVA_OPTS` 在这个镜像里不生效（ENTRYPOINT 是 exec 形式不展开变量，Dockerfile 里那句 `ENV JAVA_OPTS="-Dlog4jDebug=true"` 本来就是死的）。

### 折算 141 万行

按 INFO 日志下 868 行/s ≈ **27 分钟**。这是开发机单表数字，SIT 的 MySQL、网络、CK 负载都不同，只能当同口径参考，不是承诺值。`snapshot.max.threads: 4` 是按表并行的，对单张 141 万行的表没有任何作用。

---

## 四、不能用 / 未覆盖 / 不建议用的清单

### A. 会搞坏数据或卡死任务 —— 明确禁止

**A1. `CREATE TABLE ... AS SELECT`（含任何建表即带数据的写法）**

MySQL CTAS 不复制 PRIMARY KEY → CK 生成 `ORDER BY tuple()` → 全表行共享空去重键 → merge 后只剩 1 行。**实测 3 行变 1 行，无报错。** 拆成 `CREATE TABLE`（显式 PRIMARY KEY）+ `INSERT INTO ... SELECT`。

**A2. 任何没有 PRIMARY KEY / 非空 UNIQUE 键的表**

同一个根因。binlog 里这种表没有行标识，InnoDB 内部的 `DB_ROW_ID` 不是列、不进 binlog。连接器启动时的 KeylessTablePreflight 会拒绝并打 ERROR。修复：

```sql
ALTER TABLE ... ADD COLUMN my_row_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT INVISIBLE PRIMARY KEY;
```

**A3. `MODIFY COLUMN` 把可空列改成 `NOT NULL`**

MySQL 侧补完 NULL 再改是合法的，但 CK 要 MUTATE 重写所有历史 part，而 ReplacingMergeTree 里旧版本行仍带 NULL → `Code: 349 CANNOT_INSERT_NULL_IN_ORDINARY_COLUMN` 无限重试（实测 8.9 万次）。**后果：该表连 SELECT 都报错，后续所有 ALTER 拿 `Code: 517`，全部表一起停在同一 offset 且不自愈。** 这是语义不兼容不是 bug。替代方案：加一个非空新列 → 回填 → 删旧列。

**A4. 括号包多列的 ADD COLUMN**

```sql
-- 禁止
ALTER TABLE t ADD COLUMN (a INT, b DATETIME);
-- 必须写成
ALTER TABLE t ADD COLUMN a INT, ADD COLUMN b DATETIME;
```

parser 生成出空的 `ALTER TABLE db.t` → `Code: 62 SYNTAX_ERROR`，DDL 丢失；随后写入路径用不带 `IF NOT EXISTS` 的语句补建，把列建成**非 Nullable**（NULL 永久变成 0 / 1970-01-01），之后每次撞 `Code: 15 DUPLICATE_COLUMN` → 整个连接器无限重试卡死，**必须手工重启容器**。

**A5. `ALTER ... DEFAULT CURRENT_TIMESTAMP` / `ON UPDATE CURRENT_TIMESTAMP`**

没有翻译规则，拼成 `Code: 47 Missing columns: 'CURRENT_TIMESTAMPONUPDATECURRENT_TIMESTAMP'`，**整条 ALTER 被丢弃**（不只是这一段）。建表时带着可以，别在 ALTER 里改。

**A6. 光秃秃的 `ALTER TABLE <table>` 不带任何动作**

`Code: 62`。正常人不会写，但自动化脚本拼串时可能拼出来。

### B. 有副作用但不致命 —— 知道就行

**B1. 每次重启会重放最近的 DDL 两遍，第二遍落到 `system` 库**（复现 2/2）

```
06:50:47  ClickHouse DDL: CREATE TABLE if not exists stat_qa.`t_demo_e` ON CLUSTER ...   ← 对
06:50:54  ClickHouse DDL: CREATE TABLE if not exists system.`t_demo_e`      ON CLUSTER ...   ← 错
```

根因 `DebeziumChangeEventCapture.getDatabaseName()`（`:993-1000`）：schema change 记录 key 里 `databaseName` 为空时兜底返回 `"system"`，override map 匹配不上。后果是 `system` 库里堆垃圾空表，**而且要求 CK 账号对 `system` 库有 CREATE 权限** —— SIT 用的是 `cdc_stat_user`，如果没这个权限，重启后这条 DDL 会失败重试。这也是 `IF EXISTS` / `IF NOT EXISTS` 守卫为什么是刚需（重放才幂等）。**建议在 SIT 上先确认权限。**

**B2. `CREATE TABLE` 不受 `table.include.list` 约束** → 未订阅表在 CK 生成永远空的幽灵表。gh-ost / pt-osc 的影子表已用 `ignore.ddl.regex` 挡掉。

**B3. MySQL 空表不会在 CK 建表** —— auto-create 在写入路径触发，没有行就不建。

**B4. 快照阶段创建的表没有列注释** —— 那条路径从 Kafka Connect 的 `Field[]` 推 schema，Connect 的 Schema/Field 没有列注释概念。只有增量阶段走 DDL 路径的 CREATE TABLE 才带注释。已有表要注释得手工补一次。

**B5. `ADD COLUMN` 后紧邻的那一个 batch 会丢新列值**，日志报 `Column index missing for column`。机制：同一条 UPDATE 生成两个列集不同的 INSERT 且 `_version` 相同，ReplacingMergeTree 任选一个。**再 UPDATE 一次即可自愈。** 修复方案已评估，未实施。

**B6. 注释里带逗号会让日志出现 `Error parsing precision, scale` 噪音**（`split("COMMENT")[0]` 那个老毛病），注释本身是对的，只是日志脏。

**B7. `/status` 不可信** —— 连接器完全卡死时仍返回 `Replica_Running: true, Seconds_Behind_Source: 0`；数据已追平时又报 `Seconds_Behind_Source: 305`。**判断是否真的在跑要看 `cdc_offsets` 里 `record_insert_ts` 是否在推进。**

### C. 本次未覆盖的

- MySQL 分区表的 `ALTER TABLE ... PARTITION` 系列（`ANALYZE PARTITION` 已在 `ignore.ddl.regex` 里挡掉，其余没测）
- 索引类 DDL（`ADD INDEX` / `DROP INDEX`）—— CK 没有对应概念，预期是忽略，没验证
- `RENAME TABLE`
- 字符集 / COLLATE 变更
- 生成列（`GENERATED ALWAYS AS`）—— 代码走 `ALIAS` 分支，有单测但没在真环境跑
- JSON / 空间类型、`ENUM` / `SET`
- 多表事务里混着 DDL 的场景
- 增量快照（Debezium 的 signal 机制）—— 连接器自身代码里没有 signal 支持，理论上属性能透传给 Debezium，但**没验证过**，别当成补数据的手段

### D. 配置层面不建议动的

- `single.threaded: "true"` —— 单线程模式下 `this.executor` 永不赋值，而 `drainBeforeDDL()` 无条件调 `executor.pause()`，**每个 DDL 事件都死于 NPE 且完全不报错**。必须 `false`。
- `enable.snapshot.ddl: "true"` —— Debezium 快照阶段会发 `DROP TABLE IF EXISTS`，开启后任何一次重新快照都先删光 CK 目标表。
- `restart.event.loop.timeout.period.secs` 不要调小 —— 它比的是**源端事件时间**，快照期间可能长时间不动。实测 600 秒会在 2.5 小时内误重启 3 次，而非增量快照无法续跑，每次都从第一张表重来。
- `sink.connector.max.queue.size` 不要往几百上调 —— 这个数是**批次数**不是行数，队列元素是整个 List。4G 容器下 8 已经是 8 万个 struct 约 75MB。
- 容器日志驱动别用 journald（实测 10 分钟丢 18 行），用 `json-file`。

---

## 五、环境状态

已还原：演示表列回到 10 个业务列（MySQL 与 CK 一致核对过），`t_demo_*` 四张表两侧全删，`system` 库里的垃圾表清掉，`perf_db` 库和 perf 元数据表删掉，开发机 `config.yml` 从备份还原（白名单里 `t_demo` 计数 0），主连接器正常运行。

150 万行的 `db_camp.tb_bulk_perf` **保留**在 MySQL 里，方便换参数复测。

### 建议接着做

1. ~~把 `log4j2.xml`（root=info）加进 `dist/` 并改 `docker-compose.yml` 挂载~~ —— **已完成**，见第六节
2. 在 SIT 上确认 `cdc_stat_user` 对 `system` 库有没有 CREATE 权限 —— 关系到重启后 DDL 会不会失败重试

---

## 六、全量快照性能优化（28分48秒 → 约 60 秒）

第三节那个 28 分 48 秒不是硬件限制，是一处代码问题。这一节是定位过程和结果。

### 定位：ClickHouse 吃满 CPU，但不是在写数据

在基线配置下跑快照，同时采样三个容器的 CPU（宿主机只有 **4 vCPU**，连接器 / ClickHouse / ZooKeeper 全挤在一起）：

```
t=132s rows=100324 cpu=[pv:32.05% clickhouse:263.15% zookeeper:1.05%]
t=169s rows=140324 cpu=[pv:34.60% clickhouse:251.07% zookeeper:0.50%]
t=251s rows=220324 cpu=[pv:30.96% clickhouse:261.67% zookeeper:0.58%]
```

ClickHouse 稳定在 260%（2.6 个核），连接器只有 0.3 个核。方向反了 —— 瓶颈在 ClickHouse 侧，但写入量只有 150MB，不该这么贵。

查 `system.query_log`，20 分钟窗口内：

```
┌─query_kind─┬──────n─┬─avg_ms─┬─total_s─┐
│ Select     │ 664230 │      0 │     244 │
│ Insert     │    129 │      6 │       1 │
└────────────┴────────┴────────┴─────────┘
```

**66 万条 SELECT，129 条 INSERT。** 按 `normalized_query_hash` 排名，前两名是：

```
n: 332057   SELECT name FROM system.columns WHERE (table='tb_bulk_perf') AND (database='perf_db')
            and (default_kind='ALIAS' or default_kind='MATERIALIZED')
n: 332054   SELECT name, type, default_kind FROM system.columns
            WHERE database='perf_db' AND table='tb_bulk_perf' ORDER BY position
```

同期真正的 INSERT：

```
inserts: 33     avg_ms: 19     avg_rows: 9707     total_rows: 320324
```

32 万行数据只花了 33 条 INSERT、平均 19ms —— 写数据几乎免费。**时间全花在每写一行就查两次列元数据上**，2 条/行 × 33 万行 = 66 万条。

### 根因

`GroupInsertQueryWithBatchRecords.groupQueryWithRecords()` 的**每条记录**循环里：

```java
if (enableSchemaEvolution) {
    new ClickHouseAlterTable().alterTable(...);          // 99.9999% 情况下什么都不做
    columnNameToDataTypeMap = new DBMetadata(config)      // 但这里无条件重读元数据
            .getColumnsDataTypesForTable(tableName, connection, databaseName);
}
```

`getColumnsDataTypesForTable()` 内部是两条 `system.columns` 查询（`DBMetadata.java:512` 与 `:608`）。
`alterTable` 只在记录里出现了 ClickHouse 表没有的列时才真的发 ALTER，绝大多数记录它是空转，
但后面那次重读是**无条件**的。

### 改法

两处改动，都在 `sink-connector` 模块：

1. `ClickHouseAlterTable.alterTable()` 的返回值从 `void` 改成 `boolean`，真的发了 ALTER 才返回 `true`。
2. `GroupInsertQueryWithBatchRecords.groupQueryWithRecords()`：
   - 元数据刷新从**每条记录**移到**每批一次**（放到记录循环之前）
   - 循环内只在 `alterTable` 返回 `true` 时额外刷新一次

不能干脆全删掉这次刷新 —— 它同时也是连接器发现「ClickHouse 表被带外改过」的唯一途径。
只保留"ALTER 后刷新"的版本实测会退化：CK 侧列被删掉后，INSERT 仍引用该列，
ClickHouse 回 `Code: 16 NO_SUCH_COLUMN_IN_TABLE`，连接器判定为致命错误并停掉 task。
改成每批一次，这个自愈能力保留，成本变成 1/`max.batch.size`（150 万行从 300 万条元数据查询降到约 300 条）。

### 结果

同一台开发机、同一份 4G 配置（`-m 3500m` / `-Xmx2g` / `max.batch.size=10000` / `thread.pool.size=4`）、
同一张 150 万行源表：

- **基线（镜像默认 DEBUG 日志）**：2103s = 35 分 03 秒
- **只把日志降到 INFO**：1728s = 28 分 48 秒
- **代码修复 + INFO 日志**：**约 60 秒**（`schema.evolution` 保持 `true`）

过程采样：

```
t=15s rows=219997   cpu=[pv:245.83% clickhouse:13.22%]
t=33s rows=779997   cpu=[pv:76.36%  clickhouse:8.83%]
t=60s rows=1500000  cpu=[pv:2.71%   clickhouse:0.72%]
```

ClickHouse 的 CPU 从 260% 掉到 10% 上下，连接器变成了忙的那一方 —— 这才是正常形态。

### 数据校验：MySQL 与 ClickHouse 完全一致

```
MySQL:      rows=1500000  min_id=1  max_id=1827644  sum(status)=2998923  sum(amount)=75000385265.35  sum(qty)=749131900
ClickHouse: rows=1500000  min_id=1  max_id=1827644  sum(status)=2998923  sum(amount)=75000385265.35  sum(qty)=749131900
```

`count() = uniqExact(id) = FINAL 后 = 1500000`，落盘 155.8MiB / 8 个 part，与优化前的产物一致。

### 回归测试

**1. DML/DDL 逐句（第二节那 14 项）**：14/14 通过，0 项超时，延迟仍是 1~2s。

**2. schema evolution 兜底能力**：这是本次改动唯一可能影响的行为，用隔离实例做了 A/B。
场景：一张 3 行的表快照完成后，**只在 ClickHouse 侧**删掉一列（模拟带外漂移 / DDL 漏同步），
然后往 MySQL 插一行带该列的新数据。

- **基线 jar**：列**没有**补回，`自动 ALTER 次数=0`；新行到了 CK，但**该列的值被静默丢弃**（无报错、无日志）
- **修复 jar**：列**补回来了**，`自动 ALTER 次数=1`；新行到了 CK 且 `drift_col='healed'` 正确写入；`Code 16 打停=0`、`NPE=0`、`重试循环=0`

```
┌─id─┬─biz_no─┬─drift_col─┐
│  1 │ r1     │           │
│  2 │ r2     │           │
│  3 │ r3     │ 　        │
│  4 │ r4     │ healed    │
└────┴────────┴───────────┘
```

修复版在这个场景下比基线**更好**：基线是静默丢值，修复版会把列补回来并写入。
（历史行 r1~r3 的值查不回来是我手工 DROP 造成的，与连接器无关；
补回来的列是非 Nullable `String`，这是写入路径 auto-ALTER 的既有缺陷，见第四节 A4，本次未改。）

### 后续：`-4` 修掉的那个既有缺陷

上面的 A/B 只插了一行，没暴露出一个更严重的既有问题。外部执行者按
`REGRESSION_SOP.md` 在 `tb_campaign_work_order` 上跑 D 组时失败，追下去发现：

写入路径自动补列后，那一批的 INSERT 会带一个**永远绑不上的占位符**，
clickhouse-jdbc 在 `PreparedStatementImpl.addBatch()` 抛
`NullPointerException: str is null`，批次被判为「可重试」后**无限重试且不自愈**，
该行永远到不了 ClickHouse。

用同一张表、同一套步骤，只换 jar 做了三次对照：

| jar | 自动 ALTER | NPE | 重试循环 | 行是否到 CK |
|---|---:|---:|---:|---|
| 基线 `604d53ac…`（本节优化之前） | 35 | 105 | 35 | 否 |
| `-3` `4eefc4c2…`（本节的优化） | 1 | 105 | 35 | 否 |
| `-4` `4593128c…` | 1 | 0 | 0 | 是，值正确 |

基线和 `-3` 表现一致，**说明它不是本节这次优化引入的**，是更早就存在的缺陷。
（基线的「自动 ALTER 35 次」只是因为它每条记录都刷新元数据，每次重试都重发一遍 ALTER。）

根因：`groupQueryWithRecords()` 刷新的是方法内的**局部** map，而真正绑参数时用的是
`DbWriter` 里那份**没被更新**的 map —— 调用方
（`ClickHouseBatchRunnable:754` / `ClickHouseBatchWriter:552`）传进去的是
`writer.getColumnNameToDataTypeMap()`，方法内对参数重新赋值不会影响它。
于是 INSERT 的列清单里可能出现一列在绑参循环中永远遍历不到。

`-4` 的改法：新增 `DbWriter.refreshColumnNameToDataTypeMap()` 把刷新结果发布到
writer 上，`groupQueryWithRecords()` 增加 `SchemaRefresher` 参数，两处刷新
（每批一次、ALTER 之后）都走它；字段加 `volatile`，因为 `thread.pool.size > 1`
时多个 worker 共享同一个 `DbWriter`。吞吐不受影响（仍是每批 2 条元数据查询），
150 万行快照复测仍是约 60 秒，数据逐项一致。

### 后续：`-5` 修掉的 identifier 解析缺陷

外部执行者在扩展健壮性矩阵（23 场景 / 105 断言）里发现一个稳定复现的延迟缺陷：

```sql
ALTER TABLE t ADD COLUMN `space col` INT NULL COMMENT 'space';
```

ClickHouse 侧其实立刻就建好了列，但 `DDLSchemaChangeWaiter` 把列名解析成 `space`，
于是死等一个不存在的列直到 30 秒超时，之后才继续执行 COMMENT、刷新 schema cache、写 DML。

根因是正则 `` `?([^`\s]+)`? ``：反引号是可选的，但捕获组仍然禁止空白字符，所以
`` `space col` `` 只捕到 `space`。`ADD` / `DROP` / `ALTER TABLE` 的库名表名三处都是这个写法。
DROP 侧的后果更隐蔽——错列名本来就不存在，等待器立刻返回，从来没确认过真实列已从
所有副本消失。

改法是把 quoted 和 bare 拆成两个候选分支 `` (?:`((?:``|[^`])+)`|([^\s,]+)) ``，
抽取时取第一个非空捕获组并把 `` `` `` 还原成一个反引号，三处共用同一个片段。

同一套用例只换镜像的实测：

| 用例 | `-4` | `-5` |
|---|---:|---:|
| 关键字列名 `` `select` `` | 1669 ms | 1171 ms |
| 带空格列名 `` `space col` `` | **31856 ms** | **1171 ms** |
| 转义 COMMENT | 1686 ms | 1171 ms |
| 三列混合 Round 1 / 2 / 3 | 31982 / 31987 / 30947 ms | 1171 / 2244 / 1171 ms |
| 等待器提取到的列名 | `[space]` | `[space col]` |
| `Timeout (30000ms)` 次数 | 4 | **0** |

回归：A+B 组 14 项全过、D 组通过、150 万行快照仍是约 60 秒且与 MySQL 逐项一致。

顺带发现并修掉一个更基础的问题：`sink-connector/pom.xml` 的 `testSourceDirectory`
指向 `src/test` 而不是 `src/test/java`，于是每个测试类被额外编译成 `java.com.altinity...`
包，Surefire 扫描时 JVM 拒绝以 `java` 开头的包名（`SecurityException: Prohibited
package name`），**这个模块的单元测试从来没跑起来过**。修正后 282 个测试真正执行，
262 通过，20 个因为本机 testcontainers 找不到 Docker 环境而报错，不是代码缺陷。

### 这次没有动的东西

- 吞吐类配置一个都没改：`max.batch.size` / `thread.pool.size` / 队列深度 / `snapshot.fetch.size` 全是原值。
  之前怀疑的「批次太小、part 太多、ZooKeeper 事务太贵」全部不是主因 —— 33 条 INSERT 总共只花了 1 秒。
- `snapshot.max.threads` 仍然对单张大表无效（它是按表并行）。
- 4G 容器依然够用：修复后峰值内存 2.29GiB / 3.418GiB。吞吐上去了内存压力会变大，别把 `-Xmx` 再往上顶。

### 折算 141 万行

按这次实测，141 万行的全量快照大约 **1 分钟**量级，不再是几十分钟。
SIT 的 MySQL、网络、ClickHouse 负载和这台开发机都不同，且 SIT 的 ClickHouse 是独立集群
（这台机是连接器和 CK 挤 4 个核），所以实际会更快还是更慢要在 SIT 上再测一次，
但「每行两次元数据查询」这个成本是配置无关的，SIT 上同样存在、同样会被这次修复消掉。














