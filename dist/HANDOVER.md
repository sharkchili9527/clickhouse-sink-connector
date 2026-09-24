# 交付清单 — MySQL → ClickHouse 同步连接器

镜像版本：`clickhouse_debezium_embedded:ck22.8-fix-20260904-5`（linux/amd64）

接手人按本清单从上到下勾一遍即可独立部署与运维，不需要再问开发。
部署细节、监控接入、巡检、故障处置、重灌恢复全部在 `README.md`，本文件只做清单与边界。

## 1. 文件清单

拷贝方式：整目录拷贝。带 ✅ 的是运行必需，缺一项容器起不来。

| 文件 | 必需 | 必须挂载 | 用途 |
|---|:--:|:--:|---|
| `clickhouse_debezium_embedded-ck22.8-fix-20260904-5-amd64.tar.gz` | ✅ | — | 镜像离线包，`docker load` 导入 |
| `docker-compose.yml` | ✅ | — | 部署编排（内存 / 日志驱动 / 挂载 / javaagent） |
| `config.yml`（从 `config.yml.template` 生成） | ✅ | ✅ `/config.yml` | 连接器配置 |
| `log4j2.xml` | ✅ | ✅ `/log4j2.xml` | 日志级别。不挂载则走镜像内置 DEBUG |
| `jmx_prometheus_javaagent-0.20.0.jar` | ✅ | ✅ `/jmx-exporter.jar` | JMX → Prometheus agent |
| `jmx-exporter.yml` | ✅ | ✅ `/jmx-exporter.yml` | agent 规则（Debezium MBean → 指标） |
| `config.yml.template` | — | — | 配置模板，逐项带注释与取值理由 |
| `truncate_prod_targets.sh` | — | — | **破坏性**：清空目标库 20 张表。重灌用 |
| `ddl_probe.sh` | — | — | 能力演示脚本（源端发 DDL，两边比对） |
| `Dockerfile` / `app.jar` | — | — | 需要自行重建镜像时用 |
| `sink-connector-client` | — | — | 官方 CLI（x86-64 ELF），镜像内 `/sink-connector-client` |
| `README.md` | — | — | 部署 + 运维 SOP，**必读** |
| `REGRESSION_SOP.md` | — | — | 24 项回归用例，可直接交他人执行 |
| `SYNC_TEST_REPORT.md` | — | — | 全部实测记录与禁用清单出处 |

### 校验和

```
6156d8d102d45173d1f7c01143a9d94b36f954d8d88ba1a740d0adab1f3ea8a7  clickhouse_debezium_embedded-ck22.8-fix-20260904-5-amd64.tar.gz
e20b324217f2be4e3a7316c19e5635d57066e4e3e1b9fe97bf1c162f98122776  app.jar
5c8b62a4e63d9873e285e2f03db251e94422f7491802634d5cba6ad9a6262831  sink-connector-client
8b67ed40485d475648c36d11d21464b484406f85faf912b3363f71a6a7861320  jmx_prometheus_javaagent-0.20.0.jar
c46774acde28b0ddc3ff6f13282fbebf998672a7ff4b98bed5e4549d03d56a95  log4j2.xml
5e1c94d61fb64474ecbb07938d366bbac21f610cda85f430a7ceb0d2f1101329  jmx-exporter.yml
```

```bash
shasum -a 256 -c <<< "<上面对应行>"
```

二进制文件（`app.jar`、`sink-connector-client`、镜像包、agent jar）**不在 git 仓库里**，
按 `dist/.gitignore` 的约定随交付包物理拷贝，一致性靠上面的校验和保证。

## 2. 导入后自检

```bash
gunzip -c clickhouse_debezium_embedded-ck22.8-fix-20260904-5-amd64.tar.gz | docker load

docker image inspect clickhouse_debezium_embedded:ck22.8-fix-20260904-5 --format '{{.Architecture}}/{{.Os}}'
#   期望 amd64/linux
docker run --rm --entrypoint md5sum clickhouse_debezium_embedded:ck22.8-fix-20260904-5 /app.jar
#   期望 d8c2e7607ac53ab2684e75a11e15c835
```

起容器后再看这两条：

```bash
docker logs -f connector | grep -E "Snapshot ended|Connected to binlog"
docker logs connector 2>&1 | grep -c "http-outgoing"   # 必须 0，否则 log4j2.xml 没挂上
curl -s localhost:9072/metrics | grep -c debezium_metrics_   # 应 > 0，否则 agent 没生效
```

## 3. 环境信息

主机地址、账号、口令**一律不写在本文件**，见部署记录 / 密码库 / 口头交接。
这里只给拓扑与库表边界。

带真值与口令的运维手册（含已填好可直接执行的巡检、故障速查、重灌命令）单独保管在代码库外，
**不入库**：`~/<项目>-handover/OPS-RUNBOOK-prod.md`（权限 600）。交接时随凭据一并转交。

> 下表及全部 `dist/` 文档里的库名（`db_*` / `stat_*`）与主机名（`<...>`）都是脱敏后的
> 占位与示例值，真实取值见本机 `dist/ENV_LOCAL.md`（不入库）。

| 环境 | 源 | 目标 | 说明 |
|---|---|---|---|
| prod | MySQL RDS，三个库：`db_base` / `db_soft` / `db_camp` | ClickHouse 22.8 集群，统一落到 `stat_prod`；状态表在 `stat_prod_connector` | 容器由 podman 托管；源账号**只读**，不能在此做 DDL 验证 |
| dev | 自建 MySQL | ClickHouse 22.8，单分片双副本 | 能力验证 / 回归测试只在这里做 |

订阅 20 张表：`db_base` 9 张、`db_soft` 3 张、`db_camp` 8 张。
完整清单见 `truncate_prod_targets.sh` 的 `TABLES` 数组与 `config.yml` 的 `table.include.list`。

两条必须落实的运维前置：

- ClickHouse 账号需对 `system` 库有 `CREATE` 权限。连接器重启会把最近的 DDL 重放一遍，
  第二遍会落到 `system` 库；没有权限就会失败重试。
- 部署后 `chmod 600 config.yml`。该文件含 MySQL 与 ClickHouse 的明文口令，
  已被 `dist/.gitignore` 排除，不要提交、不要贴进聊天或工单。

## 4. 已知坑索引

不要凭直觉排查，先查这三处：

| 场景 | 去哪看 |
|---|---|
| 上线前硬性约束（7 条） | `README.md`「上线前必读」 |
| 完整 DDL 禁用清单（6 条，含后果与恢复办法） | `REGRESSION_SOP.md` 第 8 节 |
| 实测数据、性能结论、CREATE TABLE 五种情形 | `SYNC_TEST_REPORT.md` |
| 四类典型故障的判据与动作 | `README.md`「故障处置」 |
| 重灌为什么要清 `cdc_offsets` | `README.md`「重灌恢复」 |

最容易踩的四条，提前说：

1. `single.threaded` 必须 `false`。为 `true` 时每个 DDL 事件死于 NPE，且**完全不报错**。
2. `log4j2.xml` 必须挂载，否则快照慢 18%、日志 503MB。
3. 源端禁止「可空列改 `NOT NULL`」与括号式多列 `ADD COLUMN`：会让该表不可读并卡死整个任务，**不自愈**。
4. 源端禁止 `CREATE TABLE ... AS SELECT`：生成 `ORDER BY tuple()`，整表 merge 后只剩一行且无报错。

## 5. 责任边界

- **源端 DDL 规范由业务方遵守，连接器不做拦截。** 禁用清单里的语句发下来一样会被执行，
  后果是数据不可读或任务卡死。规范需要写进业务方的变更流程，不能只靠这份文档。
- **prod 源账号只读**，所有能力验证、回归测试在 dev 做，不要在 prod 试 DDL。
- **监控告警由运维配置。** `README.md`「监控接入」给了可用判据、三个陷阱与阈值建议；
  连接器只暴露指标，不自带告警。此前一次 9 天未被发现的停机，直接原因就是没有告警。
- **重灌是运维动作**，`truncate_prod_targets.sh` 只清 ClickHouse 目标表，
  不动 MySQL、也不清状态表。状态表那一步必须手工做。
