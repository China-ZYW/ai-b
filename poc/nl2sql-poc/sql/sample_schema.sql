-- =============================================================
-- 示例电商数据库：用于 NL2SQL PoC / MVP 后端骨架联调
-- 库名：aibi_demo
-- 导入：mysql -uroot -p < sample_schema.sql
-- =============================================================

CREATE DATABASE IF NOT EXISTS aibi_demo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE aibi_demo;

DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS customers;

CREATE TABLE categories (
  id   INT PRIMARY KEY AUTO_INCREMENT COMMENT '品类ID',
  name VARCHAR(50) NOT NULL COMMENT '品类名称'
) COMMENT '商品品类';

CREATE TABLE products (
  id          INT PRIMARY KEY AUTO_INCREMENT COMMENT '商品ID',
  name        VARCHAR(100)  NOT NULL COMMENT '商品名称',
  category_id INT           NOT NULL COMMENT '品类ID',
  price       DECIMAL(10,2) NOT NULL COMMENT '商品单价'
) COMMENT '商品';

CREATE TABLE customers (
  id     INT PRIMARY KEY AUTO_INCREMENT COMMENT '客户ID',
  name   VARCHAR(50) NOT NULL COMMENT '客户姓名',
  region VARCHAR(50) NOT NULL COMMENT '所在地区',
  level  VARCHAR(20) NOT NULL COMMENT '客户等级：VIP/普通/企业'
) COMMENT '客户';

CREATE TABLE orders (
  id          INT PRIMARY KEY AUTO_INCREMENT COMMENT '订单ID',
  customer_id INT           NOT NULL COMMENT '客户ID',
  amount      DECIMAL(12,2) NOT NULL COMMENT '订单金额',
  status      VARCHAR(20)   NOT NULL COMMENT '订单状态：paid/pending/refunded',
  created_at  DATETIME      NOT NULL COMMENT '下单时间'
) COMMENT '订单';

CREATE TABLE order_items (
  id         INT PRIMARY KEY AUTO_INCREMENT COMMENT '明细ID',
  order_id   INT           NOT NULL COMMENT '订单ID',
  product_id INT           NOT NULL COMMENT '商品ID',
  quantity   INT           NOT NULL COMMENT '购买数量',
  price      DECIMAL(10,2) NOT NULL COMMENT '成交单价'
) COMMENT '订单明细';

-- ------------------------- 样本数据 -------------------------
INSERT INTO categories (id, name) VALUES
  (1,'电子产品'),(2,'家居'),(3,'服饰'),(4,'食品');

INSERT INTO products (id, name, category_id, price) VALUES
  (1,'智能手机',1,2999.00),
  (2,'笔记本电脑',1,5999.00),
  (3,'布艺沙发',2,1899.00),
  (4,'床上四件套',2,399.00),
  (5,'纯棉T恤',3,99.00),
  (6,'直筒牛仔裤',3,299.00),
  (7,'坚果礼盒',4,129.00);

INSERT INTO customers (id, name, region, level) VALUES
  (1,'张伟','华东','VIP'),
  (2,'李娜','华北','普通'),
  (3,'王强','华南','VIP'),
  (4,'刘洋','华东','普通'),
  (5,'陈静','西南','企业');

INSERT INTO orders (id, customer_id, amount, status, created_at) VALUES
  (1,1,2999.00,'paid',    '2026-07-05 10:00:00'),
  (2,2, 399.00,'paid',    '2026-07-12 14:30:00'),
  (3,3,5999.00,'paid',    '2026-07-20 09:15:00'),
  (4,1, 299.00,'refunded','2026-08-01 16:45:00'),
  (5,4,1899.00,'paid',    '2026-08-08 11:20:00'),
  (6,5, 129.00,'paid',    '2026-08-15 08:05:00'),
  (7,3,  99.00,'pending', '2026-08-22 19:40:00'),
  (8,2,5999.00,'paid',    '2026-08-28 13:10:00');

INSERT INTO order_items (order_id, product_id, quantity, price) VALUES
  (1,1,1,2999.00),
  (2,4,1, 399.00),
  (3,2,1,5999.00),
  (4,6,1, 299.00),
  (5,3,1,1899.00),
  (6,7,1, 129.00),
  (7,5,1,  99.00),
  (8,2,1,5999.00);

-- ------------------------- 测试问题（G0 评测参考）-------------------------
-- 1. 2026年8月每个地区的已支付订单总金额是多少？
-- 2. 每个品类的商品数量有多少？
-- 3. VIP 客户的订单总数是多少？
-- 4. 销售额最高的前 3 个商品是哪些？
-- 5. 每个月的有效订单（paid）数量趋势如何？
