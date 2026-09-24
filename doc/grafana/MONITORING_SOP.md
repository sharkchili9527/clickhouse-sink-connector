# ClickHouse Sink Connector 监控 SOP

适用：MySQL → ClickHouse（lightweight embedded，镜像 `clickhouse_debezium_embedded:ck22.8-fix-20260904-5`）。
配套：面板 `doc/grafana/clickhouse-sink-connector-dashboard.json`、告警 `doc/grafana/sink-connector-alerts.yml`。

## 0. 排查顺序

1. 任务存活 → `连接器在线`、`运行时长`
2. binlog 健康 → `binlog 已连接`、`源端延迟`、`距上次事件`（源端指标，见第 3 节）
3. 数据在流 → `写入速率`、`源端事件速率`
4. 有无出错 → `错误速率`、`源端错误事件`、`DDL fail`

## 1. 两套指标

- 连接器指标：`:8083`，前缀 `clickhouse_sink_*`。写入路径触发，停滞时冻结在旧值。
- 源端指标：`:9072`，前缀 `debezium_metrics_*`（`context="streaming"`）。源端视角，不受写入冻结，binlog 健康以此为准。

`name` 标签 = `topic.prefix`（SIT `<TOPIC_PREFIX_QA>`，prod `<TOPIC_PREFIX_PROD>`）。

## 2. 指标字典

### 2.1 存活（连接器指标）
- `clickhouse_sink_connector_uptime`：运行时长(ms)，回退/归零 = 重启。
- `up{...:8083}`：抓取成功 = 1，0 = 进程挂或端口不可达。
- `resets(clickhouse_sink_connector_uptime[15m])`：15 分钟内重启次数。

### 2.2 延迟
- `clickhouse_sink_db_lag{Topic}`（连接器指标,ms）：端到端延迟，停滞时冻结。
- `clickhouse_sink_debezium_lag{Topic}`（连接器指标,ms）：连接器内部延迟。
- `debezium_metrics_MilliSecondsBehindSource`（源端指标,ms）：源端视角延迟，停滞时持续攀升，首选延迟指标。

### 2.3 binlog 健康（源端指标，context=streaming）
- `Connected`：1 = 连着，0 = 断开。
- `NumberOfDisconnects`：累计断连次数。
- `MilliSecondsSinceLastEvent`：距上次事件毫秒数，空闲也会涨。
- `TotalNumberOfEventsSeen`：累计事件数，`rate()>0` = 还在读 binlog。
- `NumberOfErroneousEvents`：源端出错事件数。
- `SnapshotRunning` / `SnapshotCompleted`：快照状态，断点续传启动时 Completed=0 正常。

### 2.4 吞吐 / 写入错误（连接器指标）
- `clickhouse_sink_topics_num_records_total{topic}`：累计写入行数。
- `clickhouse_sink_topics_error_records_total{topic}`：累计写入错误。
- `clickhouse_sink_binlog_pos{file}`：已消费位点，仅写入成功才更新。

### 2.5 DDL
- `clickhouse_sink_ddl_total{ddl,fail}`：`fail="true"` = DDL 同步失败。

## 3. binlog 健康怎么感知（重点）

连接器指标是写入路径触发。binlog 断连、位点被 purge、无新事件时不产生批次，延迟和位点冻结在旧值，看着正常实则已停——"延迟爬高"类告警覆盖不到这种故障。真信号在源端指标：`Connected`、`NumberOfDisconnects`、`MilliSecondsBehindSource`、`MilliSecondsSinceLastEvent`、`rate(TotalNumberOfEventsSeen)`。

### 3.1 故障场景 → 信号

| 场景 | 源端指标 | 连接器指标 |
|------|----------|-----------|
| ① 源库宕机 / 网络断 | `Connected=0`，`NumberOfDisconnects` 抬升 | up 可能仍=1，延迟冻结 |
| ② 反复掉线 | `Connected` 0/1 抖动，`NumberOfDisconnects` 增长 | 延迟锯齿或冻结 |
| ③ binlog 被 purge / 位点丢失 | 常伴崩溃 | `uptime` 回退、崩溃重启循环，日志报找不到 binlog file |
| ④ 连着但无新事件 | `Connected=1`，`MilliSecondsSinceLastEvent` 单调爬升，`rate(TotalNumberOfEventsSeen)=0` | `binlog_pos` 不动、写入速率 0 |
| ⑤ 复制变慢（大事务 / CH 写入慢） | `MilliSecondsBehindSource` 攀升，事件仍在流 | `db_lag` 攀升，写入速率>0 |
| ⑥ 快照卡住 / 中断 | `SnapshotRunning=1` 不结束 或 `SnapshotAborted` | 全量期写入停滞 |

场景④判据（三者同时成立才是故障）：`Connected==1` 且 `rate(TotalNumberOfEventsSeen[10m])==0` 且源库确有写入。源库空闲时事件为 0 属正常，故对应告警用 30m `for`、标 warning。

### 3.2 决策树

1. `Connected==0` → binlog 断了（①/②），查网络/源库/账号。
2. `uptime` 15m 内回退 → 崩溃重启循环（③，多为位点丢失），查日志 binlog 报错。
3. `MilliSecondsBehindSource` 攀升：事件在流（`rate(TotalNumberOfEventsSeen)>0`）= 慢（⑤），查大事务/CH 写入；事件=0 且 `Connected=1` = 场景④。
4. 皆否、`Connected=1`、事件在流、延迟低 = 健康。

## 4. 告警

### 4.1 加载
`prometheus.yml` 的 `rule_files` 加入 `sink-connector-alerts.yml`，`promtool check rules` 校验后 reload。发送渠道按 severity 在 Alertmanager 配。

### 4.2 告警清单

| 告警 | 触发条件 | 级别 | 首步处置 |
|------|----------|------|----------|
| `SinkAppMetricsMissing` | `absent(clickhouse_sink_connector_uptime)` 2m | critical | 进程挂/端口不可达，查 `docker compose ps/logs` |
| `SinkConnectorCrashLoop` | `resets(uptime[15m])>=3` | critical | 崩溃重启循环，查日志 binlog file 报错（③） |
| `BinlogDisconnected` | `Connected==0` 2m | critical | 查网络/源库存活/账号权限（①/②） |
| `ReplicationLagWarning` | `MilliSecondsBehindSource>30s` 5m | warning | 观察是否持续，查大事务/CH 写入 |
| `ReplicationLagCritical` | `MilliSecondsBehindSource>5min` 5m | critical | 查 CH 写入瓶颈/资源/大事务（⑤） |
| `ConnectedButNoEvents` | `Connected==1` 且 `rate(TotalNumberOfEventsSeen[10m])==0` 30m | warning | 结合源库是否有写入判断（④），空闲期可能误报 |
| `SourceErroneousEvents` | `increase(NumberOfErroneousEvents[10m])>0` | warning | 查日志对应事件 |
| `SinkWriteErrors` | `rate(clickhouse_sink_topics_error_records_total[5m])>0` 5m | warning | 查 CH 错误、schema/类型不匹配 |
| `SinkDDLFailure` | `increase(clickhouse_sink_ddl_total{fail="true"}[10m])>0` | critical | DDL 失败。MySQL 侧改可空列为 NOT NULL 会卡死整表 |

阈值为起点值，按实际流量调整。

### 4.3 路由
- critical → 电话/即时消息，分钟级响应。
- warning → 群消息，工作时间跟进。
- 按 `name` 标签分实例。

## 5. 上 prod

- 部署物一致：同一 jar + `jmx-exporter.yml` + compose 的 `-javaagent`；离线拷 jar，镜像不重造。
- `name` 变 `<TOPIC_PREFIX_PROD>`，面板/告警按 name 聚合，无需改。
- `:9072` 无鉴权，防火墙只放行 Prometheus。
- 快照期 streaming 指标未注册，短暂"无源端指标数据"正常。
- 别上调 `-Xmx`（容器 3500m，快照峰值已 2.0~2.3GiB）。

## 6. 每日巡检

1. 连接器在线=UP，运行时长增长。
2. binlog 已连接，断连次数=0。
3. 源端延迟、距上次事件 低且平稳。
4. 写入速率 / 源端事件速率 与业务量相符。
5. 错误速率 / 源端错误事件 / DDL fail = 0。

异常按第 3 节决策树定位。
