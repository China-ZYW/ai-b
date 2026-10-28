# -*- coding: utf-8 -*-
"""电商销售库 10 张大宽表：DDL + 派生 SQL（全部由基础表 JOIN 生成，不独立造数）。

三条设计原则：

1. **宽表数据一律 ``INSERT … SELECT`` 从基础表派生**，不用 Python 另造一份。这样宽表与基础表
   天然勾稽一致——用户从宽表问到的数和从基础表 JOIN 出来的数必然相同，才配当「关联关系」的验证素材。
   分层也照数仓惯例：``dwd_order_item_wide`` 是明细宽表（DWD），其余 9 张 DWS 汇总表大多从它再聚合。

2. **表名统一 ``_wide`` 结尾**。``JoinGraph.normTableName`` 会剥掉 ``dwd_/dws_/ads_/ods_/dim_/fact_``
   等分层前缀，若把汇总表命名为 ``dws_orders``，规范化后得到 ``orders``，与基础表 ``orders`` 在
   ``nameIndex`` 里撞成同一个键——``inferByIdSuffix`` 遇到「命中 >1 张表」会**判为歧义而双双放弃推断**。
   加 ``_wide`` 后缀可确保规范化名永不与基础表重合。

3. **聚合全程规避 fan-out**。订单级度量（订单数、客户数）用 ``COUNT(DISTINCT …)``；退款/售后这类
   与商品明细不同粒度的度量，先在派生表里按目标粒度聚合好再 JOIN，绝不在明细 JOIN 后直接 ``SUM``
   订单级字段——那正是「SQL 跑通、结果非空、只有数值被放大」的静默错数。
"""

# 宽表名 → (业务域, DDL)
WIDE_TABLES = [
    ("dwd_order_item_wide", "明细宽表", """
CREATE TABLE dwd_order_item_wide (
  id                 INT AUTO_INCREMENT COMMENT '宽表主键',
  order_item_id      INT           NOT NULL COMMENT '订单明细ID',
  order_id           INT           NOT NULL COMMENT '订单ID',
  order_no           VARCHAR(32)   NOT NULL COMMENT '订单编号',
  order_date         DATE          NOT NULL COMMENT '下单日期',
  order_month        VARCHAR(7)    NOT NULL COMMENT '下单月份 YYYY-MM',
  order_status       VARCHAR(16)   NOT NULL COMMENT '订单状态',
  customer_id        INT           NOT NULL COMMENT '客户ID',
  customer_name      VARCHAR(64)   NOT NULL COMMENT '客户姓名',
  customer_level_name VARCHAR(16)  NOT NULL COMMENT '会员等级名称',
  gender             VARCHAR(4)    NOT NULL COMMENT '客户性别',
  age                INT           NOT NULL COMMENT '客户年龄',
  age_group          VARCHAR(16)   NOT NULL COMMENT '客户年龄段：18-25/26-35/36-45/46-55/55以上',
  customer_province  VARCHAR(16)   NOT NULL COMMENT '客户所在省份',
  customer_city      VARCHAR(16)   NOT NULL COMMENT '客户所在城市',
  is_first_order     TINYINT       NOT NULL COMMENT '是否客户首单：1是 0否',
  channel_name       VARCHAR(32)   NOT NULL COMMENT '销售渠道名称',
  channel_type       VARCHAR(16)   NOT NULL COMMENT '渠道类型：线上/线下',
  platform_name      VARCHAR(32)            COMMENT '第三方平台名称',
  store_name         VARCHAR(64)   NOT NULL COMMENT '门店名称',
  store_type         VARCHAR(16)   NOT NULL COMMENT '门店类型',
  big_region         VARCHAR(16)   NOT NULL COMMENT '所属大区',
  sales_rep_name     VARCHAR(32)            COMMENT '销售员姓名',
  sales_team_name    VARCHAR(32)            COMMENT '销售团队名称',
  warehouse_name     VARCHAR(32)   NOT NULL COMMENT '发货仓库名称',
  product_id         INT           NOT NULL COMMENT '商品SPU ID',
  product_name       VARCHAR(128)  NOT NULL COMMENT '商品名称',
  product_sku_id     INT           NOT NULL COMMENT 'SKU ID',
  sku_spec           VARCHAR(64)   NOT NULL COMMENT 'SKU规格',
  category_name      VARCHAR(64)   NOT NULL COMMENT '三级品类名称',
  category_l1_name   VARCHAR(64)   NOT NULL COMMENT '一级品类名称',
  brand_name         VARCHAR(64)   NOT NULL COMMENT '品牌名称',
  brand_level        VARCHAR(16)   NOT NULL COMMENT '品牌定位：高端/中端/大众',
  supplier_name      VARCHAR(128)  NOT NULL COMMENT '主供应商名称',
  quantity           INT           NOT NULL COMMENT '购买数量',
  unit_price         DECIMAL(12,2) NOT NULL COMMENT '成交单价',
  amount             DECIMAL(14,2) NOT NULL COMMENT '小计金额',
  discount_amount    DECIMAL(12,2) NOT NULL COMMENT '行优惠金额',
  cost_amount        DECIMAL(14,2) NOT NULL COMMENT '成本小计',
  gross_profit       DECIMAL(14,2) NOT NULL COMMENT '毛利额',
  gross_margin       DECIMAL(6,4)  NOT NULL COMMENT '毛利率(0-1)',
  campaign_name      VARCHAR(64)            COMMENT '归因营销活动名称',
  coupon_name        VARCHAR(64)            COMMENT '使用的优惠券名称',
  carrier_name       VARCHAR(32)            COMMENT '承运商名称',
  delivery_hours     DECIMAL(6,1)           COMMENT '配送时长(小时)',
  is_on_time         TINYINT                COMMENT '是否准时送达：1是 0否',
  is_refunded        TINYINT       NOT NULL COMMENT '是否发生售后（退货/退款）：1是 0否',
  is_reviewed        TINYINT       NOT NULL COMMENT '是否已评价：1是 0否',
  review_rating      DECIMAL(3,2)           COMMENT '商品评分(1-5)',
  PRIMARY KEY (id),
  KEY idx_w1_month (order_month),
  KEY idx_w1_region (big_region),
  KEY idx_w1_cat (category_l1_name),
  KEY idx_w1_brand (brand_name),
  KEY idx_w1_customer (customer_id),
  KEY idx_w1_product (product_id),
  KEY idx_w1_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【明细宽表DWD】订单明细全链路：订单+客户+会员等级+渠道+平台+门店+销售员+团队+仓库+商品+SKU+品类+品牌+供应商+履约+营销+评价，一行一个订单明细，多维分析无需再JOIN'
"""),
    ("dws_sales_monthly_wide", "销售汇总", """
CREATE TABLE dws_sales_monthly_wide (
  id                INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month        VARCHAR(7)    NOT NULL COMMENT '统计月份 YYYY-MM',
  big_region        VARCHAR(16)   NOT NULL COMMENT '所属大区',
  channel_name      VARCHAR(32)   NOT NULL COMMENT '销售渠道名称',
  channel_type      VARCHAR(16)   NOT NULL COMMENT '渠道类型：线上/线下',
  category_l1_name  VARCHAR(64)   NOT NULL COMMENT '一级品类名称',
  order_count       INT           NOT NULL COMMENT '订单数',
  customer_count    INT           NOT NULL COMMENT '下单客户数',
  new_customer_count INT          NOT NULL COMMENT '首单客户数（新客数）',
  item_qty          INT           NOT NULL COMMENT '销售件数',
  sales_amount      DECIMAL(16,2) NOT NULL COMMENT '销售金额（原价口径）',
  pay_amount        DECIMAL(16,2) NOT NULL COMMENT '实付金额（扣除优惠）',
  discount_amount   DECIMAL(16,2) NOT NULL COMMENT '优惠金额',
  gross_profit      DECIMAL(16,2) NOT NULL COMMENT '毛利额',
  gross_margin      DECIMAL(6,4)  NOT NULL COMMENT '毛利率(0-1)',
  avg_order_value   DECIMAL(12,2) NOT NULL COMMENT '客单价（销售金额/订单数）',
  avg_customer_value DECIMAL(12,2) NOT NULL COMMENT '人均消费（销售金额/客户数）',
  refund_amount     DECIMAL(16,2) NOT NULL COMMENT '退货金额',
  refund_rate       DECIMAL(6,4)  NOT NULL COMMENT '退货率（退货金额/销售金额）',
  avg_rating        DECIMAL(4,2)           COMMENT '平均商品评分',
  on_time_rate      DECIMAL(6,4)           COMMENT '物流准时率',
  PRIMARY KEY (id),
  KEY idx_w2_month (stat_month),
  KEY idx_w2_region (big_region),
  KEY idx_w2_channel (channel_name),
  KEY idx_w2_cat (category_l1_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【汇总宽表DWS】月度销售：按 月份×大区×渠道×一级品类 汇总销售额、订单数、客户数、毛利、退货率、准时率，可直接出趋势与占比'
"""),
    ("dws_product_sales_wide", "销售汇总", """
CREATE TABLE dws_product_sales_wide (
  id               INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month       VARCHAR(7)    NOT NULL COMMENT '统计月份 YYYY-MM',
  product_id       INT           NOT NULL COMMENT '商品SPU ID',
  product_name     VARCHAR(128)  NOT NULL COMMENT '商品名称',
  product_type     VARCHAR(16)   NOT NULL COMMENT '商品类型：自营/POP第三方/预售/赠品',
  category_name    VARCHAR(64)   NOT NULL COMMENT '三级品类名称',
  category_l1_name VARCHAR(64)   NOT NULL COMMENT '一级品类名称',
  brand_name       VARCHAR(64)   NOT NULL COMMENT '品牌名称',
  brand_level      VARCHAR(16)   NOT NULL COMMENT '品牌定位',
  supplier_name    VARCHAR(128)  NOT NULL COMMENT '主供应商名称',
  list_price       DECIMAL(12,2) NOT NULL COMMENT '商品标价',
  order_count      INT           NOT NULL COMMENT '订单数',
  buyer_count      INT           NOT NULL COMMENT '购买人数',
  sales_qty        INT           NOT NULL COMMENT '销售数量',
  sales_amount     DECIMAL(16,2) NOT NULL COMMENT '销售金额',
  gross_profit     DECIMAL(16,2) NOT NULL COMMENT '毛利额',
  gross_margin     DECIMAL(6,4)  NOT NULL COMMENT '毛利率(0-1)',
  avg_price        DECIMAL(12,2) NOT NULL COMMENT '平均成交单价',
  refund_qty       INT           NOT NULL COMMENT '退货数量',
  refund_rate      DECIMAL(6,4)  NOT NULL COMMENT '退货率（退货数量/销售数量）',
  review_count     INT           NOT NULL COMMENT '评价数',
  avg_rating       DECIMAL(4,2)           COMMENT '平均评分',
  positive_rate    DECIMAL(6,4)           COMMENT '好评率',
  PRIMARY KEY (id),
  KEY idx_w3_month (stat_month),
  KEY idx_w3_product (product_id),
  KEY idx_w3_cat (category_l1_name),
  KEY idx_w3_brand (brand_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【汇总宽表DWS】商品月度销售：按 月份×商品 汇总销量、销售额、毛利、退货率、好评率，畅销与滞销商品分析'
"""),
    ("dws_customer_profile_wide", "客户画像", """
CREATE TABLE dws_customer_profile_wide (
  id               INT AUTO_INCREMENT COMMENT '宽表主键',
  customer_id      INT           NOT NULL COMMENT '客户ID',
  customer_no      VARCHAR(32)   NOT NULL COMMENT '客户编号',
  customer_name    VARCHAR(64)   NOT NULL COMMENT '客户姓名',
  gender           VARCHAR(4)    NOT NULL COMMENT '性别',
  age              INT           NOT NULL COMMENT '年龄',
  age_group        VARCHAR(16)   NOT NULL COMMENT '年龄段',
  level_code       VARCHAR(16)   NOT NULL COMMENT '会员等级编码',
  level_name       VARCHAR(16)   NOT NULL COMMENT '会员等级名称',
  province         VARCHAR(16)   NOT NULL COMMENT '所在省份',
  city             VARCHAR(16)   NOT NULL COMMENT '所在城市',
  big_region       VARCHAR(16)   NOT NULL COMMENT '所属大区',
  city_tier        VARCHAR(8)             COMMENT '城市线级',
  register_date    DATE          NOT NULL COMMENT '注册日期',
  register_channel VARCHAR(16)   NOT NULL COMMENT '注册来源渠道',
  tenure_days      INT           NOT NULL COMMENT '会员天数（注册至2026-09-01）',
  order_count      INT           NOT NULL COMMENT '累计订单数',
  total_spend      DECIMAL(14,2) NOT NULL COMMENT '累计消费金额',
  total_qty        INT           NOT NULL COMMENT '累计购买件数',
  avg_order_value  DECIMAL(12,2) NOT NULL COMMENT '客单价',
  last_order_date  DATE                   COMMENT '最近下单日期',
  recency_days     INT                    COMMENT '距最近下单天数（R值）',
  total_points     INT           NOT NULL COMMENT '当前积分',
  tag_list         VARCHAR(500)           COMMENT '客户标签列表（顿号分隔）',
  tag_count        INT           NOT NULL COMMENT '标签数量',
  is_active        TINYINT       NOT NULL COMMENT '是否活跃：1是 0否',
  refund_count     INT           NOT NULL COMMENT '退款次数',
  refund_amount    DECIMAL(14,2) NOT NULL COMMENT '累计退款金额',
  after_sale_count INT           NOT NULL COMMENT '售后申请次数',
  review_count     INT           NOT NULL COMMENT '评价数',
  avg_rating       DECIMAL(4,2)           COMMENT '客户给出的平均评分',
  cart_abandon_count INT         NOT NULL COMMENT '购物车弃单次数',
  rfm_segment      VARCHAR(24)   NOT NULL COMMENT 'RFM分层：重要价值/重要保持/重要发展/重要挽留/一般/流失',
  PRIMARY KEY (id),
  UNIQUE KEY uk_w4_customer (customer_id),
  KEY idx_w4_level (level_name),
  KEY idx_w4_region (big_region),
  KEY idx_w4_rfm (rfm_segment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【画像宽表DWS】客户360画像：人口属性+会员等级+区域+RFM分层+累计消费+复购+积分+标签+退款售后+评价+弃单，一行一个客户'
"""),
    ("dws_store_perf_wide", "门店经营", """
CREATE TABLE dws_store_perf_wide (
  id               INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month       VARCHAR(7)    NOT NULL COMMENT '统计月份 YYYY-MM',
  store_id         INT           NOT NULL COMMENT '门店ID',
  store_name       VARCHAR(64)   NOT NULL COMMENT '门店名称',
  store_type       VARCHAR(16)   NOT NULL COMMENT '门店类型',
  channel_name     VARCHAR(32)   NOT NULL COMMENT '销售渠道名称',
  platform_name    VARCHAR(32)            COMMENT '第三方平台名称',
  province         VARCHAR(16)   NOT NULL COMMENT '所在省份',
  city             VARCHAR(16)   NOT NULL COMMENT '所在城市',
  big_region       VARCHAR(16)   NOT NULL COMMENT '所属大区',
  category_l1_name VARCHAR(64)   NOT NULL COMMENT '一级品类名称',
  store_age_months INT           NOT NULL COMMENT '门店经营月数',
  staff_count      INT           NOT NULL COMMENT '门店员工数',
  area_sqm         DECIMAL(8,2)  NOT NULL COMMENT '营业面积(平方米)',
  order_count      INT           NOT NULL COMMENT '订单数',
  customer_count   INT           NOT NULL COMMENT '下单客户数',
  sales_amount     DECIMAL(16,2) NOT NULL COMMENT '销售金额',
  gross_profit     DECIMAL(16,2) NOT NULL COMMENT '毛利额',
  gross_margin     DECIMAL(6,4)  NOT NULL COMMENT '毛利率(0-1)',
  sales_per_sqm    DECIMAL(12,2) NOT NULL COMMENT '坪效（销售金额/营业面积）',
  sales_per_staff  DECIMAL(12,2) NOT NULL COMMENT '人效（销售金额/员工数）',
  refund_amount    DECIMAL(16,2) NOT NULL COMMENT '退货金额',
  refund_rate      DECIMAL(6,4)  NOT NULL COMMENT '退货率',
  avg_delivery_hours DECIMAL(6,1)         COMMENT '平均配送时长(小时)',
  PRIMARY KEY (id),
  KEY idx_w5_month (stat_month),
  KEY idx_w5_store (store_id),
  KEY idx_w5_region (big_region),
  KEY idx_w5_cat (category_l1_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【汇总宽表DWS】门店月度经营：按 月份×门店×一级品类 汇总销售额、毛利、坪效、人效、退货率，门店排名与效率分析'
"""),
    ("dws_channel_roi_wide", "营销效果", """
CREATE TABLE dws_channel_roi_wide (
  id                INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month        VARCHAR(7)    NOT NULL COMMENT '统计月份 YYYY-MM',
  platform_name     VARCHAR(32)   NOT NULL COMMENT '投放平台名称',
  ad_position       VARCHAR(32)   NOT NULL COMMENT '广告位',
  channel_name      VARCHAR(32)   NOT NULL COMMENT '关联销售渠道名称',
  campaign_type     VARCHAR(16)   NOT NULL COMMENT '活动类型',
  campaign_count    INT           NOT NULL COMMENT '投放活动数',
  spend             DECIMAL(16,2) NOT NULL COMMENT '投放花费',
  impressions       BIGINT        NOT NULL COMMENT '曝光量',
  clicks            BIGINT        NOT NULL COMMENT '点击量',
  ctr               DECIMAL(8,4)  NOT NULL COMMENT '点击率',
  avg_cpc           DECIMAL(10,4) NOT NULL COMMENT '平均单次点击成本',
  cpm               DECIMAL(10,2) NOT NULL COMMENT '千次曝光成本',
  conversions       INT           NOT NULL COMMENT '转化订单数',
  conversion_amount DECIMAL(16,2) NOT NULL COMMENT '转化金额',
  roi               DECIMAL(8,2)  NOT NULL COMMENT '投资回报率（转化金额/花费）',
  cvr               DECIMAL(8,4)  NOT NULL COMMENT '点击转化率（转化数/点击量）',
  budget_used_rate  DECIMAL(6,4)  NOT NULL COMMENT '预算使用率',
  PRIMARY KEY (id),
  KEY idx_w6_month (stat_month),
  KEY idx_w6_platform (platform_name),
  KEY idx_w6_pos (ad_position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【汇总宽表DWS】渠道营销ROI：按 月份×平台×广告位×渠道 汇总花费、曝光、点击、CTR、CPC、转化、ROI，投放效率分析'
"""),
    ("dws_inventory_turnover_wide", "库存周转", """
CREATE TABLE dws_inventory_turnover_wide (
  id               INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month       VARCHAR(7)    NOT NULL COMMENT '统计月份 YYYY-MM',
  product_sku_id   INT           NOT NULL COMMENT 'SKU ID',
  sku_code         VARCHAR(40)   NOT NULL COMMENT 'SKU编码',
  sku_spec         VARCHAR(64)   NOT NULL COMMENT 'SKU规格',
  product_name     VARCHAR(128)  NOT NULL COMMENT '商品名称',
  category_name    VARCHAR(64)   NOT NULL COMMENT '三级品类名称',
  category_l1_name VARCHAR(64)   NOT NULL COMMENT '一级品类名称',
  brand_name       VARCHAR(64)   NOT NULL COMMENT '品牌名称',
  supplier_name    VARCHAR(128)  NOT NULL COMMENT '主供应商名称',
  warehouse_count  INT           NOT NULL COMMENT '涉及仓库数',
  begin_qty        INT           NOT NULL COMMENT '期初库存数量',
  end_qty          INT           NOT NULL COMMENT '期末库存数量',
  avg_qty          INT           NOT NULL COMMENT '平均库存数量',
  inbound_qty      INT           NOT NULL COMMENT '本月入库数量',
  outbound_qty     INT           NOT NULL COMMENT '本月出库数量',
  stock_value      DECIMAL(16,2) NOT NULL COMMENT '期末库存金额',
  turnover_days    INT           NOT NULL COMMENT '库存周转天数',
  turnover_rate    DECIMAL(8,4)  NOT NULL COMMENT '库存周转率（出库量/平均库存）',
  stock_status     VARCHAR(8)    NOT NULL COMMENT '库存状态：缺货/预警/正常/积压',
  is_stockout      TINYINT       NOT NULL COMMENT '是否发生过缺货：1是 0否',
  PRIMARY KEY (id),
  KEY idx_w7_month (stat_month),
  KEY idx_w7_sku (product_sku_id),
  KEY idx_w7_cat (category_l1_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【汇总宽表DWS】库存周转：按 月份×SKU 汇总期初期末库存、出入库量、库存金额、周转天数与周转率，呆滞与缺货分析'
"""),
    ("dws_after_sale_wide", "售后质量", """
CREATE TABLE dws_after_sale_wide (
  id                INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month        VARCHAR(7)    NOT NULL COMMENT '统计月份 YYYY-MM',
  category_l1_name  VARCHAR(64)   NOT NULL COMMENT '一级品类名称',
  brand_name        VARCHAR(64)   NOT NULL COMMENT '品牌名称',
  brand_level       VARCHAR(16)   NOT NULL COMMENT '品牌定位',
  sales_qty         INT           NOT NULL COMMENT '销售数量',
  sales_amount      DECIMAL(16,2) NOT NULL COMMENT '销售金额',
  order_count       INT           NOT NULL COMMENT '订单数',
  after_sale_count  INT           NOT NULL COMMENT '售后单数',
  after_sale_qty    INT           NOT NULL COMMENT '售后数量',
  refund_amount     DECIMAL(16,2) NOT NULL COMMENT '退款金额',
  return_rate       DECIMAL(6,4)  NOT NULL COMMENT '退货率（售后数量/销售数量）',
  quality_qty       INT           NOT NULL COMMENT '商品质量责任数量',
  logistics_qty     INT           NOT NULL COMMENT '物流损坏责任数量',
  subjective_qty    INT           NOT NULL COMMENT '客户主观责任数量',
  desc_qty          INT           NOT NULL COMMENT '描述不符责任数量',
  quality_defect_rate DECIMAL(6,4) NOT NULL COMMENT '质量缺陷率（质量责任数/销售数量）',
  avg_handle_hours  DECIMAL(6,1)  NOT NULL COMMENT '平均售后处理时长(小时)',
  avg_satisfaction  DECIMAL(4,2)           COMMENT '售后平均满意度',
  review_count      INT           NOT NULL COMMENT '评价数',
  avg_rating        DECIMAL(4,2)           COMMENT '平均评分',
  negative_rate     DECIMAL(6,4)           COMMENT '差评率（非好评数/评价数）',
  PRIMARY KEY (id),
  KEY idx_w8_month (stat_month),
  KEY idx_w8_cat (category_l1_name),
  KEY idx_w8_brand (brand_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【汇总宽表DWS】售后质量：按 月份×一级品类×品牌 汇总退货率、责任归属分布、质量缺陷率、售后时效、差评率，品质归因分析'
"""),
    ("dws_traffic_funnel_wide", "流量转化", """
CREATE TABLE dws_traffic_funnel_wide (
  id               INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month       VARCHAR(7)    NOT NULL COMMENT '统计月份 YYYY-MM',
  channel_name     VARCHAR(32)   NOT NULL COMMENT '销售渠道名称',
  channel_type     VARCHAR(16)   NOT NULL COMMENT '渠道类型：线上/线下',
  device_type      VARCHAR(16)   NOT NULL COMMENT '设备类型：iOS/Android/PC/H5/小程序',
  traffic_source   VARCHAR(16)   NOT NULL COMMENT '流量来源：自然流量/付费广告/活动引流/直接访问/社交分享',
  pv               BIGINT        NOT NULL COMMENT '浏览量PV',
  uv               INT           NOT NULL COMMENT '独立访客数UV（去重客户数）',
  visit_count      INT           NOT NULL COMMENT '访问记录数',
  bounce_count     INT           NOT NULL COMMENT '跳出次数',
  bounce_rate      DECIMAL(6,4)  NOT NULL COMMENT '跳出率',
  avg_stay_seconds INT           NOT NULL COMMENT '平均停留时长(秒)',
  detail_pv        INT           NOT NULL COMMENT '商品详情页浏览量',
  cart_count       INT           NOT NULL COMMENT '加购次数',
  cart_rate        DECIMAL(6,4)  NOT NULL COMMENT '加购率（加购数/详情页浏览数）',
  order_count      INT           NOT NULL COMMENT '下单次数',
  order_rate       DECIMAL(6,4)  NOT NULL COMMENT '下单转化率（下单数/UV）',
  pay_count        INT           NOT NULL COMMENT '支付次数',
  pay_rate         DECIMAL(6,4)  NOT NULL COMMENT '支付转化率（支付数/下单数）',
  sales_amount     DECIMAL(16,2) NOT NULL COMMENT '成交金额',
  overall_cvr      DECIMAL(6,4)  NOT NULL COMMENT '整体转化率（支付数/UV）',
  PRIMARY KEY (id),
  KEY idx_w9_month (stat_month),
  KEY idx_w9_channel (channel_name),
  KEY idx_w9_device (device_type),
  KEY idx_w9_source (traffic_source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【漏斗宽表DWS】流量转化：按 月份×渠道×设备×流量来源 汇总PV/UV/跳出率/加购率/下单率/支付率/成交额，转化漏斗分析'
"""),
    ("dws_supplier_delivery_wide", "供应商绩效", """
CREATE TABLE dws_supplier_delivery_wide (
  id               INT AUTO_INCREMENT COMMENT '宽表主键',
  stat_month       VARCHAR(7)    NOT NULL COMMENT '采购月份 YYYY-MM',
  supplier_id      INT           NOT NULL COMMENT '供应商ID',
  supplier_name    VARCHAR(128)  NOT NULL COMMENT '供应商名称',
  supplier_level   VARCHAR(16)   NOT NULL COMMENT '供应商等级：战略/核心/普通/淘汰',
  supplier_province VARCHAR(16)  NOT NULL COMMENT '供应商所在省份',
  credit_score     DECIMAL(5,2)  NOT NULL COMMENT '供应商信用评分',
  category_l1_name VARCHAR(64)   NOT NULL COMMENT '采购一级品类名称',
  po_count         INT           NOT NULL COMMENT '采购单数',
  sku_count        INT           NOT NULL COMMENT '采购SKU数',
  po_qty           INT           NOT NULL COMMENT '采购数量',
  po_amount        DECIMAL(16,2) NOT NULL COMMENT '采购金额',
  received_qty     INT           NOT NULL COMMENT '到货数量',
  rejected_qty     INT           NOT NULL COMMENT '验收不合格数量',
  pass_rate        DECIMAL(6,4)  NOT NULL COMMENT '验收合格率',
  fulfill_rate     DECIMAL(6,4)  NOT NULL COMMENT '到货满足率（到货量/采购量）',
  on_time_count    INT           NOT NULL COMMENT '按时到货采购单数',
  on_time_rate     DECIMAL(6,4)  NOT NULL COMMENT '到货及时率',
  avg_lead_time_days INT         NOT NULL COMMENT '平均供货周期(天)',
  PRIMARY KEY (id),
  KEY idx_w10_month (stat_month),
  KEY idx_w10_supplier (supplier_id),
  KEY idx_w10_cat (category_l1_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='【汇总宽表DWS】供应商交付绩效：按 月份×供应商×一级品类 汇总采购额、到货率、验收合格率、到货及时率、供货周期，供应商考核'
"""),
]

# 宽表名 → 派生 SQL（按此顺序执行：W1 明细宽表先建，其余汇总表多从它再聚合）
WIDE_POPULATE = {
    # ---------- W1 订单明细全链路：14 张基础表 JOIN，一行一个订单明细 ----------
    "dwd_order_item_wide": """
INSERT INTO dwd_order_item_wide (
  order_item_id, order_id, order_no, order_date, order_month, order_status,
  customer_id, customer_name, customer_level_name, gender, age, age_group,
  customer_province, customer_city, is_first_order,
  channel_name, channel_type, platform_name, store_name, store_type, big_region,
  sales_rep_name, sales_team_name, warehouse_name,
  product_id, product_name, product_sku_id, sku_spec, category_name, category_l1_name,
  brand_name, brand_level, supplier_name,
  quantity, unit_price, amount, discount_amount, cost_amount, gross_profit, gross_margin,
  campaign_name, coupon_name, carrier_name, delivery_hours, is_on_time,
  is_refunded, is_reviewed, review_rating)
SELECT oi.id, o.id, o.order_no, o.order_date, o.order_month, o.order_status,
       cu.id, cu.customer_name, cl.level_name, cu.gender, cu.age,
       CASE WHEN cu.age <= 25 THEN '18-25' WHEN cu.age <= 35 THEN '26-35'
            WHEN cu.age <= 45 THEN '36-45' WHEN cu.age <= 55 THEN '46-55'
            ELSE '55以上' END,
       cu.province, cu.city, o.is_first_order,
       ch.channel_name, ch.channel_type, pf.platform_name, st.store_name, st.store_type, o.region,
       sr.rep_name, tm.team_name, wh.warehouse_name,
       p.id, p.product_name, sk.id, sk.sku_spec, c3.category_name, c1.category_name,
       b.brand_name, b.brand_level, sup.supplier_name,
       oi.quantity, oi.unit_price, oi.amount, oi.discount_amount, oi.cost_amount,
       oi.gross_profit, oi.gross_margin,
       cp.campaign_name, ct.coupon_name, ca.carrier_name, sh.delivery_hours, sh.is_on_time,
       oi.is_refunded, oi.is_reviewed, pr.rating
FROM order_item oi
JOIN orders o             ON o.id = oi.order_id
JOIN customer cu          ON cu.id = o.customer_id
JOIN customer_level cl    ON cl.level_code = cu.level_code
JOIN channel ch           ON ch.id = o.channel_id
JOIN store st             ON st.id = o.store_id
LEFT JOIN platform pf     ON pf.id = st.platform_id
LEFT JOIN sales_rep sr    ON sr.id = o.sales_rep_id
LEFT JOIN sales_team tm   ON tm.id = sr.sales_team_id
JOIN warehouse wh         ON wh.id = o.warehouse_id
JOIN product p            ON p.id = oi.product_id
JOIN product_sku sk       ON sk.id = oi.product_sku_id
JOIN category c3          ON c3.id = p.category_id
JOIN category c1          ON c1.id = CAST(SUBSTRING_INDEX(c3.path, '/', 1) AS UNSIGNED)
JOIN brand b              ON b.id = p.brand_id
JOIN supplier sup         ON sup.id = p.supplier_id
LEFT JOIN campaign cp     ON cp.id = o.campaign_id
LEFT JOIN coupon_grant cg ON cg.id = o.coupon_grant_id
LEFT JOIN coupon_template ct ON ct.id = cg.coupon_template_id
LEFT JOIN shipment sh     ON sh.order_id = o.id
LEFT JOIN carrier ca      ON ca.carrier_code = sh.carrier_code
LEFT JOIN product_review pr ON pr.order_item_id = oi.id
""",
    # ---------- W2 月度销售：从 W1 再聚合，订单级度量一律 COUNT(DISTINCT) 防 fan-out ----------
    "dws_sales_monthly_wide": """
INSERT INTO dws_sales_monthly_wide (
  stat_month, big_region, channel_name, channel_type, category_l1_name,
  order_count, customer_count, new_customer_count, item_qty, sales_amount, pay_amount,
  discount_amount, gross_profit, gross_margin, avg_order_value, avg_customer_value,
  refund_amount, refund_rate, avg_rating, on_time_rate)
SELECT order_month, big_region, channel_name, channel_type, category_l1_name,
       COUNT(DISTINCT order_id),
       COUNT(DISTINCT customer_id),
       COUNT(DISTINCT CASE WHEN is_first_order = 1 THEN order_id END),
       SUM(quantity),
       ROUND(SUM(amount), 2),
       ROUND(SUM(amount - discount_amount), 2),
       ROUND(SUM(discount_amount), 2),
       ROUND(SUM(gross_profit), 2),
       ROUND(SUM(gross_profit) / NULLIF(SUM(amount), 0), 4),
       ROUND(SUM(amount) / NULLIF(COUNT(DISTINCT order_id), 0), 2),
       ROUND(SUM(amount) / NULLIF(COUNT(DISTINCT customer_id), 0), 2),
       ROUND(SUM(CASE WHEN is_refunded = 1 THEN amount ELSE 0 END), 2),
       ROUND(SUM(CASE WHEN is_refunded = 1 THEN amount ELSE 0 END) / NULLIF(SUM(amount), 0), 4),
       ROUND(AVG(review_rating), 2),
       ROUND(AVG(is_on_time), 4)
FROM dwd_order_item_wide
GROUP BY order_month, big_region, channel_name, channel_type, category_l1_name
""",
    # ---------- W3 商品月度销售：从 W1 聚合 ----------
    "dws_product_sales_wide": """
INSERT INTO dws_product_sales_wide (
  stat_month, product_id, product_name, product_type, category_name, category_l1_name,
  brand_name, brand_level, supplier_name, list_price,
  order_count, buyer_count, sales_qty, sales_amount, gross_profit, gross_margin,
  avg_price, refund_qty, refund_rate, review_count, avg_rating, positive_rate)
SELECT w.order_month, w.product_id, w.product_name, p.product_type, w.category_name,
       w.category_l1_name, w.brand_name, w.brand_level, w.supplier_name, p.list_price,
       COUNT(DISTINCT w.order_id),
       COUNT(DISTINCT w.customer_id),
       SUM(w.quantity),
       ROUND(SUM(w.amount), 2),
       ROUND(SUM(w.gross_profit), 2),
       ROUND(SUM(w.gross_profit) / NULLIF(SUM(w.amount), 0), 4),
       ROUND(SUM(w.amount) / NULLIF(SUM(w.quantity), 0), 2),
       SUM(w.is_refunded),
       ROUND(SUM(CASE WHEN w.is_refunded = 1 THEN w.quantity ELSE 0 END)
             / NULLIF(SUM(w.quantity), 0), 4),
       COUNT(w.review_rating),
       ROUND(AVG(w.review_rating), 2),
       ROUND(SUM(CASE WHEN w.review_rating >= 4 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(w.review_rating), 0), 4)
FROM dwd_order_item_wide w
JOIN product p ON p.id = w.product_id
GROUP BY w.order_month, w.product_id, w.product_name, p.product_type, w.category_name,
         w.category_l1_name, w.brand_name, w.brand_level, w.supplier_name, p.list_price
""",
    # ---------- W4 客户画像：各来源先按 customer_id 聚合成派生表再 JOIN，粒度一致故无 fan-out ----------
    "dws_customer_profile_wide": """
INSERT INTO dws_customer_profile_wide (
  customer_id, customer_no, customer_name, gender, age, age_group, level_code, level_name,
  province, city, big_region, city_tier, register_date, register_channel, tenure_days,
  order_count, total_spend, total_qty, avg_order_value, last_order_date, recency_days,
  total_points, tag_list, tag_count, is_active,
  refund_count, refund_amount, after_sale_count, review_count, avg_rating,
  cart_abandon_count, rfm_segment)
SELECT cu.id, cu.customer_no, cu.customer_name, cu.gender, cu.age,
       CASE WHEN cu.age <= 25 THEN '18-25' WHEN cu.age <= 35 THEN '26-35'
            WHEN cu.age <= 45 THEN '36-45' WHEN cu.age <= 55 THEN '46-55'
            ELSE '55以上' END,
       cu.level_code, cl.level_name, cu.province, cu.city, rg.big_region, rg.city_tier,
       cu.register_date, cu.register_channel,
       DATEDIFF('2026-09-01', cu.register_date),
       IFNULL(oa.order_count, 0),
       IFNULL(oa.total_spend, 0),
       IFNULL(oa.total_qty, 0),
       ROUND(IFNULL(oa.total_spend, 0) / NULLIF(oa.order_count, 0), 2),
       oa.last_order_date,
       CASE WHEN oa.last_order_date IS NULL THEN NULL
            ELSE DATEDIFF('2026-09-01', oa.last_order_date) END,
       cu.total_points, tg.tag_list, IFNULL(tg.tag_count, 0), cu.is_active,
       IFNULL(rf.refund_count, 0), IFNULL(rf.refund_amount, 0),
       IFNULL(af.after_sale_count, 0),
       IFNULL(rv.review_count, 0), rv.avg_rating,
       IFNULL(ct.cart_abandon_count, 0),
       CASE
         WHEN IFNULL(oa.order_count, 0) >= 8
              AND IFNULL(oa.total_spend, 0) >= 20000
              AND oa.last_order_date IS NOT NULL
              AND DATEDIFF('2026-09-01', oa.last_order_date) <= 90 THEN '重要价值客户'
         WHEN IFNULL(oa.order_count, 0) >= 8
              AND oa.last_order_date IS NOT NULL
              AND DATEDIFF('2026-09-01', oa.last_order_date) > 90 THEN '重要保持客户'
         WHEN IFNULL(oa.order_count, 0) < 8
              AND IFNULL(oa.total_spend, 0) >= 20000
              AND oa.last_order_date IS NOT NULL
              AND DATEDIFF('2026-09-01', oa.last_order_date) <= 90 THEN '重要发展客户'
         WHEN IFNULL(oa.order_count, 0) < 8
              AND IFNULL(oa.total_spend, 0) >= 20000 THEN '重要挽留客户'
         WHEN oa.last_order_date IS NULL
              OR DATEDIFF('2026-09-01', oa.last_order_date) > 180 THEN '流失客户'
         ELSE '一般客户'
       END
FROM customer cu
JOIN customer_level cl ON cl.level_code = cu.level_code
JOIN region rg         ON rg.id = cu.region_id
LEFT JOIN (SELECT customer_id, COUNT(*) AS order_count, ROUND(SUM(amount), 2) AS total_spend,
                  SUM(quantity) AS total_qty, MAX(order_date) AS last_order_date
           FROM orders WHERE is_paid = 1 GROUP BY customer_id) oa ON oa.customer_id = cu.id
LEFT JOIN (SELECT customer_id, COUNT(*) AS refund_count, ROUND(SUM(refund_amount), 2) AS refund_amount
           FROM refund WHERE refund_status = '已退款' GROUP BY customer_id) rf ON rf.customer_id = cu.id
LEFT JOIN (SELECT customer_id, COUNT(*) AS after_sale_count
           FROM after_sale_order GROUP BY customer_id) af ON af.customer_id = cu.id
LEFT JOIN (SELECT customer_id, COUNT(*) AS review_count, ROUND(AVG(rating), 2) AS avg_rating
           FROM product_review GROUP BY customer_id) rv ON rv.customer_id = cu.id
LEFT JOIN (SELECT customer_id, SUM(CASE WHEN is_ordered = 0 THEN 1 ELSE 0 END) AS cart_abandon_count
           FROM shopping_cart GROUP BY customer_id) ct ON ct.customer_id = cu.id
LEFT JOIN (SELECT r.customer_id, GROUP_CONCAT(t.tag_name ORDER BY t.id SEPARATOR '、') AS tag_list,
                  COUNT(*) AS tag_count
           FROM customer_tag_rel r JOIN customer_tag t ON t.id = r.customer_tag_id
           GROUP BY r.customer_id) tg ON tg.customer_id = cu.id
""",
    # ---------- W5 门店经营：从 W1 聚合到 月×门店×品类；坪效人效需要门店属性，故 JOIN store ----------
    "dws_store_perf_wide": """
INSERT INTO dws_store_perf_wide (
  stat_month, store_id, store_name, store_type, channel_name, platform_name,
  province, city, big_region, category_l1_name, store_age_months, staff_count, area_sqm,
  order_count, customer_count, sales_amount, gross_profit, gross_margin,
  sales_per_sqm, sales_per_staff, refund_amount, refund_rate, avg_delivery_hours)
SELECT w.order_month, st.id, st.store_name, st.store_type, st2.channel_name, pf.platform_name,
       st.province, st.city, w.big_region, w.category_l1_name,
       TIMESTAMPDIFF(MONTH, st.open_date, CONCAT(w.order_month, '-01')),
       st.staff_count, st.area_sqm,
       COUNT(DISTINCT w.order_id),
       COUNT(DISTINCT w.customer_id),
       ROUND(SUM(w.amount), 2),
       ROUND(SUM(w.gross_profit), 2),
       ROUND(SUM(w.gross_profit) / NULLIF(SUM(w.amount), 0), 4),
       ROUND(SUM(w.amount) / NULLIF(st.area_sqm, 0), 2),
       ROUND(SUM(w.amount) / NULLIF(st.staff_count, 0), 2),
       ROUND(SUM(CASE WHEN w.is_refunded = 1 THEN w.amount ELSE 0 END), 2),
       ROUND(SUM(CASE WHEN w.is_refunded = 1 THEN w.amount ELSE 0 END) / NULLIF(SUM(w.amount), 0), 4),
       ROUND(AVG(w.delivery_hours), 1)
FROM dwd_order_item_wide w
JOIN store st        ON st.store_name = w.store_name
JOIN channel st2     ON st2.id = st.channel_id
LEFT JOIN platform pf ON pf.id = st.platform_id
GROUP BY w.order_month, st.id, st.store_name, st.store_type, st2.channel_name, pf.platform_name,
         st.province, st.city, w.big_region, w.category_l1_name, st.open_date,
         st.staff_count, st.area_sqm
""",
    # ---------- W6 渠道营销ROI：广告投放 + 活动 + 平台 + 渠道 ----------
    "dws_channel_roi_wide": """
INSERT INTO dws_channel_roi_wide (
  stat_month, platform_name, ad_position, channel_name, campaign_type, campaign_count,
  spend, impressions, clicks, ctr, avg_cpc, cpm, conversions, conversion_amount,
  roi, cvr, budget_used_rate)
SELECT ap.placement_month, pf.platform_name, ap.ad_position, ch.channel_name, cp.campaign_type,
       COUNT(DISTINCT cp.id),
       ROUND(SUM(ap.spend), 2),
       SUM(ap.impressions),
       SUM(ap.clicks),
       ROUND(SUM(ap.clicks) / NULLIF(SUM(ap.impressions), 0), 4),
       ROUND(SUM(ap.spend) / NULLIF(SUM(ap.clicks), 0), 4),
       ROUND(SUM(ap.spend) * 1000 / NULLIF(SUM(ap.impressions), 0), 2),
       SUM(ap.conversions),
       ROUND(SUM(ap.conversion_amount), 2),
       ROUND(SUM(ap.conversion_amount) / NULLIF(SUM(ap.spend), 0), 2),
       ROUND(SUM(ap.conversions) / NULLIF(SUM(ap.clicks), 0), 4),
       ROUND(SUM(ap.spend) / NULLIF(SUM(cp.budget), 0), 4)
FROM ad_placement ap
JOIN campaign cp    ON cp.id = ap.campaign_id
JOIN platform pf    ON pf.id = ap.platform_id
JOIN channel ch     ON ch.id = cp.channel_id
GROUP BY ap.placement_month, pf.platform_name, ap.ad_position, ch.channel_name, cp.campaign_type
""",
    # ---------- W7 库存周转：月末快照 + 出入库流水，两者各自按月×SKU 聚合后再 JOIN ----------
    "dws_inventory_turnover_wide": """
INSERT INTO dws_inventory_turnover_wide (
  stat_month, product_sku_id, sku_code, sku_spec, product_name, category_name,
  category_l1_name, brand_name, supplier_name, warehouse_count,
  begin_qty, end_qty, avg_qty, inbound_qty, outbound_qty, stock_value,
  turnover_days, turnover_rate, stock_status, is_stockout)
SELECT sn.snapshot_month, sk.id, sk.sku_code, sk.sku_spec, p.product_name, c3.category_name,
       c1.category_name, b.brand_name, sup.supplier_name, sn.warehouse_count,
       sn.begin_qty, sn.end_qty, sn.avg_qty,
       IFNULL(tx.inbound_qty, 0), IFNULL(tx.outbound_qty, 0), sn.stock_value,
       sn.turnover_days,
       ROUND(IFNULL(tx.outbound_qty, 0) / NULLIF(sn.avg_qty, 0), 4),
       sn.stock_status, sn.is_stockout
FROM (SELECT snapshot_month, product_sku_id,
             COUNT(DISTINCT warehouse_id) AS warehouse_count,
             SUM(CASE WHEN snapshot_date = min_d THEN on_hand_qty ELSE 0 END) AS begin_qty,
             SUM(CASE WHEN snapshot_date = max_d THEN on_hand_qty ELSE 0 END) AS end_qty,
             CAST(AVG(on_hand_qty) AS SIGNED) AS avg_qty,
             ROUND(SUM(CASE WHEN snapshot_date = max_d THEN stock_value ELSE 0 END), 2) AS stock_value,
             CAST(AVG(turnover_days) AS SIGNED) AS turnover_days,
             MAX(CASE WHEN snapshot_date = max_d THEN stock_status END) AS stock_status,
             MAX(CASE WHEN stock_status = '缺货' THEN 1 ELSE 0 END) AS is_stockout
      FROM (SELECT s.*, MIN(snapshot_date) OVER (PARTITION BY snapshot_month, product_sku_id) AS min_d,
                   MAX(snapshot_date) OVER (PARTITION BY snapshot_month, product_sku_id) AS max_d
            FROM inventory_snapshot s) x
      GROUP BY snapshot_month, product_sku_id) sn
JOIN product_sku sk  ON sk.id = sn.product_sku_id
JOIN product p       ON p.id = sk.product_id
JOIN category c3     ON c3.id = p.category_id
JOIN category c1     ON c1.id = CAST(SUBSTRING_INDEX(c3.path, '/', 1) AS UNSIGNED)
JOIN brand b         ON b.id = p.brand_id
JOIN supplier sup    ON sup.id = p.supplier_id
LEFT JOIN (SELECT DATE_FORMAT(trans_date, '%Y-%m') AS m, product_sku_id,
                  SUM(CASE WHEN quantity > 0 THEN quantity ELSE 0 END) AS inbound_qty,
                  SUM(CASE WHEN quantity < 0 THEN -quantity ELSE 0 END) AS outbound_qty
           FROM inventory_transaction GROUP BY DATE_FORMAT(trans_date, '%Y-%m'), product_sku_id) tx
       ON tx.m = sn.snapshot_month AND tx.product_sku_id = sn.product_sku_id
""",
    # ---------- W8 售后质量：售后明细按责任归属聚合 + W1 销售口径 + 评价口径，三份同粒度派生表 JOIN ----------
    "dws_after_sale_wide": """
INSERT INTO dws_after_sale_wide (
  stat_month, category_l1_name, brand_name, brand_level,
  sales_qty, sales_amount, order_count, after_sale_count, after_sale_qty, refund_amount,
  return_rate, quality_qty, logistics_qty, subjective_qty, desc_qty, quality_defect_rate,
  avg_handle_hours, avg_satisfaction, review_count, avg_rating, negative_rate)
SELECT s.order_month, s.category_l1_name, s.brand_name, s.brand_level,
       s.sales_qty, s.sales_amount, s.order_count,
       IFNULL(a.after_sale_count, 0), IFNULL(a.after_sale_qty, 0), IFNULL(a.refund_amount, 0),
       ROUND(IFNULL(a.after_sale_qty, 0) / NULLIF(s.sales_qty, 0), 4),
       IFNULL(a.quality_qty, 0), IFNULL(a.logistics_qty, 0),
       IFNULL(a.subjective_qty, 0), IFNULL(a.desc_qty, 0),
       ROUND(IFNULL(a.quality_qty, 0) / NULLIF(s.sales_qty, 0), 4),
       IFNULL(a.avg_handle_hours, 0), a.avg_satisfaction,
       IFNULL(r.review_count, 0), r.avg_rating, r.negative_rate
FROM (SELECT order_month, category_l1_name, brand_name, brand_level,
             SUM(quantity) AS sales_qty, ROUND(SUM(amount), 2) AS sales_amount,
             COUNT(DISTINCT order_id) AS order_count
      FROM dwd_order_item_wide
      GROUP BY order_month, category_l1_name, brand_name, brand_level) s
LEFT JOIN (
      SELECT DATE_FORMAT(aso.apply_time, '%Y-%m') AS m, w.category_l1_name, w.brand_name,
             COUNT(DISTINCT aso.id) AS after_sale_count,
             SUM(asi.quantity) AS after_sale_qty,
             ROUND(SUM(asi.amount), 2) AS refund_amount,
             SUM(CASE WHEN asi.defect_type = '商品质量' THEN asi.quantity ELSE 0 END) AS quality_qty,
             SUM(CASE WHEN asi.defect_type = '物流损坏' THEN asi.quantity ELSE 0 END) AS logistics_qty,
             SUM(CASE WHEN asi.defect_type = '客户主观' THEN asi.quantity ELSE 0 END) AS subjective_qty,
             SUM(CASE WHEN asi.defect_type = '描述不符' THEN asi.quantity ELSE 0 END) AS desc_qty,
             ROUND(AVG(aso.handle_hours), 1) AS avg_handle_hours,
             ROUND(AVG(aso.satisfaction), 2) AS avg_satisfaction
      FROM after_sale_item asi
      JOIN after_sale_order aso   ON aso.id = asi.after_sale_order_id
      JOIN dwd_order_item_wide w  ON w.order_item_id = asi.order_item_id
      GROUP BY DATE_FORMAT(aso.apply_time, '%Y-%m'), w.category_l1_name, w.brand_name) a
       ON a.m = s.order_month AND a.category_l1_name = s.category_l1_name AND a.brand_name = s.brand_name
LEFT JOIN (SELECT order_month, category_l1_name, brand_name,
                  COUNT(review_rating) AS review_count,
                  ROUND(AVG(review_rating), 2) AS avg_rating,
                  ROUND(SUM(CASE WHEN review_rating IS NOT NULL AND review_rating < 4 THEN 1 ELSE 0 END)
                        / NULLIF(COUNT(review_rating), 0), 4) AS negative_rate
           FROM dwd_order_item_wide
           GROUP BY order_month, category_l1_name, brand_name) r
       ON r.order_month = s.order_month AND r.category_l1_name = s.category_l1_name
      AND r.brand_name = s.brand_name
""",
    # ---------- W9 流量转化漏斗：页面访问 + 行为埋点 + 订单，三份按月×渠道×设备聚合后 JOIN ----------
    "dws_traffic_funnel_wide": """
INSERT INTO dws_traffic_funnel_wide (
  stat_month, channel_name, channel_type, device_type, traffic_source,
  pv, uv, visit_count, bounce_count, bounce_rate, avg_stay_seconds, detail_pv,
  cart_count, cart_rate, order_count, order_rate, pay_count, pay_rate,
  sales_amount, overall_cvr)
SELECT v.visit_month, ch.channel_name, ch.channel_type, v.device_type, v.traffic_source,
       v.pv, v.uv, v.visit_count, v.bounce_count, v.bounce_rate, v.avg_stay_seconds, v.detail_pv,
       IFNULL(e.cart_count, 0),
       ROUND(IFNULL(e.cart_count, 0) / NULLIF(v.detail_pv, 0), 4),
       IFNULL(e.order_count, 0),
       ROUND(IFNULL(e.order_count, 0) / NULLIF(v.uv, 0), 4),
       IFNULL(e.pay_count, 0),
       ROUND(IFNULL(e.pay_count, 0) / NULLIF(e.order_count, 0), 4),
       IFNULL(o.sales_amount, 0),
       ROUND(IFNULL(e.pay_count, 0) / NULLIF(v.uv, 0), 4)
FROM (SELECT visit_month, channel_id, device_type, traffic_source,
             SUM(pv) AS pv, COUNT(DISTINCT customer_id) AS uv, COUNT(*) AS visit_count,
             SUM(is_bounce) AS bounce_count,
             ROUND(SUM(is_bounce) / COUNT(*), 4) AS bounce_rate,
             CAST(AVG(stay_seconds) AS SIGNED) AS avg_stay_seconds,
             SUM(CASE WHEN page_type = '详情页' THEN pv ELSE 0 END) AS detail_pv
      FROM page_visit GROUP BY visit_month, channel_id, device_type, traffic_source) v
JOIN channel ch ON ch.id = v.channel_id
LEFT JOIN (SELECT DATE_FORMAT(event_date, '%Y-%m') AS m, device_type,
                  SUM(CASE WHEN event_type = '加购' THEN 1 ELSE 0 END) AS cart_count,
                  SUM(CASE WHEN event_type = '下单' THEN 1 ELSE 0 END) AS order_count,
                  SUM(CASE WHEN event_type = '支付' THEN 1 ELSE 0 END) AS pay_count
           FROM user_event GROUP BY DATE_FORMAT(event_date, '%Y-%m'), device_type) e
       ON e.m = v.visit_month AND e.device_type = v.device_type
LEFT JOIN (SELECT order_month, channel_id, ROUND(SUM(amount), 2) AS sales_amount
           FROM orders WHERE is_paid = 1 GROUP BY order_month, channel_id) o
       ON o.order_month = v.visit_month AND o.channel_id = v.channel_id
""",
    # ---------- W10 供应商交付绩效：采购单 + 采购明细 + 商品品类 + 供货关系 ----------
    "dws_supplier_delivery_wide": """
INSERT INTO dws_supplier_delivery_wide (
  stat_month, supplier_id, supplier_name, supplier_level, supplier_province, credit_score,
  category_l1_name, po_count, sku_count, po_qty, po_amount, received_qty, rejected_qty,
  pass_rate, fulfill_rate, on_time_count, on_time_rate, avg_lead_time_days)
SELECT po.order_month, sup.id, sup.supplier_name, sup.supplier_level, sup.province, sup.credit_score,
       c1.category_name,
       COUNT(DISTINCT po.id),
       COUNT(DISTINCT poi.product_sku_id),
       SUM(poi.quantity),
       ROUND(SUM(poi.amount), 2),
       SUM(poi.received_qty),
       SUM(poi.rejected_qty),
       ROUND((SUM(poi.received_qty) - SUM(poi.rejected_qty)) / NULLIF(SUM(poi.received_qty), 0), 4),
       ROUND(SUM(poi.received_qty) / NULLIF(SUM(poi.quantity), 0), 4),
       COUNT(DISTINCT CASE WHEN po.is_on_time = 1 THEN po.id END),
       ROUND(COUNT(DISTINCT CASE WHEN po.is_on_time = 1 THEN po.id END)
             / NULLIF(COUNT(DISTINCT po.id), 0), 4),
       IFNULL(ROUND(AVG(sp.lead_time_days)), 0)
FROM purchase_order po
JOIN purchase_order_item poi ON poi.purchase_order_id = po.id
JOIN supplier sup            ON sup.id = po.supplier_id
JOIN product_sku sk          ON sk.id = poi.product_sku_id
JOIN product p               ON p.id = sk.product_id
JOIN category c3             ON c3.id = p.category_id
JOIN category c1             ON c1.id = CAST(SUBSTRING_INDEX(c3.path, '/', 1) AS UNSIGNED)
LEFT JOIN supplier_product sp ON sp.supplier_id = sup.id AND sp.product_id = p.id
WHERE po.status <> '已取消'
GROUP BY po.order_month, sup.id, sup.supplier_name, sup.supplier_level, sup.province,
         sup.credit_score, c1.category_name
""",
}
