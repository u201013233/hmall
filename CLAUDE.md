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

## 服务保护（Sentinel）

已在本仓库实现（day05 的「服务保护」部分，**分布式事务/Seata 尚未实现**）。

- **接入模块**：`cart-service`（购物车，含 Tomcat 线程数调小，便于演示线程隔离）、`trade-service`、`pay-service`。三者都有 `spring-cloud-starter-alibaba-sentinel` 依赖，并在 `application.yaml` 中配置了控制台地址 `localhost:8090`、`http-method-specify: true`（簇点资源名带请求方式前缀，如 `GET:/carts`）、`sentinel.log.dir: logs/sentinel`。
- **Feign 整合**：三个服务都开了 `feign.sentinel.enabled: true`，FeignClient 会作为簇点资源出现（如 `GET:http://item-service/items`）。
- **降级逻辑**：`hm-api` 中 `ItemClientFallback`（新建）与 `PayClientFallback` 实现 `FallbackFactory`，都在 `DefaultFeignConfig` 里注册成 Bean，并通过 `@FeignClient(fallbackFactory = ...)` 挂到客户端上。
  - `ItemClient.queryItemByIds` 降级返回空集合 —— 购物车列表仍能正常展示，只是没有最新商品信息（`CartServiceImpl.handleCartItems` 已经处理了空集合）。
  - `deductStock` / `restoreStock` 这类**写操作不降级**，降级方法直接抛 `BizIllegalException`，避免「库存没扣但订单成功」的脏数据。
- **启动 Sentinel 控制台**：需要一个 sentinel-dashboard（1.8.x）jar，命令见课件，端口 8090；控制台不在线时服务也能正常启动，只是看不到簇点链路、配不了规则。
- **限流/线程隔离/熔断规则**都在控制台上配置：簇点链路 → 流控（QPS / 并发线程数）→ 熔断（慢调用比例）。

### 已实测的三条规则（2026-09-26）

控制台 jar 在课件资料里（`sentinel-dashboard-1.8.6.jar`），启动命令：

```bash
java -Dserver.port=8090 -Dcsp.sentinel.dashboard.server=localhost:8090 -Dproject.name=sentinel-dashboard -jar sentinel-dashboard-1.8.6.jar
```

实测结论（cart-service 的簇点资源名就是 `GET:/carts` 和 `GET:http://item-service/items`）：

| 规则 | 配置 | 实测结果 |
| --- | --- | --- |
| 请求限流 | `GET:/carts`，QPS 阈值 6 | 每秒 30 请求压测：60 个请求 18 个通过、42 个被拒（HTTP 429） |
| 线程隔离 | `GET:http://item-service/items`，并发线程数 5 | 400 并发压测：全部 200，其中 259 个走了降级（商品信息为空），没有一个 500 |
| 服务熔断 | 同上资源，异常比例 > 0.5，时长 20s | 停掉 item-service 后连续请求 14 次：5 次通过（记异常）+ 9 次被熔断直接拒绝，接口仍返回 200 且商品信息降级 |

规则默认只存在内存里，重启服务即失效，所以上面这些规则我在验证完后已经清空。

### Sentinel 踩坑

- **降级工厂必须是 Bean**：开启 `feign.sentinel.enabled` 后，Feign 会按类型从容器里找 `fallbackFactory` 的实例，找不到就报 `No fallbackFactory instance of type class ... found`。本仓库统一放在 `hm-api` 的 `DefaultFeignConfig` 里注册，所以**每个用 Feign 的服务都必须在 `@EnableFeignClients` 上写 `defaultConfiguration = DefaultFeignConfig.class`**（`cart-service` 原来没写，本次已补上）。
- **Sentinel 日志目录**：默认写在用户目录下，容易报 `AccessDeniedException logs\csp\sentinel-record.log`，本项目统一指到 `logs/sentinel`。
- **限流规则不持久化**：控制台里配的规则存在内存里，服务重启就没了；要持久化得接 Nacos 等数据源（本仓库暂未接）。
- **客户端端口**：控制台自己也注册成一个 Sentinel 客户端并占用 8719，所以同一台机器上的微服务会自动顺延（cart-service 在 8720）。用客户端命令接口（`/setRules`、`/getRules`）时要先确认端口。
- **JDK 版本**：命令行直接 `java -cp ...` 启动服务时用 JDK 17 会在 MyBatis-Plus 的 lambda 查询上抛 `InaccessibleObjectException: java.base/java.lang.invoke`（本项目按 Java 11 编译）；用 IDEA 里的 JDK 11（`~/Library/Java/JavaVirtualMachines/ms-11.0.31`）启动即可，或在 JDK 17 上加 `--add-opens java.base/java.lang.invoke=ALL-UNNAMED`。
- **改了 `hm-common` / `hm-api` 之后要重新 install**：其它模块单独编译或运行时（不带 `-am`）走的是本地仓库里的 jar，不重装就会「找不到类」，用 `mvn -pl hm-common,hm-api -am install -DskipTests` 更新。

## 踩坑记录

- **日志**：MyBatis-Plus 的 `ServiceImpl` 自带一个 `log` 字段，类型是 `org.apache.ibatis.logging.Log`，只支持 `error(String)` / `error(String, Throwable)`，**不支持 SLF4J 的 `{}` 占位符**。要用占位符日志就给类加 Lombok `@Slf4j`（生成的字段会遮蔽父类字段）。`PayOrderServiceImpl` 和 `OrderServiceImpl` 已如此处理。
- **Spring AMQP 2.4**：`@Queue.durable` 是 `String` 类型（`durable = "true"`），不是 `boolean`。
- **默认消息转换器**是 Java 序列化（`application/x-java-serialized-object`），所以 `RabbitTemplate.convertAndSend` 发的 `Long` 订单 id，消费者侧也要用 `Long` 参数接收。发普通 JSON 数字不会反序列化成 `Long`。
- **8080 端口**是网关的端口；之前被一个 RocketMQ dashboard 容器占用，现已从 Docker 移除。不要再往宿主机 8080 上挂任何容器。
