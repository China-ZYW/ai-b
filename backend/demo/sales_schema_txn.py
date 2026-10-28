# -*- coding: utf-8 -*-
"""电商销售库（aibi_demo_sales）交易域建表 DDL：交易 / 履约物流 / 营销 / 售后评价 / 流量行为，共 25 张。

命名与注释约束见 ``sales_schema_master.py`` 模块头，此处只补充交易域特有的三条：

1. **``orders`` 原地扩展，11 个历史列一个不改**。线上已有 12 个指标 + 「电商销售分析大屏」直接
   ``SELECT order_month/region/province/channel/category/product/amount/quantity/score/stage FROM orders``，
   这些列名与类型必须逐字保留，否则指标全部报错。新增的只是关联列（customer_id/product_id/…）与
   交易金额细分列，行数从 1600 扩到约 1 万（已确认接受大屏数值随之变化）。

2. **同时保留反范式列与规范化外键列**。``orders`` 里既有 ``region/province/channel/category/product``
   这些历史冗余列（供旧指标直接用），又有 ``channel_id/store_id/product_id`` 等外键列（供多表 JOIN）。
   这是真实业务库的常态，也正好构成对 NL2SQL 的考验：同一个「渠道」维度存在两条取数路径，
   模型必须选对外键列而不是把冗余列当维度硬 JOIN。

3. **``payment`` 的支付渠道用 ``pay_channel`` 字符串而非 ``pay_channel_id``**。若命名成
   ``pay_channel_id``，``JoinGraph`` 规则①会去找名为 ``pay_channel`` 的表，找不到即放弃推断（无害），
   但列名会误导模型以为存在这样一张表。业务上支付宝/微信本就是枚举值，用字符串更准确。
"""

# 表名 → (业务域, DDL)。DDL 按依赖拓扑序排列，可直接顺序执行。
TXN_TABLES = [
    # ==================== 域 F：交易核心（8 张） ====================
    ("orders", "交易核心", """
CREATE TABLE orders (
  id              INT AUTO_INCREMENT COMMENT '订单主键ID',
  order_no        VARCHAR(32)   NOT NULL COMMENT '订单编号',
  order_date      DATE          NOT NULL COMMENT '下单日期',
  order_month     VARCHAR(7)    NOT NULL COMMENT '下单月份 YYYY-MM',
  customer_id     INT           NOT NULL COMMENT '下单客户ID',
  product_id      INT           NOT NULL COMMENT '主商品SPU ID（订单内金额最高的商品）',
  store_id        INT           NOT NULL COMMENT '成交门店/店铺ID',
  channel_id      INT           NOT NULL COMMENT '销售渠道ID',
  sales_rep_id    INT                    COMMENT '负责销售员ID（线上自助下单为空）',
  warehouse_id    INT           NOT NULL COMMENT '发货仓库ID',
  coupon_grant_id INT                    COMMENT '使用的优惠券领取记录ID（未用券为空）',
  campaign_id     INT                    COMMENT '归因营销活动ID（自然流量为空）',
  region          VARCHAR(16)   NOT NULL COMMENT '所属大区（冗余列，兼容历史指标）',
  province        VARCHAR(16)   NOT NULL COMMENT '收货省份（冗余列）',
  channel         VARCHAR(16)   NOT NULL COMMENT '渠道名称（冗余列）',
  category        VARCHAR(16)   NOT NULL COMMENT '一级品类名称（冗余列）',
  product         VARCHAR(32)   NOT NULL COMMENT '主商品名称（冗余列）',
  amount          DECIMAL(12,2) NOT NULL COMMENT '订单金额（商品原价合计）',
  pay_amount      DECIMAL(12,2) NOT NULL COMMENT '实付金额',
  discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '优惠金额',
  freight_amount  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '运费金额',
  quantity        INT           NOT NULL COMMENT '商品总件数',
  item_count      INT           NOT NULL COMMENT '订单行项目数（不同SKU种类数）',
  order_status    VARCHAR(16)   NOT NULL COMMENT '订单状态：待支付/已支付/已发货/已完成/已取消/退款中',
  pay_type        VARCHAR(16)            COMMENT '支付方式：支付宝/微信/银行卡/货到付款/白条分期',
  stage           VARCHAR(8)    NOT NULL COMMENT '销售阶段：浏览/加购/下单/支付/完成/退款',
  score           DECIMAL(4,2)           COMMENT '订单满意度评分(1-5)',
  is_first_order  TINYINT       NOT NULL DEFAULT 0 COMMENT '是否客户首单：1是 0否',
  is_paid         TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已支付：1是 0否',
  created_at      DATETIME      NOT NULL COMMENT '订单创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_orders_customer (customer_id),
  KEY idx_orders_product (product_id),
  KEY idx_orders_store (store_id),
  KEY idx_orders_channel (channel_id),
  KEY idx_orders_rep (sales_rep_id),
  KEY idx_orders_warehouse (warehouse_id),
  KEY idx_orders_date (order_date),
  KEY idx_orders_month (order_month),
  KEY idx_orders_province (province)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售订单主表（一笔订单一行，含客户/商品/门店/渠道/销售员/仓库/活动关联与金额细分）'
"""),
    ("order_item", "交易核心", """
CREATE TABLE order_item (
  id              INT AUTO_INCREMENT COMMENT '订单明细ID',
  order_id        INT           NOT NULL COMMENT '订单ID',
  product_id      INT           NOT NULL COMMENT '商品SPU ID',
  product_sku_id  INT           NOT NULL COMMENT 'SKU ID',
  quantity        INT           NOT NULL COMMENT '购买数量',
  unit_price      DECIMAL(12,2) NOT NULL COMMENT '成交单价',
  amount          DECIMAL(14,2) NOT NULL COMMENT '小计金额（单价×数量）',
  discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '行优惠金额',
  cost_amount     DECIMAL(14,2) NOT NULL COMMENT '成本小计',
  gross_profit    DECIMAL(14,2) NOT NULL COMMENT '毛利额（小计-优惠-成本）',
  gross_margin    DECIMAL(6,4)  NOT NULL COMMENT '毛利率(0-1)',
  is_reviewed     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已评价：1是 0否',
  is_refunded     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否发生售后（退货/退款）：1是 0否',
  PRIMARY KEY (id),
  KEY idx_oi_order (order_id),
  KEY idx_oi_product (product_id),
  KEY idx_oi_sku (product_sku_id),
  CONSTRAINT fk_oi_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品明细（一笔订单可含多个SKU，含毛利额与毛利率，是销售额与销量的最细粒度事实表）'
"""),
    ("order_status_log", "交易核心", """
CREATE TABLE order_status_log (
  id          INT AUTO_INCREMENT COMMENT '主键ID',
  order_id    INT         NOT NULL COMMENT '订单ID',
  from_status VARCHAR(16) NOT NULL COMMENT '变更前状态',
  to_status   VARCHAR(16) NOT NULL COMMENT '变更后状态',
  change_time DATETIME    NOT NULL COMMENT '状态变更时间',
  operator    VARCHAR(32) NOT NULL COMMENT '操作人（系统自动流转记为 SYSTEM）',
  remark      VARCHAR(255)         COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_osl_order (order_id),
  KEY idx_osl_time (change_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态流转日志（下单到完成/退款的每次状态变更，可算各环节转化时长）'
"""),
    ("payment", "交易核心", """
CREATE TABLE payment (
  id           INT AUTO_INCREMENT COMMENT '支付单ID',
  payment_no   VARCHAR(40)   NOT NULL COMMENT '支付流水号',
  order_id     INT           NOT NULL COMMENT '订单ID',
  customer_id  INT           NOT NULL COMMENT '付款客户ID',
  pay_amount   DECIMAL(12,2) NOT NULL COMMENT '支付金额',
  pay_type     VARCHAR(16)   NOT NULL COMMENT '支付方式：支付宝/微信/银行卡/货到付款/白条分期',
  pay_channel  VARCHAR(16)   NOT NULL COMMENT '支付渠道（第三方通道，非销售渠道）',
  pay_time     DATETIME               COMMENT '支付完成时间（未支付为空）',
  pay_status   VARCHAR(16)   NOT NULL COMMENT '支付状态：成功/失败/处理中/已退款/未支付',
  trade_no     VARCHAR(64)            COMMENT '第三方交易号',
  is_refunded  TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已退款：1是 0否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_no (payment_no),
  KEY idx_pay_order (order_id),
  KEY idx_pay_customer (customer_id),
  CONSTRAINT fk_pay_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付单（订单的收款记录，含支付方式与支付状态，实收口径以此为准）'
"""),
    ("refund", "交易核心", """
CREATE TABLE refund (
  id            INT AUTO_INCREMENT COMMENT '退款单ID',
  refund_no     VARCHAR(40)   NOT NULL COMMENT '退款单号',
  order_id      INT           NOT NULL COMMENT '订单ID',
  payment_id    INT           NOT NULL COMMENT '原支付单ID',
  customer_id   INT           NOT NULL COMMENT '客户ID',
  refund_amount DECIMAL(12,2) NOT NULL COMMENT '退款金额',
  refund_reason VARCHAR(32)   NOT NULL COMMENT '退款原因：质量问题/七天无理由/发错货/不想要了/物流太慢/价格保护',
  apply_time    DATETIME      NOT NULL COMMENT '退款申请时间',
  finish_time   DATETIME               COMMENT '退款完成时间',
  refund_status VARCHAR(16)   NOT NULL COMMENT '退款状态：待审核/已同意/已拒绝/已退款',
  audit_hours   DECIMAL(6,1)  NOT NULL COMMENT '审核时长(小时)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_no (refund_no),
  KEY idx_refund_order (order_id),
  KEY idx_refund_payment (payment_id),
  KEY idx_refund_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款单（订单的退款记录，退款率与退款时效分析的事实表）'
"""),
    ("invoice", "交易核心", """
CREATE TABLE invoice (
  id            INT AUTO_INCREMENT COMMENT '发票ID',
  invoice_no    VARCHAR(40)   NOT NULL COMMENT '发票号码',
  order_id      INT           NOT NULL COMMENT '订单ID',
  customer_id   INT           NOT NULL COMMENT '客户ID',
  invoice_type  VARCHAR(24)   NOT NULL COMMENT '发票类型：增值税普通发票/增值税专用发票/电子发票',
  invoice_title VARCHAR(128)  NOT NULL COMMENT '发票抬头',
  tax_no        VARCHAR(32)            COMMENT '纳税人识别号（个人抬头为空）',
  amount        DECIMAL(12,2) NOT NULL COMMENT '开票金额',
  tax_amount    DECIMAL(12,2) NOT NULL COMMENT '税额',
  invoice_date  DATE          NOT NULL COMMENT '开票日期',
  status        VARCHAR(16)   NOT NULL COMMENT '开票状态：待开/已开/已作废',
  PRIMARY KEY (id),
  UNIQUE KEY uk_invoice_no (invoice_no),
  KEY idx_inv_order (order_id),
  KEY idx_inv_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票（订单开票记录，含发票类型与税额）'
"""),
    ("shopping_cart", "交易核心", """
CREATE TABLE shopping_cart (
  id              INT AUTO_INCREMENT COMMENT '购物车ID',
  customer_id     INT           NOT NULL COMMENT '客户ID',
  created_at      DATETIME      NOT NULL COMMENT '创建时间',
  updated_at      DATETIME      NOT NULL COMMENT '最近更新时间',
  item_kind_count INT           NOT NULL COMMENT '商品种类数',
  total_amount    DECIMAL(14,2) NOT NULL COMMENT '购物车总金额',
  is_ordered      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已下单转化：1是 0否（弃单为0）',
  PRIMARY KEY (id),
  KEY idx_cart_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车（一个客户一个购物车，加购到下单的转化率以此计算）'
"""),
    ("cart_item", "交易核心", """
CREATE TABLE cart_item (
  id               INT AUTO_INCREMENT COMMENT '主键ID',
  shopping_cart_id INT           NOT NULL COMMENT '购物车ID',
  product_sku_id   INT           NOT NULL COMMENT 'SKU ID',
  quantity         INT           NOT NULL COMMENT '数量',
  unit_price       DECIMAL(12,2) NOT NULL COMMENT '加入时单价',
  added_at         DATETIME      NOT NULL COMMENT '加入购物车时间',
  is_selected      TINYINT       NOT NULL DEFAULT 1 COMMENT '是否勾选结算：1是 0否',
  PRIMARY KEY (id),
  KEY idx_ci_cart (shopping_cart_id),
  KEY idx_ci_sku (product_sku_id),
  CONSTRAINT fk_ci_cart FOREIGN KEY (shopping_cart_id) REFERENCES shopping_cart (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车明细（购物车内每个SKU的数量与单价）'
"""),

    # ==================== 域 G：履约与物流（5 张） ====================
    ("carrier", "履约物流", """
CREATE TABLE carrier (
  carrier_code       VARCHAR(20)  NOT NULL COMMENT '承运商编码：SF/JD/ZTO/YTO/YD/JT/DB/EMS/TC/RRS',
  carrier_name       VARCHAR(32)  NOT NULL COMMENT '承运商名称：顺丰/京东物流/中通/圆通/韵达/极兔/德邦/邮政/同城配送/日日顺',
  carrier_type       VARCHAR(16)  NOT NULL COMMENT '承运商类型：快递/快运/同城配送',
  base_fee           DECIMAL(8,2) NOT NULL COMMENT '首重费用(元)',
  extra_fee_per_kg   DECIMAL(8,2) NOT NULL COMMENT '续重单价(元/kg)',
  avg_delivery_hours DECIMAL(6,1) NOT NULL COMMENT '平均配送时效(小时)',
  cooperation_level  VARCHAR(16)  NOT NULL COMMENT '合作等级：核心/重要/普通',
  PRIMARY KEY (carrier_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='承运商（**以业务编码 carrier_code 为主键**，发货单按此编码关联，含运费标准与时效）'
"""),
    ("inventory_snapshot", "履约物流", """
CREATE TABLE inventory_snapshot (
  id             INT AUTO_INCREMENT COMMENT '主键ID',
  snapshot_date  DATE          NOT NULL COMMENT '库存快照日期',
  snapshot_month VARCHAR(7)    NOT NULL COMMENT '快照月份 YYYY-MM',
  product_sku_id INT           NOT NULL COMMENT 'SKU ID',
  warehouse_id   INT           NOT NULL COMMENT '仓库ID',
  on_hand_qty    INT           NOT NULL COMMENT '在库数量',
  available_qty  INT           NOT NULL COMMENT '可用数量',
  locked_qty     INT           NOT NULL COMMENT '锁定数量（已下单未出库）',
  in_transit_qty INT           NOT NULL COMMENT '在途数量（采购已下单未入库）',
  stock_value    DECIMAL(14,2) NOT NULL COMMENT '库存金额',
  turnover_days  INT           NOT NULL COMMENT '库存周转天数',
  stock_status   VARCHAR(8)    NOT NULL COMMENT '库存状态：缺货/预警/正常/积压',
  PRIMARY KEY (id),
  KEY idx_inv_sku (product_sku_id),
  KEY idx_inv_wh (warehouse_id),
  KEY idx_inv_date (snapshot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存快照（按日记录每个SKU在每个仓库的库存，库存周转与缺货分析的事实表）'
"""),
    ("inventory_transaction", "履约物流", """
CREATE TABLE inventory_transaction (
  id                INT AUTO_INCREMENT COMMENT '主键ID',
  trans_no          VARCHAR(40) NOT NULL COMMENT '库存流水号',
  trans_date        DATE        NOT NULL COMMENT '业务日期',
  product_sku_id    INT         NOT NULL COMMENT 'SKU ID',
  warehouse_id      INT         NOT NULL COMMENT '仓库ID',
  order_id          INT                  COMMENT '关联销售订单ID（销售出库/退货入库时填）',
  purchase_order_id INT                  COMMENT '关联采购单ID（采购入库时填）',
  trans_type        VARCHAR(16) NOT NULL COMMENT '出入库类型：采购入库/销售出库/退货入库/调拨出库/调拨入库/盘盈/盘损',
  quantity          INT         NOT NULL COMMENT '变动数量（正为入库，负为出库）',
  before_qty        INT         NOT NULL COMMENT '变动前库存',
  after_qty         INT         NOT NULL COMMENT '变动后库存',
  unit_cost         DECIMAL(12,2) NOT NULL COMMENT '单位成本',
  PRIMARY KEY (id),
  KEY idx_it_sku (product_sku_id),
  KEY idx_it_wh (warehouse_id),
  KEY idx_it_order (order_id),
  KEY idx_it_po (purchase_order_id),
  KEY idx_it_date (trans_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存出入库流水（每笔库存变动的记录，同时关联销售订单与采购单，是库存勾稽的源头）'
"""),
    ("shipment", "履约物流", """
CREATE TABLE shipment (
  id               INT AUTO_INCREMENT COMMENT '发货单ID',
  shipment_no      VARCHAR(40)   NOT NULL COMMENT '运单号',
  order_id         INT           NOT NULL COMMENT '订单ID',
  warehouse_id     INT           NOT NULL COMMENT '发货仓库ID',
  carrier_code     VARCHAR(20)   NOT NULL COMMENT '承运商编码（关联 carrier.carrier_code）',
  ship_date        DATE          NOT NULL COMMENT '发货日期',
  sign_date        DATE                   COMMENT '签收日期（未签收为空）',
  receiver_province VARCHAR(16)  NOT NULL COMMENT '收货省份',
  receiver_city    VARCHAR(16)   NOT NULL COMMENT '收货城市',
  distance_km      INT           NOT NULL COMMENT '配送距离(km)',
  weight_kg        DECIMAL(8,2)  NOT NULL COMMENT '包裹重量(kg)',
  freight_amount   DECIMAL(10,2) NOT NULL COMMENT '运费金额',
  delivery_hours   DECIMAL(6,1)  NOT NULL COMMENT '配送时长(小时)',
  shipment_status  VARCHAR(16)   NOT NULL COMMENT '物流状态：已发货/运输中/派送中/已签收/异常',
  is_on_time       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否准时送达：1是 0否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_shipment_no (shipment_no),
  KEY idx_ship_order (order_id),
  KEY idx_ship_wh (warehouse_id),
  KEY idx_ship_carrier (carrier_code),
  KEY idx_ship_date (ship_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货单（订单的履约记录，含承运商、配送距离、时效与运费，物流准时率以此计算）'
"""),
    ("shipment_trace", "履约物流", """
CREATE TABLE shipment_trace (
  id          INT AUTO_INCREMENT COMMENT '主键ID',
  shipment_id INT         NOT NULL COMMENT '发货单ID',
  trace_time  DATETIME    NOT NULL COMMENT '轨迹时间',
  trace_node  VARCHAR(32) NOT NULL COMMENT '轨迹节点：已揽收/到达分拨中心/已发出/到达派送网点/派送中/已签收',
  location    VARCHAR(64) NOT NULL COMMENT '所在位置',
  operator    VARCHAR(32)          COMMENT '操作员',
  PRIMARY KEY (id),
  KEY idx_trace_shipment (shipment_id),
  KEY idx_trace_time (trace_time),
  CONSTRAINT fk_trace_shipment FOREIGN KEY (shipment_id) REFERENCES shipment (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹（一个发货单的多条轨迹节点，可算各节点停留时长）'
"""),

    # ==================== 域 H：营销（7 张） ====================
    ("campaign", "营销", """
CREATE TABLE campaign (
  id            INT AUTO_INCREMENT COMMENT '营销活动ID',
  campaign_code VARCHAR(32)   NOT NULL COMMENT '活动编码',
  campaign_name VARCHAR(64)   NOT NULL COMMENT '活动名称（月份+主题），如：2025-11双11狂欢、年货节、品牌日',
  campaign_type VARCHAR(16)   NOT NULL COMMENT '活动类型：满减/折扣/秒杀/赠品/拼团/直播专享',
  channel_id    INT           NOT NULL COMMENT '投放销售渠道ID',
  start_date    DATE          NOT NULL COMMENT '活动开始日期',
  end_date      DATE          NOT NULL COMMENT '活动结束日期',
  budget        DECIMAL(14,2) NOT NULL COMMENT '活动预算',
  actual_spend  DECIMAL(14,2) NOT NULL COMMENT '实际花费',
  target_gmv    DECIMAL(14,2) NOT NULL COMMENT '目标GMV',
  actual_gmv    DECIMAL(14,2) NOT NULL COMMENT '实际GMV',
  owner         VARCHAR(32)   NOT NULL COMMENT '活动负责人',
  status        VARCHAR(16)   NOT NULL COMMENT '活动状态：未开始/进行中/已结束/已取消',
  PRIMARY KEY (id),
  UNIQUE KEY uk_campaign_code (campaign_code),
  KEY idx_campaign_channel (channel_id),
  KEY idx_campaign_date (start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='营销活动（大促与日常活动主表，含预算、花费与GMV目标，ROI分析主体）'
"""),
    ("coupon_template", "营销", """
CREATE TABLE coupon_template (
  id           INT AUTO_INCREMENT COMMENT '券模板ID',
  coupon_code  VARCHAR(32)   NOT NULL COMMENT '券编码',
  coupon_name  VARCHAR(64)   NOT NULL COMMENT '券名称，如：满199减30、8.5折券、包邮券、新人专享券',
  campaign_id  INT           NOT NULL COMMENT '所属营销活动ID',
  coupon_type  VARCHAR(16)   NOT NULL COMMENT '券类型：满减券/折扣券/无门槛券/运费券',
  face_value   DECIMAL(10,2) NOT NULL COMMENT '券面额（折扣券为折扣值）',
  min_spend    DECIMAL(10,2) NOT NULL COMMENT '使用门槛金额（无门槛券为0）',
  total_qty    INT           NOT NULL COMMENT '发行总量',
  valid_days   INT           NOT NULL COMMENT '领取后有效天数',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon_code (coupon_code),
  KEY idx_ct_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板（券的种类定义，含面额、门槛与发行量）'
"""),
    ("coupon_grant", "营销", """
CREATE TABLE coupon_grant (
  id                 INT AUTO_INCREMENT COMMENT '领券记录ID',
  customer_id        INT         NOT NULL COMMENT '领取客户ID',
  coupon_template_id INT         NOT NULL COMMENT '券模板ID',
  grant_date         DATE        NOT NULL COMMENT '领取日期',
  expire_date        DATE        NOT NULL COMMENT '过期日期',
  grant_channel      VARCHAR(16) NOT NULL COMMENT '领取渠道：活动页/首页弹窗/客服发放/任务奖励',
  is_used            TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已使用：1是 0否',
  used_date          DATE                 COMMENT '使用日期（未使用为空）',
  PRIMARY KEY (id),
  KEY idx_cg_customer (customer_id),
  KEY idx_cg_template (coupon_template_id),
  KEY idx_cg_date (grant_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券发放领取记录（谁在何时领了哪张券，核销率与过期率以此计算）'
"""),
    ("coupon_usage", "营销", """
CREATE TABLE coupon_usage (
  id                 INT AUTO_INCREMENT COMMENT '主键ID',
  coupon_grant_id    INT           NOT NULL COMMENT '领券记录ID',
  order_id           INT           NOT NULL COMMENT '核销订单ID',
  customer_id        INT           NOT NULL COMMENT '客户ID',
  coupon_template_id INT           NOT NULL COMMENT '券模板ID',
  used_time          DATETIME      NOT NULL COMMENT '核销时间',
  discount_amount    DECIMAL(10,2) NOT NULL COMMENT '优惠金额',
  order_amount       DECIMAL(12,2) NOT NULL COMMENT '订单金额',
  PRIMARY KEY (id),
  KEY idx_cu_grant (coupon_grant_id),
  KEY idx_cu_order (order_id),
  KEY idx_cu_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券核销记录（券实际用在了哪笔订单，优惠投入产出分析的事实表）'
"""),
    ("promotion_rule", "营销", """
CREATE TABLE promotion_rule (
  id               INT AUTO_INCREMENT COMMENT '促销规则ID',
  campaign_id      INT           NOT NULL COMMENT '所属营销活动ID',
  rule_name        VARCHAR(64)   NOT NULL COMMENT '规则名称',
  rule_type        VARCHAR(16)   NOT NULL COMMENT '规则类型：满件折/满额减/第二件半价/买赠/限时特价',
  threshold_qty    INT           NOT NULL DEFAULT 0 COMMENT '门槛件数',
  threshold_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '门槛金额',
  benefit_value    DECIMAL(12,2) NOT NULL COMMENT '优惠值（折扣率或减免金额）',
  start_time       DATETIME      NOT NULL COMMENT '生效开始时间',
  end_time         DATETIME      NOT NULL COMMENT '生效结束时间',
  is_stackable     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否可与其他促销叠加：1是 0否',
  PRIMARY KEY (id),
  KEY idx_pr_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销规则（满减、满件折、第二件半价等具体玩法规则）'
"""),
    ("promotion_product", "营销", """
CREATE TABLE promotion_product (
  id                 INT AUTO_INCREMENT COMMENT '主键ID',
  promotion_rule_id  INT           NOT NULL COMMENT '促销规则ID',
  product_id         INT           NOT NULL COMMENT '参与促销的商品SPU ID',
  promo_price        DECIMAL(12,2) NOT NULL COMMENT '促销价',
  stock_limit        INT           NOT NULL COMMENT '活动限量',
  PRIMARY KEY (id),
  KEY idx_pp_rule (promotion_rule_id),
  KEY idx_pp_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销商品（促销规则与商品的多对多桥表，记录每个商品的活动价与限量）'
"""),
    ("ad_placement", "营销", """
CREATE TABLE ad_placement (
  id                INT AUTO_INCREMENT COMMENT '主键ID',
  campaign_id       INT           NOT NULL COMMENT '所属营销活动ID',
  platform_id       INT           NOT NULL COMMENT '投放平台ID',
  placement_date    DATE          NOT NULL COMMENT '投放日期',
  placement_month   VARCHAR(7)    NOT NULL COMMENT '投放月份 YYYY-MM',
  ad_position       VARCHAR(32)   NOT NULL COMMENT '广告位：首页Banner/搜索置顶/信息流/详情页推荐/开屏/直播间挂车',
  spend             DECIMAL(12,2) NOT NULL COMMENT '投放花费',
  impressions       INT           NOT NULL COMMENT '曝光量',
  clicks            INT           NOT NULL COMMENT '点击量',
  ctr               DECIMAL(6,4)  NOT NULL COMMENT '点击率',
  conversions       INT           NOT NULL COMMENT '转化订单数',
  conversion_amount DECIMAL(14,2) NOT NULL COMMENT '转化金额',
  roi               DECIMAL(6,2)  NOT NULL COMMENT '投资回报率（转化金额/花费）',
  PRIMARY KEY (id),
  KEY idx_ad_campaign (campaign_id),
  KEY idx_ad_platform (platform_id),
  KEY idx_ad_date (placement_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告投放明细（按活动×平台×广告位×日记录花费、曝光、点击、转化与ROI）'
"""),

    # ==================== 域 I：售后与评价（3 张） ====================
    ("after_sale_order", "售后评价", """
CREATE TABLE after_sale_order (
  id              INT AUTO_INCREMENT COMMENT '售后单ID',
  after_sale_no   VARCHAR(40)   NOT NULL COMMENT '售后单号',
  order_id        INT           NOT NULL COMMENT '原销售订单ID',
  customer_id     INT           NOT NULL COMMENT '客户ID',
  after_sale_type VARCHAR(16)   NOT NULL COMMENT '售后类型：仅退款/退货退款/换货/维修',
  reason          VARCHAR(32)   NOT NULL COMMENT '售后原因：质量问题/尺寸不符/与描述不符/物流损坏/不想要了',
  apply_time      DATETIME      NOT NULL COMMENT '售后申请时间',
  finish_time     DATETIME               COMMENT '售后完成时间',
  refund_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '退款金额',
  handle_hours    DECIMAL(6,1)  NOT NULL COMMENT '处理时长(小时)',
  after_sale_status VARCHAR(16) NOT NULL COMMENT '售后状态：待审核/处理中/已完成/已拒绝',
  satisfaction    DECIMAL(4,2)           COMMENT '售后满意度评分(1-5)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_after_sale_no (after_sale_no),
  KEY idx_as_order (order_id),
  KEY idx_as_customer (customer_id),
  KEY idx_as_time (apply_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后单（退货退款/换货/维修申请，退货率与售后时效分析的事实表）'
"""),
    ("after_sale_item", "售后评价", """
CREATE TABLE after_sale_item (
  id                  INT AUTO_INCREMENT COMMENT '主键ID',
  after_sale_order_id INT           NOT NULL COMMENT '售后单ID',
  order_item_id       INT           NOT NULL COMMENT '原订单明细ID',
  product_sku_id      INT           NOT NULL COMMENT 'SKU ID',
  quantity            INT           NOT NULL COMMENT '售后数量',
  amount              DECIMAL(12,2) NOT NULL COMMENT '售后金额',
  defect_type         VARCHAR(16)   NOT NULL COMMENT '责任归属：商品质量/物流损坏/客户主观/描述不符',
  PRIMARY KEY (id),
  KEY idx_asi_as (after_sale_order_id),
  KEY idx_asi_oi (order_item_id),
  KEY idx_asi_sku (product_sku_id),
  CONSTRAINT fk_asi_as FOREIGN KEY (after_sale_order_id) REFERENCES after_sale_order (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后明细（售后单涉及的具体SKU与数量，含责任归属，可下钻到品类/品牌的退货率）'
"""),
    ("product_review", "售后评价", """
CREATE TABLE product_review (
  id                INT AUTO_INCREMENT COMMENT '评价ID',
  order_item_id     INT           NOT NULL COMMENT '订单明细ID',
  product_sku_id    INT           NOT NULL COMMENT 'SKU ID',
  customer_id       INT           NOT NULL COMMENT '评价客户ID',
  review_date       DATE          NOT NULL COMMENT '评价日期',
  review_month      VARCHAR(7)    NOT NULL COMMENT '评价月份 YYYY-MM',
  rating            DECIMAL(3,2)  NOT NULL COMMENT '综合评分(1-5)',
  desc_rating       DECIMAL(3,2)  NOT NULL COMMENT '描述相符评分(1-5)',
  logistics_rating  DECIMAL(3,2)  NOT NULL COMMENT '物流服务评分(1-5)',
  service_rating    DECIMAL(3,2)  NOT NULL COMMENT '服务态度评分(1-5)',
  content           VARCHAR(500)           COMMENT '评价内容',
  has_image         TINYINT       NOT NULL DEFAULT 0 COMMENT '是否晒图：1是 0否',
  is_positive       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否好评（4分及以上）：1是 0否',
  reply_content     VARCHAR(500)           COMMENT '商家回复内容',
  PRIMARY KEY (id),
  KEY idx_pr_oi (order_item_id),
  KEY idx_pr_sku (product_sku_id),
  KEY idx_pr_customer (customer_id),
  KEY idx_pr_date (review_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品评价（订单明细维度的评分与评价内容，好评率与差评归因的事实表）'
"""),

    # ==================== 域 J：流量行为（2 张） ====================
    ("page_visit", "流量行为", """
CREATE TABLE page_visit (
  id             INT AUTO_INCREMENT COMMENT '主键ID',
  visit_date     DATE        NOT NULL COMMENT '访问日期',
  visit_month    VARCHAR(7)  NOT NULL COMMENT '访问月份 YYYY-MM',
  customer_id    INT                  COMMENT '访问客户ID（未登录访客为空）',
  product_id     INT                  COMMENT '访问的商品SPU ID（仅商品详情页填）',
  channel_id     INT         NOT NULL COMMENT '来源销售渠道ID',
  page_type      VARCHAR(16) NOT NULL COMMENT '页面类型：首页/列表页/详情页/购物车/结算页/支付成功页',
  device_type    VARCHAR(16) NOT NULL COMMENT '设备类型：iOS/Android/PC/H5/小程序',
  traffic_source VARCHAR(16) NOT NULL COMMENT '流量来源：自然流量/付费广告/活动引流/直接访问/社交分享',
  stay_seconds   INT         NOT NULL COMMENT '页面停留时长(秒)',
  pv             INT         NOT NULL COMMENT '浏览量',
  is_bounce      TINYINT     NOT NULL DEFAULT 0 COMMENT '是否跳出：1是 0否',
  is_converted   TINYINT     NOT NULL DEFAULT 0 COMMENT '是否转化下单：1是 0否',
  PRIMARY KEY (id),
  KEY idx_pv_customer (customer_id),
  KEY idx_pv_product (product_id),
  KEY idx_pv_channel (channel_id),
  KEY idx_pv_date (visit_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页面访问日志（浏览行为明细，含跳出与转化标记，转化漏斗分析的事实表）'
"""),
    ("user_event", "流量行为", """
CREATE TABLE user_event (
  id             INT AUTO_INCREMENT COMMENT '主键ID',
  event_time     DATETIME    NOT NULL COMMENT '事件发生时间',
  event_date     DATE        NOT NULL COMMENT '事件日期',
  customer_id    INT                  COMMENT '客户ID（未登录为空）',
  product_sku_id INT                  COMMENT 'SKU ID（非商品相关事件为空）',
  event_type     VARCHAR(16) NOT NULL COMMENT '事件类型：浏览/搜索/收藏/加购/下单/支付/分享/评价',
  event_source   VARCHAR(16) NOT NULL COMMENT '事件来源页面',
  device_type    VARCHAR(16) NOT NULL COMMENT '设备类型：iOS/Android/PC/H5/小程序',
  session_id     VARCHAR(40) NOT NULL COMMENT '会话ID',
  duration_ms    INT         NOT NULL DEFAULT 0 COMMENT '停留时长(毫秒)',
  PRIMARY KEY (id),
  KEY idx_ue_customer (customer_id),
  KEY idx_ue_sku (product_sku_id),
  KEY idx_ue_date (event_date),
  KEY idx_ue_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为埋点（浏览/搜索/收藏/加购/下单等全链路事件，行为路径与转化分析的事实表）'
"""),
]
