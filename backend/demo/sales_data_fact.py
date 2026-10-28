# -*- coding: utf-8 -*-
"""电商销售库造数：交易主线（客户 / 领券 / 订单 / 明细 / 支付 / 退款 / 发票 / 购物车，8 张表）。

**先规划、后插入**：所有表都在 Python 里规划成完整的行列表，再统一入库。这样才能满足
``sales_common`` 模块头承诺的第三条——同一口径的多条取数路径给出相同的数：

- ``orders.amount`` = 其 ``order_item.amount`` 之和；``orders.quantity`` = 明细数量之和；
  ``orders.item_count`` = 明细行数。旧指标 ``SUM(amount) FROM orders`` 与
  ``SUM(amount) FROM order_item`` 因此必然相等，宽表与基础表也不会互相打脸。
- ``orders.product_id`` 取该订单金额最高的明细行对应的 SPU（与列注释「订单内金额最高的商品」一致）。
- ``customer.total_spend / order_count / last_order_date / is_active`` 不另行随机，
  统一由已规划的订单反算后 ``UPDATE`` 回写，杜绝「客户表说消费 5 万、订单表加起来只有 8 千」。
- 每个客户的订单日期严格递增，故第一条即首单，``is_first_order`` 可直接判定，无需事后回填。
"""
import random
from datetime import date, timedelta

from sales_common import (CHANGE_TYPES, CHANGE_TYPES_W, CHANNELS, CHANNEL_WEIGHTS, GIVEN_NAMES,
                          LEVEL_WEIGHTS, OPERATORS, ORDER_STATUS, ORDER_STATUS_WEIGHTS,
                          PAID_STATUS, PAY_TYPES, PAY_TYPE_WEIGHTS, PROVINCES, PROVINCE_MAP,
                          REFUND_REASONS, REFUND_REASONS_W, STAGE_OF_STATUS, SURNAMES, TODAY,
                          insert, month_of, pick, rnd_date, rnd_dt, weighted)

N_CUSTOMERS = 10000
N_ORDERS = 10000
GRANT_CHANNELS = ["活动页", "首页弹窗", "客服发放", "任务奖励"]
# 支付渠道（第三方通道）：与 pay_type（支付方式）是两个不同口径，不可混用
PAY_CHANNELS = ["支付宝", "微信支付", "银联在线", "京东支付", "抖音支付"]
# 积分减少类流水：余额不足时不能记这些类型，否则会出现负积分
POINT_NEG_TYPES = ("退款扣减", "兑换消耗", "过期清零")


def build(cur, pool):
    """生成交易主线 8 张表，并把 customer / orders / order_item 池子挂回 pool 供后续模块引用。"""
    skus_by_l1 = _index_skus_by_l1(pool["product_sku"])
    stores_by_channel = {}
    for st in pool["store"]:
        stores_by_channel.setdefault(st["channel_id"], []).append(st)
    reps_by_store = {}
    for rp in pool["sales_rep"]:
        reps_by_store.setdefault(rp["store_id"], []).append(rp)

    customers = _plan_customers(pool)
    grants = _plan_coupon_grants(customers, pool["coupon_template"])
    orders, items = _plan_orders(customers, grants, pool, skus_by_l1, stores_by_channel, reps_by_store)
    _backfill_customer_stats(customers, orders)

    _insert_customers(cur, customers)
    _insert_addresses(cur, customers)
    _insert_tag_rel(cur, customers, pool["customer_tag"])
    _insert_coupon_grants(cur, grants)
    _insert_orders(cur, orders)
    _insert_order_items(cur, items, orders)
    pool["customer"] = customers
    pool["orders"] = orders
    pool["order_item"] = items
    pool["coupon_grant"] = grants
    return pool


# ==================== 客户 ====================
def _plan_customers(pool):
    """1 万个客户：人口属性 + 会员等级 + 区域。消费类字段先置 0，订单规划完再回写。"""
    cities = pool["region"]["cities"]
    levels = pool["customer_level"]
    out = []
    for i in range(1, N_CUSTOMERS + 1):
        r = pick(cities)
        lvl = weighted(levels, LEVEL_WEIGHTS)
        gender = pick(["男", "女"])
        age = min(72, max(18, int(random.gauss(34, 11))))
        name = pick(SURNAMES) + pick(GIVEN_NAMES)
        out.append({
            "id": i, "no": "CU%08d" % (10000000 + i), "name": name, "gender": gender, "age": age,
            "birth": date(2026 - age, random.randint(1, 12), random.randint(1, 28)),
            "phone": "1%d%08d" % (pick([3, 5, 7, 8, 9]), random.randint(0, 99999999)),
            "email": "user%08d@demo.local" % i,
            "level": lvl, "region": r, "province": r["province"], "city": r["city"],
            "big_region": r["big_region"], "city_tier": r["city_tier"],
            "register_channel": weighted([c[1] for c in CHANNELS], CHANNEL_WEIGHTS),
            # 注册日期在此就定下来：领券日期要以它为下界，不能等到订单规划完再重掷
            "register_date": rnd_date(date(2018, 1, 1), TODAY - timedelta(days=30)),
            "first_order": None, "points": 0, "spend": 0.0, "orders": 0,
            "last_order": None, "active": 0,
        })
    return out


def _backfill_customer_stats(customers, orders):
    """由已规划的订单反算客户累计消费/订单数/最近下单/活跃标记。

    注册日期只做「必要时往前拉」，不整体重掷——重掷会让已按旧注册日生成的领券日期早于注册日期。
    """
    for o in orders:
        c = o["customer"]
        d = o["date"]
        if c["first_order"] is None or d < c["first_order"]:
            c["first_order"] = d
        if not o["is_paid"]:
            continue
        c["spend"] += o["pay_amount"]
        c["orders"] += 1
        c["points"] += int(o["pay_amount"] * c["level"]["ratio"])
        if c["last_order"] is None or d > c["last_order"]:
            c["last_order"] = d
        c["active"] = 1 if (TODAY - d).days <= 90 else c["active"]
    for c in customers:
        c["spend"] = round(c["spend"], 2)
        # 注册日期必须早于首单（且留出至少 30 天会员期），否则「会员天数」会出现负数这类脏数据
        if c["first_order"] and c["register_date"] > c["first_order"] - timedelta(days=30):
            c["register_date"] = max(date(2016, 1, 1),
                                     c["first_order"] - timedelta(days=random.randint(30, 600)))


def _insert_customers(cur, customers):
    rows = [(c["id"], c["no"], c["name"], c["gender"], c["age"], c["birth"], c["phone"], c["email"],
             c["level"]["code"], c["region"]["id"], c["province"], c["city"], c["register_date"],
             c["register_channel"], c["points"], c["spend"], c["orders"], c["last_order"],
             c["active"]) for c in customers]
    insert(cur, "customer",
           ["id", "customer_no", "customer_name", "gender", "age", "birth_date", "phone", "email",
            "level_code", "region_id", "province", "city", "register_date", "register_channel",
            "total_points", "total_spend", "order_count", "last_order_date", "is_active"], rows)
    print("    customer              rows=%d" % len(rows))


def _insert_addresses(cur, customers):
    """收货地址：每客户 1~3 个，默认地址恰好一个；省市以客户常住地为主体，少量异地收货。"""
    rows, aid = [], 0
    for c in customers:
        for k in range(random.randint(1, 3)):
            aid += 1
            if k == 0 or random.random() < 0.7:
                prov, city = c["province"], c["city"]
            else:
                prov = pick(PROVINCES)
                city = pick(PROVINCE_MAP[prov][1])
            rows.append((aid, c["id"], c["name"] if random.random() < 0.8 else pick(SURNAMES) + pick(GIVEN_NAMES),
                         "1%d%08d" % (pick([3, 5, 7, 8, 9]), random.randint(0, 99999999)),
                         prov, city, pick(["朝阳区", "海淀区", "天河区", "鼓楼区", "武侯区", "雁塔区", "历下区"]),
                         "%s路%d号%d栋%d单元%d01室" % (pick(["中山", "人民", "解放", "建设", "文化", "科技"]),
                                                       random.randint(1, 999), random.randint(1, 30),
                                                       random.randint(1, 4), random.randint(1, 20)),
                         1 if k == 0 else 0))
    insert(cur, "customer_address",
           ["id", "customer_id", "receiver_name", "receiver_phone", "province", "city", "district",
            "detail_address", "is_default"], rows)
    print("    customer_address      rows=%d" % len(rows))


def _insert_tag_rel(cur, customers, tags):
    """客户标签关联（多对多）：每客户 0~4 个标签，同一标签不重复挂。"""
    rows, rid = [], 0
    for c in customers:
        for t in random.sample(tags, random.randint(0, 4)):
            rid += 1
            rows.append((rid, c["id"], t["id"],
                         rnd_dt(rnd_date(c["register_date"], TODAY)),
                         round(random.uniform(0.62, 0.99), 3)))
    insert(cur, "customer_tag_rel",
           ["id", "customer_id", "customer_tag_id", "tagged_at", "confidence"], rows)
    print("    customer_tag_rel      rows=%d" % len(rows))


# ==================== 优惠券领取 ====================
def _plan_coupon_grants(customers, templates):
    """领券记录 1 万条：先全部标记未使用，下单规划时再从未使用池里领用。"""
    out = []
    for i in range(1, 10001):
        c = pick(customers)
        t = pick(templates)
        gd = rnd_date(max(c["register_date"], date(2024, 9, 1)), TODAY)
        out.append({"id": i, "customer": c, "template": t, "grant_date": gd,
                    "expire_date": gd + timedelta(days=t["valid_days"]),
                    "channel": pick(GRANT_CHANNELS),
                    "is_used": 0, "used_date": None, "order_id": None})
    return out


def _insert_coupon_grants(cur, grants):
    rows = [(g["id"], g["customer"]["id"], g["template"]["id"], g["grant_date"], g["expire_date"],
             g["channel"], g["is_used"], g["used_date"]) for g in grants]
    insert(cur, "coupon_grant",
           ["id", "customer_id", "coupon_template_id", "grant_date", "expire_date", "grant_channel",
            "is_used", "used_date"], rows)
    print("    coupon_grant          rows=%d (used=%d)"
          % (len(rows), sum(1 for g in grants if g["is_used"])))


# ==================== 订单与明细 ====================
def _index_skus_by_l1(skus):
    """按一级品类分桶 SKU，并预置抽样权重：让品类销量结构符合 L1_SALES_WEIGHT。"""
    by_l1 = {}
    for s in skus:
        by_l1.setdefault(s["product"]["l1_name"], []).append(s)
    return by_l1


# 一级品类被选中的权重：食品饮料与服装走量、电子产品低频高客单
L1_WEIGHTS = {"电子产品": 18, "家居用品": 15, "服装鞋帽": 22,
              "食品饮料": 20, "美妆个护": 15, "母婴玩具": 10}
# 每单商品行数分布：多数订单只买 1 种，少数囤货
ITEM_COUNT_CHOICES = [1, 2, 3, 4, 5, 6]
ITEM_COUNT_WEIGHTS = [42, 24, 16, 9, 6, 3]
QTY_CHOICES = [1, 2, 3, 4, 5, 8, 12]
QTY_WEIGHTS = [46, 22, 12, 8, 5, 4, 3]


def _plan_orders(customers, grants, pool, skus_by_l1, stores_by_channel, reps_by_store):
    """规划 1 万张订单及其明细。

    客户订单数服从帕累托分布（多数客户只买 1 次、少数重度复购），且同一客户的订单日期严格递增，
    这样「第一条即首单」天然成立，``is_first_order`` 无需事后回填。
    """
    channels = pool["channel"]
    campaigns = pool["campaign"]
    warehouses = pool["warehouse"]
    wh_by_big = {}
    for w in warehouses:
        wh_by_big.setdefault(w["big_region"], []).append(w)

    # 每客户订单数：帕累托抽样后归一到 N_ORDERS，并夹在 [0, 40] 防止极端长尾
    raw = [random.paretovariate(1.55) for _ in customers]
    tot = sum(raw)
    counts = [min(40, max(0, int(round(N_ORDERS * x / tot)))) for x in raw]
    diff = N_ORDERS - sum(counts)
    while diff > 0:
        counts[random.randrange(len(counts))] += 1
        diff -= 1
    while diff < 0:
        i = random.randrange(len(counts))
        if counts[i] > 0:
            counts[i] -= 1
            diff += 1

    # 未使用的券池：按客户分组，下单时只领用「本客户的、未过期的、门槛达标的」券
    grants_by_customer = {}
    for g in grants:
        if not g["is_used"]:
            grants_by_customer.setdefault(g["customer"]["id"], []).append(g)

    orders, items = [], []
    oid, iid = 0, 0
    l1_names = list(skus_by_l1.keys())
    l1_weights = [L1_WEIGHTS[n] for n in l1_names]
    # 下单日期窗口：一次性抽 k 个互不相同的日期再排序，既保证「同客户日期严格递增」，
    # 也不会像「逐单累加随机步长」那样在窗口尽头被截断——截断会让实际订单数远少于 N_ORDERS。
    win_start = date(2024, 9, 1)
    win_days = (TODAY - timedelta(days=5) - win_start).days
    for c, k in zip(customers, counts):
        if k == 0:
            continue
        for off in sorted(random.sample(range(win_days + 1), min(k, win_days + 1))):
            d = win_start + timedelta(days=off)
            oid += 1
            mo = month_of(d)
            ch = weighted(channels, CHANNEL_WEIGHTS)
            store = pick(stores_by_channel.get(ch["id"]) or pool["store"])
            wh = pick(wh_by_big.get(c["big_region"]) or warehouses)
            status = weighted(ORDER_STATUS, ORDER_STATUS_WEIGHTS)
            # 归因活动：仅当订单日期落在活动窗口内且渠道一致，否则视为自然流量（不强凑关联）
            cp = next((x for x in random.sample(campaigns, 12)
                       if x["channel_id"] == ch["id"] and x["start"] <= d <= x["end"]
                       and x["status"] != "已取消"), None)
            l1 = weighted(l1_names, l1_weights)
            n_item = weighted(ITEM_COUNT_CHOICES, ITEM_COUNT_WEIGHTS)
            chosen, order_items = [], []
            for _ in range(n_item):
                sku = pick(skus_by_l1[l1] if random.random() < 0.72
                           else skus_by_l1[pick(l1_names)])
                if sku["id"] in chosen:
                    continue
                chosen.append(sku["id"])
                qty = weighted(QTY_CHOICES, QTY_WEIGHTS)
                unit = round(sku["sale_price"] * random.uniform(0.86, 1.0), 2)
                amt = round(unit * qty, 2)
                # 有活动归因或用券时才产生行优惠，否则为 0——避免「无活动也打折」的失真
                disc = round(amt * random.uniform(0.05, 0.28), 2) if (cp or random.random() < 0.22) else 0.0
                cost = round(sku["cost_price"] * qty, 2)
                gp = round(amt - disc - cost, 2)
                iid += 1
                order_items.append({"id": iid, "sku": sku, "qty": qty, "unit": unit, "amount": amt,
                                    "discount": disc, "cost": cost, "gp": gp,
                                    "margin": round(gp / amt, 4) if amt else 0,
                                    "reviewed": 0, "refunded": 0})
            if not order_items:
                iid += 1
                sku = pick(skus_by_l1[l1])
                qty = 1
                unit = sku["sale_price"]
                order_items = [{"id": iid, "sku": sku, "qty": qty, "unit": unit, "amount": unit,
                                "discount": 0.0, "cost": sku["cost_price"],
                                "gp": round(unit - sku["cost_price"], 2),
                                "margin": round((unit - sku["cost_price"]) / unit, 4) if unit else 0,
                                "reviewed": 0, "refunded": 0}]
            amount = round(sum(x["amount"] for x in order_items), 2)
            discount = round(sum(x["discount"] for x in order_items), 2)
            qty_total = sum(x["qty"] for x in order_items)
            freight = 0.0 if amount >= 99 else round(random.uniform(6, 18), 2)
            pay_amount = round(amount - discount + freight, 2)
            main = max(order_items, key=lambda x: x["amount"])["sku"]["product"]
            # 用券：门槛达标才可用，用掉即从未使用池移除（一张券只能用一次）
            grant = None
            pool_g = grants_by_customer.get(c["id"])
            if pool_g:
                for gi, g in enumerate(pool_g):
                    if g["grant_date"] <= d <= g["expire_date"] and g["template"]["min_spend"] <= amount:
                        grant = pool_g.pop(gi)
                        grant["is_used"], grant["used_date"], grant["order_id"] = 1, d, oid
                        discount = round(discount + _coupon_discount(g["template"], amount), 2)
                        pay_amount = round(amount - discount + freight, 2)
                        break
            is_paid = 1 if status in PAID_STATUS else 0
            # 遗留列 stage：订单到达的最远阶段；已取消的少数记为「浏览」以保留漏斗首档
            stage = STAGE_OF_STATUS[status]
            if status == "已取消" and random.random() < 0.4:
                stage = "浏览"
            rep = None
            if ch["type"] == "线下" or ch["name"] == "企业团购":
                rep = pick(reps_by_store.get(store["id"]) or pool["sales_rep"])
            o = {
                "id": oid, "no": "SO%s%06d" % (mo.replace("-", ""), oid), "date": d, "month": mo,
                "customer": c, "product": main, "store": store, "channel": ch, "rep": rep,
                "warehouse": wh, "grant": grant, "campaign": cp,
                "region": c["big_region"], "province": c["province"],
                "amount": amount, "pay_amount": pay_amount, "discount": discount, "freight": freight,
                "quantity": qty_total, "item_count": len(order_items), "status": status,
                "pay_type": weighted(PAY_TYPES, PAY_TYPE_WEIGHTS) if is_paid else None,
                "stage": stage, "score": round(random.uniform(3.2, 5.0), 2),
                # is_first_order 统一由 _fix_first_order 按客户维度校正，规划阶段不猜
                "is_first": 0, "is_paid": is_paid, "created_at": rnd_dt(d), "items": order_items,
            }
            orders.append(o)
            items.extend(order_items)
    _fix_first_order(orders)
    print("    orders(planned)       rows=%d  order_item(planned) rows=%d" % (len(orders), len(items)))
    return orders, items


def _coupon_discount(t, amount):
    """券的实际优惠额：折扣券的 face_value 存的是折扣率（如 0.85 表示 85 折），
    其余券型存的是面额；统一封顶为订单金额的一半。下单与核销必须走同一个函数，
    否则 ``orders.discount_amount`` 与 ``coupon_usage.discount_amount`` 会对不上。
    """
    raw = amount * (1 - t["face"]) if t["type"] == "折扣券" else t["face"]
    return round(min(raw, amount * 0.5), 2)


def _fix_first_order(orders):
    """按客户维度重算 is_first_order：同一客户日期最早的那单才是首单。

    规划阶段用的 ``orders[-6:]`` 只是近似判断（客户订单不连续时会误判），此处统一校正。
    """
    seen = set()
    for o in sorted(orders, key=lambda x: (x["customer"]["id"], x["date"], x["id"])):
        cid = o["customer"]["id"]
        o["is_first"] = 0 if cid in seen else 1
        seen.add(cid)


def _insert_orders(cur, orders):
    rows = [(o["id"], o["no"], o["date"], o["month"], o["customer"]["id"], o["product"]["id"],
             o["store"]["id"], o["channel"]["id"], o["rep"]["id"] if o["rep"] else None,
             o["warehouse"]["id"], o["grant"]["id"] if o["grant"] else None,
             o["campaign"]["id"] if o["campaign"] else None,
             o["region"], o["province"], o["channel"]["name"],
             o["product"]["l1_name"][:16], o["product"]["name"][:32],
             o["amount"], o["pay_amount"], o["discount"], o["freight"], o["quantity"],
             o["item_count"], o["status"], o["pay_type"], o["stage"], o["score"],
             o["is_first"], o["is_paid"], o["created_at"]) for o in orders]
    insert(cur, "orders",
           ["id", "order_no", "order_date", "order_month", "customer_id", "product_id", "store_id",
            "channel_id", "sales_rep_id", "warehouse_id", "coupon_grant_id", "campaign_id",
            "region", "province", "channel", "category", "product",
            "amount", "pay_amount", "discount_amount", "freight_amount", "quantity", "item_count",
            "order_status", "pay_type", "stage", "score", "is_first_order", "is_paid",
            "created_at"], rows)
    print("    orders                rows=%d" % len(rows))


def _insert_order_items(cur, items, orders):
    """订单明细：``is_reviewed`` 与 ``is_refunded`` 由后续模块规划好再回填，故此处最后插入。"""
    order_of_item = {}
    for o in orders:
        for it in o["items"]:
            order_of_item[it["id"]] = o
    rows = [(it["id"], order_of_item[it["id"]]["id"], it["sku"]["product_id"], it["sku"]["id"],
             it["qty"], it["unit"], it["amount"],
             it["discount"], it["cost"], it["gp"], it["margin"], it["reviewed"], it["refunded"])
            for it in items]
    insert(cur, "order_item",
           ["id", "order_id", "product_id", "product_sku_id", "quantity", "unit_price", "amount",
            "discount_amount", "cost_amount", "gross_profit", "gross_margin", "is_reviewed",
            "is_refunded"], rows)
    print("    order_item            rows=%d" % len(rows))


# ==================== 支付 / 退款 / 发票 / 状态流转 ====================
def build_payment_chain(cur, pool):
    """支付单、退款单、发票、订单状态流转日志、积分流水、券核销（6 张表）。

    严格 1:1 或 1:N 且父行存在：一张已支付订单恰好一条支付单；退款单必须挂在真实支付单上；
    积分流水的 order_id 只取已支付订单。
    """
    orders = pool["orders"]
    customers = {c["id"]: c for c in pool["customer"]}
    paid = [o for o in orders if o["is_paid"]]

    # 退款方案先定下来：支付单的 is_refunded / pay_status 与退款单必须同源，
    # 否则会出现「支付单说没退款、退款单却存在」这类互相打脸的数据。
    done_orders = [o for o in paid if o["status"] == "已完成"]
    refund_plan = [(o, True, True) for o in paid if o["status"] == "退款中"]
    refund_plan += [(o, False, random.random() < 0.7)
                    for o in random.sample(done_orders, min(700, len(done_orders)))]
    refunded_orders = {o["id"] for o, _full, ok in refund_plan if ok}

    payments, pay_of_order = [], {}
    for i, o in enumerate(paid, start=1):
        refunded = o["id"] in refunded_orders
        payments.append((i, "PAY%s%07d" % (o["month"].replace("-", ""), i), o["id"],
                         o["customer"]["id"], o["pay_amount"], o["pay_type"],
                         pick(PAY_CHANNELS),
                         # 支付时间必须晚于下单时间，不能另抽一个时刻
                         o["created_at"] + timedelta(minutes=random.randint(1, 180)),
                         "已退款" if refunded else "成功",
                         "TRD%016d" % random.randint(1, 10 ** 15),
                         1 if refunded else 0))
        pay_of_order[o["id"]] = i
    insert(cur, "payment",
           ["id", "payment_no", "order_id", "customer_id", "pay_amount", "pay_type", "pay_channel",
            "pay_time", "pay_status", "trade_no", "is_refunded"], payments)
    print("    payment               rows=%d" % len(payments))

    # 退款单：全部「退款中」订单全额退，另抽部分「已完成」订单做部分退款
    refunds, rid = [], 0
    for o, full, ok in refund_plan:
        rid += 1
        amt = round(o["pay_amount"] if full else o["pay_amount"] * random.uniform(0.2, 0.8), 2)
        at = rnd_dt(min(TODAY, o["date"] + timedelta(days=random.randint(1, 25))))
        # 状态与完成时间同源：走完流程才有 finish_time，否则只能停在待审核/已同意/已拒绝
        if ok:
            status, finish = "已退款", at + timedelta(hours=random.randint(2, 96))
        else:
            status, finish = pick(["待审核", "已同意", "已拒绝"]), None
        refunds.append((rid, "RF%s%07d" % (o["month"].replace("-", ""), rid), o["id"],
                        pay_of_order[o["id"]], o["customer"]["id"], amt,
                        weighted(REFUND_REASONS, REFUND_REASONS_W), at, finish, status,
                        round(random.uniform(0.5, 72), 1)))
    insert(cur, "refund",
           ["id", "refund_no", "order_id", "payment_id", "customer_id", "refund_amount",
            "refund_reason", "apply_time", "finish_time", "refund_status", "audit_hours"], refunds)
    print("    refund                rows=%d" % len(refunds))

    # 发票：约 40% 的已支付订单开票；企业团购渠道必开专票
    invoices, vid = [], 0
    for o in paid:
        if o["channel"]["name"] != "企业团购" and random.random() > 0.4:
            continue
        vid += 1
        ent = o["channel"]["name"] == "企业团购" or random.random() < 0.28
        itype = "增值税专用发票" if ent else pick(["增值税普通发票", "电子发票"])
        amt = o["pay_amount"]
        invoices.append((vid, "INV%s%07d" % (o["month"].replace("-", ""), vid), o["id"],
                         o["customer"]["id"], itype,
                         ("%s科技有限公司" % o["customer"]["name"]) if ent else o["customer"]["name"],
                         "91%02d%010d" % (random.randint(10, 99), random.randint(0, 10 ** 9 - 1)) if ent else None,
                         amt, round(amt * 0.06 / 1.06, 2),
                         min(TODAY, o["date"] + timedelta(days=random.randint(0, 20))),
                         "已作废" if o["status"] == "退款中" and random.random() < 0.5 else "已开"))
    insert(cur, "invoice",
           ["id", "invoice_no", "order_id", "customer_id", "invoice_type", "invoice_title", "tax_no",
            "amount", "tax_amount", "invoice_date", "status"], invoices)
    print("    invoice               rows=%d" % len(invoices))

    _order_status_log(cur, orders)
    _member_point_log(cur, orders, customers)
    _coupon_usage(cur, orders)
    return pool


# 订单状态流转链：每个终态对应一条固定的状态序列
STATUS_FLOW = {
    "待支付": ["待支付"],
    "已支付": ["待支付", "已支付"],
    "已发货": ["待支付", "已支付", "已发货"],
    "已完成": ["待支付", "已支付", "已发货", "已完成"],
    "已取消": ["待支付", "已取消"],
    "退款中": ["待支付", "已支付", "已发货", "已完成", "退款中"],
}


def _order_status_log(cur, orders):
    rows, lid = [], 0
    for o in orders:
        flow = STATUS_FLOW[o["status"]]
        t = o["created_at"]
        prev = "创建"
        for st in flow:
            lid += 1
            rows.append((lid, o["id"], prev, st, t,
                         "SYSTEM" if st in ("待支付", "已支付", "已发货") else pick(OPERATORS),
                         None))
            prev = st
            t = t + timedelta(hours=random.randint(1, 60), minutes=random.randint(0, 59))
    insert(cur, "order_status_log",
           ["id", "order_id", "from_status", "to_status", "change_time", "operator", "remark"], rows)
    print("    order_status_log      rows=%d" % len(rows))


def _member_point_log(cur, orders, customers):
    """积分流水：已支付订单按等级倍率得积分；另有退款扣减/活动赠送/兑换消耗/过期清零。

    ``balance_points`` 按客户逐笔累加，流水走完后把最终余额回写 ``customer.total_points``，
    保证「流水加总」与「客户表积分」两条取数路径给出同一个数。
    """
    rows, lid = [], 0
    balance = {c["id"]: 0 for c in customers.values()}
    for o in orders:
        if not o["is_paid"]:
            continue
        c = o["customer"]
        lid += 1
        pts = int(o["pay_amount"] * c["level"]["ratio"])
        balance[c["id"]] += pts
        rows.append((lid, c["id"], o["id"], o["date"], "消费获得", pts, balance[c["id"]]))
    for _ in range(2600):
        c = pick(list(customers.values()))
        lid += 1
        ct = weighted(CHANGE_TYPES[1:], CHANGE_TYPES_W[1:])
        if ct in POINT_NEG_TYPES:
            if balance[c["id"]] < 50:
                ct = "活动赠送"   # 无积分可扣时不能硬扣，否则余额为负
                pts = random.randint(50, 1500)
            else:
                pts = -random.randint(50, min(2000, balance[c["id"]]))
        else:
            pts = random.randint(50, 1500)
        balance[c["id"]] += pts
        rows.append((lid, c["id"], None, rnd_date(date(2024, 9, 1), TODAY), ct, pts,
                     balance[c["id"]]))
    insert(cur, "member_point_log",
           ["id", "customer_id", "order_id", "change_date", "change_type", "change_points",
            "balance_points"], rows)
    cur.executemany("UPDATE customer SET total_points = %s WHERE id = %s",
                    [(b, cid) for cid, b in balance.items()])
    for c in customers.values():
        c["points"] = balance[c["id"]]
    print("    member_point_log      rows=%d" % len(rows))


def _coupon_usage(cur, orders):
    """券核销：与 orders.coupon_grant_id 一一对应，并回写 coupon_grant 的使用状态。"""
    rows, uid = [], 0
    for o in orders:
        g = o["grant"]
        if not g:
            continue
        uid += 1
        t = g["template"]
        rows.append((uid, g["id"], o["id"], o["customer"]["id"], t["id"],
                     rnd_dt(o["date"]), _coupon_discount(t, o["amount"]), o["amount"]))
    insert(cur, "coupon_usage",
           ["id", "coupon_grant_id", "order_id", "customer_id", "coupon_template_id", "used_time",
            "discount_amount", "order_amount"], rows)
    print("    coupon_usage          rows=%d" % len(rows))


# ==================== 购物车 ====================
def build_cart(cur, pool):
    """购物车与购物车明细：约 55% 的购物车最终转化下单，其余为弃单。"""
    customers = pool["customer"]
    skus = pool["product_sku"]
    carts, cart_items = [], []
    cid = iid = 0
    for _ in range(8000):
        cid += 1
        c = pick(customers)
        created = rnd_dt(rnd_date(max(c["register_date"], date(2024, 9, 1)), TODAY))
        kinds = random.randint(1, 4)
        chosen, total = [], 0.0
        for _ in range(kinds):
            s = pick(skus)
            if s["id"] in chosen:
                continue
            chosen.append(s["id"])
            iid += 1
            qty = weighted([1, 2, 3, 5], [58, 22, 12, 8])
            cart_items.append((iid, cid, s["id"], qty, s["sale_price"], created,
                               1 if random.random() < 0.8 else 0))
            total += s["sale_price"] * qty
        carts.append((cid, c["id"], created, created + timedelta(hours=random.randint(0, 72)),
                      len(chosen), round(total, 2), 1 if random.random() < 0.55 else 0))
    insert(cur, "shopping_cart",
           ["id", "customer_id", "created_at", "updated_at", "item_kind_count", "total_amount",
            "is_ordered"], carts)
    insert(cur, "cart_item",
           ["id", "shopping_cart_id", "product_sku_id", "quantity", "unit_price", "added_at",
            "is_selected"], cart_items)
    print("    shopping_cart         rows=%d" % len(carts))
    print("    cart_item             rows=%d" % len(cart_items))
