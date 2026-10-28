# -*- coding: utf-8 -*-
"""电商销售库（aibi_demo_sales）主数据域建表 DDL：商品 / 采购 / 客户会员 / 组织渠道 / 仓储，共 25 张。

设计约束（每一条都对应 NL2SQL 链路上的一个具体机制，不是随手写的）：

1. **每张表、每个列都写中文 COMMENT**。``SchemaLinker.score()`` 里表注释命中 +3、列注释命中 +2，
   是中文问法命中英文表名的**主要桥梁**；而 ``MAX_TABLES_IN_PROMPT = 12``，60 张表里只有 12 张能进
   Prompt，注释质量直接决定选表准确率。故注释要覆盖用户真会问的业务词（退货率、周转、复购、ROI…）。

2. **外键列一律命名「被引用表名 + _id」**。``JoinGraph`` 规则①是「列名剥掉尾部 id 后能唯一命中一张表」，
   所以指向 ``product_sku`` 的列必须叫 ``product_sku_id``——写成 ``sku_id`` 会去找名为 ``sku`` 的表，
   找不到就**推不出关联**（业务库里没有外键约束时，命名推断是唯一自动来源）。同理不用 ``cart_id``、
   ``attribute_id``、``tag_id``。

3. **8 对真 FOREIGN KEY，其余只建普通索引**。三来源可信度是 MANUAL > FOREIGN_KEY > INFERRED，
   混合建约束才能让三条路径都被测到；真 FK 集中在「删除必须级联」的父子表上（订单-明细、购物车-明细、
   售后单-售后明细、发货单-轨迹、客户-地址、商品-SKU、采购单-明细、支付-订单）。

4. **2 张表用业务编码作主键**（``carrier.carrier_code``、``customer_level.level_code``），
   用来触发规则②「列名恰等于另一张表的单列主键名」。这两张表没有 ``id`` 列，故引用方写
   ``carrier_code`` / ``level_code`` 而非 ``carrier_id`` / ``customer_level_id``。

5. **id 统一 INT**，与既有 ``orders.id INT`` 同族；``JoinGraph.family()`` 按 int/decimal/char/date
   分族判类型兼容，同族才推断，混用 BIGINT 虽也属数值族但没必要引入差异。

6. 品类与区域用 ``parent_id`` 自引用建树。自引用**不会**被推断（``inferByIdSuffix`` 里 ``ref == t`` 直接
   返回 null），这是刻意的：层级自连接属特殊场景，交给语义层登记。``JoinGuard`` 也不会误报——
   ``collectKeys`` 要求左右表名不同才登记连接键。
"""

# 表名 → (业务域, DDL)。DDL 按依赖拓扑序排列，可直接顺序执行。
MASTER_TABLES = [
    # ==================== 域 A：商品主数据（7 张） ====================
    ("category", "商品主数据", """
CREATE TABLE category (
  id              INT AUTO_INCREMENT COMMENT '品类ID',
  parent_id       INT          NOT NULL DEFAULT 0 COMMENT '上级品类ID（一级品类为0，构成品类树）',
  category_code   VARCHAR(32)  NOT NULL COMMENT '品类编码',
  category_name   VARCHAR(64)  NOT NULL COMMENT '品类名称，如 电子产品/智能手机',
  category_level  TINYINT      NOT NULL COMMENT '层级：1一级大类 2二级中类 3三级小类',
  path            VARCHAR(255) NOT NULL COMMENT '层级路径，如 1/12/1203',
  sort_no         INT          NOT NULL DEFAULT 0 COMMENT '同级排序号',
  commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '平台佣金率（0-1）',
  is_enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_category_code (category_code),
  KEY idx_category_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品品类（三级层级树：大类/中类/小类）'
"""),
    ("brand", "商品主数据", """
CREATE TABLE brand (
  id            INT AUTO_INCREMENT COMMENT '品牌ID',
  brand_code    VARCHAR(32) NOT NULL COMMENT '品牌编码',
  brand_name    VARCHAR(64) NOT NULL COMMENT '品牌名称',
  brand_country VARCHAR(32) NOT NULL COMMENT '品牌所属国家/地区',
  brand_level   VARCHAR(16) NOT NULL COMMENT '品牌定位：高端/中端/大众',
  founded_year  INT         NOT NULL COMMENT '品牌创立年份',
  logo_url      VARCHAR(255)         COMMENT '品牌LOGO地址',
  is_enabled    TINYINT     NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_brand_code (brand_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品品牌'
"""),
    ("product", "商品主数据", """
CREATE TABLE product (
  id           INT AUTO_INCREMENT COMMENT '商品ID（SPU，标准产品单位）',
  product_code VARCHAR(32)   NOT NULL COMMENT '商品编码',
  product_name VARCHAR(128)  NOT NULL COMMENT '商品名称',
  category_id  INT           NOT NULL COMMENT '所属品类ID',
  brand_id     INT           NOT NULL COMMENT '所属品牌ID',
  supplier_id  INT           NOT NULL COMMENT '主供应商ID',
  product_type VARCHAR(16)   NOT NULL COMMENT '商品类型：自营/POP第三方/预售/赠品',
  unit         VARCHAR(16)   NOT NULL COMMENT '计量单位：台/件/盒/瓶/套',
  list_price   DECIMAL(12,2) NOT NULL COMMENT '标价（吊牌价）',
  cost_price   DECIMAL(12,2) NOT NULL COMMENT '成本价',
  weight_kg    DECIMAL(8,3)  NOT NULL DEFAULT 0 COMMENT '商品重量(kg)',
  launch_date  DATE          NOT NULL COMMENT '上架日期',
  is_enabled   TINYINT       NOT NULL DEFAULT 1 COMMENT '是否在售：1在售 0下架',
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_code (product_code),
  KEY idx_product_category (category_id),
  KEY idx_product_brand (brand_id),
  KEY idx_product_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SPU（标准产品单位，一个SPU下含多个SKU规格）'
"""),
    ("product_sku", "商品主数据", """
CREATE TABLE product_sku (
  id          INT AUTO_INCREMENT COMMENT 'SKU ID（最小库存单位）',
  product_id  INT           NOT NULL COMMENT '所属商品SPU ID',
  sku_code    VARCHAR(40)   NOT NULL COMMENT 'SKU编码',
  sku_spec    VARCHAR(64)   NOT NULL COMMENT '规格描述，如 曜石黑/128G',
  sale_price  DECIMAL(12,2) NOT NULL COMMENT '销售价',
  cost_price  DECIMAL(12,2) NOT NULL COMMENT '成本价',
  barcode     VARCHAR(40)            COMMENT '商品条码',
  is_enabled  TINYINT       NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_code (sku_code),
  KEY idx_sku_product (product_id),
  CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU（最小库存单位，含颜色/尺码/容量等具体规格）'
"""),
    ("product_attribute", "商品主数据", """
CREATE TABLE product_attribute (
  id             INT AUTO_INCREMENT COMMENT '属性ID',
  attribute_name VARCHAR(32) NOT NULL COMMENT '属性名称，如 颜色/尺码/容量/材质',
  attribute_type VARCHAR(16) NOT NULL COMMENT '属性类型：销售属性/规格属性',
  input_type     VARCHAR(16) NOT NULL COMMENT '录入方式：单选/多选/文本',
  is_required    TINYINT     NOT NULL DEFAULT 0 COMMENT '是否必填：1是 0否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_attribute_name (attribute_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品属性定义（颜色、尺码、容量等属性的字典）'
"""),
    ("product_attr_value", "商品主数据", """
CREATE TABLE product_attr_value (
  id                   INT AUTO_INCREMENT COMMENT '主键ID',
  product_sku_id       INT         NOT NULL COMMENT 'SKU ID',
  product_attribute_id INT         NOT NULL COMMENT '属性ID',
  attribute_value      VARCHAR(64) NOT NULL COMMENT '属性值，如 曜石黑/XL/256G',
  PRIMARY KEY (id),
  KEY idx_attrval_sku (product_sku_id),
  KEY idx_attrval_attr (product_attribute_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU属性值（SKU与属性定义的多对多桥表，如 某SKU=黑色+128G）'
"""),
    ("product_price_history", "商品主数据", """
CREATE TABLE product_price_history (
  id             INT AUTO_INCREMENT COMMENT '主键ID',
  product_sku_id INT           NOT NULL COMMENT 'SKU ID',
  effective_date DATE          NOT NULL COMMENT '调价生效日期',
  old_price      DECIMAL(12,2) NOT NULL COMMENT '调价前价格',
  new_price      DECIMAL(12,2) NOT NULL COMMENT '调价后价格',
  change_rate    DECIMAL(6,4)  NOT NULL COMMENT '调价幅度（(新-旧)/旧）',
  change_reason  VARCHAR(32)   NOT NULL COMMENT '调价原因：促销活动/成本上涨/竞品对标/清仓降价/新品定价',
  operator       VARCHAR(32)   NOT NULL COMMENT '操作人',
  PRIMARY KEY (id),
  KEY idx_price_sku (product_sku_id),
  KEY idx_price_date (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品价格变动历史（每次调价的流水，可做价格走势与毛利分析）'
"""),

    # ==================== 域 B：采购供应链（4 张） ====================
    ("supplier", "采购供应链", """
CREATE TABLE supplier (
  id                    INT AUTO_INCREMENT COMMENT '供应商ID',
  supplier_code         VARCHAR(32)  NOT NULL COMMENT '供应商编码',
  supplier_name         VARCHAR(128) NOT NULL COMMENT '供应商名称',
  contact_person        VARCHAR(32)  NOT NULL COMMENT '联系人',
  contact_phone         VARCHAR(20)  NOT NULL COMMENT '联系电话',
  province              VARCHAR(16)  NOT NULL COMMENT '所在省份',
  city                  VARCHAR(16)  NOT NULL COMMENT '所在城市',
  supplier_level        VARCHAR(16)  NOT NULL COMMENT '供应商等级：战略/核心/普通/淘汰',
  cooperation_start_date DATE        NOT NULL COMMENT '合作开始日期',
  credit_score          DECIMAL(5,2) NOT NULL COMMENT '信用评分(0-100)',
  on_time_rate          DECIMAL(5,4) NOT NULL COMMENT '历史到货及时率(0-1)',
  quality_pass_rate     DECIMAL(5,4) NOT NULL COMMENT '历史验收合格率(0-1)',
  is_enabled            TINYINT      NOT NULL DEFAULT 1 COMMENT '是否合作中：1是 0否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_code (supplier_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商（含信用等级、到货及时率、验收合格率）'
"""),
    ("supplier_product", "采购供应链", """
CREATE TABLE supplier_product (
  id             INT AUTO_INCREMENT COMMENT '主键ID',
  supplier_id    INT           NOT NULL COMMENT '供应商ID',
  product_id     INT           NOT NULL COMMENT '商品SPU ID',
  supply_price   DECIMAL(12,2) NOT NULL COMMENT '供货价',
  min_order_qty  INT           NOT NULL COMMENT '最小起订量',
  lead_time_days INT           NOT NULL COMMENT '供货周期(天)',
  is_primary     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否主供应商：1是 0否',
  PRIMARY KEY (id),
  KEY idx_sp_supplier (supplier_id),
  KEY idx_sp_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商供货关系（供应商与商品的多对多桥表，一个商品可有多个供应商）'
"""),
    ("purchase_order", "采购供应链", """
CREATE TABLE purchase_order (
  id                 INT AUTO_INCREMENT COMMENT '采购单ID',
  purchase_order_no  VARCHAR(32)   NOT NULL COMMENT '采购单号',
  supplier_id        INT           NOT NULL COMMENT '供应商ID',
  warehouse_id       INT           NOT NULL COMMENT '收货仓库ID',
  order_date         DATE          NOT NULL COMMENT '采购下单日期',
  order_month        VARCHAR(7)    NOT NULL COMMENT '采购月份 YYYY-MM',
  expect_arrive_date DATE          NOT NULL COMMENT '预计到货日期',
  actual_arrive_date DATE                   COMMENT '实际到货日期（未到货为空）',
  total_amount       DECIMAL(14,2) NOT NULL COMMENT '采购总金额',
  total_qty          INT           NOT NULL COMMENT '采购总数量',
  status             VARCHAR(16)   NOT NULL COMMENT '采购状态：待审核/已下单/部分到货/已完成/已取消',
  buyer              VARCHAR(32)   NOT NULL COMMENT '采购员',
  is_on_time         TINYINT       NOT NULL DEFAULT 0 COMMENT '是否按时到货：1是 0否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_po_no (purchase_order_no),
  KEY idx_po_supplier (supplier_id),
  KEY idx_po_warehouse (warehouse_id),
  KEY idx_po_date (order_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单（向供应商采购的主表，含到货及时率考核字段）'
"""),
    ("purchase_order_item", "采购供应链", """
CREATE TABLE purchase_order_item (
  id                INT AUTO_INCREMENT COMMENT '主键ID',
  purchase_order_id INT           NOT NULL COMMENT '采购单ID',
  product_sku_id    INT           NOT NULL COMMENT 'SKU ID',
  quantity          INT           NOT NULL COMMENT '采购数量',
  unit_price        DECIMAL(12,2) NOT NULL COMMENT '采购单价',
  amount            DECIMAL(14,2) NOT NULL COMMENT '小计金额',
  received_qty      INT           NOT NULL DEFAULT 0 COMMENT '已到货数量',
  rejected_qty      INT           NOT NULL DEFAULT 0 COMMENT '验收不合格数量',
  PRIMARY KEY (id),
  KEY idx_poi_po (purchase_order_id),
  KEY idx_poi_sku (product_sku_id),
  CONSTRAINT fk_poi_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购单明细（采购单与SKU的一对多明细，含到货与验收不合格数量）'
"""),

    # ==================== 域 C：客户与会员（6 张） ====================
    ("customer_level", "客户会员", """
CREATE TABLE customer_level (
  level_code   VARCHAR(16)   NOT NULL COMMENT '会员等级编码：NORMAL/SILVER/GOLD/PLATINUM/DIAMOND',
  level_name   VARCHAR(16)   NOT NULL COMMENT '会员等级名称：普通/银卡/金卡/铂金/钻石',
  level_order  TINYINT       NOT NULL COMMENT '等级序号（1最低5最高）',
  min_points   INT           NOT NULL COMMENT '升级所需最低积分',
  min_spend    DECIMAL(12,2) NOT NULL COMMENT '升级所需最低累计消费',
  discount_rate DECIMAL(4,3) NOT NULL COMMENT '折扣率（0.95表示95折）',
  point_ratio  DECIMAL(4,2)  NOT NULL COMMENT '积分倍率',
  benefit_desc VARCHAR(255)  NOT NULL COMMENT '等级权益说明',
  PRIMARY KEY (level_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级规则（**以业务编码 level_code 为主键**，客户表按此编码关联）'
"""),
    ("customer_tag", "客户会员", """
CREATE TABLE customer_tag (
  id         INT AUTO_INCREMENT COMMENT '标签ID',
  tag_name   VARCHAR(32)  NOT NULL COMMENT '标签名称，如 高价值客户/沉睡客户/价格敏感/母婴人群',
  tag_group  VARCHAR(32)  NOT NULL COMMENT '标签分组：价值分层/生命周期/消费偏好/风险预警',
  rule_desc  VARCHAR(255) NOT NULL COMMENT '打标规则说明',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tag_name (tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户标签字典（价值分层、生命周期、消费偏好等标签定义）'
"""),
    ("customer", "客户会员", """
CREATE TABLE customer (
  id               INT AUTO_INCREMENT COMMENT '客户ID',
  customer_no      VARCHAR(32)   NOT NULL COMMENT '客户编号',
  customer_name    VARCHAR(64)   NOT NULL COMMENT '客户姓名',
  gender           VARCHAR(4)    NOT NULL COMMENT '性别：男/女',
  age              INT           NOT NULL COMMENT '年龄',
  birth_date       DATE                   COMMENT '出生日期',
  phone            VARCHAR(20)   NOT NULL COMMENT '手机号',
  email            VARCHAR(64)            COMMENT '邮箱',
  level_code       VARCHAR(16)   NOT NULL COMMENT '会员等级编码（关联 customer_level.level_code）',
  region_id        INT           NOT NULL COMMENT '所属区域ID',
  province         VARCHAR(16)   NOT NULL COMMENT '所在省份',
  city             VARCHAR(16)   NOT NULL COMMENT '所在城市',
  register_date    DATE          NOT NULL COMMENT '注册日期',
  register_channel VARCHAR(16)   NOT NULL COMMENT '注册来源渠道：线上商城/第三方平台/线下门店/分销商/直播带货/企业团购/私域小程序/O2O到家',
  total_points     INT           NOT NULL DEFAULT 0 COMMENT '当前可用积分',
  total_spend      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '累计消费金额',
  order_count      INT           NOT NULL DEFAULT 0 COMMENT '累计订单数',
  last_order_date  DATE                   COMMENT '最近一次下单日期',
  is_active        TINYINT       NOT NULL DEFAULT 1 COMMENT '是否活跃客户：1是 0否（近90天有下单）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_customer_no (customer_no),
  KEY idx_customer_level (level_code),
  KEY idx_customer_region (region_id),
  KEY idx_customer_province (province)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户（会员主表，含RFM基础字段：累计消费、订单数、最近下单日期）'
"""),
    ("customer_address", "客户会员", """
CREATE TABLE customer_address (
  id             INT AUTO_INCREMENT COMMENT '主键ID',
  customer_id    INT          NOT NULL COMMENT '客户ID',
  receiver_name  VARCHAR(32)  NOT NULL COMMENT '收货人姓名',
  receiver_phone VARCHAR(20)  NOT NULL COMMENT '收货人电话',
  province       VARCHAR(16)  NOT NULL COMMENT '收货省份',
  city           VARCHAR(16)  NOT NULL COMMENT '收货城市',
  district       VARCHAR(16)  NOT NULL COMMENT '收货区县',
  detail_address VARCHAR(255) NOT NULL COMMENT '详细地址',
  is_default     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认地址：1是 0否',
  PRIMARY KEY (id),
  KEY idx_addr_customer (customer_id),
  CONSTRAINT fk_addr_customer FOREIGN KEY (customer_id) REFERENCES customer (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户收货地址（一个客户可有多个收货地址）'
"""),
    ("customer_tag_rel", "客户会员", """
CREATE TABLE customer_tag_rel (
  id              INT AUTO_INCREMENT COMMENT '主键ID',
  customer_id     INT          NOT NULL COMMENT '客户ID',
  customer_tag_id INT          NOT NULL COMMENT '客户标签ID',
  tagged_at       DATETIME     NOT NULL COMMENT '打标时间',
  confidence      DECIMAL(4,3) NOT NULL COMMENT '打标置信度(0-1)',
  PRIMARY KEY (id),
  KEY idx_tagrel_customer (customer_id),
  KEY idx_tagrel_tag (customer_tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户标签关联（客户与标签的多对多桥表，一个客户可有多个标签）'
"""),
    ("member_point_log", "客户会员", """
CREATE TABLE member_point_log (
  id             INT AUTO_INCREMENT COMMENT '主键ID',
  customer_id    INT         NOT NULL COMMENT '客户ID',
  order_id       INT                  COMMENT '关联销售订单ID（消费得积分/退款扣积分时填）',
  change_date    DATE        NOT NULL COMMENT '积分变动日期',
  change_type    VARCHAR(16) NOT NULL COMMENT '变动类型：消费获得/退款扣减/活动赠送/兑换消耗/过期清零',
  change_points  INT         NOT NULL COMMENT '变动积分（正为增加，负为减少）',
  balance_points INT         NOT NULL COMMENT '变动后积分余额',
  PRIMARY KEY (id),
  KEY idx_point_customer (customer_id),
  KEY idx_point_order (order_id),
  KEY idx_point_date (change_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员积分流水（每笔积分增减的记录，可追溯复购与活跃度）'
"""),

    # ==================== 域 D：组织与渠道（7 张） ====================
    ("region", "组织渠道", """
CREATE TABLE region (
  id          INT AUTO_INCREMENT COMMENT '区域ID',
  parent_id   INT         NOT NULL DEFAULT 0 COMMENT '上级区域ID（大区为0，构成区域树）',
  region_code VARCHAR(16) NOT NULL COMMENT '区域编码',
  region_name VARCHAR(32) NOT NULL COMMENT '区域名称',
  region_level TINYINT    NOT NULL COMMENT '层级：1大区 2省份 3城市',
  region_type VARCHAR(16) NOT NULL COMMENT '类型：大区/省份/城市',
  big_region  VARCHAR(16) NOT NULL COMMENT '所属大区：华东/华北/华南/华中/西南/西北/东北',
  province    VARCHAR(16)          COMMENT '所属省份（大区行为空）',
  city        VARCHAR(16)          COMMENT '所属城市（大区与省份行为空）',
  city_tier   VARCHAR(8)           COMMENT '城市线级：一线/新一线/二线/三线/四线',
  PRIMARY KEY (id),
  UNIQUE KEY uk_region_code (region_code),
  KEY idx_region_parent (parent_id),
  KEY idx_region_province (province)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行政区域（三级层级树：大区/省份/城市，含城市线级）'
"""),
    ("channel", "组织渠道", """
CREATE TABLE channel (
  id           INT AUTO_INCREMENT COMMENT '渠道ID',
  channel_code VARCHAR(16)  NOT NULL COMMENT '渠道编码',
  channel_name VARCHAR(32)  NOT NULL COMMENT '渠道名称：线上商城/第三方平台/线下门店/分销商/直播带货/企业团购/私域小程序/O2O到家',
  channel_type VARCHAR(16)  NOT NULL COMMENT '渠道类型：线上/线下',
  fee_rate     DECIMAL(5,4) NOT NULL COMMENT '渠道费率(0-1)',
  is_enabled   TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_code (channel_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售渠道字典（线上商城、第三方平台、线下门店、直播带货等）'
"""),
    ("platform", "组织渠道", """
CREATE TABLE platform (
  id                INT AUTO_INCREMENT COMMENT '平台ID',
  platform_code     VARCHAR(16)  NOT NULL COMMENT '平台编码',
  platform_name     VARCHAR(32)  NOT NULL COMMENT '平台名称：天猫/京东/拼多多/抖音/快手/小红书/唯品会/苏宁易购/美团闪购/微信小店',
  settle_cycle_days INT          NOT NULL COMMENT '结算周期(天)',
  commission_rate   DECIMAL(5,4) NOT NULL COMMENT '平台扣点率(0-1)',
  is_enabled        TINYINT      NOT NULL DEFAULT 1 COMMENT '是否合作中：1是 0否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_code (platform_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='第三方电商平台（天猫、京东、抖音等，含扣点率与结算周期）'
"""),
    ("store", "组织渠道", """
CREATE TABLE store (
  id          INT AUTO_INCREMENT COMMENT '门店ID',
  store_code  VARCHAR(32)  NOT NULL COMMENT '门店编码',
  store_name  VARCHAR(64)  NOT NULL COMMENT '门店名称',
  store_type  VARCHAR(16)  NOT NULL COMMENT '门店类型：旗舰店/直营店/加盟店/前置仓/专柜/线上店铺',
  channel_id  INT          NOT NULL COMMENT '所属销售渠道ID',
  platform_id INT                   COMMENT '所属电商平台ID（线下门店为空）',
  region_id   INT          NOT NULL COMMENT '所属区域ID',
  province    VARCHAR(16)  NOT NULL COMMENT '所在省份',
  city        VARCHAR(16)  NOT NULL COMMENT '所在城市',
  open_date   DATE         NOT NULL COMMENT '开业日期',
  area_sqm    DECIMAL(8,2) NOT NULL COMMENT '营业面积(平方米)',
  staff_count INT          NOT NULL COMMENT '员工数',
  is_enabled  TINYINT      NOT NULL DEFAULT 1 COMMENT '是否营业中：1是 0否',
  PRIMARY KEY (id),
  UNIQUE KEY uk_store_code (store_code),
  KEY idx_store_channel (channel_id),
  KEY idx_store_region (region_id),
  KEY idx_store_province (province)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店/店铺（线下门店与线上店铺统一建模，含营业面积与员工数）'
"""),
    ("store_staff", "组织渠道", """
CREATE TABLE store_staff (
  id          INT AUTO_INCREMENT COMMENT '员工ID',
  store_id    INT           NOT NULL COMMENT '所属门店ID',
  staff_no    VARCHAR(20)   NOT NULL COMMENT '员工工号',
  staff_name  VARCHAR(32)   NOT NULL COMMENT '员工姓名',
  position    VARCHAR(16)   NOT NULL COMMENT '岗位：店长/副店长/导购/收银/仓管',
  hire_date   DATE          NOT NULL COMMENT '入职日期',
  base_salary DECIMAL(10,2) NOT NULL COMMENT '底薪',
  is_enabled  TINYINT       NOT NULL DEFAULT 1 COMMENT '是否在职：1在职 0离职',
  PRIMARY KEY (id),
  UNIQUE KEY uk_staff_no (staff_no),
  KEY idx_staff_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店员工（导购、店长等，人效分析的基础）'
"""),
    ("sales_team", "组织渠道", """
CREATE TABLE sales_team (
  id            INT AUTO_INCREMENT COMMENT '销售团队ID',
  team_code     VARCHAR(16)   NOT NULL COMMENT '团队编码',
  team_name     VARCHAR(32)   NOT NULL COMMENT '团队名称',
  team_type     VARCHAR(16)   NOT NULL COMMENT '团队类型：大客户组/区域组/线上组/渠道组',
  region_id     INT           NOT NULL COMMENT '负责区域ID',
  leader_name   VARCHAR(32)   NOT NULL COMMENT '团队负责人',
  headcount     INT           NOT NULL COMMENT '团队编制人数',
  target_amount DECIMAL(14,2) NOT NULL COMMENT '团队年度销售目标',
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_code (team_code),
  KEY idx_team_region (region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售团队（含团队类型、负责人与销售目标，用于达成率分析）'
"""),
    ("sales_rep", "组织渠道", """
CREATE TABLE sales_rep (
  id              INT AUTO_INCREMENT COMMENT '销售员ID',
  sales_team_id   INT           NOT NULL COMMENT '所属销售团队ID',
  store_id        INT           NOT NULL COMMENT '常驻门店ID',
  rep_no          VARCHAR(20)   NOT NULL COMMENT '销售员工号',
  rep_name        VARCHAR(32)   NOT NULL COMMENT '销售员姓名',
  rep_level       VARCHAR(16)   NOT NULL COMMENT '职级：初级/中级/高级/资深',
  hire_date       DATE          NOT NULL COMMENT '入职日期',
  commission_rate DECIMAL(5,4)  NOT NULL COMMENT '销售提成比例(0-1)',
  target_amount   DECIMAL(14,2) NOT NULL COMMENT '个人年度销售目标',
  is_enabled      TINYINT       NOT NULL DEFAULT 1 COMMENT '是否在职：1在职 0离职',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rep_no (rep_no),
  KEY idx_rep_team (sales_team_id),
  KEY idx_rep_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售员（含职级、提成比例与个人目标，业绩归因到人）'
"""),

    # ==================== 域 E：仓储（1 张） ====================
    ("warehouse", "仓储", """
CREATE TABLE warehouse (
  id             INT AUTO_INCREMENT COMMENT '仓库ID',
  warehouse_code VARCHAR(16)   NOT NULL COMMENT '仓库编码',
  warehouse_name VARCHAR(32)   NOT NULL COMMENT '仓库名称：华东中心仓/华北中心仓/华南区域仓等',
  warehouse_type VARCHAR(16)   NOT NULL COMMENT '仓库类型：中心仓/区域仓/前置仓/退货仓',
  region_id      INT           NOT NULL COMMENT '所属区域ID',
  province       VARCHAR(16)   NOT NULL COMMENT '所在省份',
  city           VARCHAR(16)   NOT NULL COMMENT '所在城市',
  capacity_sqm   DECIMAL(10,2) NOT NULL COMMENT '仓储面积(平方米)',
  is_enabled     TINYINT       NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouse_code (warehouse_code),
  KEY idx_wh_region (region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库（中心仓/区域仓/前置仓/退货仓，库存与发货的主体）'
"""),
]
