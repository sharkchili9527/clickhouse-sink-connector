# 旧版升级 SOP：`-5` 及更早 → `ck22.8-fix-20261022-6`

适用：已经在跑 `clickhouse_debezium_embedded:ck22.8-fix-20260904-5`（或更早）的环境，SIT、prod 通用。
prod 上的 `docker` 实际是 podman，命令照抄即可；prod 没装 clickhouse-client，CK 一律走 curl 8123。

本次只换镜像里的 `/app.jar`，**配置文件、挂载、状态表都不用改**。升级后从原位点续跑，不会重新全量。
修了什么见 `README.md`「`-6` 相对 `-5` 的变化」。

> 升级只保证**之后**写入正确，**已经错的数据不会自己修好**，第 5 步必须做。

全程约 15 分钟（不含第 5 步重灌）。第 3 步会停同步 1~2 分钟，选业务低峰。

---

## 1. 升级前准备（不停机）

```bash
cd <交付目录>
# 1) 校验交付包
shasum -a 256 -c clickhouse_debezium_embedded-ck22.8-fix-20261022-6-amd64.tar.gz.sha256
#    期望：OK

# 2) 记下当前在跑的镜像和容器启动参数（回滚要用）
docker inspect connector --format '{{.Config.Image}}'
docker inspect connector --format '{{json .HostConfig.Binds}} {{json .Config.Env}}' > /root/connector_before_upgrade.txt

# 3) 保存旧容器日志 —— 第 3 步删容器后就没了，第 5 步要靠它判断哪些表受影响
docker logs connector > /root/connector_before_upgrade.log 2>&1
grep 'Column index missing' /root/connector_before_upgrade.log \
  | grep -oE 'Table\([^)]*\)' | sort | uniq -c
#    这里列出的就是受影响的表，记下来
grep 'Column index missing' /root/connector_before_upgrade.log \
  | grep -oE 'column \*+[A-Za-z_]+' | sort | uniq -c
#    这里列出的是受影响的列
```

## 2. 导入新镜像并自检（不停机）

```bash
gunzip -c clickhouse_debezium_embedded-ck22.8-fix-20261022-6-amd64.tar.gz | docker load

docker image inspect clickhouse_debezium_embedded:ck22.8-fix-20261022-6 --format '{{.Architecture}}/{{.Os}}'
#   期望：amd64/linux
docker run --rm --entrypoint md5sum clickhouse_debezium_embedded:ck22.8-fix-20261022-6 /app.jar
#   期望：b261ff8932798c4b6ed45ccb1e8ddc99
```

旧镜像**不要删**，回滚要用。

## 3. 切换镜像（停同步 1~2 分钟）

按当前的部署方式二选一。

**用 `docker compose` 部署的**：

```bash
sed -i 's/ck22.8-fix-20260904-5/ck22.8-fix-20261022-6/' docker-compose.yml
grep 'image:' docker-compose.yml          # 确认已是 -6
docker compose up -d                      # 只会重建 connector 容器
```

**用 `docker run` 部署的**：照抄原来的 `docker run` 命令，只把最后的镜像 tag 换成 `-6`。
挂载、`JAVA_TOOL_OPTIONS`、日志驱动一项都不能少，以第 1 步保存的
`/root/connector_before_upgrade.txt` 为准。

```bash
docker stop connector && docker rm connector
docker run -d --name connector --network host --restart unless-stopped \
  <与原来完全相同的 -e / -v / --log-driver 参数> \
  clickhouse_debezium_embedded:ck22.8-fix-20261022-6
```

## 4. 升级后验收（四条都要满足）

```bash
# a) 镜像对了
docker inspect connector --format '{{.Config.Image}}'
#    期望：clickhouse_debezium_embedded:ck22.8-fix-20261022-6

# b) 是续跑，不是重新全量：第一条应 > 0，第二条应为 0（Debezium 开始全量快照时会打这一行）
docker logs connector 2>&1 | grep -c "Connected to binlog"
docker logs connector 2>&1 | grep -c "Snapshot step 1"

# c) 位点在推进：间隔 30 秒执行两次，第二次必须更大
curl -sS "http://<CK_HOST>:8123/" --user "<CK_USER>:<CK_PWD>" \
  --data-binary "SELECT max(record_insert_ts) FROM <META_DB>.cdc_offsets"

# d) 日志正常：前两条应为 0，第三条应 > 0
docker logs connector 2>&1 | grep -c "http-outgoing"
docker logs connector 2>&1 | grep -c "Column index missing"
curl -s localhost:9072/metrics | grep -c debezium_metrics_
```

`Column index missing` 要在业务有过「创建任务」这类写入之后再看一次，仍为 0 才算通过。
SIT 上还可以按 `REGRESSION_SOP.md` 第 7.6 节跑 G 组（要求测试表可写）。

## 5. 修复存量数据

第 1 步列出的表都要修。推荐整体重灌：步骤已在 prod 实际执行过（2026-09-21），
结果可预期。

1. 按 `README.md`「重灌恢复」五步执行（停连接器 → 清目标表 → 清 `cdc_offsets` /
   `cdc_schema_history` → 起连接器 → 验收）。**此时镜像已经是 `-6`**，重灌出来的数据就是对的。
2. offset 是整个任务一份，只重灌一张表也要清状态表，其余表也会重新全量一次。
   参考耗时：150 万行约 60 秒，可以按源库总行数估算。
3. 重灌期间 ClickHouse 里的目标表是空的，提前通知看报表的人。

重灌完成后，对第 1 步列出的每一列，两边 NULL 行数必须相等：

```sql
-- MySQL
SELECT COUNT(*) FROM <源库>.<表> WHERE <列> IS NULL;
-- ClickHouse
SELECT count() FROM <CK库>.<表> FINAL WHERE _sign = 1 AND <列> IS NULL;
```

以 SIT 的 `tb_campaign` 为例，修复前 `gen_id` 两边是 0 / 843，修复后两边都应为 0；
`search_text`、`total_time` 同理。之前用来比对差异的脚本再跑一遍，差异数应为 0。

## 6. 回滚（只在第 4 步验收不通过时做）

```bash
# compose 部署
sed -i 's/ck22.8-fix-20261022-6/ck22.8-fix-20260904-5/' docker-compose.yml && docker compose up -d
# docker run 部署：同第 3 步，把镜像 tag 换回 -5
```

两版的 offset、schema history 格式完全一样，回滚后同样是续跑，不需要清状态表。
回滚后缺陷会重新出现，把第 4 步的失败现象和 `docker logs connector` 一起反馈给开发。

---

## 执行记录（填好交回）

```
环境：SIT / prod        执行人 ____   时间 ____
升级前镜像 ____         受影响的表 / 列 ____（第 1 步）
新镜像 md5 ____（期望 b261ff8932798c4b6ed45ccb1e8ddc99）
验收 a/b/c/d：____ / ____ / ____ / ____
Column index missing（业务写入后）：____（期望 0）
存量修复方式：重灌 / 未做（原因 ____）    完成时间 ____
修复后 NULL 行数比对（逐列）：____
是否回滚：是 / 否
```
