# -*- coding: utf-8 -*-
"""灌数后的关联完整性与口径一致性校验。

两类检查，分工明确：

1. **孤儿行检查（由 schema 自动派生）**。对 ``sales_meta.infer_relations`` 推断出的每一条关系、
   DDL 里显式声明的每一对 FOREIGN KEY、以及每一处 ``parent_id`` 自引用，都跑一次 LEFT JOIN 反查，
   要求孤儿行数为 0。检查项从元数据生成，新增表或列不用改这里，也就不会漏检——
   这正是「造的数据都有真实关联关系」这句话唯一可被机器验证的形式。

2. **口径一致性检查（逐条手写）**。同一业务口径往往有多条取数路径：订单头金额 vs 明细汇总、
   客户表累计消费 vs 订单合计、客户积分 vs 积分流水加总、明细上的 ``is_reviewed`` 标记 vs
   评价表是否存在对应行。这些路径必须给出同一个数。若对不上，模型无论走哪条都「看起来对」，
   但两条结果互相打脸——这比没有关联更危险，因为它会静默地产出错误的业务结论。

所有检查的期望值都是 0（返回「不一致的行数」），因此可以统一判定。
"""
import sales_meta as meta

# ==================== 口径一致性检查（手写，期望值均为 0） ====================
CONSISTENCY_CHECKS = [
    ("订单头金额/件数/行数 = 明细汇总", """
        SELECT COUNT(*) FROM orders o
        JOIN (SELECT order_id, SUM(amount) s, SUM(quantity) q, COUNT(*) n
              FROM order_item GROUP BY order_id) i ON i.order_id = o.id
        WHERE ABS(o.amount - i.s) > 0.01 OR o.quantity <> i.q OR o.item_count <> i.n"""),

    ("订单头 product_id = 明细中金额最高的商品", """
        SELECT COUNT(*) FROM orders o
        JOIN (SELECT order_id, product_id FROM (
                SELECT oi.order_id, oi.product_id,
                       ROW_NUMBER() OVER (PARTITION BY oi.order_id ORDER BY oi.amount DESC, oi.id) rn
                FROM order_item oi) x WHERE rn = 1) t ON t.order_id = o.id
        WHERE t.product_id <> o.product_id"""),

    ("支付单与已支付订单严格 1:1", """
        SELECT (SELECT COUNT(*) FROM orders WHERE is_paid = 1)
             - (SELECT COUNT(*) FROM payment)
             + (SELECT COUNT(*) FROM payment p JOIN orders o ON o.id = p.order_id
                WHERE o.is_paid = 0)"""),

    ("支付时间不早于下单时间", """
        SELECT COUNT(*) FROM payment p JOIN orders o ON o.id = p.order_id
        WHERE p.pay_time < o.created_at"""),

    ("payment.is_refunded = 存在已退款的退款单", """
        SELECT COUNT(*) FROM payment p
        WHERE p.is_refunded <> (CASE WHEN EXISTS (
                SELECT 1 FROM refund r WHERE r.payment_id = p.id AND r.refund_status = '已退款')
              THEN 1 ELSE 0 END)"""),

    ("退款单完成时间与状态同源", """
        SELECT COUNT(*) FROM refund
        WHERE (refund_status = '已退款') <> (finish_time IS NOT NULL)"""),

    ("客户累计消费/订单数 = 已支付订单合计", """
        SELECT COUNT(*) FROM customer c
        LEFT JOIN (SELECT customer_id, SUM(pay_amount) s, COUNT(*) n FROM orders
                   WHERE is_paid = 1 GROUP BY customer_id) t ON t.customer_id = c.id
        WHERE ABS(c.total_spend - IFNULL(t.s, 0)) > 0.01 OR c.order_count <> IFNULL(t.n, 0)"""),

    ("客户积分 = 积分流水变动合计", """
        SELECT COUNT(*) FROM customer c
        LEFT JOIN (SELECT customer_id, SUM(change_points) s FROM member_point_log
                   GROUP BY customer_id) t ON t.customer_id = c.id
        WHERE c.total_points <> IFNULL(t.s, 0)"""),

    ("积分余额不为负", """
        SELECT COUNT(*) FROM member_point_log WHERE balance_points < 0"""),

    ("客户注册日期不晚于首单日期", """
        SELECT COUNT(*) FROM customer c
        JOIN (SELECT customer_id, MIN(order_date) d FROM orders GROUP BY customer_id) t
          ON t.customer_id = c.id
        WHERE c.register_date > t.d"""),

    ("领券日期不早于注册日期", """
        SELECT COUNT(*) FROM coupon_grant g JOIN customer c ON c.id = g.customer_id
        WHERE g.grant_date < c.register_date"""),

    ("券核销标记与核销记录严格等价", """
        SELECT (SELECT COUNT(*) FROM coupon_grant g WHERE g.is_used = 1 AND NOT EXISTS
                  (SELECT 1 FROM coupon_usage u WHERE u.coupon_grant_id = g.id))
             + (SELECT COUNT(*) FROM coupon_grant g WHERE g.is_used = 0 AND EXISTS
                  (SELECT 1 FROM coupon_usage u WHERE u.coupon_grant_id = g.id))
             + (SELECT COUNT(*) FROM orders o WHERE o.coupon_grant_id IS NOT NULL AND NOT EXISTS
                  (SELECT 1 FROM coupon_usage u WHERE u.order_id = o.id))"""),

    ("券核销优惠额与订单优惠口径一致", """
        SELECT COUNT(*) FROM coupon_usage u JOIN orders o ON o.id = u.order_id
        WHERE u.discount_amount > o.discount_amount + 0.01"""),

    ("明细 is_reviewed 与评价表存在性严格等价", """
        SELECT (SELECT COUNT(*) FROM order_item i WHERE i.is_reviewed = 1 AND NOT EXISTS
                  (SELECT 1 FROM product_review r WHERE r.order_item_id = i.id))
             + (SELECT COUNT(*) FROM order_item i WHERE i.is_reviewed = 0 AND EXISTS
                  (SELECT 1 FROM product_review r WHERE r.order_item_id = i.id))"""),

    ("明细 is_refunded 与售后明细存在性严格等价", """
        SELECT (SELECT COUNT(*) FROM order_item i WHERE i.is_refunded = 1 AND NOT EXISTS
                  (SELECT 1 FROM after_sale_item a WHERE a.order_item_id = i.id))
             + (SELECT COUNT(*) FROM order_item i WHERE i.is_refunded = 0 AND EXISTS
                  (SELECT 1 FROM after_sale_item a WHERE a.order_item_id = i.id))"""),

    ("一条明细最多一条评价、一张订单最多一条发货单（宽表不 fan-out 的前提）", """
        SELECT (SELECT COUNT(*) FROM (SELECT order_item_id FROM product_review
                  GROUP BY order_item_id HAVING COUNT(*) > 1) a)
             + (SELECT COUNT(*) FROM (SELECT order_id FROM shipment
                  GROUP BY order_id HAVING COUNT(*) > 1) b)
             + (SELECT COUNT(*) FROM (SELECT order_item_id FROM after_sale_item
                  GROUP BY order_item_id HAVING COUNT(*) > 1) c)"""),

    ("评价日期不早于下单日期", """
        SELECT COUNT(*) FROM product_review r JOIN order_item oi ON oi.id = r.order_item_id
        JOIN orders o ON o.id = oi.order_id WHERE r.review_date < o.order_date"""),

    ("发货日期不早于下单日期、签收日期不早于发货日期", """
        SELECT (SELECT COUNT(*) FROM shipment s JOIN orders o ON o.id = s.order_id
                WHERE s.ship_date < o.order_date)
             + (SELECT COUNT(*) FROM shipment WHERE sign_date IS NOT NULL AND sign_date < ship_date)"""),

    ("物流签收状态与签收日期同源", """
        SELECT COUNT(*) FROM shipment
        WHERE (shipment_status = '已签收') <> (sign_date IS NOT NULL)"""),

    ("发货运费 = 订单运费（两条取数路径同口径）", """
        SELECT COUNT(*) FROM shipment s JOIN orders o ON o.id = s.order_id
        WHERE ABS(s.freight_amount - o.freight_amount) > 0.01"""),

    ("售后单完成时间与状态同源", """
        SELECT COUNT(*) FROM after_sale_order
        WHERE (after_sale_status = '已完成') <> (finish_time IS NOT NULL)"""),

    ("售后申请时间不早于下单时间", """
        SELECT COUNT(*) FROM after_sale_order a JOIN orders o ON o.id = a.order_id
        WHERE a.apply_time < o.created_at"""),

    ("采购单头金额/数量 = 采购明细汇总", """
        SELECT COUNT(*) FROM purchase_order po
        JOIN (SELECT purchase_order_id, SUM(amount) s, SUM(quantity) q
              FROM purchase_order_item GROUP BY purchase_order_id) i
          ON i.purchase_order_id = po.id
        WHERE ABS(po.total_amount - i.s) > 0.01 OR po.total_qty <> i.q"""),

    ("采购到货量不超过采购量、不合格量不超过到货量", """
        SELECT COUNT(*) FROM purchase_order_item
        WHERE received_qty > quantity OR rejected_qty > received_qty"""),

    ("SKU 现价 = 最后一次调价的新价", """
        SELECT COUNT(*) FROM product_sku s
        JOIN (SELECT h.product_sku_id, h.new_price FROM product_price_history h
              JOIN (SELECT product_sku_id, MAX(id) mid FROM product_price_history
                    GROUP BY product_sku_id) m ON m.mid = h.id) last
          ON last.product_sku_id = s.id
        WHERE ABS(s.sale_price - last.new_price) > 0.01"""),

    ("调价流水按 SKU 严格日期递增（后一条不早于前一条）", """
        SELECT COUNT(*) FROM product_price_history h1
        JOIN product_price_history h2
          ON h2.product_sku_id = h1.product_sku_id AND h2.id > h1.id
        WHERE h2.effective_date < h1.effective_date"""),

    ("库存快照 可用量 = 在库量 - 锁定量，且不为负", """
        SELECT COUNT(*) FROM inventory_snapshot
        WHERE available_qty <> on_hand_qty - locked_qty OR available_qty < 0
           OR on_hand_qty < 0 OR in_transit_qty < 0"""),

    ("库存快照 (日期, SKU, 仓) 唯一", """
        SELECT COUNT(*) FROM (SELECT snapshot_date, product_sku_id, warehouse_id
              FROM inventory_snapshot GROUP BY snapshot_date, product_sku_id, warehouse_id
              HAVING COUNT(*) > 1) x"""),

    ("库存流水 变动后 = 变动前 + 变动量，且不为负", """
        SELECT COUNT(*) FROM inventory_transaction
        WHERE after_qty <> before_qty + quantity OR after_qty < 0 OR before_qty < 0"""),

    ("广告投放 ctr 与 roi 由曝光/点击/花费精确算出", """
        SELECT COUNT(*) FROM ad_placement
        WHERE ABS(ctr - clicks / impressions) > 0.0002
           OR ABS(roi - conversion_amount / spend) > 0.02
           OR clicks > impressions OR conversions > clicks"""),

    ("订单遗留列 channel 与规范化列 channel_id 同口径", """
        SELECT COUNT(*) FROM orders o JOIN channel c ON c.id = o.channel_id
        WHERE c.channel_name <> o.channel"""),

    ("订单遗留列 product / category 与 product_id 同口径", """
        SELECT COUNT(*) FROM orders o
        JOIN product p ON p.id = o.product_id
        JOIN category c3 ON c3.id = p.category_id
        JOIN category c1 ON c1.id = CAST(SUBSTRING_INDEX(c3.path, '/', 1) AS UNSIGNED)
        WHERE LEFT(p.product_name, 32) <> o.product OR LEFT(c1.category_name, 16) <> o.category"""),

    ("订单门店所属渠道 = 订单渠道", """
        SELECT COUNT(*) FROM orders o JOIN store s ON s.id = o.store_id
        WHERE s.channel_id <> o.channel_id"""),

    ("购物车明细金额汇总 = 购物车总金额", """
        SELECT COUNT(*) FROM shopping_cart c
        JOIN (SELECT shopping_cart_id, SUM(quantity * unit_price) s, COUNT(*) n
              FROM cart_item GROUP BY shopping_cart_id) i ON i.shopping_cart_id = c.id
        WHERE ABS(c.total_amount - i.s) > 0.01 OR c.item_kind_count <> i.n"""),

    ("明细宽表行数 = 订单明细行数（14 表 JOIN 未 fan-out）", """
        SELECT (SELECT COUNT(*) FROM dwd_order_item_wide)
             - (SELECT COUNT(*) FROM order_item)"""),

    ("明细宽表销售额 = 订单明细销售额", """
        SELECT CASE WHEN ABS((SELECT SUM(amount) FROM dwd_order_item_wide)
                           - (SELECT SUM(amount) FROM order_item)) > 0.01 THEN 1 ELSE 0 END"""),

    ("明细宽表 is_reviewed / is_refunded 与基础表一致", """
        SELECT COUNT(*) FROM dwd_order_item_wide w JOIN order_item i ON i.id = w.order_item_id
        WHERE w.is_reviewed <> i.is_reviewed OR w.is_refunded <> i.is_refunded"""),
]


def orphan_sql(table, col, ref, refcol):
    return ("SELECT COUNT(*) FROM `%s` x LEFT JOIN `%s` y ON y.`%s` = x.`%s` "
            "WHERE x.`%s` IS NOT NULL AND y.`%s` IS NULL" % (table, ref, refcol, col, col, refcol))


def self_ref_sql(table, col="parent_id"):
    return ("SELECT COUNT(*) FROM `%s` x LEFT JOIN `%s` y ON y.`id` = x.`%s` "
            "WHERE x.`%s` <> 0 AND y.`id` IS NULL" % (table, table, col, col))


def build_orphan_checks(base, wide):
    """由 schema 自动派生孤儿行检查：真外键 + 命名推断关系 + 自引用 parent_id。"""
    checks = []
    seen = set()
    for t, c, r, rc in meta.real_fks(base, wide):
        key = (t, c)
        seen.add(key)
        checks.append(("真外键 %s.%s -> %s.%s" % (t, c, r, rc), orphan_sql(t, c, r, rc)))
    for t, c, r, rc, rule in meta.infer_relations(base, wide)[0]:
        if t not in base or (t, c) in seen:      # 只查基础表，且真外键已覆盖的不重复查
            continue
        seen.add((t, c))
        checks.append(("推断关联 %s.%s -> %s.%s [%s]" % (t, c, r, rc, rule),
                       orphan_sql(t, c, r, rc)))
    for name in sorted(base):
        if "parent_id" in base[name].columns:
            checks.append(("自引用 %s.parent_id（0 为根）" % name, self_ref_sql(name)))
    return checks


def row_counts(cur, base, wide):
    out = []
    for name in list(base) + list(wide):
        cur.execute("SELECT COUNT(*) FROM `%s`" % name)
        out.append((name, cur.fetchone()[0], "宽表" if name in wide else base[name].domain))
    return out


def run(cur, verbose=True):
    """跑全部校验，返回失败项列表（空列表代表全部通过）。"""
    base, wide, _ = meta.load_tables()
    fails = []

    orphans = build_orphan_checks(base, wide)
    if verbose:
        print("\n[1/3] 关联完整性（孤儿行）检查 %d 项" % len(orphans))
    for desc, sql in orphans:
        cur.execute(sql)
        n = cur.fetchone()[0]
        if n:
            fails.append((desc, n))
            if verbose:
                print("    FAIL %-58s 孤儿行=%d" % (desc, n))
    if verbose:
        print("    孤儿行检查完成：%s" % ("全部为 0" if not fails else "%d 项不为 0" % len(fails)))

    if verbose:
        print("\n[2/3] 口径一致性检查 %d 项" % len(CONSISTENCY_CHECKS))
    cfails = 0
    for desc, sql in CONSISTENCY_CHECKS:
        cur.execute(sql)
        n = cur.fetchone()[0]
        if n:
            cfails += 1
            fails.append((desc, n))
            if verbose:
                print("    FAIL %-58s 不一致=%s" % (desc, n))
    if verbose:
        print("    口径一致性检查完成：%s"
              % ("全部一致" if not cfails else "%d 项不一致" % cfails))

    if verbose:
        print("\n[3/3] 行数报告")
        counts = row_counts(cur, base, wide)
        total = 0
        for name, n, domain in counts:
            total += n
            print("    %-26s %10d  %s" % (name, n, domain))
        print("    %-26s %10d" % ("合计", total))
        print("    基础表 %d 张 / 宽表 %d 张" % (len(base), len(wide)))
    return fails
