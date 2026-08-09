# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在本仓库工作时提供指引。

## 项目概述

`hmall`（黑马商城）—— Spring Cloud Alibaba 微服务电商项目，用于微服务课程。技术栈：Java 11、Spring Boot 2.7.12、Spring Cloud 2021.0.3、Spring Cloud Alibaba 2021.0.4.0、MyBatis-Plus 3.4.3、Lombok、Knife4j。Maven 多模块工程，`hmall` 为父 POM。

## 构建与运行

**Maven 不在 PATH 上**，请使用 IntelliJ IDEA 自带的 Maven：

```bash
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"

# 编译某个模块（连同其 reactor 依赖）
$MVN -pl trade-service,pay-service -am compile -DskipTests

# 单独启动某个服务
$MVN -pl trade-service spring-boot:run -DskipTests
```

- 各服务依赖 `hm-common` / `hm-api`；用 `-am` 一起构建，或先 `mvn install` 一次（`$MVN -pl hm-common,hm-api -am install -DskipTests`），之后才能单独运行某个模块。
- 所有服务固定激活 `local` profile（每个 `application.yaml` 里写死 `spring.profiles.active: local`）；数据库账号密码在 `application-local.yaml`（`hm.db.host: localhost`、`hm.db.port: 3307`、`hm.db.pw: root`）。

## 架构

请求链路：浏览器 → **nginx**（`hmall-frontend` 容器，18080/18081）→ 剥离 `/api` 前缀 → **hm-gateway**（8080）→ 按路径经 `lb://<service>` 路由到各服务。

- **hm-gateway**（8080）：Spring Cloud Gateway，JWT 鉴权，路由分发
- **item-service**（8081）：商品
- **cart-service**（8082）：购物车
- **user-service**（8084）：用户/余额
- **trade-service**（8085）：订单
- **pay-service**（8086）：支付
- **hm-service**（8088）：空壳，未使用

- **网关路由**（`hm-gateway/application.yaml`）匹配 `/items/**`、`/orders/**` 等路径——**不带 `/api` 前缀**。nginx 先把 `/api/(.*)` 重写为 `/$1` 再转发。因此**直接访问网关 `:8080/api/...` 会 404，必须走 nginx**。
- **Feign 客户端**位于 `hm-api`（`CartClient`、`ItemClient`、`TradeClient`、`UserClient`），服务间调用通过 OpenFeign + LoadBalancer。
- **服务发现**：Nacos `localhost:8848`（所有服务注册于此）。
- **`hm-common`** 里的 AMQP / Jackson-XML 依赖都是 `provided` 作用域——**不会传递到下游**，每个需要 MQ 的服务必须自己加 `spring-boot-starter-amqp`。

## 基础设施（Docker 容器，均在 localhost）

- **mysql**（3307）：root / root，库 `hmall`
- **nacos**（8848）：服务注册发现
- **rabbitmq**（5672，管理台 15672）：vhost `/hmall`，用户 `hmall`，密码 `123`
- **hmall-frontend**（18080 门户 / 18081 后台 / 18082 刷新后台）：nginx；`/api` → `host.docker.internal:8080`

RabbitMQ 管理台：http://localhost:15672（hmall/123）。

## 支付成功异步流程（RabbitMQ）

已在本仓库实现。余额支付成功后：

1. `pay-service` 的 `PayOrderServiceImpl.tryPayOrderByBalance` 向交换机 **`pay.direct`**、routing key **`pay.success`** 发送 `po.getBizOrderNo()`（`Long` 类型订单 id）。（原先同步调用 `tradeClient.markOrderPaySuccess` 已注释）
2. `trade-service` 的 `PayStatusListener`（`@RabbitListener` 绑定队列 **`trade.pay.success.queue`** ← `pay.direct`，key `pay.success`）调用 `IOrderService.markOrderPaySuccess(orderId)` → 将订单状态置为 `2` 并写入 `pay_time`。

交换机 / 队列 / 绑定由 `@RabbitListener` 在启动时自动声明，无需手动建 MQ。

## 踩坑记录

- **日志**：MyBatis-Plus 的 `ServiceImpl` 自带一个 `log` 字段，类型是 `org.apache.ibatis.logging.Log`，只支持 `error(String)` / `error(String, Throwable)`，**不支持 SLF4J 的 `{}` 占位符**。要用占位符日志就给类加 Lombok `@Slf4j`（生成的字段会遮蔽父类字段）。`PayOrderServiceImpl` 和 `OrderServiceImpl` 已如此处理。
- **Spring AMQP 2.4**：`@Queue.durable` 是 `String` 类型（`durable = "true"`），不是 `boolean`。
- **默认消息转换器**是 Java 序列化（`application/x-java-serialized-object`），所以 `RabbitTemplate.convertAndSend` 发的 `Long` 订单 id，消费者侧也要用 `Long` 参数接收。发普通 JSON 数字不会反序列化成 `Long`。
- **8080 端口**是网关的端口；之前被一个 RocketMQ dashboard 容器占用，现已从 Docker 移除。不要再往宿主机 8080 上挂任何容器。
