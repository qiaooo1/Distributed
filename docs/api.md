# JobLab API

除登录外，公开接口要求 Authorization: Bearer <token>。服务从已验签令牌读取租户和账户 ID，并按数据库里的当前角色和凭据版本授权。普通请求体无法指定操作租户。访问其他租户的资源返回 404，避免泄露资源是否存在。

| 角色 | 权限 |
| --- | --- |
| VIEWER | 读取本租户任务、报表、周期调度和状态概览 |
| OPERATOR | VIEWER 权限，加上提交、取消、重放和管理周期调度 |
| ADMIN | OPERATOR 权限，加上管理本租户账号和审计查询 |
| BUYER | 查看本租户演出、抢票和查看自己的锁票记录 |

## 身份与成员

- POST /api/auth/token 接收 tenant、username、password，成功返回 accessToken、tokenType、expiresIn、tenant、role。密码校验使用 BCrypt。无效凭据返回 401。
- GET /api/admin/members 支持 afterUsername 和 limit。按用户名升序返回最多 limit 个账户。
- POST /api/admin/members 接收 username、password、role，返回 201。密码长度至少 12 个字符，最多 72 UTF-8 字节。用户在租户内唯一。
- PUT /api/admin/members/{id} 接收 role 和 enabled。变更使原 JWT 即时失效。管理员自身权限只能由本租户的另一位管理员变更。

## 即时与延时任务

- POST /api/jobs body 包含 reportType、fromDate、toDate、可选 runAt 和 maxAttempts。日期使用 ISO 日期，范围含起止日且至多 31 天。runAt 使用 ISO-8601 UTC 时间，最多提前计划 30 天。maxAttempts 默认为 3，允许 1 至 5。
- reportType 支持 DAILY_TOTALS 和 SALES_DETAIL。仓库包含 2026-01-01 至 2026-01-03 的固定演示销售数据。
- 提交要求 Idempotency-Key，长度 1–64，允许字母、数字、点、下划线、冒号和连字符。相同租户、相同键及请求返回原任务。键相同但请求不同返回 409。
- GET /api/jobs 支持 state、cursor、limit。返回 items 和 nextCursor。沿用相同过滤条件传入上一页的 nextCursor 继续查询。
- POST /api/jobs/{id}/cancel 可以取消 SCHEDULED、QUEUED 或 RUNNING。已取消的请求可重复调用。SUCCEEDED 和 FAILED 的取消返回 409。
- POST /api/jobs/{id}/replay 对 FAILED 任务重新提交一次执行，要求独立的幂等键，返回 202。新任务通过 replayedFromId 指向原任务，原执行历史保留。相同键重试同一重放返回同一新任务。
- GET /api/jobs/{id}/result 在 SUCCEEDED 后下载 UTF-8 CSV。任务未成功时返回 409。

## 周期调度

POST /api/schedules 创建，PUT /api/schedules/{id} 替换定义。请求体示例：

~~~json
{
  "name": "daily-sales",
  "cronExpression": "0 0 9 * * *",
  "timeZone": "Asia/Shanghai",
  "reportType": "DAILY_TOTALS",
  "lookbackDays": 3,
  "maxAttempts": 3,
  "misfirePolicy": "FIRE_ONCE",
  "overlapPolicy": "SKIP_IF_ACTIVE"
}
~~~

使用含秒的 Spring Cron 和 IANA 时区。秒字段支持 0、15、30、45 的组合及 */15、*/30。lookbackDays 支持 1–31，统计窗口为执行对应本地日期之前的完整自然日。超过补偿阈值后，SKIP 直接前移调度时刻，FIRE_ONCE 对这一段迟到调度最多补跑一次。ALLOW 允许同一规则的多个任务等待或执行，SKIP_IF_ACTIVE 在上一轮仍待执行或运行时跳过本轮。

PUT /api/schedules/{id}/state 接收 enabled 布尔值，暂停后不再生成新任务，已生成的任务可从任务接口独立取消。恢复时根据当前时间计算下次触发。GET /api/schedules 支持 afterName 与 limit，列表返回 nextAfterName 游标。

## 演出票务

场次、抢票与本人锁票查询位于 `/api/tickets`。权限、锁票状态和请求字段见 [票务接口](concert-platform.md#第一阶段)。

## 审计与网关反馈

GET /api/operations/audit 支持 before、limit，返回 items 和 nextBefore。动作包含登录、任务提交/取消/重放、成员变更、周期规则变更、触发和策略跳过。发起修改的 X-Request-Id 会进入审计，内部定时操作以系统身份记录。

GET /api/operations/overview 返回当前租户按任务状态的计数。429 响应带有 Retry-After。登录限流依赖 Redis，Redis 不可用时返回 503。任务提交在 Redis 故障时降级，仍经过租户授权、数据库幂等和执行并发配额。
