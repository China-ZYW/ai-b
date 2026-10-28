# -*- coding: utf-8 -*-
"""电商销售库造数：履约 / 售后 / 库存 / 采购 / 投放 / 流量（14 张表）。

这些表都在交易主线之下游，因此有两条硬约束：

1. **父行必须真实存在，且状态自洽**。发货单只从「已发货及之后」的订单里取父行；评价只给已签收订单的明细；
   库存「销售出库」流水的 ``order_id`` 必须是真实已支付订单。否则就是一堆孤儿行，
   JOIN 出来全是 NULL，比没有关联更糟。

2. **一单最多一条发货单、一条明细最多一条评价**——这是 ``dwd_order_item_wide`` 用 14 表 JOIN
   还不 fan-out 的前提。若这里放开了 1:N，宽表的销售额会凭空翻倍，成为静默错数。

回填约定：``order_item.is_reviewed`` / ``is_refunded`` 在这里规划完后统一 UPDATE 回写，
使「明细标记」与「评价表 / 售后明细表是否存在对应行」严格等价。
"""
import calendar
import random
from datetime import date, datetime, timedelta

from sales_common import (AD_POSITIONS, AFTER_SALE_REASONS, AFTER_SALE_REASONS_W,
                          AFTER_SALE_TYPES, AFTER_SALE_TYPES_W, BUYERS, CARRIER_WEIGHTS,
                          CHANNEL_WEIGHTS, DEFECT_TYPES, DEFECT_TYPES_W, DEVICE_TYPES,
                          DEVICE_WEIGHTS, EVENT_TYPES, EVENT_WEIGHTS, OPERATORS, PAGE_TYPES,
                          PAGE_TYPES_W, SHIPPED_STATUS, SIGNED_STATUS, STOCK_STATUS, TODAY,
                          TRACE_NODES, TRAFFIC_SOURCES, TRAFFIC_SOURCE_WEIGHTS, TRANS_TYPES,
                          insert, month_of, months_back, pick, rnd_date, rnd_dt, weighted)

N_PRICE_HISTORY = 10000
N_PURCHASE = 3000
N_PROMO_PRODUCT = 800
N_AD = 10000
N_SNAPSHOT = 10080          # 24 个月末 × 420 个 SKU-仓组合
SNAP_PER_DATE = 420
N_TRANS = 10000
N_AFTER_SALE = 2500
N_REVIEW = 6000
N_PAGE_VISIT = 10000
N_USER_EVENT = 10000

MONTHS_24 = months_back(24)
PRICE_REASONS = ["促销活动", "成本上涨", "竞品对标", "清仓降价", "新品定价"]
PURCHASE_STATUS = ["待审核", "已下单", "部分到货", "已完成", "已取消"]
PURCHASE_STATUS_W = [6, 12, 12, 66, 4]
HUBS = ["广州分拨中心", "上海分拨中心", "北京分拨中心", "成都分拨中心",
        "武汉分拨中心", "杭州分拨中心", "西安分拨中心", "沈阳分拨中心"]
SKU_EVENTS = {"浏览", "收藏", "加购", "下单", "支付", "评价"}
# 库存流水的类型配比：键必须取自 TRANS_TYPES，否则写错字就会造出注释里没有的枚举值
TRANS_MIX = {"销售出库": 5000, "采购入库": 2200, "退货入库": 900, "调拨出库": 700,
             "调拨入库": 700, "盘盈": 300, "盘损": 200}
# 配比合计必须等于 N_TRANS：循环以配比驱动，少配了不会报错，只会静默少造几百行流水
assert sum(TRANS_MIX.values()) == N_TRANS, "TRANS_MIX 合计 %d != N_TRANS %d" % (
    sum(TRANS_MIX.values()), N_TRANS)
POSITIVE_REVIEWS = [
    "质量很好，包装严实，物流也快，下次还会回购。",
    "和详情页描述一致，用着顺手，性价比不错。",
    "客服态度好，发货速度快，整体很满意。",
    "第二次购买了，品质一如既往地稳定。",
    "做工精细，细节到位，值得推荐给朋友。",
]
NEUTRAL_REVIEWS = [
    "还行，中规中矩，没有惊喜也没什么问题。",
    "东西一般，价格稍微贵了一点。",
    "包装比较简单，功能正常。",
]
NEGATIVE_REVIEWS = [
    "收到的时候外包装破损，商品上有划痕。",
    "尺寸和详情页写的有出入，偏小了一号。",
    "用了两天就出问题，售后处理也比较慢。",
    "颜色和图片差别较大，有点失望。",
    "物流太慢，等了快一个星期才到货。",
]
REPLIES = [
    "非常抱歉给您带来不好的体验，已为您安排补发并赠送优惠券。",
    "感谢反馈，问题已同步给品控与仓储团队核查改进。",
    "抱歉让您久等，已加急处理，期待再次为您服务。",
]


def build(cur, pool):
    """生成 14 张下游表，并回填 order_item 的评价/售后标记。"""
    _price_history(cur, pool)
    _purchase(cur, pool)
    _promotion_product(cur, pool)
    _ad_placement(cur, pool)
    pool["shipment"] = _shipment(cur, pool)
    _after_sale(cur, pool)
    _product_review(cur, pool)
    _sync_order_item_flags(cur, pool)
    _inventory_snapshot(cur, pool)
    _inventory_transaction(cur, pool)
    _page_visit(cur, pool)
    _user_event(cur, pool)
    return pool


# ==================== 商品价格变动历史 ====================
def _price_history(cur, pool):
    """调价流水：每个 SKU 1~4 次调价，日期递增，**最后一次的新价恰好等于 SKU 现价**。

    若末次价格与 ``product_sku.sale_price`` 对不上，「价格走势」与「当前售价」两条路径就会互相打脸。
    """
    skus = pool["product_sku"]
    rows, hid = [], 0
    for s in skus:
        if hid >= N_PRICE_HISTORY:
            break
        n = random.randint(1, 4)
        # 把 n 次调价均匀铺在 [起始日, TODAY] 内，保证日期递增且末次不越过数据截止日
        start = rnd_date(date(2024, 9, 1), TODAY - timedelta(days=60 * n))
        step = max(1, (TODAY - start).days // n)
        price = round(s["sale_price"] * random.uniform(1.06, 1.42), 2)
        for k in range(n):
            hid += 1
            d = min(TODAY, start + timedelta(days=step * k + random.randint(0, step // 3 + 1)))
            new = s["sale_price"] if k == n - 1 else round(price * random.uniform(0.84, 0.99), 2)
            old = price
            rows.append((hid, s["id"], d, old, new, round((new - old) / old, 4),
                         pick(PRICE_REASONS), pick(OPERATORS[:7])))
            price = new
    insert(cur, "product_price_history",
           ["id", "product_sku_id", "effective_date", "old_price", "new_price", "change_rate",
            "change_reason", "operator"], rows)
    print("    product_price_history rows=%d" % len(rows))


# ==================== 采购 ====================
def _purchase(cur, pool):
    """采购单 3000 张 + 明细约 9000 行；表头金额/数量严格等于明细之和。

    只向「该商品的主供应商」下采购单，保证 供应商→商品→采购明细 这条链在业务上讲得通。
    """
    products_by_supplier = {}
    for p in pool["product"]:
        products_by_supplier.setdefault(p["supplier_id"], []).append(p)
    skus_by_product = {}
    for s in pool["product_sku"]:
        skus_by_product.setdefault(s["product_id"], []).append(s)
    suppliers = [s for s in pool["supplier"] if s["id"] in products_by_supplier]
    warehouses = pool["warehouse"]

    heads, items = [], []
    hid = iid = 0
    for _ in range(N_PURCHASE):
        sup = pick(suppliers)
        prods = products_by_supplier[sup["id"]]
        hid += 1
        wh = pick(warehouses)
        d = rnd_date(date(2024, 9, 1), TODAY - timedelta(days=3))
        expect = d + timedelta(days=random.randint(3, 30))
        status = weighted(PURCHASE_STATUS, PURCHASE_STATUS_W)
        if status in ("已完成", "部分到货"):
            actual = expect + timedelta(days=random.randint(-4, 9))
            actual = min(actual, TODAY)
        else:
            actual = None
        amount = qty_total = 0
        for p in random.sample(prods, min(len(prods), random.randint(1, 5))):
            iid += 1
            sku = pick(skus_by_product[p["id"]])
            q = random.randint(20, 800)
            if status == "已完成":
                received = q
            elif status == "部分到货":
                received = max(1, int(q * random.uniform(0.3, 0.8)))
            else:
                received = 0
            rejected = random.randint(0, max(1, int(received * 0.04))) if received else 0
            up = round(sku["cost_price"] * random.uniform(0.92, 1.06), 2)
            amt = round(up * q, 2)
            amount += amt
            qty_total += q
            items.append((iid, hid, sku["id"], q, up, amt, received, rejected))
        heads.append((hid, "PO%s%06d" % (d.strftime("%Y%m"), hid), sup["id"], wh["id"], d,
                      month_of(d), expect, actual, round(amount, 2), qty_total, status,
                      pick(BUYERS), 1 if (actual and actual <= expect) else 0))
    insert(cur, "purchase_order",
           ["id", "purchase_order_no", "supplier_id", "warehouse_id", "order_date", "order_month",
            "expect_arrive_date", "actual_arrive_date", "total_amount", "total_qty", "status",
            "buyer", "is_on_time"], heads)
    insert(cur, "purchase_order_item",
           ["id", "purchase_order_id", "product_sku_id", "quantity", "unit_price", "amount",
            "received_qty", "rejected_qty"], items)
    print("    purchase_order        rows=%d" % len(heads))
    print("    purchase_order_item   rows=%d" % len(items))
    # 采购明细也挂进 pool：库存「采购入库」流水要按真实明细取 SKU 与仓库，不能另抽
    pool["purchase_order"] = {h[0]: h for h in heads}
    pool["purchase_order_item"] = items


# ==================== 促销商品 / 广告投放 ====================
def _promotion_product(cur, pool):
    """促销规则与商品的多对多桥表：同一 (规则, 商品) 不重复，促销价必低于商品标价。"""
    rules = pool["promotion_rule"]
    products = pool["product"]
    rows, pid, seen = [], 0, set()
    guard = 0
    while len(rows) < N_PROMO_PRODUCT and guard < N_PROMO_PRODUCT * 20:
        guard += 1
        r, p = pick(rules), pick(products)
        if (r["id"], p["id"]) in seen:
            continue
        seen.add((r["id"], p["id"]))
        pid += 1
        rows.append((pid, r["id"], p["id"],
                     round(p["list_price"] * random.uniform(0.55, 0.92), 2),
                     random.randint(50, 5000)))
    insert(cur, "promotion_product",
           ["id", "promotion_rule_id", "product_id", "promo_price", "stock_limit"], rows)
    print("    promotion_product     rows=%d" % len(rows))


def _ad_placement(cur, pool):
    """广告投放 1 万行：投放日必落在活动窗口内；ctr 与 roi 由曝光/点击/花费精确算出，不另掷随机数。"""
    campaigns = [c for c in pool["campaign"] if c["status"] != "未开始"]
    platforms = pool["platform"]
    rows, aid, guard = [], 0, 0
    while len(rows) < N_AD and guard < N_AD * 15:
        guard += 1
        cp = pick(campaigns)
        lo, hi = max(cp["start"], date(2024, 9, 1)), min(cp["end"], TODAY)
        if lo > hi:
            continue
        d = rnd_date(lo, hi)
        spend = round(random.uniform(300, 60000), 2)
        impressions = max(1000, int(spend / random.uniform(0.004, 0.06)))
        clicks = max(1, int(impressions * random.uniform(0.004, 0.09)))
        conversions = int(clicks * random.uniform(0.008, 0.12))
        conv_amt = round(conversions * random.uniform(90, 620), 2)
        aid += 1
        rows.append((aid, cp["id"], pick(platforms)["id"], d, month_of(d), pick(AD_POSITIONS),
                     spend, impressions, clicks, round(clicks / impressions, 4),
                     conversions, conv_amt, round(conv_amt / spend, 2)))
    insert(cur, "ad_placement",
           ["id", "campaign_id", "platform_id", "placement_date", "placement_month", "ad_position",
            "spend", "impressions", "clicks", "ctr", "conversions", "conversion_amount", "roi"],
           rows)
    print("    ad_placement          rows=%d" % len(rows))


# ==================== 发货与物流轨迹 ====================
def _distance(wh_province, cu_province):
    """同省按省内配送估距，跨省按干线估距——只为让「配送距离」这一列有业务形态。"""
    return random.randint(15, 260) if wh_province == cu_province else random.randint(300, 3200)


def _shipment(cur, pool):
    """发货单：一张订单最多一条（宽表 JOIN 不 fan-out 的前提）。

    运费直接取 ``orders.freight_amount``，重量由明细数量 × 商品单件重量累加，
    签收与否严格由订单状态决定：只有「已完成 / 退款中」才算签收。
    """
    carriers = pool["carrier"]
    targets = [o for o in pool["orders"] if o["status"] in SHIPPED_STATUS]
    rows, traces = [], []
    sid = tid = 0
    ship_of_order = {}
    for o in targets:
        sid += 1
        ca = weighted(carriers, CARRIER_WEIGHTS)
        ship_d = min(TODAY, o["date"] + timedelta(days=random.randint(0, 2)))
        signed = o["status"] in SIGNED_STATUS and ship_d < TODAY
        hours = round(ca["hours"] * random.uniform(0.55, 1.85), 1)
        if signed:
            sign_d = min(TODAY, ship_d + timedelta(days=max(1, int(hours // 24) + 1)))
            status = "已签收"
        else:
            sign_d = None
            hours = round(min(hours, max(6.0, (TODAY - ship_d).days * 24.0)), 1)
            status = weighted(["运输中", "派送中", "异常"], [52, 40, 8])
        weight = round(sum(it["qty"] * it["sku"]["product"]["weight_kg"] for it in o["items"]), 2)
        cu = o["customer"]
        rows.append((sid, "SF%s%07d" % (o["month"].replace("-", ""), sid), o["id"],
                     o["warehouse"]["id"], ca["code"], ship_d, sign_d,
                     cu["province"], cu["city"],
                     _distance(o["warehouse"]["province"], cu["province"]),
                     max(0.10, weight), o["freight"], hours, status,
                     1 if (signed and hours <= ca["hours"] * 1.2) else 0))
        ship_of_order[o["id"]] = {"ship_date": ship_d, "sign_date": sign_d,
                                  "carrier": ca["name"], "city": cu["city"]}

        # 轨迹节点：签收走完 6 个节点，未签收只走到当前环节
        reached = len(TRACE_NODES) if signed else {"派送中": 5, "运输中": 3, "异常": 3}[status]
        t = datetime(ship_d.year, ship_d.month, ship_d.day,
                     random.randint(8, 20), random.randint(0, 59))
        for node in TRACE_NODES[:reached]:
            tid += 1
            if node == "已揽收":
                loc = "%s%s仓" % (o["warehouse"]["city"], o["warehouse"]["name"][:4])
            elif node == "已签收":
                loc = "%s%s" % (cu["province"], cu["city"])
            elif "分拨" in node:
                loc = pick(HUBS)
            else:
                loc = "%s派送网点" % cu["city"]
            traces.append((tid, sid, t, node, loc,
                           "SYSTEM" if node in ("已揽收", "已发出") else pick(OPERATORS[:7])))
            t = t + timedelta(hours=random.randint(3, 22), minutes=random.randint(0, 59))
    insert(cur, "shipment",
           ["id", "shipment_no", "order_id", "warehouse_id", "carrier_code", "ship_date",
            "sign_date", "receiver_province", "receiver_city", "distance_km", "weight_kg",
            "freight_amount", "delivery_hours", "shipment_status", "is_on_time"], rows)
    insert(cur, "shipment_trace",
           ["id", "shipment_id", "trace_time", "trace_node", "location", "operator"], traces)
    print("    shipment              rows=%d" % len(rows))
    print("    shipment_trace        rows=%d" % len(traces))
    return ship_of_order


# ==================== 售后 ====================
def _after_sale(cur, pool):
    """售后单 2500 张 + 售后明细；一条明细最多进一张售后单，并回写 ``order_item.is_refunded``。

    父行只从已发货及之后的订单里取；申请时间必晚于下单时间且不晚于数据截止日。
    """
    orders = [o for o in pool["orders"] if o["status"] in SHIPPED_STATUS]
    heads, items = [], []
    hid = iid = 0
    used = set()
    guard = 0
    while len(heads) < N_AFTER_SALE and guard < N_AFTER_SALE * 15:
        guard += 1
        o = pick(orders)
        free = [it for it in o["items"] if it["id"] not in used]
        if not free:
            continue
        hid += 1
        chosen = random.sample(free, min(len(free), random.randint(1, 2)))
        for it in chosen:
            used.add(it["id"])
        atype = weighted(AFTER_SALE_TYPES, AFTER_SALE_TYPES_W)
        # 申请时间至少晚于下单当天，且不晚于数据截止日
        apply_t = max(rnd_dt(min(TODAY, o["date"] + timedelta(days=random.randint(1, 25))), 8, 22),
                      o["created_at"] + timedelta(hours=1))
        if apply_t.date() > TODAY:
            apply_t = datetime(TODAY.year, TODAY.month, TODAY.day, 23, 0, 0)
        handle = round(random.uniform(1.0, 168.0), 1)
        status = weighted(["已完成", "处理中", "待审核", "已拒绝"], [62, 18, 10, 10])
        if status == "已完成":
            finish = min(datetime(TODAY.year, TODAY.month, TODAY.day, 23, 0, 0),
                         apply_t + timedelta(hours=handle))
            handle = round(max(0.5, (finish - apply_t).total_seconds() / 3600.0), 1)
            satis = round(random.uniform(2.4, 5.0), 2)
        else:
            finish, satis = None, None
        refund = round(sum(it["amount"] for it in chosen), 2) if atype in ("仅退款", "退货退款") else 0.0
        heads.append((hid, "AS%s%07d" % (o["month"].replace("-", ""), hid), o["id"],
                      o["customer"]["id"], atype,
                      weighted(AFTER_SALE_REASONS, AFTER_SALE_REASONS_W),
                      apply_t, finish, refund, handle, status, satis))
        for it in chosen:
            iid += 1
            q = it["qty"] if atype != "维修" else max(1, it["qty"] // 2)
            items.append((iid, hid, it["id"], it["sku"]["id"], q,
                          round(it["amount"] * q / it["qty"], 2),
                          weighted(DEFECT_TYPES, DEFECT_TYPES_W)))
            it["refunded"] = 1
    insert(cur, "after_sale_order",
           ["id", "after_sale_no", "order_id", "customer_id", "after_sale_type", "reason",
            "apply_time", "finish_time", "refund_amount", "handle_hours", "after_sale_status",
            "satisfaction"], heads)
    insert(cur, "after_sale_item",
           ["id", "after_sale_order_id", "order_item_id", "product_sku_id", "quantity", "amount",
            "defect_type"], items)
    print("    after_sale_order      rows=%d" % len(heads))
    print("    after_sale_item       rows=%d" % len(items))


# ==================== 商品评价 ====================
def _product_review(cur, pool):
    """评价 6000 条：一条订单明细最多一条评价，且只给已签收订单的明细。

    评价日期不早于签收日期（无发货记录时退化为下单后 3~20 天）；
    ``is_positive`` 由 ``rating >= 4`` 直接判定，不另掷随机数。
    """
    ships = pool.get("shipment") or {}
    cand = []
    for o in pool["orders"]:
        if o["status"] not in SIGNED_STATUS:
            continue
        for it in o["items"]:
            if not it["refunded"]:
                cand.append((o, it))
    if not cand:
        print("    product_review        rows=0 (无可评价明细)")
        return
    picked = random.sample(cand, min(N_REVIEW, len(cand)))
    rows = []
    for rid, (o, it) in enumerate(picked, start=1):
        sh = ships.get(o["id"])
        base = sh["sign_date"] if (sh and sh["sign_date"]) else o["date"] + timedelta(days=random.randint(3, 20))
        d = min(TODAY, base + timedelta(days=random.randint(0, 12)))
        r = weighted([5, 4, 3, 2, 1], [58, 24, 10, 5, 3])
        rating = round(min(5.0, max(1.0, r + random.uniform(-0.4, 0.4))), 2)

        def sub_rating():
            """分项评分围绕总评上下浮动，不能另抽一个无关的数。"""
            return round(min(5.0, max(1.0, rating + random.uniform(-0.8, 0.8))), 2)

        if rating >= 4.5:
            content, has_img = pick(POSITIVE_REVIEWS), 1 if random.random() < 0.42 else 0
        elif rating >= 3.5:
            content, has_img = pick(NEUTRAL_REVIEWS), 1 if random.random() < 0.16 else 0
        else:
            content, has_img = pick(NEGATIVE_REVIEWS), 1 if random.random() < 0.55 else 0
        reply = pick(REPLIES) if rating < 4 and random.random() < 0.85 else None
        rows.append((rid, it["id"], it["sku"]["id"], o["customer"]["id"], d, month_of(d),
                     rating, sub_rating(), sub_rating(), sub_rating(), content, has_img,
                     1 if rating >= 4 else 0, reply))
        it["reviewed"] = 1
    insert(cur, "product_review",
           ["id", "order_item_id", "product_sku_id", "customer_id", "review_date", "review_month",
            "rating", "desc_rating", "logistics_rating", "service_rating", "content", "has_image",
            "is_positive", "reply_content"], rows)
    print("    product_review        rows=%d" % len(rows))


def _sync_order_item_flags(cur, pool):
    """把规划好的评价/售后标记回写 order_item，使明细标记与子表存在性严格等价。"""
    items = pool["order_item"]
    reviewed = [(it["id"],) for it in items if it["reviewed"]]
    refunded = [(it["id"],) for it in items if it["refunded"]]
    if reviewed:
        cur.executemany("UPDATE order_item SET is_reviewed = 1 WHERE id = %s", reviewed)
    if refunded:
        cur.executemany("UPDATE order_item SET is_refunded = 1 WHERE id = %s", refunded)
    print("    order_item flags      reviewed=%d refunded=%d" % (len(reviewed), len(refunded)))


# ==================== 库存 ====================
def _month_end(mo):
    y, m = int(mo[:4]), int(mo[5:7])
    return min(date(y, m, calendar.monthrange(y, m)[1]), TODAY)


def _inventory_snapshot(cur, pool):
    """库存快照：24 个月末 × 420 个 (SKU, 仓) 组合 = 10080 行。

    组合在每个月内去重、月末日期互不相同，故 (日期, SKU, 仓) 全局唯一，不会重复计数；
    ``available_qty = on_hand_qty - locked_qty``、``stock_value = on_hand_qty × SKU 成本价``
    都由已生成的字段算出，库存状态由在库量分档判定——四个口径互相自洽。
    """
    skus = pool["product_sku"]
    sku_by_id = {s["id"]: s for s in skus}
    warehouses = pool["warehouse"]
    rows, sid = [], 0
    for mo in MONTHS_24:
        d = _month_end(mo)
        combos = set()
        while len(combos) < SNAP_PER_DATE:
            combos.add((pick(skus)["id"], pick(warehouses)["id"]))
        for sku_id, wh_id in sorted(combos):
            sid += 1
            on_hand = weighted([0, random.randint(1, 40), random.randint(41, 400),
                                random.randint(401, 2000)], [8, 22, 45, 25])
            locked = int(on_hand * random.uniform(0, 0.35))
            sku = sku_by_id[sku_id]
            if on_hand == 0:
                status, turn = STOCK_STATUS[0], 0
            elif on_hand <= 40:
                status, turn = STOCK_STATUS[1], random.randint(3, 25)
            elif on_hand > 1200:
                status, turn = STOCK_STATUS[3], random.randint(120, 320)
            else:
                status, turn = STOCK_STATUS[2], random.randint(18, 95)
            rows.append((sid, d, mo, sku_id, wh_id, on_hand, on_hand - locked, locked,
                         random.randint(0, 900), round(on_hand * sku["cost_price"], 2),
                         turn, status))
    insert(cur, "inventory_snapshot",
           ["id", "snapshot_date", "snapshot_month", "product_sku_id", "warehouse_id",
            "on_hand_qty", "available_qty", "locked_qty", "in_transit_qty", "stock_value",
            "turnover_days", "stock_status"], rows)
    print("    inventory_snapshot    rows=%d" % len(rows))


def _inventory_transaction(cur, pool):
    """出入库流水 1 万行：销售出库挂真实订单、采购入库挂真实采购单、退货入库挂真实售后明细。

    ``after_qty = before_qty + quantity`` 恒成立，且出库前把 before_qty 抬到足够扣减，
    避免出现负库存这种一眼假的数。
    """
    skus = {s["id"]: s for s in pool["product_sku"]}
    paid_items = []
    refunded_items = []
    for o in pool["orders"]:
        for it in o["items"]:
            if o["is_paid"]:
                paid_items.append((o, it))
            if it["refunded"]:
                refunded_items.append((o, it))
    purchases = pool.get("purchase_order") or {}
    purchase_items = pool.get("purchase_order_item") or []
    warehouses = pool["warehouse"]
    all_skus = list(skus.values())
    rows, tid = [], 0
    for ttype in TRANS_TYPES:
        for _ in range(TRANS_MIX[ttype]):
            if len(rows) >= N_TRANS:
                break
            tid += 1
            order_id = purchase_id = None
            if ttype == "销售出库" and paid_items:
                o, it = pick(paid_items)
                sku, wh, qty = it["sku"], o["warehouse"], -it["qty"]
                order_id, d = o["id"], o["date"]
            elif ttype == "退货入库" and refunded_items:
                o, it = pick(refunded_items)
                sku, wh, qty = it["sku"], o["warehouse"], max(1, it["qty"] // 2)
                order_id, d = o["id"], min(TODAY, o["date"] + timedelta(days=random.randint(3, 30)))
            elif ttype == "采购入库" and purchase_items:
                # 按真实采购明细取 SKU，仓库与日期回到它的采购单头，不另抽
                pi = pick(purchase_items)
                po = purchases[pi[1]]
                purchase_id, sku = pi[1], skus[pi[2]]
                wh = next(w for w in warehouses if w["id"] == po[3])
                qty, d = max(1, pi[6]), po[4]
            else:
                sku, wh = pick(all_skus), pick(warehouses)
                qty = random.randint(1, 60) if ttype in ("调拨入库", "盘盈") else -random.randint(1, 60)
                d = rnd_date(date(2024, 9, 1), TODAY)
            before = random.randint(0, 1500)
            if before + qty < 0:
                before = -qty + random.randint(0, 200)
            rows.append((tid, "IT%s%08d" % (d.strftime("%Y%m%d"), tid), d, sku["id"], wh["id"],
                         order_id, purchase_id, ttype, qty, before, before + qty,
                         sku["cost_price"]))
    insert(cur, "inventory_transaction",
           ["id", "trans_no", "trans_date", "product_sku_id", "warehouse_id", "order_id",
            "purchase_order_id", "trans_type", "quantity", "before_qty", "after_qty",
            "unit_cost"], rows)
    print("    inventory_transaction rows=%d" % len(rows))


# ==================== 流量与行为 ====================
def _page_visit(cur, pool):
    """页面访问 1 万行：约 15% 为未登录访客（customer_id 为空），商品ID只在详情页出现。

    ``is_converted`` 只在结算页/支付成功页置 1——转化必须发生在漏斗末端；
    ``is_bounce`` 由 pv 与停留时长判定（单次访问且停留不足 15 秒）。
    """
    customers = pool["customer"]
    products = pool["product"]
    channels = pool["channel"]
    rows = []
    for vid in range(1, N_PAGE_VISIT + 1):
        c = pick(customers) if random.random() < 0.85 else None
        ptype = weighted(PAGE_TYPES, PAGE_TYPES_W)
        d = rnd_date(date(2024, 9, 1), TODAY)
        pv = 1 if random.random() < 0.42 else random.randint(2, 9)
        stay = random.randint(2, 600)
        if ptype == "支付成功页":
            converted = 1
        elif ptype == "结算页":
            converted = 1 if random.random() < 0.55 else 0
        else:
            converted = 0
        rows.append((vid, d, month_of(d), c["id"] if c else None,
                     pick(products)["id"] if ptype == "详情页" else None,
                     weighted(channels, CHANNEL_WEIGHTS)["id"],
                     ptype, weighted(DEVICE_TYPES, DEVICE_WEIGHTS),
                     weighted(TRAFFIC_SOURCES, TRAFFIC_SOURCE_WEIGHTS),
                     stay, pv, 1 if (pv == 1 and stay < 15) else 0, converted))
    insert(cur, "page_visit",
           ["id", "visit_date", "visit_month", "customer_id", "product_id", "channel_id",
            "page_type", "device_type", "traffic_source", "stay_seconds", "pv", "is_bounce",
            "is_converted"], rows)
    print("    page_visit            rows=%d" % len(rows))


def _user_event(cur, pool):
    """埋点事件 1 万行：按会话成组生成，同一会话的客户/设备一致、事件时间递增。

    ``session_id`` 故意不建 session 表——这是一条「推不出关联」的真实场景，
    用于验证 table_relation 登记表能否补上这类靠命名推断补不了的关系。
    """
    customers = pool["customer"]
    skus = pool["product_sku"]
    rows, eid, sid = [], 0, 0
    while len(rows) < N_USER_EVENT:
        sid += 1
        session = "SES%012d" % sid
        c = pick(customers) if random.random() < 0.85 else None
        dev = weighted(DEVICE_TYPES, DEVICE_WEIGHTS)
        t = rnd_dt(rnd_date(date(2024, 9, 1), TODAY), 7, 23)
        for _ in range(random.randint(1, 8)):
            if len(rows) >= N_USER_EVENT:
                break
            eid += 1
            et = weighted(EVENT_TYPES, EVENT_WEIGHTS)
            sku = pick(skus) if et in SKU_EVENTS else None
            rows.append((eid, t, t.date(), c["id"] if c else None, sku["id"] if sku else None,
                         et, pick(PAGE_TYPES), dev, session, random.randint(200, 180000)))
            t = t + timedelta(seconds=random.randint(5, 900))
    insert(cur, "user_event",
           ["id", "event_time", "event_date", "customer_id", "product_sku_id", "event_type",
            "event_source", "device_type", "session_id", "duration_ms"], rows)
    print("    user_event            rows=%d (sessions=%d)" % (len(rows), sid))
