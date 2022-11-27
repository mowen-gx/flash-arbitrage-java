/*
 Navicat Premium Data Transfer

 Source Server         : 本机DockerMySQL
 Source Server Type    : MySQL
 Source Server Version : 50733
 Source Host           : localhost
 Source Database       : delta

 Target Server Type    : MySQL
 Target Server Version : 50733
 File Encoding         : utf-8

 Date: 11/27/2022 12:09:44 PM
*/

SET NAMES utf8;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
--  Table structure for `t_chain_info`
-- ----------------------------
DROP TABLE IF EXISTS `t_chain_info`;
CREATE TABLE `t_chain_info` (
  `id` int(11) NOT NULL,
  `name` varchar(56) NOT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
--  Table structure for `t_delta_log`
-- ----------------------------
DROP TABLE IF EXISTS `t_delta_log`;
CREATE TABLE `t_delta_log` (
  `id` int(11) NOT NULL,
  `tx_id` varchar(58) NOT NULL COMMENT '交易id',
  `estimate_profit` bigint(20) NOT NULL COMMENT '预计利润',
  `estimate_gas` bigint(20) NOT NULL COMMENT '预计gas',
  `real_profit` bigint(20) DEFAULT NULL COMMENT '实际利润',
  `real_gas` bigint(20) DEFAULT NULL,
  `token_amount_in` bigint(20) DEFAULT NULL COMMENT '输入Token量',
  `token_amount_out` bigint(20) DEFAULT NULL COMMENT '输出的token量',
  `pair_info_list` varchar(256) DEFAULT NULL COMMENT '交易对信息json',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
--  Table structure for `t_dex_factory_info`
-- ----------------------------
DROP TABLE IF EXISTS `t_dex_factory_info`;
CREATE TABLE `t_dex_factory_info` (
  `id` int(11) NOT NULL,
  `addr` varchar(56) DEFAULT NULL,
  `name` varchar(56) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
--  Table structure for `t_dex_info`
-- ----------------------------
DROP TABLE IF EXISTS `t_dex_info`;
CREATE TABLE `t_dex_info` (
  `id` int(11) NOT NULL COMMENT 'ID',
  `name` varchar(256) DEFAULT NULL COMMENT '交易所名字',
  `addr` varchar(56) DEFAULT NULL COMMENT '合约地址',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
--  Table structure for `t_dex_router_info`
-- ----------------------------
DROP TABLE IF EXISTS `t_dex_router_info`;
CREATE TABLE `t_dex_router_info` (
  `id` int(11) NOT NULL,
  `addr` varchar(56) DEFAULT NULL,
  `name` varchar(56) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
--  Table structure for `t_gateway_info`
-- ----------------------------
DROP TABLE IF EXISTS `t_gateway_info`;
CREATE TABLE `t_gateway_info` (
  `id` int(11) NOT NULL,
  `http_url` varchar(256) NOT NULL COMMENT 'httpurl',
  `name` varchar(56) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `web_socket_url` varchar(256) DEFAULT NULL COMMENT 'wss链接地址',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
--  Table structure for `t_pair_info`
-- ----------------------------
DROP TABLE IF EXISTS `t_pair_info`;
CREATE TABLE `t_pair_info` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `pair_addr` varchar(56) NOT NULL COMMENT '交易对合约地址',
  `token0` varchar(56) NOT NULL COMMENT '交易对代币1',
  `token1` varchar(56) NOT NULL COMMENT '交易对代币2',
  `reserve0` bigint(20) NOT NULL DEFAULT '0' COMMENT '交易对代币1的资金池资金数量',
  `reserve1` bigint(20) NOT NULL DEFAULT '0' COMMENT '交易对Token2的目前数量',
  `fee` bigint(20) NOT NULL COMMENT '交易对SWAP费用',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
--  Table structure for `t_token_info`
-- ----------------------------
DROP TABLE IF EXISTS `t_token_info`;
CREATE TABLE `t_token_info` (
  `id` int(11) NOT NULL,
  `addr` varchar(56) NOT NULL COMMENT '代币合约地址',
  `name` varchar(256) NOT NULL COMMENT '代币名字',
  `symbol` varchar(255) NOT NULL COMMENT '简称',
  `decimals` int(11) DEFAULT NULL COMMENT '精度',
  `owner` varchar(56) DEFAULT NULL COMMENT '代币拥有者',
  `create_time` datetime NOT NULL COMMENT '记录创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_white` tinyint(4) NOT NULL COMMENT '是否为白名单',
  `is_black` tinyint(4) NOT NULL COMMENT '是否为黑名单',
  `is_base` tinyint(4) NOT NULL COMMENT '是否为BaseToken（稳定币）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;
