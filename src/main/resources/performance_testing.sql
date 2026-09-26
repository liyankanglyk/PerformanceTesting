/*
 Navicat Premium Dump SQL

 Source Server         : 8.0
 Source Server Type    : MySQL
 Source Server Version : 80012 (8.0.12)
 Source Host           : localhost:3380
 Source Schema         : performance_testing

 Target Server Type    : MySQL
 Target Server Version : 80012 (8.0.12)
 File Encoding         : 65001

 Date: 26/09/2026 23:45:32
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for goods
-- ----------------------------
DROP TABLE IF EXISTS `goods`;
CREATE TABLE `goods`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `goods_name` varchar(200) CHARACTER SET utf8 COLLATE utf8_unicode_ci NOT NULL,
  `price` decimal(10, 2) NOT NULL,
  `stock` int(11) NOT NULL DEFAULT 0,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = MyISAM AUTO_INCREMENT = 16 CHARACTER SET = utf8 COLLATE = utf8_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of goods
-- ----------------------------
INSERT INTO `goods` VALUES (1, '苹果 iPhone 16 Pro Max', 9999.00, 500, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (2, '华为 Mate 70 Pro', 6999.00, 300, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (3, '小米 15 Ultra', 5999.00, 400, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (4, '索尼 WH-1000XM6 头戴式耳机', 2499.00, 800, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (5, '戴森 V16 无线吸尘器', 4999.00, 200, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (6, 'Apple MacBook Pro 14 英寸', 14999.00, 150, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (7, '联想 ThinkPad X1 Carbon', 10999.00, 180, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (8, '任天堂 Switch 2', 2599.00, 600, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (9, '三星 Galaxy S25 Ultra', 8999.00, 350, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (10, '大疆 Air 4 无人机', 7999.00, 120, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (11, 'AirPods Pro 3', 1899.00, 1000, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (12, 'iPad Air M2', 4799.00, 250, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (13, 'Apple Watch Ultra 3', 6499.00, 200, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (14, '佳能 EOS R6 Mark III', 18999.00, 80, '2026-09-26 23:45:17');
INSERT INTO `goods` VALUES (15, '戴尔 U4025QW 曲面显示器', 8999.00, 100, '2026-09-26 23:45:17');

-- ----------------------------
-- Table structure for operation_log
-- ----------------------------
DROP TABLE IF EXISTS `operation_log`;
CREATE TABLE `operation_log`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `operator_id` bigint(20) NOT NULL,
  `username` varchar(50) CHARACTER SET utf8 COLLATE utf8_unicode_ci NOT NULL,
  `role` tinyint(4) NULL DEFAULT NULL,
  `action` varchar(50) CHARACTER SET utf8 COLLATE utf8_unicode_ci NOT NULL,
  `detail` varchar(500) CHARACTER SET utf8 COLLATE utf8_unicode_ci NULL DEFAULT '',
  `ip` varchar(45) CHARACTER SET utf8 COLLATE utf8_unicode_ci NULL DEFAULT '',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_create_time`(`create_time`) USING BTREE
) ENGINE = MyISAM AUTO_INCREMENT = 2 CHARACTER SET = utf8 COLLATE = utf8_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of operation_log
-- ----------------------------
INSERT INTO `operation_log` VALUES (1, 1, 'admin', 1, 'DB_RESET', '重新灌入测试数据（mode=full，耗时 221ms）；重置后 user=4, goods=15, orders=0, logs=0', '127.0.0.1', '2026-09-26 23:45:18');

-- ----------------------------
-- Table structure for orders
-- ----------------------------
DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) CHARACTER SET utf8 COLLATE utf8_unicode_ci NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `goods_id` bigint(20) NOT NULL,
  `pay_price` decimal(10, 2) NOT NULL,
  `create_ts` bigint(20) NOT NULL,
  `status` int(11) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `order_no`(`order_no`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_goods_id`(`goods_id`) USING BTREE,
  INDEX `idx_create_ts`(`create_ts`) USING BTREE,
  INDEX `idx_status_create_ts`(`status`, `create_ts`) USING BTREE
) ENGINE = MyISAM AUTO_INCREMENT = 1 CHARACTER SET = utf8 COLLATE = utf8_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of orders
-- ----------------------------

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8 COLLATE utf8_unicode_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8 COLLATE utf8_unicode_ci NOT NULL,
  `role` int(11) NOT NULL DEFAULT 0,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username`) USING BTREE
) ENGINE = MyISAM AUTO_INCREMENT = 5 CHARACTER SET = utf8 COLLATE = utf8_unicode_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user
-- ----------------------------
INSERT INTO `user` VALUES (1, 'admin', '123456', 1, '2026-09-26 23:45:17');
INSERT INTO `user` VALUES (2, 'test001', '123456', 0, '2026-09-26 23:45:17');
INSERT INTO `user` VALUES (3, 'test002', '123456', 0, '2026-09-26 23:45:17');
INSERT INTO `user` VALUES (4, 'test003', '123456', 0, '2026-09-26 23:45:17');

SET FOREIGN_KEY_CHECKS = 1;
