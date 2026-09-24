#!/usr/bin/env bash
# MySQL → ClickHouse 同步能力演示脚本
#
# 每个测试项只做三件事，全部摆在明面上：
#   ① 打印并执行发往 MySQL 的语句
#   ② 打印并执行 MySQL 侧的查询，输出 mysql 客户端的原生结果
#   ③ 打印一条 ClickHouse 查询语句，由你自己执行、自己比对
#
# 每项只动一条数据（或一列），便于肉眼核对。
#
# 用法：
#   ./ddl_probe.sh <类别> [表名]
#
# 类别：
#   dml            INSERT / UPDATE / 置 NULL / DELETE，全程只动一行
#   add-column     ADD COLUMN：单列带 DEFAULT+NOT NULL+COMMENT / 一条语句两列 / FIRST / AFTER
#   modify-column  MODIFY COLUMN：类型加宽 / NOT NULL→NULL / 只改注释
#   change-column  CHANGE COLUMN 改列名 + 注释
#   drop-column    DROP COLUMN：单列 / 一条语句两列
#   create-table   新建一张带列注释的表并插一行
#   drop-table     建一张临时表插一行再删（不碰演示表）
#   truncate       TRUNCATE TABLE（会清空演示表）
#   cleanup        清掉历史演示残留的列 / 行 / 临时表（反复演示后用）
#   all            dml + add/modify/change/drop-column
#
# 环境变量：MYSQL_HOST=<MySQL 地址> MYSQL_PORT=3306 MYSQL_USER=<账号> MYSQL_PWD=<口令>
#           MYSQL_HOST / MYSQL_USER 没有默认值，必须显式给
#           DDL_DB=db_camp  CK_DB=stat_qa  DRY_RUN=1 只打印不执行
#           NO_PAUSE=1 不在每步之间停顿（默认每步停下来等你核对）
set -uo pipefail

CAT="${1:-}"
TBL="${2:-tb_campaign_work_order}"
DB="${DDL_DB:-db_camp}"
CK_DB="${CK_DB:-stat_qa}"
MYSQL_HOST="${MYSQL_HOST:?需通过环境变量指定 MySQL 地址}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:?需通过环境变量指定 MySQL 账号}"
# 口令不写在脚本里。优先读同目录的 ddl_probe.env（该文件不入库），
# 也可以直接用环境变量传：MYSQL_PWD=xxx CK_PWD=yyy ./ddl_probe.sh dml
# ddl_probe.env 内容示例：
#   MYSQL_PWD=你的MySQL口令
#   CK_PWD=你的ClickHouse口令
[ -f "$(dirname "$0")/ddl_probe.env" ] && . "$(dirname "$0")/ddl_probe.env"
MYSQL_PWD="${MYSQL_PWD:-}"
CK_PWD="${CK_PWD:-}"
if [ -z "$MYSQL_PWD" ]; then
    echo "缺少 MySQL 口令。请在脚本同目录建 ddl_probe.env 写入 MYSQL_PWD=... ，" >&2
    echo "或用环境变量：MYSQL_PWD=xxx CK_PWD=yyy $0 $*" >&2
    exit 2
fi
DRY_RUN="${DRY_RUN:-0}"
export MYSQL_PWD

T="$(date +%m%d%H%M%S)$((RANDOM % 100))"

my() {
    if command -v mysql >/dev/null 2>&1; then
        mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" \
              --default-character-set=utf8mb4 -e "$1" 2>&1 | grep -v "Using a password"
    else
        docker run --rm -e MYSQL_PWD mysql:8.0 mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" \
              -u"$MYSQL_USER" --ssl-mode=DISABLED --default-character-set=utf8mb4 \
              -e "$1" 2>&1 | grep -v "Using a password"
    fi
}

# $1 标题  $2 发往 MySQL 的语句  $3 MySQL 核对语句  $4 ClickHouse 核对语句
step() {
    echo
    echo "══════════════════════════════════════════════════════════════"
    echo "【$1】"
    echo
    echo "① 发往 MySQL："
    printf '%s\n' "$2" | sed 's/^/     /'
    [ "$DRY_RUN" = "1" ] || my "$2" > /dev/null
    echo
    echo "② MySQL 查询结果："
    printf '%s\n' "$3" | sed 's/^/     -- /'
    [ "$DRY_RUN" = "1" ] || my "$3" | sed 's/^/     /'
    echo
    echo "③ 到 ClickHouse 执行这条，自己比对："
    printf '%s\n' "$4" | sed 's/^/     /'
    pause
}

# 每一步之间停住，等你去 ClickHouse 核对完再继续。
# 否则脚本会一口气跑完，等你去查的时候数据已经是最后一步的状态了。
pause() {
    [ "${NO_PAUSE:-0}" = "1" ] && return 0
    [ "$DRY_RUN" = "1" ] && return 0
    [ -t 0 ] || return 0
    echo
    read -r -p "     ── 核对完按回车进入下一步 ── " _
}

prep() {
    echo
    echo "  [准备] $1"
    [ "$DRY_RUN" = "1" ] || my "$1" > /dev/null
}

COL_MY() { echo "SELECT column_name, column_type, is_nullable, column_default, column_comment FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$TBL' AND column_name IN ($1);"; }
COL_CK() { echo "SELECT name, type, comment FROM system.columns WHERE database='$CK_DB' AND table='$TBL' AND name IN ($1) ORDER BY position FORMAT PrettyCompact;"; }
ROW_MY() { echo "SELECT biz_no, name, status, deleted, amount FROM \`$DB\`.\`$TBL\` WHERE biz_no='$1';"; }
ROW_CK() { echo "SELECT biz_no, name, status, deleted, amount FROM $CK_DB.$TBL FINAL WHERE _sign=1 AND biz_no='$1' FORMAT PrettyCompact;"; }

cat_dml() {
    local K="d-$T"
    step "DML 1/4 · INSERT 一行" \
"INSERT INTO \`$DB\`.\`$TBL\` (biz_no,name,status,deleted,amount) VALUES ('$K','演示行',1,0,12.34);" \
"$(ROW_MY "$K")" "$(ROW_CK "$K")"

    step "DML 2/4 · UPDATE 同一行（改 name 和 amount）" \
"UPDATE \`$DB\`.\`$TBL\` SET name='改过了-$T', amount=99.99 WHERE biz_no='$K';" \
"$(ROW_MY "$K")" "$(ROW_CK "$K")"

    step "DML 3/4 · 把两个字段置为 NULL（验证 NULL 不会被默认值吞掉）" \
"UPDATE \`$DB\`.\`$TBL\` SET amount=NULL, deleted=NULL WHERE biz_no='$K';" \
"$(ROW_MY "$K")" "$(ROW_CK "$K")"

    step "DML 4/4 · DELETE 同一行（CK 侧是 _sign=-1 标记，不是物理删除）" \
"DELETE FROM \`$DB\`.\`$TBL\` WHERE biz_no='$K';" \
"SELECT count(*) AS mysql_still_has FROM \`$DB\`.\`$TBL\` WHERE biz_no='$K';" \
"SELECT biz_no, _sign, _version FROM $CK_DB.$TBL WHERE biz_no='$K' ORDER BY _version FORMAT PrettyCompact;
     -- 再看一遍：加了 FINAL + _sign=1 之后应该查不到这一行
     SELECT count() FROM $CK_DB.$TBL FINAL WHERE _sign=1 AND biz_no='$K';"
}

cat_add_column() {
    step "ADD COLUMN 1/4 · 单列，带 DEFAULT + NOT NULL + COMMENT" \
"ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN a1_$T INT NOT NULL DEFAULT 7 COMMENT '单列注释-$T';" \
"$(COL_MY "'a1_$T'")" "$(COL_CK "'a1_$T'")"

    step "ADD COLUMN 2/4 · 一条语句加两列，各带注释" \
"ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN a2_$T VARCHAR(32) NULL COMMENT '两列之一-$T', ADD COLUMN a3_$T DECIMAL(9,3) NULL COMMENT '两列之二-$T';" \
"$(COL_MY "'a2_$T','a3_$T'")" "$(COL_CK "'a2_$T','a3_$T'")"

    step "ADD COLUMN 3/4 · FIRST（插到最前面，CK 列顺序应一致）" \
"ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN a4_$T INT NULL COMMENT '放最前-$T' FIRST;" \
"SELECT ordinal_position, column_name FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$TBL' ORDER BY ordinal_position LIMIT 3;" \
"SELECT position, name FROM system.columns WHERE database='$CK_DB' AND table='$TBL' ORDER BY position LIMIT 3 FORMAT PrettyCompact;"

    step "ADD COLUMN 4/4 · AFTER name（插在 name 之后）" \
"ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN a5_$T TEXT NULL COMMENT '跟在name后-$T' AFTER name;" \
"SELECT ordinal_position, column_name FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$TBL' AND ordinal_position BETWEEN 3 AND 5 ORDER BY ordinal_position;" \
"SELECT position, name FROM system.columns WHERE database='$CK_DB' AND table='$TBL' AND position BETWEEN 3 AND 5 ORDER BY position FORMAT PrettyCompact;"
}

cat_modify_column() {
    prep "ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN m1_$T INT NULL COMMENT '改之前-$T';"
    step "MODIFY COLUMN 1/3 · 类型加宽 int → bigint，同时改注释" \
"ALTER TABLE \`$DB\`.\`$TBL\` MODIFY COLUMN m1_$T BIGINT NULL COMMENT '加宽后-$T';" \
"$(COL_MY "'m1_$T'")" "$(COL_CK "'m1_$T'")"

    prep "ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN m2_$T INT NOT NULL DEFAULT 0 COMMENT '本来非空-$T';"
    step "MODIFY COLUMN 2/3 · NOT NULL → NULL（CK 类型应变成 Nullable）" \
"ALTER TABLE \`$DB\`.\`$TBL\` MODIFY COLUMN m2_$T INT NULL COMMENT '改成可空-$T';" \
"$(COL_MY "'m2_$T'")" "$(COL_CK "'m2_$T'")"

    prep "ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN m3_$T VARCHAR(32) NULL COMMENT '旧注释-$T';"
    step "MODIFY COLUMN 3/3 · 只改注释，类型不变" \
"ALTER TABLE \`$DB\`.\`$TBL\` MODIFY COLUMN m3_$T VARCHAR(32) NULL COMMENT '新注释-$T';" \
"$(COL_MY "'m3_$T'")" "$(COL_CK "'m3_$T'")"
}

cat_change_column() {
    prep "ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN c1_$T VARCHAR(64) NULL COMMENT '改名前-$T';"
    step "CHANGE COLUMN · 改列名 + 改注释（注释应落在新列名上）" \
"ALTER TABLE \`$DB\`.\`$TBL\` CHANGE COLUMN c1_$T c2_$T VARCHAR(128) NULL COMMENT '改名后-$T';" \
"$(COL_MY "'c1_$T','c2_$T'")" "$(COL_CK "'c1_$T','c2_$T'")"
}

cat_drop_column() {
    prep "ALTER TABLE \`$DB\`.\`$TBL\` ADD COLUMN x1_$T INT NULL, ADD COLUMN x2_$T INT NULL, ADD COLUMN x3_$T INT NULL;"
    step "DROP COLUMN 1/2 · 删单列" \
"ALTER TABLE \`$DB\`.\`$TBL\` DROP COLUMN x1_$T;" \
"$(COL_MY "'x1_$T','x2_$T','x3_$T'")" "$(COL_CK "'x1_$T','x2_$T','x3_$T'")"

    step "DROP COLUMN 2/2 · 一条语句删两列" \
"ALTER TABLE \`$DB\`.\`$TBL\` DROP COLUMN x2_$T, DROP COLUMN x3_$T;" \
"$(COL_MY "'x1_$T','x2_$T','x3_$T'")" "$(COL_CK "'x1_$T','x2_$T','x3_$T'")"
}

cat_create_table() {
    local NT="${TBL}_new_$T"
    step "CREATE TABLE · 增量期新建表（列注释应一起同步过去）" \
"CREATE TABLE \`$DB\`.\`$NT\` (
       id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
       nm VARCHAR(64) NULL COMMENT '名称',
       amt DECIMAL(12,2) NULL COMMENT '金额',
       plain VARCHAR(32) NULL,
       PRIMARY KEY (id)
     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
     INSERT INTO \`$DB\`.\`$NT\` (nm,amt,plain) VALUES ('演示-$T',3.14,'无注释列');" \
"SELECT column_name, column_type, column_comment FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$NT' ORDER BY ordinal_position;
     SELECT nm, amt, plain FROM \`$DB\`.\`$NT\`;" \
"SELECT name, type, comment FROM system.columns WHERE database='$CK_DB' AND table='$NT' ORDER BY position FORMAT PrettyCompact;
     SELECT nm, amt, plain FROM $CK_DB.$NT FINAL WHERE _sign=1 FORMAT PrettyCompact;"
    echo
    echo "     注意：这张新表不在 table.include.list 里，所以 CK 会建出表结构但没有数据行"
    echo "           （DDL 不按表过滤，DML 才按表过滤）——这是预期行为"
}

cat_drop_table() {
    local NT="${TBL}_tmp_$T"
    prep "CREATE TABLE \`$DB\`.\`$NT\` (id BIGINT NOT NULL AUTO_INCREMENT, v VARCHAR(32) NULL COMMENT '值', PRIMARY KEY(id));"
    echo "     (先等 10 秒让 CK 侧建好表，再删)"
    [ "$DRY_RUN" = "1" ] || sleep 10
    step "DROP TABLE · 删表（CK 侧表应一起消失）" \
"DROP TABLE \`$DB\`.\`$NT\`;" \
"SELECT count(*) AS mysql_still_has FROM information_schema.tables WHERE table_schema='$DB' AND table_name='$NT';" \
"SELECT count() AS ck_still_has FROM system.tables WHERE database='$CK_DB' AND name='$NT';"
}

cat_truncate() {
    step "TRUNCATE TABLE · 清空演示表（会清掉所有数据，慎用）" \
"TRUNCATE TABLE \`$DB\`.\`$TBL\`;" \
"SELECT count(*) AS mysql_rows FROM \`$DB\`.\`$TBL\`;" \
"SELECT count() AS ck_rows FROM $CK_DB.$TBL FORMAT PrettyCompact;"
}

# 反复演示会累积测试列和测试表，这个类别把它们清掉，只认严格的命名模式，
# 绝不会误伤业务列（例如 amount 不匹配 a[1-5]_<11到12位数字>）。
cat_cleanup() {
    echo
    echo "══════════════════════════════════════════════════════════════"
    echo "【cleanup · 清理历史演示残留】"
    local pat='^(a[1-5]|m[1-3]|c[12]|x[1-3])_[0-9]{11,12}$'
    echo
    echo "① 将要删除的列（严格匹配 $pat）："
    my "SELECT column_name FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$TBL' AND column_name REGEXP '$pat' ORDER BY ordinal_position;" | sed 's/^/     /'
    local cols
    cols=$(my "SELECT group_concat(concat('DROP COLUMN \`',column_name,'\`') SEPARATOR ', ') FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$TBL' AND column_name REGEXP '$pat';" | tail -1)
    if [ -n "$cols" ] && [ "$cols" != "NULL" ] && [ "$cols" != "group_concat(concat('DROP COLUMN \`',column_name,'\`') SEPARATOR ', ')" ]; then
        echo
        echo "② 执行："
        echo "     ALTER TABLE \`$DB\`.\`$TBL\` $cols;"
        [ "$DRY_RUN" = "1" ] || my "ALTER TABLE \`$DB\`.\`$TBL\` $cols;" > /dev/null
    else
        echo "     （没有需要清理的列）"
    fi
    echo
    echo "③ 删除演示残留的行和临时表："
    echo "     DELETE FROM \`$DB\`.\`$TBL\` WHERE biz_no LIKE 'd-%';"
    [ "$DRY_RUN" = "1" ] || my "DELETE FROM \`$DB\`.\`$TBL\` WHERE biz_no LIKE 'd-%';" > /dev/null
    my "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND (table_name LIKE '${TBL}_new_%' OR table_name LIKE '${TBL}_tmp_%');" | tail -n +2 | while read -r t; do
        [ -z "$t" ] && continue
        echo "     DROP TABLE \`$DB\`.\`$t\`;"
        [ "$DRY_RUN" = "1" ] || my "DROP TABLE \`$DB\`.\`$t\`;" > /dev/null
    done
    echo
    echo "④ 清理后的列（应只剩业务列）："
    my "SELECT group_concat(column_name ORDER BY ordinal_position) AS cols FROM information_schema.columns WHERE table_schema='$DB' AND table_name='$TBL';" | sed 's/^/     /'
    echo
    echo "⑤ 到 ClickHouse 确认两边一致："
    echo "     SELECT name FROM system.columns WHERE database='$CK_DB' AND table='$TBL' ORDER BY position FORMAT PrettyCompact;"
}

usage() { sed -n '2,32p' "$0"; exit 1; }

echo "演示目标： MySQL $DB.$TBL   →   ClickHouse $CK_DB.$TBL"
echo "本轮后缀： $T"
echo "ClickHouse 客户端： docker exec -it clickhouse clickhouse-client --password $CK_PWD"
echo "提示：发完 MySQL 语句后等几秒再执行 ③ 的 ClickHouse 语句"

case "$CAT" in
    dml)           cat_dml ;;
    add-column)    cat_add_column ;;
    modify-column) cat_modify_column ;;
    change-column) cat_change_column ;;
    drop-column)   cat_drop_column ;;
    create-table)  cat_create_table ;;
    drop-table)    cat_drop_table ;;
    truncate)      cat_truncate ;;
    cleanup)       cat_cleanup ;;
    all)           cat_dml; cat_add_column; cat_modify_column
                   cat_change_column; cat_drop_column ;;
    *)             usage ;;
esac

echo
echo "══════════════════════════════════════════════════════════════"
echo "本轮后缀 $T ，上面每个 ③ 的语句都可以直接粘到 clickhouse-client 里执行"
