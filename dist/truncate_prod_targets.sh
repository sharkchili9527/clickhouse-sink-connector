#!/usr/bin/env bash
# =============================================================================
# 清空 ClickHouse 目标库 stat_prod 的 20 张表数据,保留表结构(curl/HTTP 版)
# 本机无需安装 clickhouse-client,直接走 HTTP 8123。
#
# !!! 生产环境破坏性操作 !!! 执行顺序必须是:
#   1) 先停连接器:            docker stop connector
#   2) 跑本脚本清空目标表:     ./truncate_prod_targets.sh --yes
#   3) 清 offset / schema history 触发重新快照(本脚本不含这步,单独做)
#   4) 再启连接器:            docker start connector
#   注意:只清目标表【不会】触发重灌;不清 offset,连接器仍会带旧位点续跑。
#
# 默认 dry-run:不加参数只打印将要执行的语句,不改任何数据。
# 确认无误后:  ./truncate_prod_targets.sh --yes
# =============================================================================
set -euo pipefail

# ---------- 连接参数(按需用环境变量覆盖)----------
CK_HOST="${CK_HOST:-127.0.0.1}"
CK_PORT="${CK_PORT:-8123}"          # HTTP 端口 8123(不是 native 9000)
CK_USER="${CK_USER:-default}"
CK_PASSWORD="${CK_PASSWORD:-}"      # 强烈建议用环境变量传,不要写进脚本
CK_DB="${CK_DB:-stat_prod}"
# 复制表(ReplicatedReplacingMergeTree)必须 ON CLUSTER 才能两副本一起清。
# 用宏 {cluster};若你的集群没配 cluster 宏,把 CLUSTER 设空串即可去掉该子句。
CLUSTER="${CLUSTER:-{cluster}}"

# ---------- 20 张目标表(源三库都映射到 stat_prod)----------
TABLES=(
  # db_base -> stat_prod (9)
  tb_as_product
  tb_ecu_module
  tb_ecu_part
  tb_vehicle
  tb_vehicle_logistics_history
  tb_vehicle_logistics_original
  tb_vehicle_model
  tb_vehicle_module_info
  tb_vehicle_version_status
  # db_soft -> stat_prod (3)
  tb_baseline
  tb_baseline_version
  tb_firmware_version
  # db_camp -> stat_prod (8)
  tb_campaign
  tb_install
  tb_install_log
  tb_install_module
  tb_policy
  tb_policy_baseline
  tb_policy_ext
  tb_campaign_work_order
)

DRY_RUN=1
[[ "${1:-}" == "--yes" ]] && DRY_RUN=0

# HTTP 方式执行一条 SQL:成功打印结果并返回 0;HTTP 非 200 时把错误写到 stderr 并返回 1。
# 用 -w 取 http_code,兼容所有 curl 版本(不依赖 --fail-with-body)。
ck() {
  local out code body
  out="$(curl -sS -w $'\n%{http_code}' "http://${CK_HOST}:${CK_PORT}/" \
         --user "${CK_USER}:${CK_PASSWORD}" --data-binary "$1")" || return 1
  code="${out##*$'\n'}"
  body="${out%$'\n'*}"
  if [[ "$code" != "200" ]]; then
    echo "CK HTTP ${code}: ${body}" >&2
    return 1
  fi
  printf '%s' "$body"
}

ON=""
[[ -n "$CLUSTER" ]] && ON=" ON CLUSTER '$CLUSTER'"

echo "目标: ${CK_HOST}:${CK_PORT}  库=${CK_DB}  ON CLUSTER='${CLUSTER:-<none>}'  表数=${#TABLES[@]}"
[[ $DRY_RUN -eq 1 ]] && echo "*** DRY-RUN(只打印,不执行);确认后加 --yes ***"
# ---------- 预检:连通 + 各表是否存在 ----------
echo "--- 预检:连通性 ---"
ping_res="$(ck "SELECT 1" || true)"
if [[ "$ping_res" != "1" ]]; then
  echo "无法连接 ClickHouse(${CK_HOST}:${CK_PORT}),请检查 CK_HOST/CK_PORT/CK_USER/CK_PASSWORD。返回: ${ping_res}"
  exit 1
fi

echo "--- 预检:表存在性 ---"
missing=0
for t in "${TABLES[@]}"; do
  exists="$(ck "SELECT count() FROM system.tables WHERE database='${CK_DB}' AND name='${t}'" || echo 0)"
  if [[ "$exists" == "1" ]]; then
    printf '  [OK ] %s\n' "$t"
  else
    printf '  [MISS] %s  <== 目标库不存在该表\n' "$t"
    missing=$((missing+1))
  fi
done
[[ $missing -gt 0 ]] && echo "警告:有 ${missing} 张表在 ${CK_DB} 不存在(将被 IF EXISTS 跳过)"

# ---------- 二次确认(真跑时)----------
if [[ $DRY_RUN -eq 0 ]]; then
  echo
  echo "!!! 即将 TRUNCATE 上述 ${#TABLES[@]} 张表的全部数据(保留结构),生产环境不可逆 !!!"
  echo "请确认已执行 'docker stop connector'。输入 TRUNCATE 继续,其它任意键取消:"
  read -r ans
  [[ "$ans" == "TRUNCATE" ]] || { echo "已取消。"; exit 1; }
fi

# ---------- 执行 ----------
echo "--- 清空 ---"
for t in "${TABLES[@]}"; do
  stmt="TRUNCATE TABLE IF EXISTS \`${CK_DB}\`.\`${t}\`${ON}"
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "[dry-run] ${stmt}"
  else
    printf '>> %-32s ' "$t"
    ck "$stmt" >/dev/null && echo "done"
  fi
done

# ---------- 执行后核对(真跑时)----------
if [[ $DRY_RUN -eq 0 ]]; then
  echo "--- 核对剩余行数(应全为 0)---"
  for t in "${TABLES[@]}"; do
    n="$(ck "SELECT count() FROM \`${CK_DB}\`.\`${t}\`" 2>/dev/null || echo 'ERR')"
    printf '  %-32s %s\n' "$t" "$n"
  done
fi
echo "完成。"
