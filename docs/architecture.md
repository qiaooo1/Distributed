# JobLab 架构与运维

~~~mermaid
flowchart LR
    C[API 客户端] --> G[Gateway]
    G --> R[(Redis)]
    G --> S[Scheduler 实例组]
    E[Eureka] -. 服务发现 .-> G
    E -. 服务发现 .-> W1
    E -. 服务发现 .-> W2
    S --> D[(任务和审计 MySQL)]
    S -->|Publisher Confirm| Q[RabbitMQ]
    Q --> W1[Worker 1]
    Q --> W2[Worker 2]
    Q --> DLQ[死信队列]
    W1 & W2 -->|领取 续租 回写| S
    W1 & W2 --> T[(报表源 MySQL)]
    P[Prometheus] -. 拉取指标 .-> G & S & W1 & W2
~~~

## 写入与一致性

| 事务 | 锁与数据 |
| --- | --- |
| 提交 | 写 job，唯一键由 tenant_id 与 request_key 组成。参数摘要区分安全重试和键冲突。新任务及审计同事务提交 |
| 周期触发 | SKIP LOCKED 竞争到期 job_schedule。由规则 ID 和计划时刻唯一约束 occurrence。生成 job、调度推进、审计同事务提交 |
| 到期派发 | SKIP LOCKED 竞争 SCHEDULED 任务。QUEUED、generation 和 event_outbox 行同事务提交 |
| Outbox 发布 | 短批次获取独立行锁，等待 Broker 确认并检查 mandatory return。失败按退避时间留在表中 |
| 领取 | 比对 job generation 并锁住单个任务，再锁 tenant 行序列化同租户领取。活跃数超过并发配额时重新计划，保留原尝试次数 |
| 完成 | 以 MySQL 时钟校验租约，写入结果和执行终态。旧租约无法写入。已经接受的完成请求可以按租约令牌重试 |
| 人工重放 | 从 FAILED 任务建立独立任务 ID，保留源任务引用、原尝试历史及当前重放的审计 |

调度器重复派发只增加 generation。Worker 真正领取时才增加 attempt。宕机恢复和任务执行异常按尝试次数退避，达到上限置为 FAILED。QUEUED 且 Broker 已确认的任务在领取截止后可发新一代。调度队列和出站队列都是数据库索引扫描，消息堆积时可按实例水平扩展发布和执行。

账户按租户建模。授权层验证 JWT 的签名、有效期、issuer、audience 和账号版本。角色来自当前数据库记录。公共业务 SQL 带租户条件；Worker 在内部路径使用服务密钥。Worker 从领取响应获得租户 ID，从独立报表库的联合索引按 tenant_id 和日期查询。Demo 的两个租户拥有不同的源数据。Worker 协议版本参与服务鉴权，旧 Worker 无法领取新版本任务。

## 运维手册

### 积压或任务超时

1. 查询 /api/operations/overview 和对应任务的 attempts。SCHEDULED 可能是计划时间未到、失败退避或租户执行配额等待。
2. 如果 QUEUED 增长，检查 joblab_outbox_pending、joblab_outbox_oldest_seconds 和 RabbitMQ 的 joblab.work 队列，区分未发布、已发布待消费及 Worker 未就绪。
3. 如果 RUNNING 长时间积压，查看 joblab_leases_expired、Scheduler 恢复线程、Worker 心跳日志和数据库时钟。根据尝试记录定位故障实例。
4. RabbitMQ 的 joblab.work.dlq 保留消费重试耗尽的消息。先修复错误，再通过任务状态接口判断。已 FAILED 的任务可按业务入口重放，并保留审计。手工重新投递旧 generation 会被领取守卫拦截。
5. 本地可执行 Worker 故障脚本。监控可用 `docker compose --profile observability up -d` 启用。

调度配额存在 tenant.running_limit，演示 acme 为 4，globex 为 2。平台管理员可以用数据库部署流程调整，下一次领取就会读取新值。多副本 Scheduler 在同一个 MySQL 主库上竞争，把锁等待和活跃数统计纳入容量测试。Prometheus 的数据库状态指标以 max 按 Scheduler 副本汇总即可，避免把同源数据重复相加。

### 上线边界

- Compose 使用单节点中间件和本地 HTTP。独立部署需要 TLS、集中密钥、运行账号与迁移账号分离、MySQL 备份恢复与主从策略、Redis 和 RabbitMQ 高可用。
- 从 1.x 升级时先停旧 Scheduler 和 Worker，再构建并启动新版本，以 Flyway V3/V2 升级各自 schema。多个 Scheduler 必须共用数据库、JWT 签名和内部 Worker 密钥。账号停用通过数据库版本生效，密钥轮换需要部署策略。企业身份可接入统一 IdP。
- 运行量增长后需制定 Outbox、作业和审计的归档保留策略。大报表适合对象存储和受控下载，本项目结果保持在 MySQL 事务内。
- Redis 固定窗口在分钟切换处允许短时突发。当前登录按网关直接对端地址限流，反向代理部署需要在可信边界处理客户端地址。
- 领取租约可保证当前合法执行才能提交结果。其他需要写入外部系统的任务还应让接收方校验幂等键或 fencing token。
