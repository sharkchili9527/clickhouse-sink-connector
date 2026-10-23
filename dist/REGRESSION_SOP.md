# DML / DDL 回归测试 SOP

适用产出：`clickhouse_debezium_embedded:ck22.8-fix-20261022-6`（本目录）

本文档是给**执行者**看的操作手册，每一步都给出「要跑的语句 → 期望结果 → 判定标准」。
执行者不需要读连接器源码，但**必须逐条按顺序执行，并且不能跳过第 8 节的禁止清单**。

---

## 0. 目标

验证以下能力在新产出上没有退化：

| 编号 | 范围 | 项数 |
|---|---|---|
| A | DML：INSERT / UPDATE / 置 NULL / DELETE | 4 |
| B | 列级 DDL：ADD / MODIFY / CHANGE / DROP COLUMN | 10 |
| C | 表级 DDL：CREATE TABLE / TRUNCATE / DROP TABLE | 4 |
| D | schema evolution 兜底（`-4` 修掉的那个缺陷，**必测且必须过**） | 1 |
| F | 特殊标识符与耗时（`-5` 修掉的那个缺陷，**必测且必须过**） | 5 |
| E | 全量快照吞吐（可选，需要造 150 万行数据） | 1 |

**A~D 加 F 合计 24 项，全部通过才算回归通过。**

有两组和版本相关，跑旧产出时会失败，属于预期：

- D 组在 `-3` 及更早版本上必然失败（驱动 NPE + 无限重试），`-4` 修好，见第 7 节对照表
- F 组在 `-4` 及更早版本上必然超时（每条带空格反引号列名的 DDL 多花 30 秒），`-5` 修好，
  见第 7.5 节对照表

---

## 1. 前提条件

执行前请确认并记录下列信息（后文用 `<>` 占位）：

- MySQL：`<MYSQL_HOST>` `<MYSQL_PORT>` `<MYSQL_USER>` `<MYSQL_PWD>`
  - 账号需要：目标库的 `SELECT`、`INSERT`、`UPDATE`、`DELETE`、`ALTER`、`CREATE`、`DROP`
  - 服务端要求：`binlog_format=ROW`、`binlog_row_image=FULL`
- ClickHouse：`<CK_HOST>` `<CK_PORT>` `<CK_USER>` `<CK_PWD>`
  - 版本 22.8.x，且**必须配好 `cluster` 宏**（所有表级 DDL 都带 `ON CLUSTER {cluster}`）
- 源库名 `<DB>`（本 SOP 示例用 `db_camp`），目标库名 `<CK_DB>`（示例 `stat_qa`）
- 一张**已经在同步中**的测试表 `<TBL>`（示例 `tb_campaign_work_order`），要求：
  - 有 `PRIMARY KEY`
  - 已经出现在连接器配置的 `table.include.list` 里
  - 是测试表，可以随便加列删列删行

> 如果没有现成的测试表，先建一张并把它加进 `table.include.list`，**然后重启连接器**，
> 再往里写数据。顺序反了会丢数据，原因见 `SYNC_TEST_REPORT.md` 情形 C4。

---

## 1.5 已经搭好的开发环境（直接用这套，不用自己搭）

这套环境本次实测就是在上面跑的，**跑完第 10 节清理后是干净状态，可以直接开测**。

### 入口

```bash
ssh root@<DEV_HOST>        # 免密，密钥在本机 本机 home 下
```

宿主机：4 vCPU / 7.5G 内存，连接器、ClickHouse、ZooKeeper **都在这一台**上（所以性能数字偏保守）。
第二个 ClickHouse 副本在 `<CK2_HOST_PUBLIC>`。

### 容器

| 容器名 | 镜像 | 说明 |
|---|---|---|
| `connector` | `clickhouse_debezium_embedded:ck22.8-fix-20261022-6` | 被测对象。`/app.jar` md5 = `b261ff8932798c4b6ed45ccb1e8ddc99` |
| `clickhouse` | `clickhouse/clickhouse-server:22.8` | 副本 1 |
| `zookeeper` | `zookeeper:3.8` | CK 的 ZK |
| `mycli` | `mysql:8.0` | 只是个 mysql 客户端容器（`--entrypoint sleep infinity`），宿主机没装 mysql |

`connector` 的启动方式（如果需要重建，照抄这条）：

```bash
docker run -d --name connector --network host --restart unless-stopped \
  -e "JAVA_TOOL_OPTIONS=-Xmx2g -Dlog4j2.configurationFile=/log4j2.xml" \
  --log-driver json-file --log-opt max-size=200m --log-opt max-file=2 \
  -v /data/connector/config.yml:/config.yml:ro \
  -v /data/connector/log4j2.xml:/log4j2.xml:ro \
  clickhouse_debezium_embedded:ck22.8-fix-20261022-6
```

`mycli` 不在的话这样起：

```bash
docker run -d --name mycli --network host --entrypoint sleep mysql:8.0 infinity
```

### 连接信息

> 本文档里的主机名（`<...>`）与库名（`db_*` / `stat_*`）都是脱敏后的占位与示例值，
> 真实取值见本机 `dist/ENV_LOCAL.md`（不入库）或部署记录。

| 项 | 值 |
|---|---|
| MySQL | `<MYSQL_HOST>:3306`，用户 `<MYSQL_USER>`（VPC 内网地址，只能从宿主机连） |
| ClickHouse 副本 1 | `<CK1_HOST>:8123`（HTTP）/ `9000`（native），用户 `default` |
| ClickHouse 副本 2 | `<CK2_HOST>`，同账号 |
| 源库 | `db_base`、`db_soft`、`db_camp` |
| 目标库 | `stat_qa`（三个源库都映射到这一个） |
| 连接器元数据库 | `stat_qa_connector`（`cdc_offsets`、`cdc_schema_history`） |
| 测试表 | **`db_camp.tb_campaign_work_order`** → `stat_qa.tb_campaign_work_order` |

**口令不写在本文档里**，在宿主机上取：

```bash
source /root/ddl_probe.env    # 提供 MYSQL_PWD 和 CK_PWD 两个变量（chmod 600）
# 连接器自己用的那份在 /data/connector/config.yml 里也有
```

### 两条随手可用的命令

后文所有 SQL 都可以用这两个函数跑。先 `source /root/ddl_probe.env`：

```bash
my(){ docker exec -e MYSQL_PWD="$MYSQL_PWD" mycli mysql -h<MYSQL_HOST> -uxzw \
        --ssl-mode=DISABLED --default-character-set=utf8mb4 -e "$1" 2>&1 \
        | grep -v "Using a password"; }

ck(){ docker exec clickhouse clickhouse-client --password "$CK_PWD" --multiquery -q "$1" 2>&1; }
```

用法：`my "SELECT 1;"` / `ck "SELECT 1 FORMAT PrettyCompact;"`

> **注意 ClickHouse 客户端的引号**：`-q` 里的 SQL 用单引号包字符串（`WHERE database='stat_qa'`）。
> 如果通过 ssh 再套一层，双引号会被 shell 吃掉变成 CK 的标识符，报
> `Missing columns: 'stat_qa'`。稳妥做法是把脚本写成文件再执行，不要在 ssh 命令行里拼长 SQL。

### 环境里现成的脚本（`/root/` 下，可直接复用或参考）

| 脚本 | 用途 |
|---|---|
| `auto_probe.sh` | **自动跑完 A 组 + B 组 14 项**，每项打印「语句 / MySQL 结果 / CK 结果 / 同步耗时」。改完一条 ALTER 会等 3 秒让多条 CK 语句落完再读，避免中间态误判 |
| `probe_create.sh c1` / `c2` | C 组 CREATE TABLE 的四种情形（未订阅 / 已订阅 / 先建表后加白名单 / CTAS） |
| `regress_selfheal.sh` | D 组的手工版旧脚本，已被 `regress_d.sh` 取代，复测用不上 |
| `probe_special_ident.sh <镜像tag>` | **F 组一键跑完并打印每条耗时**，独立源库/目标库，跑完自动清理 |
| `regress_d.sh [表名]` | **D 组一键跑完并自动判定**，独立实例 + `-5` 镜像，不停主连接器，跑完自动清理。**测 `-6` 前先把脚本里的镜像 tag 改成 `ck22.8-fix-20261022-6`** |
| `ab_drift.sh` / `ab_drift2b.sh` / `ab_drift3.sh` / `ab_drift4.sh` | D 组的历史 A/B 脚本，用来对比不同 jar，复测用不上 |
| `ddl_probe.sh <类别>` | 交互式演示脚本（给人看的，每步停下来等回车） |
| `ck_diag2.sh` | 查 `query_log` 里 SELECT/INSERT 条数比，用来确认跑的是不是新产出 |
| `gen_bulk.sh` | 造 150 万行压测表（倍增法） |
| `run_perf2.sh` | E 组快照计时，独立实例 + INFO 日志 |
| `perf_variant.sh <标签> <秒数> <Xmx> [k=v...]` | 吞吐变体测试，可临时覆盖任意配置项 |
| `final_cleanup.sh` | 清理演示残留 |

**最省事的跑法**（五条命令跑完 A~F 共 24 项，G 组 3 项见第 7.6 节）：

```bash
source /root/ddl_probe.env

/root/auto_probe.sh > /root/regA.log 2>&1        # A+B 组 14 项
grep -c "超时未变化" /root/regA.log               # 必须是 0，然后看 14 段三方对比输出

/root/probe_create.sh c1                          # C 组；C2 要验数据同步就按第 6 节加白名单重启后重跑
/root/regress_d.sh                                # D 组，自动输出「通过/失败」
/root/probe_special_ident.sh ck22.8-fix-20261022-6 # F 组，打印每条耗时，都要 <5000ms
# G 组没有现成脚本，按第 7.6 节用 my / ck 两个函数手工跑（约 3 分钟）
```

### 环境里保留的东西

- `db_camp.tb_bulk_perf`：150 万行压测表，**保留着**，E 组直接用，别删
- `/data/perf/config.yml`：E 组用的独立实例配置（独立 `server.id`、独立 offset 表、目标库 `perf_db`）
- `/data/connector/config.yml.bak`：白名单版配置备份，改坏了可以还原

### 注意事项

1. `ab_drift.sh`、`run_perf*.sh`、`perf_variant.sh` 都会先 **`docker stop connector`**，
   跑完**记得 `docker start connector`**，否则主任务一直停着。
2. 改 `/data/connector/config.yml` 后必须 `docker restart connector` 才生效。
3. 用 `scp` 覆盖被 bind-mount 的文件不会生效（inode 变了），必须 `docker rm -f` 重建容器。
4. Docker Hub 在这台机上不通，拉镜像走 `https://mirror.ccs.tencentyun.com`。
5. 这是**开发环境**，表可以随便加列删列删行。不要把这套流程直接对着 SIT 跑
   （SIT 的 `<RO_USER>` 是只读账号，DDL 会失败）。

<!--SOPENV-->



---

## 2. 部署与启动

> **用第 1.5 节那套开发环境的话，这一节可以跳过**，环境已经部署好且自检通过。
> 只需要跑一遍下面的「启动自检」三条确认状态。这一节是给新环境准备的。

```bash
# 1) 导入镜像
gunzip -c clickhouse_debezium_embedded-ck22.8-fix-20261022-6-amd64.tar.gz | docker load

# 2) 确认镜像是 amd64、且里面的 jar 是本次实测的那一份
docker image inspect clickhouse_debezium_embedded:ck22.8-fix-20261022-6 --format '{{.Architecture}}/{{.Os}}'
#   期望：amd64/linux
docker run --rm --entrypoint md5sum clickhouse_debezium_embedded:ck22.8-fix-20261022-6 /app.jar
#   期望：b261ff8932798c4b6ed45ccb1e8ddc99

# 3) 填配置
cp config.yml.template config.yml
vi config.yml     # 填 <> 占位符

# 4) 启动（compose 已经带上 log4j2.xml 挂载，不要删）
docker compose up -d
docker logs -f connector | grep -E "Snapshot ended|Connected to binlog"
```

**启动自检（三条都要满足才继续）：**

```bash
# a) 没有致命错误
docker logs connector 2>&1 | grep -E "Failed to start|KEYLESS TABLE --|refused to start" | head
#    期望：空。注意别把 "Keyless-table check passed" 那条 WARN 当成失败，那是检查通过。
#    真的失败长这样："!!  KEYLESS TABLE -- BAD PRACTICE"，说明源库有表没主键，先修表再回来
# b) 日志是 INFO 级别（不该看到 HTTP 报文）
docker logs connector 2>&1 | grep -c "http-outgoing"
#    期望：0。若不为 0，说明 log4j2.xml 没挂上，回去检查 JAVA_TOOL_OPTIONS
# c) 任务真的在推进（看 offset 表的时间戳在变，不要信 /status）
#    间隔 30 秒执行两次，record_insert_ts 必须变大：
#    SELECT max(record_insert_ts) FROM <CK_DB>_connector.cdc_offsets;
```

---

## 3. 判定通则（每一项都适用）

1. **ClickHouse 侧查询必须写 `FINAL` 且带 `WHERE _sign=1`。** CK 22.8 的删除是标记删除，
   不加这两个条件会看到已删除的行。
2. **一条 MySQL `ALTER` 会被翻译成多条 CK 语句**（例如 MODIFY + COMMENT COLUMN，中间有
   500ms 保护延迟）。改完**等 3~5 秒**再查，否则会读到「类型已改、注释还是旧的」的中间态，
   误判为失败。
3. 正常延迟是 **1~2 秒**。超过 30 秒没变化就按失败处理，并去看
   `docker logs connector | grep -iE "ERROR|Code:"`。
4. 每一项都用**同一个后缀**区分本轮数据，避免和历史残留混淆。执行前先生成：
   ```bash
   T=$(date +%m%d%H%M%S)   # 后文所有 ${T} 都用这个值
   ```
5. 出现下面任一现象，**立即停止**并记录，不要继续往下跑：
   - 日志出现 `stopping task to prevent silent data loss`
   - 日志出现 `batch will be retried on next scheduled run` 连续刷屏
   - 日志出现 `Code: 349`、`Code: 517`、`Code: 15 DUPLICATE_COLUMN`

   在 `-6` 上这些都不该出现。跑旧产出时有两个已知例外：`-3` 及更早版本的 D 组会
   触发重试循环（按第 7 节末尾恢复）；`-4` 及更早版本的 F 组会出现
   `Timeout (30000ms) waiting for columns`，那不影响数据正确性，只是每条 DDL 多花 30 秒。

6. **`Timeout (30000ms) waiting for columns` 在 `-5`、`-6` 上必须一次都不出现。**
   出现就说明跑的是 `-4` 或更早版本，回第 2 节核对 `/app.jar` 的 md5。
7. **`Column index missing` 在 `-6` 上必须一次都不出现。** 出现就说明跑的是 `-5` 或更早版本。

---

## 4. A 组：DML（4 项）

每项只动一行。先定 `K="d-${T}"` 作为这行的业务编号。

### A1 INSERT

```sql
-- MySQL
INSERT INTO `<DB>`.`<TBL>` (biz_no,name,status,deleted,amount)
VALUES ('d-${T}','演示行',1,0,12.34);

-- MySQL 核对
SELECT biz_no,name,status,deleted,amount FROM `<DB>`.`<TBL>` WHERE biz_no='d-${T}';

-- ClickHouse 核对
SELECT biz_no,name,status,deleted,amount FROM <CK_DB>.<TBL> FINAL
WHERE _sign=1 AND biz_no='d-${T}';
```

**判定**：两边 5 个字段逐字相等（`演示行 / 1 / 0 / 12.34`）。

### A2 UPDATE

```sql
-- MySQL
UPDATE `<DB>`.`<TBL>` SET name='改过了-${T}', amount=99.99 WHERE biz_no='d-${T}';
```
核对语句同 A1。**判定**：CK 的 `name` 变成 `改过了-${T}`、`amount` 变成 `99.99`，且**只有一行**。

### A3 UPDATE 置 NULL

```sql
-- MySQL
UPDATE `<DB>`.`<TBL>` SET amount=NULL, deleted=NULL WHERE biz_no='d-${T}';
```
核对语句同 A1。**判定**：CK 的 `amount` 和 `deleted` 都显示 `ᴺᵁᴸᴸ`。

> 这一项专门验证 `non.default.value=true` + `input_format_null_as_default=0` 生效。
> 如果 CK 显示 `0` 而不是 NULL，说明 NULL 被列默认值吞掉了，**判失败**。

### A4 DELETE

```sql
-- MySQL
DELETE FROM `<DB>`.`<TBL>` WHERE biz_no='d-${T}';

-- MySQL 核对
SELECT count(*) AS mysql_rows FROM `<DB>`.`<TBL>` WHERE biz_no='d-${T}';

-- ClickHouse 核对（这里故意不加 _sign 过滤，要看到标记）
SELECT biz_no,name,_sign FROM <CK_DB>.<TBL> FINAL WHERE biz_no='d-${T}';
SELECT count() AS live_rows FROM <CK_DB>.<TBL> FINAL WHERE _sign=1 AND biz_no='d-${T}';
```

**判定**：`mysql_rows=0`；CK 有一行且 `_sign=-1`；`live_rows=0`。
（行还在是**正确行为**，CK 22.8 是标记删除。）

---

## 5. B 组：列级 DDL（10 项）

**通用核对语句**（把 `<COLS>` 换成本项涉及的列名，带单引号逗号分隔）：

```sql
-- MySQL
SELECT column_name,column_type,is_nullable,column_default,column_comment
FROM information_schema.columns
WHERE table_schema='<DB>' AND table_name='<TBL>' AND column_name IN (<COLS>)
ORDER BY ordinal_position;

-- ClickHouse
SELECT name,type,comment FROM system.columns
WHERE database='<CK_DB>' AND table='<TBL>' AND name IN (<COLS>) ORDER BY position;
```

### B1 ADD COLUMN 单列，带 DEFAULT + NOT NULL + COMMENT

```sql
ALTER TABLE `<DB>`.`<TBL>` ADD COLUMN a1_${T} INT NOT NULL DEFAULT 7 COMMENT '单列注释-${T}';
```
**判定**：CK 有 `a1_${T}`，`type=Int32`，`comment=单列注释-${T}`。

### B2 ADD COLUMN 一条语句两列（逗号式）

```sql
ALTER TABLE `<DB>`.`<TBL>`
  ADD COLUMN a2_${T} VARCHAR(32) NULL COMMENT '两列之一-${T}',
  ADD COLUMN a3_${T} DECIMAL(9,3) NULL COMMENT '两列之二-${T}';
```
**判定**：CK 两列都在，`Nullable(String)` 和 `Nullable(Decimal(9, 3))`，注释各自正确。

> 必须是这种「逗号 + 重复 ADD COLUMN」写法。括号包多列的写法会卡死连接器，见第 8 节。

### B3 ADD COLUMN ... FIRST

```sql
ALTER TABLE `<DB>`.`<TBL>` ADD COLUMN a4_${T} INT NULL COMMENT '放最前-${T}' FIRST;
```
**判定**：CK 有该列、`Nullable(Int32)`、注释正确，且 `position` 是 1（在最前）。

### B4 ADD COLUMN ... AFTER

```sql
ALTER TABLE `<DB>`.`<TBL>` ADD COLUMN a5_${T} TEXT NULL COMMENT '跟在name后-${T}' AFTER name;
```
**判定**：CK 有该列、`Nullable(String)`、注释正确，且紧跟在 `name` 之后。

### B5 MODIFY COLUMN 类型加宽

```sql
ALTER TABLE `<DB>`.`<TBL>` MODIFY COLUMN a1_${T} BIGINT NOT NULL DEFAULT 7 COMMENT '加宽后-${T}';
```
**判定**：CK `type=Int64`，`comment=加宽后-${T}`。（等 3~5 秒再查，注释是第二条语句才落的。）

### B6 MODIFY COLUMN NOT NULL → NULL

```sql
ALTER TABLE `<DB>`.`<TBL>` MODIFY COLUMN a1_${T} BIGINT NULL COMMENT '改成可空-${T}';
```
**判定**：CK `type=Nullable(Int64)`，`comment=改成可空-${T}`。

> 反方向（NULL → NOT NULL）是致命操作，**禁止执行**，见第 8 节。

### B7 MODIFY COLUMN 只改注释

```sql
ALTER TABLE `<DB>`.`<TBL>` MODIFY COLUMN a2_${T} VARCHAR(32) NULL COMMENT '只改注释-${T}';
```
**判定**：CK `comment=只改注释-${T}`，`type` 仍是 `Nullable(String)`。

### B8 CHANGE COLUMN 改列名 + 注释

```sql
ALTER TABLE `<DB>`.`<TBL>` CHANGE COLUMN a2_${T} c2_${T} VARCHAR(128) NULL COMMENT '改名后-${T}';
```
**判定**（三条都要满足）：
- CK 有 `c2_${T}`，`comment=改名后-${T}`
- CK **查不到** `a2_${T}`（说明是真改名，不是新增一列）
- 日志里能看到三条语句且都指向 `<CK_DB>`（不是源库名）：
  ```bash
  docker logs connector 2>&1 | grep -E "MODIFY COLUMN c2_${T}|RENAME COLUMN IF EXISTS a2_${T}|COMMENT COLUMN IF EXISTS c2_${T}"
  ```

### B9 DROP COLUMN 单列

```sql
ALTER TABLE `<DB>`.`<TBL>` DROP COLUMN a4_${T};
```
**判定**：MySQL 和 CK 两边都查不到 `a4_${T}`。

### B10 DROP COLUMN 一条语句两列

```sql
ALTER TABLE `<DB>`.`<TBL>` DROP COLUMN a5_${T}, DROP COLUMN a3_${T};
```
**判定**：两边都查不到这两列。

---

## 6. C 组：表级 DDL（4 项）

用一张**临时表** `t_reg_${T}`，不要动 `<TBL>`。

### C1 CREATE TABLE（结构 + 列注释）

```sql
CREATE TABLE `<DB>`.`t_reg_${T}` (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  biz_no VARCHAR(64) NOT NULL COMMENT '业务编号',
  name VARCHAR(128) DEFAULT NULL COMMENT '名称',
  amount DECIMAL(12,2) DEFAULT NULL COMMENT '金额',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回归临时表';
```

```sql
-- ClickHouse 核对
SELECT name,type,comment FROM system.columns
WHERE database='<CK_DB>' AND table='t_reg_${T}' ORDER BY position;
```

**判定**：CK 出现 6 列 —— 4 个业务列（`Int64` / `String` / `Nullable(String)` /
`Nullable(Decimal(12, 2))`，注释分别是 `主键 / 业务编号 / 名称 / 金额`）加上 `_sign` `_version`。

### C2 新表的数据行

```sql
INSERT INTO `<DB>`.`t_reg_${T}` (biz_no,name,amount) VALUES ('R-1','行1',11.11),('R-2','行2',22.22);
```

```sql
SELECT count() FROM <CK_DB>.t_reg_${T} FINAL WHERE _sign=1;
```

**判定分两种情况，取决于这张表有没有进 `table.include.list`：**

- **没进白名单**（默认情况）：CK `count()=0`。这是**预期行为**，不是失败 ——
  DDL 只按 `database.include.list` 过滤，DML 才按 `table.include.list` 过滤。
- **想验证数据也能同步**：把 `<DB>.t_reg_${T}` 加进 `table.include.list`，**重启连接器**，
  然后重新执行 C1 + C2（表要先 DROP 掉重建）。此时 CK `count()=2`，且两行内容与 MySQL 一致。

建议做后者一次，因为「新表的数据能不能同步」是最容易被误判为 bug 的点。

### C3 TRUNCATE TABLE

```sql
TRUNCATE TABLE `<DB>`.`t_reg_${T}`;
```

```sql
SELECT count(*) FROM `<DB>`.`t_reg_${T}`;                              -- MySQL
SELECT count() FROM <CK_DB>.t_reg_${T} FINAL WHERE _sign=1;            -- ClickHouse
```

**判定**：两边都是 0。日志里应该看到 `TRUNCATE TABLE IF EXISTS ... ON CLUSTER`。

### C4 DROP TABLE

```sql
DROP TABLE `<DB>`.`t_reg_${T}`;
```

```sql
SELECT count() FROM system.tables WHERE database='<CK_DB>' AND name='t_reg_${T}';
```

**判定**：结果为 0。**并且要确认两个副本都删掉了** —— 连另一个 CK 副本执行同一条查询，
也必须是 0（语句带 `ON CLUSTER {cluster}`）。

---

## 7. D 组：schema evolution 兜底（必测，**必须通过**）

场景：ClickHouse 表被带外改过（有人手工删了列，或某次 DDL 没同步成功），
连接器应当在下一批写入时把列补回来，并且**把这一行连值一起写进去**。

> **这一项的历史**：`-3` 及之前的所有版本在这里都是失败的，`-4` 修掉了。
> 失败表现是驱动 NPE + 无限重试（该行永远到不了 CK，且不自愈）。
> 根因：`groupQueryWithRecords()` 刷新的是方法内的局部 map，而绑参数时用的是
> `DbWriter` 里那份没被更新的 map，于是 INSERT 列清单里有一列在绑参循环中
> 永远遍历不到，占位符没绑上，clickhouse-jdbc 在 `addBatch()` 抛
> `NullPointerException: str is null`。`-4` 让刷新结果发布到 `DbWriter` 上，
> 两条路径共用同一份 map。
>
> 三个版本在同一张表、同一套步骤下的实测对照：
>
> | jar | 自动 ALTER | NPE | 重试循环 | 行是否到 CK | 值是否正确 |
> |---|---:|---:|---:|---|---|
> | 基线 `604d53ac…`（优化前） | 35 | 105 | 35 | 否 | — |
> | `-3` `4eefc4c2…` | 1 | 105 | 35 | 否 | — |
> | **`-4` `4593128c…`** | **1** | **0** | **0** | **是** | **是** |

**建议用隔离实例跑**（独立 `name` / `topic.prefix` / `database.server.id` / offset 表 /
目标库），这样万一卡住也不影响主任务。开发环境里直接跑：

```bash
source /root/ddl_probe.env && /root/regress_d.sh
```

它会自己起一个独立实例、走完四个步骤、打印六条判定并自动清理，
最后输出 `===> D 组 通过` 或 `===> D 组 失败`。全程不会停主连接器。

```bash
# 步骤 1：MySQL 加一列，等 14 秒让 DDL 同步
```
```sql
ALTER TABLE `<DB>`.`<TBL>` ADD COLUMN drift_${T} VARCHAR(32) NULL COMMENT '兜底测试';
SELECT count() FROM system.columns
WHERE database='<CK_DB>' AND table='<TBL>' AND name='drift_${T}';   -- 应为 1
```

```bash
# 步骤 2：只在 ClickHouse 侧删掉这一列，等 14 秒，期间不要往 MySQL 写数据
```
```sql
ALTER TABLE <CK_DB>.<TBL> ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS drift_${T};
SELECT count() FROM system.columns
WHERE database='<CK_DB>' AND table='<TBL>' AND name='drift_${T}';   -- 应为 0
```

```bash
# 步骤 3：往 MySQL 插一行带该列的新数据，等 35 秒
```
```sql
INSERT INTO `<DB>`.`<TBL>` (biz_no,name,status,drift_${T})
VALUES ('drift-${T}','自愈行',1,'healed');
```

**判定（五条全部满足才算过）：**

```sql
-- 1) 列被自动补回（应为 1）
SELECT count() FROM system.columns
WHERE database='<CK_DB>' AND table='<TBL>' AND name='drift_${T}';
-- 2) 该行到了 CK，且值是 healed（不是空）
SELECT biz_no,name,drift_${T} FROM <CK_DB>.<TBL> FINAL
WHERE _sign=1 AND biz_no='drift-${T}';
```
```bash
# 3) 没有 NPE（应为 0）
docker logs <容器> 2>&1 | grep -c "NullPointerException"
# 4) 没有重试循环（应为 0）
docker logs <容器> 2>&1 | grep -c "batch will be retried"
# 5) 没有被 Code 16 打停（应为 0）
docker logs <容器> 2>&1 | grep -c "stopping task to prevent"
# 参考：应该能看到恰好一次自动补列
docker logs <容器> 2>&1 | grep -c "ALTER TABLE QUERY"
```

> 已知且**不算失败**的现象：补回来的列类型是非 Nullable 的 `String`（不是
> `Nullable(String)`）。这是写入路径 auto-ALTER 的另一个既有缺陷，`-4` 未修，
> 见第 4 节 A4 与 `SYNC_TEST_REPORT.md`。历史行在该列上会是空字符串而不是 NULL。

### 如果卡住了怎么恢复（跑到 `-3` 及更早版本时会遇到）

无限重试**不会自愈**（这条记录永远绑不上参数）。恢复步骤：

```bash
docker stop <容器>
```
```sql
ALTER TABLE `<DB>`.`<TBL>` DROP COLUMN drift_${T};
DELETE FROM `<DB>`.`<TBL>` WHERE biz_no='drift-${T}';
DROP TABLE IF EXISTS <CK_DB>_connector.cdc_offsets ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS <CK_DB>_connector.cdc_schema_history ON CLUSTER '{cluster}' SYNC;
```
```bash
docker start <容器>   # 重新快照，约 90 秒后确认新写入能同步
```

## 7.5 F 组：特殊标识符与耗时（必测，**必须通过**）

反引号里可以出现空格、保留字、逗号和转义反引号。`-4` 及之前的版本对带空格的列名
会误解析成第一个空格前的片段，导致等待器死等一个不存在的列直到 30 秒超时——
DDL 本身是成功的，只是每条这样的语句都要多花 30 秒，后续 COMMENT 和 DML 全排在后面。
`-5` 修好了。

**建议用隔离实例跑。** 开发环境里直接：

```bash
source /root/ddl_probe.env && /root/probe_special_ident.sh ck22.8-fix-20261022-6
```

它会建独立源库和目标库、跑完下面所有用例、打印每条耗时和判定、最后自动清理。

### 用例与判定

```sql
-- F1 保留字列名
ALTER TABLE `<DB>`.`<TBL>` ADD COLUMN `select` INT NULL COMMENT 'kw';
INSERT INTO `<DB>`.`<TBL>` (id,name,`select`) VALUES (101,'kw',1);

-- F2 带空格列名（就是原来卡 30 秒的那条）
ALTER TABLE `<DB>`.`<TBL>` ADD COLUMN `space col` INT NULL COMMENT 'space';
INSERT INTO `<DB>`.`<TBL>` (id,name,`space col`) VALUES (102,'space',100);

-- F3 COMMENT 里带逗号、等号、转义单引号
ALTER TABLE `<DB>`.`<TBL>` ADD COLUMN esc_col VARCHAR(64) NULL COMMENT 'a,b=c it\'s';
INSERT INTO `<DB>`.`<TBL>` (id,name,esc_col) VALUES (103,'esc','x');

-- F4 一条语句三列混合，重复三轮
ALTER TABLE `<DB>`.`<TBL>`
  ADD COLUMN normal_1 INT NULL,
  ADD COLUMN `space col 1` VARCHAR(32) NULL,
  ADD COLUMN CamelCase1 BIGINT NULL;

-- F5 DROP 带空格的列
ALTER TABLE `<DB>`.`<TBL>` DROP COLUMN `space col`;
```

**判定（四条全部满足）：**

1. 每条用例从 `ALTER` 到数据可见 **小于 5 秒**（`-5` 实测 1.1~2.2 秒）
2. 日志里等待器提取的是**完整列名**：
   ```bash
   docker logs <容器> 2>&1 | grep "Waiting for"
   # 期望看到 [space col]、[normal_1, space col 1, CamelCase1]
   # 出现 [space] 就是没跑在 -5 上
   ```
3. `Timeout (30000ms) waiting for columns` 次数为 **0**
   ```bash
   docker logs <容器> 2>&1 | grep -c "Timeout (30000ms) waiting"
   ```
4. ClickHouse 里列名是完整的 `space col`，数据值正确；`DROP` 后该列在两个副本都消失

### `-4` 与 `-5` 的对照（跑旧产出时对号入座）

| 用例 | `-4` | `-5` |
|---|---:|---:|
| `` `select` `` | 1669 ms | 1171 ms |
| `` `space col` `` | **31856 ms** | **1171 ms** |
| 转义 COMMENT | 1686 ms | 1171 ms |
| 三列混合 Round 1 | **31982 ms** | 1171 ms |
| 三列混合 Round 2 | **31987 ms** | 2244 ms |
| 三列混合 Round 3 | **30947 ms** | 1171 ms |
| 等待器提取 | `[space]` | `[space col]` |
| Timeout 次数 | 4 | 0 |

## 7.6 G 组：UPDATE 回填 NULL 列（必测，**必须通过**）

场景：业务先插入一行（某些列为 NULL），再 UPDATE 把这些列补上值，常见于「先建任务、
再补生成编号」。`-5` 及之前的版本会把这条 UPDATE 写成两份 `_version` 相同的数据，
其中一份缺少刚补上的列，于是 ClickHouse 里这几列可能停在 NULL / 默认值。
`-6` 修好了。

在 A 组那张测试表上直接跑，不需要隔离实例。先定好本轮后缀和开始时间：

```bash
source /root/ddl_probe.env
T=$(date +%m%d%H%M%S)
START=$(date -u +%Y-%m-%dT%H:%M:%S)    # G3 查日志用
```

> 下面的 SQL 都通过第 1.5 节的 `my` / `ck` 函数执行。函数用双引号包 SQL，
> 所以**库表名不要加反引号**（反引号会被 shell 当成命令替换）。

### G1 同一事务里：插入空值 → 补一列 → 再补一列

```bash
my "START TRANSACTION;
INSERT INTO <DB>.<TBL> (biz_no,name,status,deleted,amount) VALUES ('g1-${T}','G1',1,NULL,NULL);
UPDATE <DB>.<TBL> SET amount=12.34 WHERE biz_no='g1-${T}';
UPDATE <DB>.<TBL> SET deleted=0 WHERE biz_no='g1-${T}';
COMMIT;"
```

### G2 分两个事务：先插入空值，5 秒后一次补两列

```bash
my "INSERT INTO <DB>.<TBL> (biz_no,name,status,deleted,amount) VALUES ('g2-${T}','G2',1,NULL,NULL);"
sleep 5
my "UPDATE <DB>.<TBL> SET amount=56.78, deleted=0 WHERE biz_no='g2-${T}';"
```

等 10 秒后两边核对：

```bash
my "SELECT biz_no,name,status,deleted,amount FROM <DB>.<TBL> WHERE biz_no IN ('g1-${T}','g2-${T}') ORDER BY biz_no;"
ck "SELECT biz_no,name,status,deleted,amount FROM <CK_DB>.<TBL> FINAL
    WHERE _sign=1 AND biz_no IN ('g1-${T}','g2-${T}') ORDER BY biz_no FORMAT PrettyCompact;"
```

**判定 G1、G2**：两边逐字相等，即 `g1` 行 `deleted=0 / amount=12.34`，`g2` 行
`deleted=0 / amount=56.78`。CK 里出现 `ᴺᵁᴸᴸ` 判失败。

### G3 没有「同一版本写两份不同数据」（核心判定，必须在 G2 后 1 分钟内做）

G1、G2 在 `-5` 上也可能碰巧通过：两份数据谁后写谁赢，顺序看运气。
G3 直接查有没有写出两份，结果不受运气影响。

```bash
# a) 日志里没有缺列告警（应为 0）
docker logs --since "$START" connector 2>&1 | grep -c "Column index missing"

# b) 原始表（不加 FINAL）里同一行、同一 _version 没有两种内容（应为空）
ck "SELECT biz_no, _version, count() AS n, uniqExact(tuple(name,status,deleted,amount)) AS variants
    FROM <CK_DB>.<TBL> WHERE biz_no IN ('g1-${T}','g2-${T}')
    GROUP BY biz_no, _version HAVING variants > 1 FORMAT PrettyCompact;"
```

**判定 G3**：a) 为 0，且 b) 返回空。

> b) 要尽快查：ClickHouse 后台合并后，同版本的重复行会被合掉，在 `-5` 上也就看不到冲突了，
> 但 a) 的日志计数不受合并影响。

### `-5` 与 `-6` 的对照（跑旧产出时对号入座）

本机端到端实测（MySQL 8.0.36 开 GTID + ClickHouse 22.8，120 行，覆盖 G1、G2 两种写法和「置 NULL」，
只换代码）：

| 指标 | `-5` | `-6` |
|---|---:|---:|
| `Column index missing` 次数 | **240** | **0** |
| 同一 (主键, `_version`) 写出不同内容 | **90** | **0** |
| FINAL 与 MySQL 逐列不一致的行 | 0（本机顺序碰巧对） | 0 |

SIT 上 `-5` 的同类数据是错的：`tb_campaign` 843 行，日志 72 小时 350 次 `Column index missing`。

### 开发者自测（可选，需要本机有 Docker）

同一套场景有自动化版本，不依赖开发环境：

```bash
JAVA_HOME=<JDK17> ./mvnw -o -pl sink-connector-lightweight -am test \
  -Dtest=UpdateNullFillSameTransactionIT -Dsurefire.failIfNoSpecifiedTests=false
# 期望 Tests run: 1, Failures: 0
```

---

## 8. 禁止执行清单（跑了会搞坏环境，必须整个任务停下来人工修）

**以下语句一条都不要执行，也不要出于「试试看」的心态执行。**

| # | 禁止的写法 | 后果 |
|---|---|---|
| 1 | `ALTER TABLE t MODIFY COLUMN c <type> NOT NULL`（把可空列改成非空） | CK 无限重试 `Code: 349`，该表**连 SELECT 都报错**，后续所有 ALTER 拿 `Code: 517`，**所有表**停在同一 offset 且不自愈 |
| 2 | `ALTER TABLE t ADD COLUMN (a INT, b DATETIME)`（括号包多列） | `Code: 62` 后走补建路径，把列建成非 Nullable（NULL 永久变 0 / 1970-01-01），之后每次撞 `Code: 15`，整个连接器无限重试卡死，**必须手工重启容器** |
| 3 | `ALTER TABLE t MODIFY/ADD COLUMN c ... DEFAULT CURRENT_TIMESTAMP` 或 `ON UPDATE CURRENT_TIMESTAMP` | `Code: 47`，**整条 ALTER 被丢弃**（不只是这一段）。建表时带着是可以的 |
| 4 | `CREATE TABLE t2 AS SELECT * FROM t1` | MySQL CTAS 不复制 PRIMARY KEY → CK 生成 `ORDER BY tuple()` → 全表行共享同一去重键 → merge 后**只剩 1 行**，无任何报错 |
| 5 | 任何没有 PRIMARY KEY / 非空 UNIQUE 键的建表 | binlog 里没有行标识，连接器下次启动会**拒绝启动** |
| 6 | `ALTER TABLE t`（不带任何动作，脚本拼串容易拼出来） | `Code: 62` |

如果误执行了 1 或 2：先 `SELECT * FROM system.mutations WHERE is_done=0` 找到卡住的
mutation，`KILL MUTATION` 掉，把列改回 `Nullable`，然后重启容器。严重时清掉
`cdc_offsets` 和 `cdc_schema_history` 重新快照。

---

## 9. E 组：全量快照吞吐（可选）

只有在需要复核性能数字时才做。需要一张 150 万行的表，并且**用独立实例跑**
（自己的 `name` / `topic.prefix` / `database.server.id` / offset 表 / 目标库），
否则会污染正在同步的任务。

关键点：

1. 造数用倍增法最快：先插 1 行，然后 `INSERT INTO t (cols) SELECT <expr> FROM t;`
   重复 20 次得到约 105 万行，再用 `LIMIT` 补到 150 万。
2. 独立实例的配置要改 6 处：`name`、`topic.prefix`、`database.server.id`（**必须全局唯一**）、
   `table.include.list`（只留压测表）、`clickhouse.database.override.map`（指向独立库）、
   offset 与 schema history 的表名。
3. 计时从**容器启动**算到 `SELECT count() FROM <目标库>.<表>` 达到 150 万。
4. 容器按 `-m 3500m` + `JAVA_TOOL_OPTIONS="-Xmx2g -Dlog4j2.configurationFile=/log4j2.xml"`。

**参考基准（开发机 4 vCPU，连接器 + CK + ZK 同机）：约 60 秒 / 150 万行。**
如果测出来是 20 分钟以上，先查这一项：

```sql
-- 跑快照期间执行。如果 SELECT 条数是 INSERT 的几千倍，说明跑的不是新产出
SELECT query_kind, count() AS n, round(sum(query_duration_ms)/1000) AS total_s
FROM system.query_log
WHERE event_time > now() - INTERVAL 10 MINUTE AND type='QueryFinish'
GROUP BY query_kind ORDER BY total_s DESC;
```

新产出上 `Select` 的条数应该和批次数同量级（150 万行约 300 条），
旧版是每行 2 条（约 300 万条）。

**数据正确性校验**（必须逐项相等）：

```sql
-- MySQL
SELECT count(*), min(id), max(id), sum(status), round(sum(amount),2), sum(qty) FROM <DB>.<压测表>;
-- ClickHouse
SELECT count(), min(id), max(id), sum(status), round(sum(amount),2), sum(qty)
FROM <CK_DB>.<压测表> FINAL WHERE _sign=1;
-- 另外确认没有重复主键
SELECT count() = uniqExact(id) FROM <CK_DB>.<压测表>;
```

---

## 10. 清理

```sql
-- MySQL：删掉本轮加的列、行、临时表
ALTER TABLE `<DB>`.`<TBL>` DROP COLUMN a1_${T}, DROP COLUMN c2_${T}, DROP COLUMN drift_${T};
DELETE FROM `<DB>`.`<TBL>` WHERE biz_no LIKE 'd-${T}%' OR biz_no LIKE 'drift-${T}%'
   OR biz_no IN ('g1-${T}','g2-${T}');
DROP TABLE IF EXISTS `<DB>`.`t_reg_${T}`;
```

等 10 秒后确认两边列一致：

```sql
-- MySQL
SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position)
FROM information_schema.columns WHERE table_schema='<DB>' AND table_name='<TBL>';
-- ClickHouse（应当只多出 _sign 和 _version）
SELECT groupArray(name) FROM (
  SELECT name FROM system.columns
  WHERE database='<CK_DB>' AND table='<TBL>' ORDER BY position);
```

如果为 C2 改过 `table.include.list`，记得把临时表从白名单里去掉并重启连接器。
另外检查 `system` 库有没有留下垃圾空表（连接器每次重启会把最近的 DDL 重放一遍，
第二遍会落到 `system` 库，这是已知缺陷）：

```sql
SELECT name FROM system.tables WHERE database='system' AND name LIKE 't_reg%';
-- 有就 DROP TABLE IF EXISTS system.<表名> ON CLUSTER '{cluster}';
```

---

## 11. 结果报告模板

把下表填满交回。**每一项都要贴 MySQL 和 ClickHouse 的原始输出**，不要只写「通过」。

```
产出版本：clickhouse_debezium_embedded:ck22.8-fix-20261022-6
/app.jar md5：____（期望 b261ff8932798c4b6ed45ccb1e8ddc99）
镜像架构：____（期望 amd64/linux）
本轮后缀 T=____
环境：用的是第 1.5 节的开发环境 / 自建环境（划掉不适用的）
      宿主机 ____ / MySQL ____ / ClickHouse ____ / 测试表 ____
log4j2 挂载生效（http-outgoing 计数为 0）：是 / 否

A1 INSERT              通过 / 失败   同步耗时 __s
A2 UPDATE              通过 / 失败   同步耗时 __s
A3 UPDATE 置 NULL      通过 / 失败   同步耗时 __s
A4 DELETE（_sign=-1）  通过 / 失败   同步耗时 __s
B1 ADD 单列            通过 / 失败   同步耗时 __s
B2 ADD 两列逗号式      通过 / 失败   同步耗时 __s
B3 ADD FIRST           通过 / 失败   同步耗时 __s
B4 ADD AFTER           通过 / 失败   同步耗时 __s
B5 MODIFY 加宽         通过 / 失败   同步耗时 __s
B6 MODIFY 改可空       通过 / 失败   同步耗时 __s
B7 MODIFY 只改注释     通过 / 失败   同步耗时 __s
B8 CHANGE 改名+注释    通过 / 失败   同步耗时 __s
B9 DROP 单列           通过 / 失败   同步耗时 __s
B10 DROP 两列          通过 / 失败   同步耗时 __s
C1 CREATE TABLE 结构   通过 / 失败   同步耗时 __s
C2 新表数据行          通过 / 失败   （是否加白名单重启：是 / 否）
C3 TRUNCATE            通过 / 失败   同步耗时 __s
C4 DROP TABLE（双副本）通过 / 失败   同步耗时 __s
D  schema evo 兜底       通过 / 失败
   列是否补回 __ / 测试行是否到 CK __ / 值是否正确 __
   自动 ALTER 次数 __（期望 1）/ NPE __（期望 0）/ 重试循环 __（期望 0）/ Code16 打停 __（期望 0）
   是否用隔离实例跑 是 / 否
F  特殊标识符          通过 / 失败
   F1 `select` __ms / F2 `space col` __ms / F3 转义 COMMENT __ms
   F4 三列混合 Round1 __ms / Round2 __ms / Round3 __ms（每条都要 <5000ms）
   F5 DROP `space col` 两副本均消失 __
   等待器提取到的列名 __（期望 `space col`，不是 `space`）
   Timeout(30000ms) 次数 __（期望 0）
G  UPDATE 回填 NULL 列
   G1 同事务插入后补列     通过 / 失败
   G2 分事务插入后补列     通过 / 失败
   G3 Column index missing 次数 __（期望 0）/ 同版本冲突行 __（期望 0）  通过 / 失败
E  150 万行快照（可选）____ 秒，数据校验 一致 / 不一致

27 项（A~D 加 F、G）中通过 __ 项。
是否触发第 3 节第 5 条的停止条件：是 / 否（如是，贴出日志）
清理后两边列是否一致：是 / 否
```

---

## 12. 附：一条命令验证「任务真的在同步」

任何时候怀疑连接器卡住了，用这个判断，**不要看 `/status`**
（实测它在任务完全卡死时仍返回 `Replica_Running: true, Seconds_Behind_Source: 0`，
在数据已追平时又报 `Seconds_Behind_Source: 305`）：

```sql
-- 间隔 30 秒执行两次，record_insert_ts 必须变大
SELECT max(record_insert_ts) FROM <CK_DB>_connector.cdc_offsets;
```

另外确认 offset 表没有膨胀（`ORDER BY` 写错时 ReplacingMergeTree 永远不折叠）：

```sql
SELECT count(), uniqExact(offset_key) FROM <CK_DB>_connector.cdc_offsets;
-- 两个数应该接近；count() 远大于 uniqExact 说明建表语句的 ORDER BY 不是 offset_key
SHOW CREATE TABLE <CK_DB>_connector.cdc_offsets;
-- 期望 ORDER BY offset_key
```








