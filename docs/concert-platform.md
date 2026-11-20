# 演唱会票务与周边履约

演出运营商作为租户，观众在开售时抢票，抢到后获得 5 分钟支付窗口。观众可在结算时加购一份周边并选择取货点。带周边的付款形成备货需求，取货点出现缺口时，平台向能按时到货的仓库派单。支付和仓库回执使用本地模拟，演示终点为仓库确认接单。

| 阶段 | 页面 | 可观察的结果 |
| --- | --- | --- |
| 1. 抢票与锁票 | 演出购票、我的票 | 余票减少，新增锁票记录并开始倒计时。到期未付时退回余票 |
| 2. 模拟支付 | 模拟结算、付款回执 | 服务端确认金额和付款时间，订单变为已支付 |
| 3. 周边补货派单 | 周边派单、补货单详情 | 取货点缺口生成补货单，可追踪每次尝试及仓库回执 |

## 抢票与库存

- 网关对抢票入口使用 Redis 固定窗口限流。超额返回 `429`，Redis 不可用时返回 `503`。
- Scheduler 在同一 MySQL 事务中锁定票档、检查库存并扣减、写入锁票。锁票截止时间按数据库时钟计算。租户和用户范围内的幂等键用于重放原请求。可空唯一索引限制同一观众在同一场演出中最多有一张有效锁票或已付票。
- 订单从 `LOCKED` 转为 `PAID` 或 `EXPIRED`。过期扫描先锁票档，再按状态更新订单和回补库存。多个实例同时发现同一笔到期订单时，只有成功把订单置为 `EXPIRED` 的事务会回补库存。页面的倒计时使用 API 返回的服务器时间校准。

库存事务和到期处理见 [TicketService](../scheduler-service/src/main/java/io/joblab/scheduler/ticket/TicketService.java) 与 [TicketExpiration](../scheduler-service/src/main/java/io/joblab/scheduler/ticket/TicketExpiration.java)。

## 模拟支付

有效锁票可直接支付门票，或同时选定一份本场周边和一个本场取货点。服务端以锁票单中的票价和周边目录计算金额，按分保存价格和回执。

- 付款与过期释放先获取同一个票档锁。付款在锁票截止前把 `LOCKED` 更新为 `PAID`，模拟回执和订单状态一起提交。更新时已经超过截止时间则整个支付事务回滚。
- 同一个账号重试相同的付款请求和幂等键，服务返回原回执。复用该键修改订单、周边或取货点返回 `409`。用新键重复支付已付款订单也会返回 `409`。
- 已付款订单可重新打开结算详情查看回执。结算和回执查询按租户和购票账户匹配。

付款事务和幂等校验见 [TicketPaymentService](../scheduler-service/src/main/java/io/joblab/scheduler/ticket/TicketPaymentService.java)。

## 周边补货与派单

1. **汇总需求。** Scheduler 扫描尚未汇总的付款记录。按租户、演出、周边 SKU 和取货点增加已付需求，并在同一事务标记付款已处理。升级前已付的周边订单也会被扫描。
2. **生成补货单。** 在需求记录的行锁内，用已付数量减去本地库存和已计划补货量。缺口大于零时创建补货单，同时增加已计划数量。演示的到货截止时间为演出开始前 30 分钟。
3. **分配仓库。** 多个 Worker 经内网 API 竞争领取，Scheduler 使用 `FOR UPDATE SKIP LOCKED` 发放 30 秒租约。候选仓必须能一次提供所需数量，并在截止前到货。符合条件的仓库按预计用时排序，仓库明确拒单后尝试下一家。
4. **恢复派单。** Worker 中断后，下一个实例复用原尝试 ID 并先读取仓库回执。模拟仓库在同一事务记录接单回执并扣减库存，尝试 ID 唯一。过期租约的 Worker 无法回写。找不到候选仓时补货单变为「需人工处理」，运营可查看原因。

补货单的状态为 `PENDING`、`DISPATCHING`、`ACCEPTED` 和 `MANUAL`。仓库明确拒单后，补货单回到 `PENDING`。单次仓库尝试的状态为 `REQUESTED`、`ACCEPTED` 或 `REJECTED`。

需求汇总、仓库派单与 Worker 接管分别由 [ConcertDemandIntake](../scheduler-service/src/main/java/io/joblab/scheduler/fulfillment/ConcertDemandIntake.java)、[ConcertFulfillmentService](../scheduler-service/src/main/java/io/joblab/scheduler/fulfillment/ConcertFulfillmentService.java) 和 [ConcertDispatchWorker](../worker-service/src/main/java/io/joblab/worker/ConcertDispatchWorker.java) 实现。

## 本地验收

1. 用 `acme / buyer` 登录，抢到票后在「我的票」确认 5 分钟倒计时。如需验收释放，等锁票到期、确认余票回升，再重新抢票。
2. 点「去结算」，加购「城市回声限定围巾」，选择「东门周边取货点」。付款后查看包含门票和周边金额的回执。
3. 用 `acme / operator` 打开「周边派单」。本地演示初始数据中，滨海前置仓拒单后，星港中转仓接单。补货单详情可查看这两次尝试及其回执。
4. 验收升级时，在第二阶段完成带周边的付款，再保留同一 MySQL 数据卷启动第三阶段。此前的付款也会生成补货需求。

演示账号和启动方式见 [README](../README.md#快速启动)。同一账户在同一场演出只允许保有一张有效锁票或已付款票。复测时可用管理员新增购票账户。

## 接口与权限

| 接口 | 用途 |
| --- | --- |
| `GET /api/tickets/events` | 查询本租户场次、票档、开售状态和服务器时间 |
| `POST /api/tickets/events/{eventId}/reservations` | 传入 `offerId` 和 `Idempotency-Key` 锁一张票 |
| `GET /api/tickets/reservations` | 查看本人最近 50 笔订单 |
| `GET /api/tickets/reservations/{id}` | 查询本人一笔订单的最新状态 |
| `GET /api/tickets/reservations/{id}/checkout` | 查询结算选项和已有的模拟付款回执 |
| `POST /api/tickets/reservations/{id}/payments` | 传入独立的 `Idempotency-Key`。只买票时请求体为 `{}`，加购时同时提供 `merchandiseId` 与 `pickupPointId` |
| `GET /api/operations/concert-fulfillment/orders` | 查询本租户最近 60 笔补货单 |
| `GET /api/operations/concert-fulfillment/orders/{id}` | 查询补货单及历次仓库尝试和回执 |

管理员、运营和购票角色可抢票和支付。只读角色可查看票务。补货单查询只对管理员和运营开放，跨租户查询返回 `404`。Worker 的领取和回执接口位于 `/internal/concert-dispatch`，通过内部 Worker 凭据与协议头鉴权。其他接口见 [JobLab API](api.md)。
