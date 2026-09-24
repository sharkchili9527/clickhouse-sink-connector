# 产出目录说明

对应连接器版本：`clickhouse_debezium_embedded:ck22.8-fix-20260904-5`（linux/amd64）

## 部署用

| 文件 | 用途 |
|---|---|
| `clickhouse_debezium_embedded-ck22.8-fix-20260904-5-amd64.tar.gz` | 镜像离线包，`docker load` 导入 |
| `docker-compose.yml` | 部署编排，已带内存/日志驱动/挂载配置 |
| `config.yml.template` | 连接器配置模板，按 141 万行场景 + **4G 容器**调优，填完 `<>` 占位符改名成 `config.yml`。队列参数与 `docker-compose.yml` 的 `-Xmx2g` / `mem_limit 3500m` 配套，换内存档位要四处一起改（模板头部有说明） |
| `log4j2.xml` | 日志配置，必须挂载。镜像内置的是 DEBUG 级别，会把每条 INSERT 的 HTTP 报文全打出来，快照慢 18%、日志 503MB |
| `jmx_prometheus_javaagent-0.20.0.jar` | JMX → Prometheus agent，必须挂载（compose 已配 `-javaagent`）。见「监控接入」 |
| `jmx-exporter.yml` | agent 规则，把 Debezium MBean 转成 `debezium_metrics_*`，必须挂载 |
| `HANDOVER.md` | 交付清单：文件校验和、导入自检、环境边界、责任边界 |
| `Dockerfile` | 镜像构建文件（无 JDWP），需要自行重建时用 |
| `app.jar` | shade 后的可执行 jar，`Dockerfile` 会 COPY 成 `/app.jar` |
| `sink-connector-client` | 官方 CLI（x86-64 ELF），镜像里在 `/sink-connector-client` |

## 运维用

| 文件 | 用途 |
|---|---|
| `truncate_prod_targets.sh` | **破坏性**：清空目标库 20 张表数据、保留结构。默认 dry-run。见「重灌恢复」 |

## 演示与验证用

| 文件 | 用途 |
|---|---|
| `ddl_probe.sh` | 能力演示脚本：发一条 MySQL 语句 → 打印 MySQL 原生查询结果 → 给一条 ClickHouse 查询语句自行比对 |
| `SYNC_TEST_REPORT.md` | 全部实测记录：DML/DDL 逐句结果、CREATE TABLE 五种情形、快照性能优化、禁用清单 |
| `REGRESSION_SOP.md` | 回归测试操作手册，24 项逐条给出「语句 → 期望 → 判定」，含实测环境信息，可直接交给他人执行 |

## 部署步骤

```bash
gunzip -c clickhouse_debezium_embedded-ck22.8-fix-20260904-5-amd64.tar.gz | docker load

# 确认拿到的是这一版（架构和 jar 都要对得上）
docker image inspect clickhouse_debezium_embedded:ck22.8-fix-20260904-5 --format '{{.Architecture}}/{{.Os}}'
#   期望 amd64/linux
docker run --rm --entrypoint md5sum clickhouse_debezium_embedded:ck22.8-fix-20260904-5 /app.jar
#   期望 d8c2e7607ac53ab2684e75a11e15c835

cp config.yml.template config.yml
vi config.yml            # 填 <MYSQL_HOST> / <CK_HOST> / 账号口令 / 表清单 等
docker compose up -d     # log4j2.xml 由 compose 挂载，不用额外操作

docker logs -f connector | grep -E "Snapshot ended|Connected to binlog"
# 起来之后自检：下面这条必须是 0，否则说明 log4j2.xml 没挂上
docker logs connector 2>&1 | grep -c "http-outgoing"
# 监控自检：必须 > 0，否则 javaagent 没生效，断流告警拿不到数据
curl -s localhost:9072/metrics | grep -c debezium_metrics_
```

交付物校验和、导入自检、环境与责任边界见 `HANDOVER.md`。

## 上线前必读

七条硬性约束：

1. `single.threaded` 必须是 `false`。为 `true` 时所有 DDL 静默失效且不报错。
2. ClickHouse 必须配 `cluster` 宏。`CREATE` / `TRUNCATE` / `DROP TABLE` 都带 `ON CLUSTER {cluster}`。
3. **`log4j2.xml` 必须挂载**，`docker-compose.yml` 里那两行别删。镜像内 shade 进去的是
   DEBUG 级别，会把每条 INSERT 的 HTTP 报文都打出来：150 万行快照多耗 18%、产出 503MB 日志。
   注意只能用 `JAVA_TOOL_OPTIONS` 传 `-Dlog4j2.configurationFile`，`JAVA_OPTS` 在这个镜像里不生效。
4. MySQL 侧禁止「把可空列改成 NOT NULL」。会让该表在 ClickHouse 不可读并卡死整个任务，
   且不自愈。替代做法：加非空新列 → 回填 → 删旧列。
5. MySQL 侧禁止 `CREATE TABLE ... AS SELECT`。MySQL 的 CTAS 不复制主键，连接器会生成
   `ORDER BY tuple()` 的 ReplacingMergeTree，整表所有行共享同一去重键，merge 后**只剩一行**
   且没有任何报错（实测 3 行变 1 行）。要建表带数据就拆成 `CREATE TABLE`（显式写主键）
   加 `INSERT INTO ... SELECT`。
6. `docker-compose.yml` 里 jmx-exporter 的**两行挂载与 `-javaagent` 参数必须同时存在**。
   只留其一容器起不来。确实不接监控就两处一起删，并接受没有断流告警。
7. `database.server.id` 全局唯一，且**同一个值禁止并行跑两个容器实例**（包括"新旧容器
   并行观察"）。MySQL 会把先连上的客户端踢掉，现象是两边轮流断流。

完整的禁用清单（含括号式多列 ADD COLUMN、`DEFAULT CURRENT_TIMESTAMP` 等 6 条）见
`REGRESSION_SOP.md` 第 8 节，每条都注明了后果和恢复办法。

两条查询约定：

- 业务查询必须 `FINAL` 且过滤 `_sign=1`。删除是标记而非物理删除。
- DDL 失败会写入 `system.error_table`（含 binlog 位点），建议对它做告警。

一条运维提醒：连接器每次重启会把最近的 DDL 重放一遍，第二遍会落到 `system` 库
（建出同名空表）。所以 ClickHouse 账号需要对 `system` 库有 CREATE 权限，否则重启后
这条 DDL 会失败重试。上线前确认一下。

## 监控接入

两个端口，职责不同，**都不能单独作为唯一判据**：

| 端口 | 来源 | 内容 |
|---|---|---|
| `:8083` | 连接器自身（`metrics.enable/metrics.port`） | 写入路径计数 |
| `:9072` | `docker-compose.yml` 挂的 jmx-exporter javaagent | Debezium 的 JMX MBean |

```bash
curl -s localhost:9072/metrics | grep debezium_metrics_
```

**可用的断流判据**（目前唯一一条来自连接器内部的可靠信号）：

```
debezium_metrics_MilliSecondsSinceLastEvent{context="streaming"}
```

持续单调增长即为已断流。

三个实测得到的陷阱，配告警前必须知道：

1. `:8083` 的写入计数在故障时是**冻结**，不是归零、也不报错。用「数值是否下降」做判据永远不会触发。
2. **没有** `MilliSecondsBehindSource`。这个镜像的 embedded 模式不暴露它，不要基于它配告警。
3. context 标签是 `streaming`，**不是** `binlog`。写成 `binlog` 会查不到任何序列，告警静默失效。

**告警阈值建议 5 分钟**。理由：`restart.event.loop.timeout.period.secs` 是 `3000`（50 分钟）
才触发自愈重启，阈值若接近这个量级，等于把 50 分钟的静默当成正常。

`-javaagent` 只能通过 `JAVA_TOOL_OPTIONS` 注入（这个镜像不读 `JAVA_OPTS`）；
agent 在同一 JVM 内读本地 MBean，不需要开 `-Dcom.sun.management.jmxremote` 远程端口。
不要因为加了 agent 就上调 `-Xmx`：快照峰值已经 2.0~2.3GiB，容器上限 3500m。

## 日常巡检

三条，按可靠性从高到低。先设好环境变量：

```bash
CK="http://<CK_USER>:<CK_PASSWORD>@<CK_HOST>:8123"   # 实际取值见部署记录
TGT=<TARGET_DB>                                      # 如 stat_prod
META=<META_DB>                                       # 如 stat_prod_connector
```

**1) 行数比对（唯一不依赖连接器自身指标的判据，必须做）**

对 `table.include.list` 里的每张表各跑一次，两边取同一时刻：

```bash
# ClickHouse 侧：删除是 _sign 标记，必须 FINAL + _sign=1
curl -s "$CK/?query=SELECT%20count()%20FROM%20$TGT.<TABLE>%20FINAL%20WHERE%20_sign=1"
# MySQL 侧
mysql -h<MYSQL_HOST> -u<MYSQL_USER> -p -N -e "select count(*) from <DB>.<TABLE>"
```

增量期间有秒级延迟属正常，判据是**差值收敛**而非恒等于 0。差值持续单调扩大即为异常。

**2) DDL 失败检查**

```bash
curl -s "$CK/?query=SELECT%20*%20FROM%20system.error_table%20ORDER%20BY%20*%20DESC%20LIMIT%2020"
```

这张表里的每一行都带 binlog 位点，建议对「新增行」直接配告警。

**3) 存活与断流**

```bash
docker ps --filter name=connector --format '{{.Status}}'
curl -s localhost:9072/metrics | grep MilliSecondsSinceLastEvent
```

## 故障处置

四类，按「判据 → 动作 → 是否需要重灌」给。

### A. 断流（`MilliSecondsSinceLastEvent` 持续增长）

判据链，按顺序排除：

```bash
docker ps -a --filter name=connector         # 容器是否还活着
docker logs --tail 500 connector | grep -E "Timeout \(30000ms\)|NullPointerException|Retrying"
```

| 现象 | 动作 | 重灌 |
|---|---|---|
| 容器已退出 | 看退出码与末尾日志；OOM 则先确认没人调过 `-Xmx` | 否 |
| 有 `Timeout (30000ms)` 反复出现 | 命中 DDL 等待器超时，对照第 8 节禁用清单找源端那条 DDL | 视情况 |
| NPE 且批次无限 `Retrying` | 确认镜像是否 `-4` 及以上；旧版有此缺陷且不自愈 | 否，换镜像 |
| 一切正常但仍在涨 | 查是否有人用同一 `database.server.id` 起了第二个实例 | 否 |

自愈窗口是 50 分钟（`restart.event.loop.timeout.period.secs=3000`），不要坐等。

### B. 某张表在 ClickHouse 读不出来，或整个任务卡死

优先怀疑源端做了这两种 DDL（均在禁用清单内，且**不自愈**）：

- 把可空列改成 `NOT NULL`
- 括号式多列 `ADD COLUMN (a INT, b INT)`

动作：源端改回 → 重灌**该表**（按下一节流程，目标表与状态表都要清）。→ **需要重灌**

### C. 某条 DDL 既没同步、也不报错

按顺序查三处，前两处是"设计如此"，不是故障：

1. `sink-connector-lightweight/.../cdc/IgnoreDDLRegexLoader.java:7-18` —— **10 条硬编码忽略规则**
   （`ADD/DROP/TRUNCATE/ANALYZE/CHECK/OPTIMIZE/REORGANIZE PARTITION`、`REMOVE PARTITIONING`、
   `AUTO_INCREMENT=`）。这些**无法通过配置关闭**。
2. `config.yml` 的 `ignore.ddl.regex`。注意 `||` 是**分隔符**（代码走 `split("\\|\\|")`），
   不是正则的"或"，别误读成空分支。
3. 查 `system` 库是否被建了同名空表 —— 连接器重启会把最近的 DDL 重放一遍，第二遍落到 `system`。

→ 前两种不需要处理；第三种清掉 `system` 里的垃圾表即可，不必重灌。

### D. 数据莫名只剩一行 / 行数远少于源端

怀疑源端用了 `CREATE TABLE ... AS SELECT`：MySQL 的 CTAS 不复制主键，连接器会生成
`ORDER BY tuple()` 的 ReplacingMergeTree，全表所有行共享同一去重键，merge 后只剩一行且无任何报错。

动作：源端改成显式建表 + `INSERT INTO ... SELECT` → 重灌该表。→ **需要重灌**

## 重灌恢复

**先理解一件事**：连接器的全部续跑状态都在 ClickHouse 的 `<META_DB>.cdc_offsets` 与
`<META_DB>.cdc_schema_history` 里，不在容器本地。所以「删容器重建」「清空目标表」都**不会**
触发重新全量——不清状态表，起来还是带旧位点续跑。这是重灌最容易漏的一步。

五步，顺序不能换：

```bash
# 1) 停连接器（必须先停，否则边清边写）
docker stop connector

# 2) 清目标表（保留表结构）。脚本默认 dry-run，先不加参数看一遍要执行什么
CK_HOST=<CK_HOST> CK_USER=<CK_USER> CK_PASSWORD=<CK_PASSWORD> ./truncate_prod_targets.sh
CK_HOST=<CK_HOST> CK_USER=<CK_USER> CK_PASSWORD=<CK_PASSWORD> ./truncate_prod_targets.sh --yes

# 3) 清连接器状态 —— 不可省略，脚本不含这步
CK="http://<CK_USER>:<CK_PASSWORD>@<CK_HOST>:8123"
curl -sS "$CK/" --data-binary "TRUNCATE TABLE IF EXISTS <META_DB>.cdc_offsets ON CLUSTER '{cluster}'"
curl -sS "$CK/" --data-binary "TRUNCATE TABLE IF EXISTS <META_DB>.cdc_schema_history ON CLUSTER '{cluster}'"

# 4) 起连接器，走全量快照
docker start connector
docker logs -f connector | grep -E "Snapshot ended|Connected to binlog"

# 5) 验收
docker logs connector 2>&1 | grep -c "http-outgoing"   # 必须是 0，否则 log4j2.xml 没挂上
```

关于 `truncate_prod_targets.sh`：**生产破坏性脚本**，只 `TRUNCATE` ClickHouse
目标库里那 20 张表（带 `ON CLUSTER '{cluster}'`，两副本一起清），保留表结构，
**不动 MySQL 源端**。默认 dry-run，真跑要 `--yes` 并手工输入 `TRUNCATE` 二次确认；
口令用环境变量传，不要写进脚本。集群没配 `cluster` 宏时把 `CLUSTER` 设为空串。

只重灌单张表时：把第 2 步换成对该表的 `TRUNCATE`，但第 3 步仍要全清——
offset 是任务级的，没有单表粒度。代价是其余表也会重新全量一次。

验收口径按「日常巡检」第 1 条：20 张表逐张比对行数，差值收敛。

## `-3` 相对 `-2` 的变化

只有一处代码改动，目标是全量快照吞吐：

- `GroupInsertQueryWithBatchRecords` 原来**每条记录**都要重读一次 ClickHouse 列元数据，
  而每次重读是两条 `system.columns` 查询。实测 32 万行快照打出 66 万条元数据 SELECT，
  吃掉 2.6 个核（共 4 核），真正的 INSERT 只占 33 条、平均 19ms。
  现在改成**每批刷新一次**，并且只在 `alterTable` 真的加了列时额外刷新。
- 效果：150 万行快照 **28分48秒 → 约 60 秒**（同一台机、同一份配置、数据校验完全一致）。
- 配套：挂载本目录的 `log4j2.xml` 把日志从 DEBUG 降到 INFO。

细节和实测数据见 `SYNC_TEST_REPORT.md` 第六节。

## `-4` 相对 `-3` 的变化

修掉一个**既有缺陷**（`-3` 及之前所有版本都有，不是 `-3` 引入的）：
写入路径自动补列后，那一批的 INSERT 会带一个永远绑不上的占位符，
clickhouse-jdbc 在 `addBatch()` 抛 `NullPointerException`，批次被判为可重试后
**无限重试且不自愈**，该行永远到不了 ClickHouse。

- 根因：`groupQueryWithRecords()` 刷新的是方法内的局部 map，而绑参数时用的是
  `DbWriter` 里那份没被更新的 map（调用方传进去的是
  `writer.getColumnNameToDataTypeMap()`）。两条路径看到的列清单不一致。
- 改法：新增 `DbWriter.refreshColumnNameToDataTypeMap()`，把刷新结果发布到
  writer 上；`groupQueryWithRecords()` 增加一个 `SchemaRefresher` 参数，
  两处刷新（每批一次、以及 ALTER 之后）都走它。字段加 `volatile`，
  因为 `thread.pool.size > 1` 时多个 worker 共享同一个 `DbWriter`。
- 实测（同一张表、同一套步骤，只换 jar）：

  | jar | 自动 ALTER | NPE | 重试循环 | 行是否到 CK |
  |---|---:|---:|---:|---|
  | 基线（优化前） | 35 | 105 | 35 | 否 |
  | `-3` | 1 | 105 | 35 | 否 |
  | **`-4`** | **1** | **0** | **0** | **是，值正确** |

- 回归：14 项 DML/DDL 全过；150 万行快照仍是约 60 秒，数据与 MySQL 逐项一致。

复现与判定步骤见 `REGRESSION_SOP.md` 第 7 节。

## `-5` 相对 `-4` 的变化

修掉 `DDLSchemaChangeWaiter` 的 identifier 解析缺陷（`-4` 及之前所有版本都有）：

- 现象：`ALTER TABLE t ADD COLUMN \`space col\` INT NULL` —— ClickHouse 侧其实立刻就建好了，
  但等待器把列名解析成 `space`，于是死等一个不存在的列直到 30 秒超时。实测单条 31.9 秒、
  三轮组合各 32 秒，**每一条带空格反引号列名的 DDL 都要多花 30 秒**，期间后续 COMMENT 和
  DML 全部排在后面。DROP 侧同一个误解析更隐蔽：错列名本来就不存在，等待器立刻返回，
  从来没确认过真实列已从所有副本消失。
- 根因：正则写成 `` `?([^`\s]+)`? `` —— 反引号是可选的，但捕获组仍然禁止空白字符。
- 改法：把 quoted 和 bare 两种形式拆成两个候选分支
  `` (?:`((?:``|[^`])+)`|([^\s,]+)) ``，抽取时取第一个非空捕获组并把 `` `` `` 还原成一个反引号。
  `ADD` / `DROP` / `ALTER TABLE` 的库名表名三处统一用同一个片段。
- 实测（同一套用例，只换镜像）：

  | 用例 | `-4` | `-5` |
  |---|---:|---:|
  | 关键字列名 `` `select` `` | 1.7s | 1.2s |
  | 带空格列名 `` `space col` `` | **31.9s** | **1.2s** |
  | 三列混合 Round 1/2/3 | 32.0 / 32.0 / 30.9s | 1.2 / 2.2 / 1.2s |
  | 等待器提取到的列名 | `[space]` | `[space col]` |
  | `Timeout (30000ms)` 次数 | 4 | **0** |

- 回归：A+B 组 14 项全过、D 组通过、150 万行快照仍是约 60 秒且数据逐项一致。

顺带修了 `sink-connector/pom.xml` 的 `testSourceDirectory`：原来指向 `src/test` 而不是
`src/test/java`，导致每个测试类被额外编译成 `java.com.altinity...` 包，Surefire 扫描时
JVM 拒绝以 `java` 开头的包名（`Prohibited package name`），**这个模块的单元测试从来没跑起来过**。
修正后 282 个测试真正执行，262 通过、20 个因为本机 testcontainers 找不到 Docker 环境而报错
（`IllegalState: Previous attempts to find a Docker environment failed`），不是代码缺陷。
打包仍然走 `-DskipTests=true`，不受影响。
