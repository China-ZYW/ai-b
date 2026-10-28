# -*- coding: utf-8 -*-
"""电商销售库造数：维度与主数据（22 张表）。

生成顺序即依赖拓扑序：地理/字典 → 品牌/品类/供应商 → 商品/SKU/属性 → 门店/组织 → 营销活动/券模板/促销规则。
所有主键由 Python 侧显式连续编号（见 ``sales_common`` 模块头说明），返回的池子供 ``sales_data_fact`` 引用。
"""
import random
from datetime import date, datetime
from datetime import time as dtime

from sales_common import (BIG_REGIONS, BRANDS, BRAND_COUNTRIES, BRAND_LEVELS, CAMPAIGN_TYPES,
                          CARRIERS, CATEGORY_L1, CATEGORY_TREE, CHANNELS, CHANNEL_WEIGHTS,
                          COUPON_TYPES, CUSTOMER_LEVELS, CUSTOMER_TAGS, GIVEN_NAMES, OPERATORS,
                          PLATFORMS, POSITIONS, POSITION_WEIGHTS, PRODUCT_TYPES, PROVINCE_MAP,
                          REP_LEVELS, RULE_TYPES, SURNAMES, TEAM_TYPES, TODAY, UNITS, WAREHOUSES,
                          city_tier, insert, months_back, pick, rnd_date, weighted)

MONTHS_24 = months_back(24)


def build(cur):
    """按拓扑序生成全部维度与主数据，返回后续事实数据要引用的池子字典。"""
    pool = {}
    pool["region"] = _region(cur)
    pool["channel"] = _channel(cur)
    pool["platform"] = _platform(cur)
    pool["carrier"] = _carrier(cur)
    pool["warehouse"] = _warehouse(cur, pool["region"])
    pool["customer_level"] = _customer_level(cur)
    pool["customer_tag"] = _customer_tag(cur)
    pool["brand"] = _brand(cur)
    pool["category"] = _category(cur)
    pool["supplier"] = _supplier(cur)
    pool["product"] = _product(cur, pool["category"], pool["brand"], pool["supplier"])
    pool["product_sku"] = _product_sku(cur, pool["product"])
    pool["product_attribute"] = _product_attribute(cur)
    _product_attr_value(cur, pool["product_sku"], pool["product_attribute"])
    pool["store"] = _store(cur, pool["channel"], pool["platform"], pool["region"])
    _store_staff(cur, pool["store"])
    pool["sales_team"] = _sales_team(cur, pool["region"])
    pool["sales_rep"] = _sales_rep(cur, pool["sales_team"], pool["store"])
    _supplier_product(cur, pool["supplier"], pool["product"])
    pool["campaign"] = _campaign(cur, pool["channel"])
    pool["coupon_template"] = _coupon_template(cur, pool["campaign"])
    pool["promotion_rule"] = _promotion_rule(cur, pool["campaign"])
    return pool


# ==================== 地理与字典 ====================
def _region(cur):
    """行政区域三级树：7 大区 → 31 省份 → 约 85 城市。"""
    rows, out, rid = [], [], 0
    region_id_of_big = {}
    for big in BIG_REGIONS:
        rid += 1
        region_id_of_big[big] = rid
        rows.append((rid, 0, "R%d" % rid, big, 1, "大区", big, None, None, None))
        out.append({"id": rid, "level": 1, "big_region": big})
    province_id_of = {}
    pid = 0
    for prov, (big, cities) in PROVINCE_MAP.items():
        rid += 1
        pid += 1
        province_id_of[prov] = rid
        rows.append((rid, region_id_of_big[big], "P%02d" % pid, prov, 2, "省份", big, prov, None, None))
        out.append({"id": rid, "level": 2, "province": prov, "big_region": big})
    cid = 0
    for prov, (big, cities) in PROVINCE_MAP.items():
        for city in cities:
            rid += 1
            cid += 1
            tier = city_tier(city)
            rows.append((rid, province_id_of[prov], "C%03d" % cid, city, 3, "城市", big, prov, city, tier))
            out.append({"id": rid, "level": 3, "province": prov, "city": city,
                        "big_region": big, "city_tier": tier})
    n = insert(cur, "region",
               ["id", "parent_id", "region_code", "region_name", "region_level", "region_type",
                "big_region", "province", "city", "city_tier"], rows)
    print("    region                rows=%d" % n)
    return {"all": out,
            "cities": [r for r in out if r["level"] == 3],
            "city_by_province": {p: [r for r in out if r.get("province") == p and r["level"] == 3]
                                 for p in PROVINCE_MAP}}


def _channel(cur):
    rows, out = [], []
    for i, (code, name, ctype, fee) in enumerate(CHANNELS, start=1):
        rows.append((i, code, name, ctype, fee, 1))
        out.append({"id": i, "code": code, "name": name, "type": ctype, "fee": fee})
    insert(cur, "channel",
           ["id", "channel_code", "channel_name", "channel_type", "fee_rate", "is_enabled"], rows)
    print("    channel               rows=%d" % len(rows))
    return out


def _platform(cur):
    rows, out = [], []
    for i, (code, name, cycle, rate) in enumerate(PLATFORMS, start=1):
        rows.append((i, code, name, cycle, rate, 1))
        out.append({"id": i, "code": code, "name": name, "cycle": cycle, "rate": rate})
    insert(cur, "platform",
           ["id", "platform_code", "platform_name", "settle_cycle_days", "commission_rate",
            "is_enabled"], rows)
    print("    platform              rows=%d" % len(rows))
    return out


def _carrier(cur):
    """承运商：主键是业务编码 carrier_code（无 id 列），用于验证 JoinGraph 规则②。"""
    rows = [(code, name, ctype, base, extra, hours, level)
            for code, name, ctype, base, extra, hours, level in CARRIERS]
    insert(cur, "carrier",
           ["carrier_code", "carrier_name", "carrier_type", "base_fee", "extra_fee_per_kg",
            "avg_delivery_hours", "cooperation_level"], rows)
    print("    carrier               rows=%d" % len(rows))
    return [{"code": c[0], "name": c[1], "type": c[2], "base_fee": c[3], "extra_fee": c[4],
             "hours": c[5], "level": c[6]} for c in CARRIERS]


def _warehouse(cur, region):
    rows, out = [], []
    for i, (code, name, wtype, prov, city, area) in enumerate(WAREHOUSES, start=1):
        r = _city_region(region, prov, city)
        rows.append((i, code, name, wtype, r["id"], prov, city, area, 1))
        out.append({"id": i, "code": code, "name": name, "type": wtype, "province": prov,
                    "city": city, "region_id": r["id"], "big_region": r["big_region"]})
    insert(cur, "warehouse",
           ["id", "warehouse_code", "warehouse_name", "warehouse_type", "region_id", "province",
            "city", "capacity_sqm", "is_enabled"], rows)
    print("    warehouse             rows=%d" % len(rows))
    return out


def _city_region(region, province, city):
    """按省+市取城市级区域行；取不到则退回该省任一城市行。"""
    for r in region["cities"]:
        if r["province"] == province and r["city"] == city:
            return r
    cand = region["city_by_province"].get(province) or region["cities"]
    return pick(cand)


def _customer_level(cur):
    """会员等级：主键是 level_code（无 id 列），用于验证 JoinGraph 规则②。"""
    rows = [(code, name, order_no, pts, spend, disc, ratio, benefit)
            for code, name, order_no, pts, spend, disc, ratio, benefit in CUSTOMER_LEVELS]
    insert(cur, "customer_level",
           ["level_code", "level_name", "level_order", "min_points", "min_spend", "discount_rate",
            "point_ratio", "benefit_desc"], rows)
    print("    customer_level        rows=%d" % len(rows))
    return [{"code": c[0], "name": c[1], "order": c[2], "discount": c[5], "ratio": c[6]}
            for c in CUSTOMER_LEVELS]


def _customer_tag(cur):
    rows, out = [], []
    for i, (name, group, rule) in enumerate(CUSTOMER_TAGS, start=1):
        rows.append((i, name, group, rule))
        out.append({"id": i, "name": name, "group": group})
    insert(cur, "customer_tag", ["id", "tag_name", "tag_group", "rule_desc"], rows)
    print("    customer_tag          rows=%d" % len(rows))
    return out


# ==================== 商品主数据 ====================
def _brand(cur):
    """品牌：每个一级品类 12~14 个，共 80 个；定位与国别按真实市场结构分布。"""
    rows, out, bid = [], [], 0
    for l1 in CATEGORY_L1:
        for name in BRANDS[l1]:
            bid += 1
            level = weighted(BRAND_LEVELS, [12, 30, 30, 12, 10, 6])
            rows.append((bid, "BR%04d" % bid, name, pick(BRAND_COUNTRIES), level,
                         random.randint(1958, 2019),
                         "https://cdn.demo.local/brand/%d.png" % bid, 1))
            out.append({"id": bid, "name": name, "level": level, "l1": l1})
    insert(cur, "brand",
           ["id", "brand_code", "brand_name", "brand_country", "brand_level", "founded_year",
            "logo_url", "is_enabled"], rows)
    print("    brand                 rows=%d" % len(rows))
    return out


# 各一级品类的价格带（最低价, 最高价）：决定客单价分布是否符合常识
PRICE_BAND = {
    "电子产品": (299, 8999), "家居用品": (59, 2999), "服装鞋帽": (49, 1599),
    "食品饮料": (15, 499), "美妆个护": (39, 1899), "母婴玩具": (29, 3999),
}
# 各一级品类的销量权重：食品饮料与服装走量、电子产品低频高客单
L1_SALES_WEIGHT = {"电子产品": 18, "家居用品": 15, "服装鞋帽": 22,
                   "食品饮料": 20, "美妆个护": 15, "母婴玩具": 10}


def _category(cur):
    """品类三级树：6 一级 × 4 二级 × 5 三级 = 150 行，path 存 '一级id/二级id/三级id'。"""
    rows, out, cid = [], [], 0
    l1_list = []
    for l1 in CATEGORY_L1:
        cid += 1
        rows.append((cid, 0, "C%03d" % cid, l1, 1, "%d" % cid, 0, 0.0300, 1))
        l1_list.append({"id": cid, "name": l1, "path": "%d" % cid})
        out.append({"id": cid, "level": 1, "name": l1, "l1_name": l1, "l1_id": cid,
                    "path": "%d" % cid})
    l2_map = {}
    for l1 in l1_list:
        subs = []
        for l2 in CATEGORY_TREE[l1["name"]]:
            cid += 1
            path = "%s/%d" % (l1["path"], cid)
            rows.append((cid, l1["id"], "C%03d" % cid, l2, 2, path, 0, 0.0350, 1))
            node = {"id": cid, "name": l2, "path": path, "l1": l1}
            subs.append(node)
            out.append({"id": cid, "level": 2, "name": l2, "l1_name": l1["name"],
                        "l1_id": l1["id"], "path": path})
        l2_map[l1["name"]] = subs
    l3_list = []
    for l1_name, subs in l2_map.items():
        for l2 in subs:
            for l3 in CATEGORY_TREE[l1_name][l2["name"]]:
                cid += 1
                path = "%s/%d" % (l2["path"], cid)
                rows.append((cid, l2["id"], "C%03d" % cid, l3, 3, path, 0, 0.0400, 1))
                node = {"id": cid, "name": l3, "path": path,
                        "l1_name": l1_name, "l1_id": l2["l1"]["id"], "l2_name": l2["name"]}
                l3_list.append(node)
                out.append({"id": cid, "level": 3, "name": l3, "l1_name": l1_name,
                            "l1_id": l2["l1"]["id"], "path": path})
    insert(cur, "category",
           ["id", "parent_id", "category_code", "category_name", "category_level", "path",
            "sort_no", "commission_rate", "is_enabled"], rows)
    print("    category              rows=%d (L1=%d L2=%d L3=%d)"
          % (len(rows), len(l1_list), sum(len(v) for v in l2_map.values()), len(l3_list)))
    return {"all": out, "l1": l1_list, "l3": l3_list}


def _supplier(cur):
    """供应商：60 家，名称按「城市+字号+行业+组织形式」拼装，等级与信用分相关。"""
    words = ["星", "恒", "泰", "润", "昌", "凯", "宏", "信", "隆", "嘉", "鑫", "越", "腾", "茂", "源"]
    inds = ["电子科技", "贸易", "供应链", "家居用品", "服饰", "食品", "化妆品", "母婴用品",
            "百货", "实业", "物流", "包装制品"]
    forms = ["有限公司", "股份有限公司", "有限责任公司"]
    rows, out = [], []
    used = set()
    for i in range(1, 61):
        # 名称按「城市+字号+行业+组织形式」拼装；城市取自 PROVINCE_MAP 的市级列表，
        # 不能直接截省份名的前两个字——那会拼出「广东市」「黑龙市」这类不存在的地名。
        while True:
            city = pick(PROVINCE_MAP[pick(list(PROVINCE_MAP.keys()))][1])
            name = "%s市%s%s%s%s" % (city, pick(words), pick(words), pick(inds), pick(forms))
            if name not in used:
                used.add(name)
                break
        level = weighted(["战略", "核心", "普通", "淘汰"], [10, 26, 56, 8])
        credit = {"战略": (88, 99), "核心": (76, 92), "普通": (60, 84), "淘汰": (35, 62)}[level]
        prov = pick(list(PROVINCE_MAP.keys()))
        rows.append((i, "SUP%04d" % i, name, pick(["王经理", "李经理", "张经理", "陈经理", "刘经理"]),
                     "1%d%08d" % (random.choice([3, 5, 7, 8]), random.randint(0, 99999999)),
                     prov, pick(PROVINCE_MAP[prov][1]), level,
                     rnd_date(date(2014, 1, 1), date(2024, 12, 31)),
                     round(random.uniform(*credit), 2),
                     round(random.uniform(0.78, 0.995), 4),
                     round(random.uniform(0.90, 0.999), 4),
                     1 if level != "淘汰" else 0))
        out.append({"id": i, "name": name, "level": level, "province": prov})
    insert(cur, "supplier",
           ["id", "supplier_code", "supplier_name", "contact_person", "contact_phone", "province",
            "city", "supplier_level", "cooperation_start_date", "credit_score", "on_time_rate",
            "quality_pass_rate", "is_enabled"], rows)
    print("    supplier              rows=%d" % len(rows))
    return out


# 商品名的修饰词：让 1200 个 SPU 名称互不相同且读起来像真实商品
DESCRIPTORS = ["旗舰版", "尊享版", "青春版", "经典款", "升级款", "限量版", "畅销款", "新款",
               "轻享版", "豪华版", "便携款", "家用款", "商用款", "礼盒装", "套装", "单品"]


def _product(cur, category, brand, supplier):
    """商品 SPU：1200 个，每个三级品类 10 个；品牌与品类的对应关系严格一致。"""
    brands_by_l1 = {}
    for b in brand:
        brands_by_l1.setdefault(b["l1"], []).append(b)
    rows, out, pid = [], [], 0
    for l3 in category["l3"]:
        l1 = l3["l1_name"]
        lo, hi = PRICE_BAND[l1]
        for _ in range(10):
            pid += 1
            b = pick(brands_by_l1[l1])
            name = "%s %s %s" % (b["name"], l3["name"], pick(DESCRIPTORS))
            list_price = round(random.uniform(lo, hi), 2)
            cost_price = round(list_price * random.uniform(0.42, 0.76), 2)
            sup = pick(supplier)
            weight = round(random.uniform(0.05, 12.0), 3)
            rows.append((pid, "P%06d" % pid, name, l3["id"], b["id"], sup["id"],
                         pick(PRODUCT_TYPES), UNITS[l1], list_price, cost_price,
                         weight,
                         rnd_date(date(2022, 1, 1), date(2026, 7, 31)),
                         1 if random.random() > 0.06 else 0))
            out.append({"id": pid, "name": name, "category_id": l3["id"],
                        "category_name": l3["name"], "l1_name": l1, "l1_id": l3["l1_id"],
                        "brand_id": b["id"], "brand_name": b["name"], "brand_level": b["level"],
                        "supplier_id": sup["id"], "supplier_name": sup["name"],
                        "list_price": list_price, "cost_price": cost_price, "weight_kg": weight,
                        "unit": UNITS[l1]})
    insert(cur, "product",
           ["id", "product_code", "product_name", "category_id", "brand_id", "supplier_id",
            "product_type", "unit", "list_price", "cost_price", "weight_kg", "launch_date",
            "is_enabled"], rows)
    print("    product               rows=%d" % len(rows))
    return out


# SKU 规格维度：按一级品类给出「属性名 → 候选值」，拼出可读的规格串
SPEC_DIMS = {
    "电子产品": [("颜色", ["曜石黑", "星河银", "冰川蓝", "樱花粉", "松石绿"]),
                 ("容量", ["64G", "128G", "256G", "512G", "1T"])],
    "家居用品": [("颜色", ["原木色", "象牙白", "雾霾蓝", "浅灰", "胡桃色"]),
                 ("规格", ["单人", "双人", "三人", "标准", "加大"])],
    "服装鞋帽": [("颜色", ["黑色", "白色", "藏青", "米色", "酒红"]),
                 ("尺码", ["S", "M", "L", "XL", "XXL"])],
    "食品饮料": [("口味", ["原味", "香辣", "低糖", "无糖", "混合口味"]),
                 ("净含量", ["250g", "500g", "1kg", "2kg", "礼盒装"])],
    "美妆个护": [("色号", ["01自然", "02象牙", "03小麦", "04复古", "05豆沙"]),
                ("规格", ["30ml", "50ml", "100ml", "套装", "旅行装"])],
    "母婴玩具": [("适用年龄", ["0-6月", "6-12月", "1-3岁", "3-6岁", "6岁以上"]),
                 ("规格", ["标准装", "加大装", "组合装", "试用装", "礼盒装"])],
}


def _product_sku(cur, products):
    """SKU：每个 SPU 2~5 个规格，共约 4000 个；售价在 SPU 标价上下浮动。"""
    rows, out, sid = [], [], 0
    for p in products:
        dims = SPEC_DIMS[p["l1_name"]]
        n = random.randint(2, 5)
        combos = set()
        while len(combos) < n:
            combos.add(tuple(pick(v) for _, v in dims))
        for combo in combos:
            sid += 1
            spec = "/".join(combo)
            sale = round(p["list_price"] * random.uniform(0.72, 1.0), 2)
            cost = round(min(p["cost_price"], sale * 0.86), 2)
            rows.append((sid, p["id"], "SKU%07d" % sid, spec, sale, cost,
                         "69%011d" % random.randint(0, 99999999999), 1))
            out.append({"id": sid, "product_id": p["id"], "sku_code": "SKU%07d" % sid,
                        "spec": spec, "sale_price": sale, "cost_price": cost, "product": p})
    insert(cur, "product_sku",
           ["id", "product_id", "sku_code", "sku_spec", "sale_price", "cost_price", "barcode",
            "is_enabled"], rows)
    print("    product_sku           rows=%d" % len(rows))
    return out


ATTR_DEFS = [
    ("颜色", "销售属性", "单选", 1), ("尺码", "销售属性", "单选", 1), ("容量", "销售属性", "单选", 1),
    ("规格", "销售属性", "单选", 1), ("口味", "销售属性", "单选", 0), ("色号", "销售属性", "单选", 0),
    ("净含量", "销售属性", "单选", 1), ("适用年龄", "销售属性", "单选", 0),
    ("材质", "规格属性", "单选", 1), ("产地", "规格属性", "文本", 1), ("保质期", "规格属性", "文本", 0),
    ("功率", "规格属性", "文本", 0), ("屏幕尺寸", "规格属性", "单选", 0), ("内存", "规格属性", "单选", 0),
    ("存储", "规格属性", "单选", 0), ("款式", "规格属性", "单选", 0), ("厚度", "规格属性", "文本", 0),
    ("件数", "规格属性", "文本", 0), ("包装", "规格属性", "单选", 0), ("香型", "规格属性", "单选", 0),
    ("适用人群", "规格属性", "多选", 0), ("适用场景", "规格属性", "多选", 0), ("防水等级", "规格属性", "单选", 0),
    ("电池容量", "规格属性", "文本", 0), ("重量", "规格属性", "文本", 0), ("尺寸", "规格属性", "文本", 1),
    ("面料", "规格属性", "单选", 0), ("填充物", "规格属性", "文本", 0), ("接口类型", "规格属性", "单选", 0),
    ("分辨率", "规格属性", "文本", 0), ("刷新率", "规格属性", "单选", 0), ("噪音等级", "规格属性", "文本", 0),
    ("能效等级", "规格属性", "单选", 0), ("认证", "规格属性", "多选", 0), ("保修期", "规格属性", "文本", 1),
    ("是否进口", "规格属性", "单选", 0), ("存储条件", "规格属性", "文本", 0), ("配方", "规格属性", "文本", 0),
    ("适用肤质", "规格属性", "多选", 0), ("适用发质", "规格属性", "多选", 0),
]


def _product_attribute(cur):
    rows, out = [], []
    for i, (name, atype, itype, req) in enumerate(ATTR_DEFS, start=1):
        rows.append((i, name, atype, itype, req))
        out.append({"id": i, "name": name, "type": atype})
    insert(cur, "product_attribute",
           ["id", "attribute_name", "attribute_type", "input_type", "is_required"], rows)
    print("    product_attribute     rows=%d" % len(rows))
    return out


def _product_attr_value(cur, skus, attributes):
    """SKU 属性值（多对多桥表）：每个 SKU 挂 3 条属性。"""
    sales_attrs = [a for a in attributes if a["type"] == "销售属性"]
    spec_attrs = [a for a in attributes if a["type"] == "规格属性"]
    spec_values = ["国产", "进口", "纯棉", "涤纶", "不锈钢", "陶瓷", "ABS", "铝合金"]
    rows, rid = [], 0
    for s in skus:
        parts = s["spec"].split("/")
        for i, part in enumerate(parts[:2]):
            rid += 1
            attr = sales_attrs[i % len(sales_attrs)]
            rows.append((rid, s["id"], attr["id"], part))
        rid += 1
        rows.append((rid, s["id"], pick(spec_attrs)["id"], pick(spec_values)))
    insert(cur, "product_attr_value",
           ["id", "product_sku_id", "product_attribute_id", "attribute_value"], rows)
    print("    product_attr_value    rows=%d" % len(rows))


# ==================== 门店与组织 ====================
# 渠道 → 允许的门店类型；线上渠道的门店没有实体面积
ONLINE_CHANNELS = {"线上商城", "第三方平台", "直播带货", "私域小程序", "O2O到家", "企业团购"}
NEED_PLATFORM = {"第三方平台", "直播带货"}
# 门店名里用的渠道简称：直接截前两个字会得到「第三」「O2」这类读不通的词
CHANNEL_ABBR = {"线上商城": "旗舰", "第三方平台": "平台", "线下门店": "门店", "分销商": "分销",
                "直播带货": "直播", "企业团购": "团购", "私域小程序": "私域", "O2O到家": "到家"}
# 只有挂在这几个平台上的店铺才需要填 platform_id
THIRD_PLATFORM_NAMES = ("天猫", "京东", "拼多多", "抖音", "快手", "小红书", "唯品会", "苏宁易购")
# 线下门店才有的业态（STORE_TYPES 里的「前置仓」「线上店铺」不适用）
OFFLINE_STORE_TYPES = ["旗舰店", "直营店", "加盟店", "专柜"]
OFFLINE_STORE_WEIGHTS = [12, 30, 44, 14]


def _store(cur, channels, platforms, region):
    """门店：120 家（8 渠道 × 15），线上店铺挂平台、线下门店挂省市。"""
    rows, out, sid = [], [], 0
    third_platforms = [p for p in platforms if p["name"] in THIRD_PLATFORM_NAMES]
    for ch in channels:
        for _ in range(15):          # 8 个渠道 × 15 家 = 120 家门店
            sid += 1
            online = ch["name"] in ONLINE_CHANNELS
            if online:
                # 线上店铺没有实体营业面积（置 0），但仍有归属省市——注册地与发货地
                stype = "旗舰店" if ch["name"] in NEED_PLATFORM else "线上店铺"
                pf = pick(third_platforms) if ch["name"] in NEED_PLATFORM else None
                area, staff = 0.00, random.randint(3, 25)
            else:
                stype = weighted(OFFLINE_STORE_TYPES, OFFLINE_STORE_WEIGHTS)
                pf = None
                area, staff = round(random.uniform(60, 1200), 2), random.randint(6, 48)
            prov = pick(list(PROVINCE_MAP.keys()))
            city = pick(PROVINCE_MAP[prov][1])
            r = _city_region(region, prov, city)
            open_date = rnd_date(date(2016, 1, 1), date(2026, 3, 31))
            name = "%s%s%d号店" % (city, CHANNEL_ABBR[ch["name"]], sid)
            rows.append((sid, "ST%05d" % sid, name, stype,
                         ch["id"], pf["id"] if pf else None, r["id"], prov, city, open_date,
                         area, staff, 1))
            out.append({"id": sid, "name": name, "type": stype,
                        "channel_id": ch["id"], "channel_name": ch["name"],
                        "platform_id": pf["id"] if pf else None, "region_id": r["id"],
                        "province": prov, "city": city, "big_region": r["big_region"],
                        "open_date": open_date, "area": area, "staff": staff})
    insert(cur, "store",
           ["id", "store_code", "store_name", "store_type", "channel_id", "platform_id",
            "region_id", "province", "city", "open_date", "area_sqm", "staff_count",
            "is_enabled"], rows)
    print("    store                 rows=%d" % len(rows))
    return out


def _store_staff(cur, stores):
    """门店员工：每店 3~9 人，共约 600 人。"""
    rows, out, iid = [], [], 0
    for st in stores:
        for _ in range(random.randint(3, 9)):
            iid += 1
            pos = weighted(POSITIONS, POSITION_WEIGHTS)
            base = {"店长": 9000, "副店长": 7200, "导购": 4800, "收银": 4200, "仓管": 4500}[pos]
            name = pick(SURNAMES) + pick(GIVEN_NAMES)
            rows.append((iid, st["id"], "SF%06d" % (100000 + iid), name,
                         pos, rnd_date(date(2016, 1, 1), date(2026, 6, 30)),
                         round(base * random.uniform(0.85, 1.6), 2),
                         1 if random.random() > 0.08 else 0))
            out.append({"id": iid, "store_id": st["id"], "name": name, "position": pos})
    insert(cur, "store_staff",
           ["id", "store_id", "staff_no", "staff_name", "position", "hire_date", "base_salary",
            "is_enabled"], rows)
    print("    store_staff           rows=%d" % len(rows))
    return out


def _sales_team(cur, region):
    """销售团队：每个大区 3~4 个团队，共 24 个。"""
    provinces = [r for r in region["all"] if r["level"] == 3]
    rows, out, tid = [], [], 0
    for big in BIG_REGIONS:
        # 每个大区 3~4 个团队，团队类型不重复；共约 24 个团队
        for ttype in random.sample(TEAM_TYPES, random.randint(3, 4)):
            tid += 1
            prov = pick([p for p in provinces if p["big_region"] == big])
            target = round(random.uniform(3_000_000, 28_000_000), 2)
            rows.append((tid, "TM%03d" % tid, "%s%s%d组" % (big, ttype[:2], tid), ttype,
                         prov["id"], pick(["王总监", "李总监", "张总监", "刘总监", "陈总监"]),
                         random.randint(6, 24), target))
            out.append({"id": tid, "name": rows[-1][2], "type": ttype, "region_id": prov["id"],
                        "big_region": big, "target": target})
    insert(cur, "sales_team",
           ["id", "team_code", "team_name", "team_type", "region_id", "leader_name", "headcount",
            "target_amount"], rows)
    print("    sales_team            rows=%d" % len(rows))
    return out


def _sales_rep(cur, teams, stores):
    """销售员：200 人，挂在团队与门店下；提成比例随职级递增。"""
    rows, out = [], []
    stores_by_big = {}
    for st in stores:
        stores_by_big.setdefault(st["big_region"], []).append(st)
    rate = {"初级": 0.015, "中级": 0.025, "高级": 0.038, "资深": 0.052}
    for i in range(1, 201):
        tm = pick(teams)
        cand = stores_by_big.get(tm["big_region"]) or stores
        st = pick(cand)
        lvl = weighted(REP_LEVELS, [34, 34, 22, 10])
        rows.append((i, tm["id"], st["id"], "SR%05d" % (20000 + i),
                     pick(list("王李张刘陈杨黄赵吴周徐孙马朱胡林何高罗郑")) + pick(["伟", "芳", "磊", "洋", "勇", "军", "杰", "涛"]),
                     lvl, rnd_date(date(2017, 1, 1), date(2026, 5, 31)), rate[lvl],
                     round(random.uniform(600_000, 6_000_000), 2),
                     1 if random.random() > 0.07 else 0))
        out.append({"id": i, "name": rows[-1][4], "team_id": tm["id"], "store_id": st["id"],
                    "level": lvl, "commission": rate[lvl]})
    insert(cur, "sales_rep",
           ["id", "sales_team_id", "store_id", "rep_no", "rep_name", "rep_level", "hire_date",
            "commission_rate", "target_amount", "is_enabled"], rows)
    print("    sales_rep             rows=%d" % len(rows))
    return out


def _supplier_product(cur, suppliers, products):
    """供货关系（多对多桥表）：主供应商必挂，另随机补 0~2 个备选供应商。"""
    rows, rid, seen = [], 0, set()
    for p in products:
        main = next(s for s in suppliers if s["id"] == p["supplier_id"])
        rid += 1
        seen.add((main["id"], p["id"]))
        rows.append((rid, main["id"], p["id"], round(p["cost_price"] * random.uniform(0.94, 1.0), 2),
                     random.randint(10, 500), random.randint(3, 30), 1))
        for _ in range(random.randint(0, 2)):
            s = pick(suppliers)
            if (s["id"], p["id"]) in seen:
                continue
            seen.add((s["id"], p["id"]))
            rid += 1
            rows.append((rid, s["id"], p["id"],
                         round(p["cost_price"] * random.uniform(0.96, 1.12), 2),
                         random.randint(10, 800), random.randint(3, 45), 0))
    insert(cur, "supplier_product",
           ["id", "supplier_id", "product_id", "supply_price", "min_order_qty", "lead_time_days",
            "is_primary"], rows)
    print("    supplier_product      rows=%d" % len(rows))


# ==================== 营销（orders 要引用 campaign，故在维度阶段生成） ====================
CAMPAIGN_NAMES = ["618大促", "双11狂欢", "双12返场", "年货节", "品牌日", "新品首发", "会员日",
                  "开学季", "五一钜惠", "国庆黄金周", "夏日清凉节", "冬季暖购节", "母婴专场",
                  "数码狂欢周", "美妆盛典", "家居焕新节", "超品日", "清仓特卖", "直播宠粉日", "周末闪购"]


def _campaign(cur, channels):
    """营销活动：150 场，分布在最近 24 个月；状态按起止日期与 TODAY 的关系推导。"""
    rows, out = [], []
    for i in range(1, 151):
        mo = pick(MONTHS_24)
        y, m = int(mo[:4]), int(mo[5:7])
        start = date(y, m, random.randint(1, 24))
        days = random.randint(1, 14)
        end = date(start.year, start.month, min(28, start.day + days))
        ch = weighted(channels, CHANNEL_WEIGHTS)
        budget = round(random.uniform(80_000, 3_000_000), 2)
        spend = round(budget * random.uniform(0.55, 1.02), 2)
        target_gmv = round(budget * random.uniform(4, 14), 2)
        actual_gmv = round(target_gmv * random.uniform(0.55, 1.45), 2)
        if end < TODAY:
            status = "已结束"
        elif start <= TODAY <= end:
            status = "进行中"
        else:
            status = "未开始"
        if random.random() < 0.04:
            status = "已取消"
        name = "%s%s" % (mo, pick(CAMPAIGN_NAMES))
        ctype = pick(CAMPAIGN_TYPES)
        rows.append((i, "CP%05d" % i, name, ctype, ch["id"], start, end, budget, spend,
                     target_gmv, actual_gmv, pick(OPERATORS[:7]), status))
        out.append({"id": i, "name": name, "type": ctype, "channel_id": ch["id"],
                    "start": start, "end": end, "month": mo, "budget": budget, "spend": spend,
                    "status": status})
    insert(cur, "campaign",
           ["id", "campaign_code", "campaign_name", "campaign_type", "channel_id", "start_date",
            "end_date", "budget", "actual_spend", "target_gmv", "actual_gmv", "owner",
            "status"], rows)
    print("    campaign              rows=%d" % len(rows))
    return out


def _coupon_template(cur, campaigns):
    """券模板：60 种，每种归属一个营销活动。"""
    rows, out = [], []
    for i in range(1, 61):
        cp = pick(campaigns)
        ctype = pick(COUPON_TYPES)
        if ctype == "满减券":
            face, minspend = pick([10, 20, 30, 50, 80, 120, 200]), 0
            minspend = face * pick([3, 4, 5, 7])
            name = "满%d减%d" % (minspend, face)
        elif ctype == "折扣券":
            face, minspend = round(random.uniform(0.7, 0.95), 2), pick([0, 199, 399])
            name = "%.1f折券" % (face * 10)
        elif ctype == "无门槛券":
            face, minspend = pick([5, 8, 15, 25]), 0
            name = "%d元无门槛券" % face
        else:
            face, minspend = pick([6, 8, 12]), 0
            name = "包邮券"
        vd = random.randint(3, 30)
        rows.append((i, "CN%05d" % i, name, cp["id"], ctype, face, minspend,
                     random.randint(2000, 200000), vd))
        out.append({"id": i, "name": name, "type": ctype, "face": face, "min_spend": minspend,
                    "campaign_id": cp["id"], "valid_days": vd})
    insert(cur, "coupon_template",
           ["id", "coupon_code", "coupon_name", "campaign_id", "coupon_type", "face_value",
            "min_spend", "total_qty", "valid_days"], rows)
    print("    coupon_template       rows=%d" % len(rows))
    return out


def _promotion_rule(cur, campaigns):
    """促销规则：80 条，每种归属一个营销活动。"""
    rows, out = [], []
    for i in range(1, 81):
        cp = pick(campaigns)
        rtype = pick(RULE_TYPES)
        if rtype == "满件折":
            tq, ta, bv = random.randint(2, 4), 0, round(random.uniform(0.7, 0.92), 2)
        elif rtype == "满额减":
            tq, ta, bv = 0, pick([199, 299, 499, 999, 1999]), pick([20, 50, 100, 200, 400])
        elif rtype == "第二件半价":
            tq, ta, bv = 2, 0, 0.5
        elif rtype == "买赠":
            tq, ta, bv = random.randint(2, 3), 0, 0
        else:
            tq, ta, bv = 0, 0, round(random.uniform(0.55, 0.88), 2)
        # start_time / end_time 是 DATETIME：与所属活动的日期对齐，起于当日 0 点、止于当日 23:59:59
        rows.append((i, cp["id"], "%s-%s" % (cp["name"][:12], rtype), rtype, tq, ta, bv,
                     datetime.combine(cp["start"], dtime.min),
                     datetime.combine(cp["end"], dtime(23, 59, 59)),
                     1 if random.random() < 0.3 else 0))
        out.append({"id": i, "campaign_id": cp["id"], "type": rtype})
    insert(cur, "promotion_rule",
           ["id", "campaign_id", "rule_name", "rule_type", "threshold_qty", "threshold_amount",
            "benefit_value", "start_time", "end_time", "is_stackable"], rows)
    print("    promotion_rule        rows=%d" % len(rows))
    return out
