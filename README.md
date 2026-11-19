# JobLab 多租户分布式任务调度平台

JobLab 提供按租户隔离的任务提交、Cron 调度、并发配额和执行审计。示例业务是销售报表导出。运营人员可以安排日报和补跑失败任务，租户管理员可以管理成员和查看审计，Worker 可以独立扩容。

| 服务 | 职责 |
| --- | --- |
| gateway-service | Spring Cloud Gateway 公共入口，经 Eureka 查找 Scheduler。使用 Redis Lua 对登录和任务提交限流 |
| discovery-service | Eureka 服务注册与发现 |
| scheduler-service | Spring Security JWT、租户授权、周期调度、任务状态、Outbox、执行租约、审计与结果 |
| worker-service | RabbitMQ 竞争消费，调用 Scheduler 领取和续租，从独立 MySQL schema 读取销售数据并生成 CSV |
| messaging-core | 内部 API 数据结构、RabbitMQ 拓扑和发布确认 |

运行栈使用 Java 17、Spring Boot 3.5、Spring Cloud 2025.0、MySQL 8.4、Redis 7.4、RabbitMQ 4、Flyway 和 Docker Compose。可选 Prometheus 监控。

## 本地启动

~~~powershell
Set-Location 'G:\java - java'
mvn -B -ntp verify
docker compose up -d --build --scale worker=2 --wait
docker compose ps
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-enterprise.ps1
~~~

| 本机地址 | 用途 |
| --- | --- |
| http://127.0.0.1:18080 | API 网关 |
| http://127.0.0.1:18761 | Eureka 注册实例 |
| http://127.0.0.1:15683 | RabbitMQ 管理台。演示账户 joblab / rabbit_local |
| http://127.0.0.1:19090 | Prometheus。启用 observability profile 后可用 |

默认 Compose 使用两个独立 MySQL schema 和账号。可以加上 --scale scheduler=2 运行双调度实例。由 1.x 升级时先执行 `docker compose stop scheduler worker`。Flyway 将已有任务和报表源数据归入 acme 租户，保留任务 ID、执行记录和结果。停止环境可用 `docker compose down`，数据卷会保留。

## 登录并提交报表

默认演示账户密码为 `JobLab-Demo-2026!`。acme 租户有 admin、operator、viewer 三种角色，globex 租户有独立 admin。可以用两套令牌验证租户隔离。

~~~powershell
$base = 'http://127.0.0.1:18080'
$credentials = @{
    tenant   = 'acme'
    username = 'operator'
    password = 'JobLab-Demo-2026!'
} | ConvertTo-Json
$token = Invoke-RestMethod -Method Post -Uri "$base/api/auth/token" -ContentType 'application/json' -Body $credentials
$session = @{ Authorization = "Bearer $($token.accessToken)" }
$headers = $session + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString('N') }
$body = @{ reportType = 'DAILY_TOTALS'; fromDate = '2026-01-01'; toDate = '2026-01-03' } | ConvertTo-Json
$job = Invoke-RestMethod -Method Post -Uri "$base/api/jobs" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$base/api/jobs/$($job.id)" -Headers $session
# 状态变为 SUCCEEDED 后下载
Invoke-WebRequest -Uri "$base/api/jobs/$($job.id)/result" -Headers $session -OutFile report.csv
~~~

| API | 角色与用途 |
| --- | --- |
| POST /api/auth/token | 登录并取得 15 分钟 Bearer JWT |
| GET /api/jobs、GET /api/jobs/{id}、GET /api/jobs/{id}/result | 租户成员查询列表、尝试记录和报表 |
| POST /api/jobs、POST /api/jobs/{id}/cancel、POST /api/jobs/{id}/replay | 管理员与运营提交、取消或人工重跑失败任务 |
| GET /api/schedules、GET /api/schedules/{id} | 租户成员查询周期调度 |
| POST /api/schedules、PUT /api/schedules/{id}、PUT /api/schedules/{id}/state | 管理员与运营创建、修改、暂停和恢复周期调度 |
| GET /api/operations/overview | 租户成员查看各任务状态计数 |
| GET /api/operations/audit | 管理员查看租户审计流 |
| GET /api/admin/members、POST /api/admin/members、PUT /api/admin/members/{id} | 管理员查看成员、创建账户、改变角色或撤销访问 |

完整字段、权限和分页规则见 [API 文档](docs/api.md)。

## 故障演练与监控

~~~powershell
$env:REPORT_MIN_RUNTIME_MS = '15000'
docker compose --profile observability up -d --build --scale worker=2 --wait
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo-worker-crash.ps1
~~~

演练脚本终止领取任务的演示 Worker，检查另一实例在租约过期后接管，然后重新启动被终止的容器。执行历史预期包含 LEASE_EXPIRED 和 SUCCEEDED。演练结束后移除 REPORT_MIN_RUNTIME_MS 并用 Compose 重建 Worker，可恢复正常执行速度。

Prometheus 采集网关、调度和 Worker 的 Actuator 指标。仓库提供 Outbox 最老积压时长、过期租约和抓取失败规则。通过 `GATEWAY_PORT`、`DISCOVERY_PORT`、`RABBIT_UI_PORT` 和 `PROMETHEUS_PORT` 可以修改本机端口。

## 设计与部署

- [架构设计、数据边界与运维手册](docs/architecture.md)
- [Java 面试讲稿、实现取舍与追问](docs/interview.md)
- [权限隔离、JWT 撤销、Cron 和审计验证脚本](scripts/verify-enterprise.ps1)

默认 Compose 供本机开发和演练。独立部署时设置 JOBLAB_DEMO_ACCOUNTS=false，提供独立的 JOBLAB_JWT_SECRET_B64、INTERNAL_KEY 和首次开通账户用的 JOBLAB_BOOTSTRAP_ADMIN_PASSWORD。已有演示账号的数据仍在原数据卷，独立部署应使用独立数据库。集群部署的认证与基础设施边界见架构文档。
