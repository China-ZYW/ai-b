# -*- coding: utf-8 -*-
"""电商销售演示库 ``aibi_demo_sales`` 建库灌数总入口。

用法::

    python seed_sales_demo.py                # 重建库 → 灌 50 张基础表 → 派生 10 张宽表 → 校验
    python seed_sales_demo.py --verify-only  # 只跑校验与行数报告，不动数据

两个刻意的设计选择：

1. **每个阶段结束立刻 commit**。目标库在远程（往返延迟高），把 50 万行放在一个事务里既容易撞上
   undo/锁开销，一旦中途失败也看不到进展；分阶段提交后，失败时能明确知道停在哪一步。

2. **宽表一律 ``INSERT…SELECT`` 从基础表派生**，绝不用 Python 另造一份数据。这样宽表与基础表
   永远勾稽一致，``sales_verify`` 里的「宽表行数 = 明细行数」这类检查才有意义。

建表阶段临时关掉 ``FOREIGN_KEY_CHECKS``：DDL 按业务域分组书写（可读性优先），
不是按外键依赖排序，关掉检查就能任意顺序建表，约束本身仍会被正常创建。
"""
import sys
import time

import sales_data_dim as dim
import sales_data_ops as ops
import sales_data_fact as fact
import sales_schema_master as schema_master
import sales_schema_txn as schema_txn
import sales_verify as verify
import sales_wide as wide
from sales_common import DB, connect, reset_random


def _phase(title):
    print("\n==== %s ====" % title)
    return time.time()


def _done(t0):
    print("  耗时 %.1f 秒" % (time.time() - t0))


def create_database(cur):
    t0 = _phase("重建数据库 %s" % DB)
    cur.execute("DROP DATABASE IF EXISTS `%s`" % DB)
    # 排序规则沿用原库，避免与既有数据源的元数据缓存出现校对规则不一致
    cur.execute("CREATE DATABASE `%s` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci" % DB)
    cur.execute("USE `%s`" % DB)
    _done(t0)


def create_base_tables(cur):
    t0 = _phase("建 50 张基础表")
    cur.execute("SET FOREIGN_KEY_CHECKS = 0")
    tables = schema_master.MASTER_TABLES + schema_txn.TXN_TABLES
    for name, domain, ddl in tables:
        cur.execute(ddl)
    cur.execute("SET FOREIGN_KEY_CHECKS = 1")
    print("  已建表 %d 张（主数据 %d + 交易 %d）"
          % (len(tables), len(schema_master.MASTER_TABLES), len(schema_txn.TXN_TABLES)))
    _done(t0)


def create_wide_tables(cur):
    t0 = _phase("建 10 张大宽表并由基础表派生数据")
    for name, domain, ddl in wide.WIDE_TABLES:
        cur.execute(ddl)
    print("  已建宽表 %d 张" % len(wide.WIDE_TABLES))
    # 客户画像宽表用 GROUP_CONCAT 拼标签，服务端默认 1024 字节会静默截断
    cur.execute("SET SESSION group_concat_max_len = 1000000")
    for name, sql in wide.WIDE_POPULATE.items():
        t1 = time.time()
        cur.execute(sql)
        cur.execute("SELECT COUNT(*) FROM `%s`" % name)
        print("  %-28s rows=%-9d %.1f 秒" % (name, cur.fetchone()[0], time.time() - t1))
    _done(t0)


def seed(conn, cur):
    reset_random()
    t0 = _phase("灌数：维度与主数据（22 张表）")
    pool = dim.build(cur)
    conn.commit()
    _done(t0)

    t0 = _phase("灌数：交易主线（客户 / 领券 / 订单 / 明细）")
    pool = fact.build(cur, pool)
    conn.commit()
    _done(t0)

    t0 = _phase("灌数：支付 / 退款 / 发票 / 状态流转 / 积分 / 券核销")
    fact.build_payment_chain(cur, pool)
    conn.commit()
    _done(t0)

    t0 = _phase("灌数：购物车")
    fact.build_cart(cur, pool)
    conn.commit()
    _done(t0)

    t0 = _phase("灌数：履约 / 售后 / 库存 / 采购 / 投放 / 流量（14 张表）")
    ops.build(cur, pool)
    conn.commit()
    _done(t0)


def main():
    only_verify = "--verify-only" in sys.argv
    t_all = time.time()
    conn = connect()
    cur = conn.cursor()
    try:
        if only_verify:
            cur.execute("USE `%s`" % DB)
        else:
            create_database(cur)
            create_base_tables(cur)
            conn.commit()
            seed(conn, cur)
            create_wide_tables(cur)
            conn.commit()
        fails = verify.run(cur)
        conn.commit()
        print("\n==== 总结 ====")
        print("  校验失败项：%d" % len(fails))
        for desc, n in fails:
            print("    - %s（%s）" % (desc, n))
        print("  全流程耗时 %.1f 秒" % (time.time() - t_all))
        return 1 if fails else 0
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
