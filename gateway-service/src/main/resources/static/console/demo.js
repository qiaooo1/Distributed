const storageKey = "joblab.console.demo.v1";
const states = ["SCHEDULED", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"];
const now = () => new Date().toISOString();
const offset = ms => new Date(Date.now() + ms).toISOString();
const attempt = (number, workerId, outcome, startedAt, finishedAt = null, error = null) =>
  ({ attempt: number, workerId, outcome, startedAt, finishedAt, error });

function seed() {
  const template = [
    "RUNNING", "SUCCEEDED", "FAILED", "QUEUED", "SUCCEEDED", "SCHEDULED", "SUCCEEDED", "RUNNING",
    "SUCCEEDED", "SUCCEEDED", "QUEUED", "SUCCEEDED", "SUCCEEDED", "SCHEDULED", "SUCCEEDED", "FAILED",
    "SUCCEEDED", "SUCCEEDED", "CANCELLED", "SUCCEEDED", "QUEUED", "SUCCEEDED", "SUCCEEDED", "SUCCEEDED",
  ];
  const jobs = template.map((state, index) => {
    const createdAt = offset(-((index + 1) * 29 + 2) * 60000);
    const id = "82547f" + String(93 + index).slice(-2) + "-894d-474b-9c7e-" + String(index + 2).padStart(12, "0");
    const reportType = index % 3 === 2 ? "SALES_DETAIL" : "DAILY_TOTALS";
    const attempts = state === "FAILED"
      ? [attempt(1, "worker-b", "TASK_FAILED", createdAt, createdAt, "演示样本的执行失败。")]
      : ["RUNNING", "SUCCEEDED"].includes(state)
        ? [attempt(1, index % 2 ? "worker-a" : "worker-b", state, createdAt, state === "SUCCEEDED" ? createdAt : null)]
        : [];
    return {
      id, state, reportType, attempts, createdAt, updatedAt: createdAt, runAt: createdAt, maxAttempts: 3,
      attemptCount: attempts.length, fromDate: "2026-01-01", toDate: "2026-01-03", scheduleId: null, replayedFromId: null,
    };
  });
  const schedules = [
    { id: "schedule-daily", name: "每日销售汇总", cronExpression: "0 0 9 * * *", reportType: "DAILY_TOTALS", enabled: true },
    { id: "schedule-detail", name: "每周销售明细", cronExpression: "0 0 10 * * MON", reportType: "SALES_DETAIL", enabled: false },
  ].map(row => ({
    ...row, timeZone: "Asia/Shanghai", lookbackDays: 3, maxAttempts: 3,
    misfirePolicy: "FIRE_ONCE", overlapPolicy: "SKIP_IF_ACTIVE", nextFireAt: row.enabled ? offset(86400000) : null, lastFireAt: offset(-86400000),
  }));
  return {
    jobs, schedules, keys: {},
    members: [
      { id: "admin", username: "admin", role: "ADMIN", enabled: true },
      { id: "operator", username: "operator", role: "OPERATOR", enabled: true },
      { id: "viewer", username: "viewer", role: "VIEWER", enabled: true },
    ],
    audit: [
      { id: 2, action: "JOB_CANCELLED", resourceId: jobs[18].id, requestId: "demo-req-2", occurredAt: offset(-120000), actorId: "admin" },
      { id: 1, action: "SCHEDULE_CREATED", resourceId: "schedule-daily", requestId: "demo-req-1", occurredAt: offset(-3600000), actorId: "admin" },
    ],
  };
}

const read = () => {
  try {
    const raw = sessionStorage.getItem(storageKey);
    return raw ? JSON.parse(raw) : seed();
  } catch {
    return seed();
  }
};

let data;
const persist = () => {
  try { sessionStorage.setItem(storageKey, JSON.stringify(data)); } catch { /* The in-memory demo stays available. */ }
};

function settle() {
  for (const job of data.jobs.filter(value => value.demoProgress && !["SUCCEEDED", "CANCELLED"].includes(value.state))) {
    const elapsed = Date.now() - Date.parse(job.runAt);
    if (elapsed < 0) continue;
    const state = elapsed < 2800 ? "QUEUED" : elapsed < 8500 ? "RUNNING" : "SUCCEEDED";
    if (state === job.state) continue;
    job.state = state;
    job.updatedAt = now();
    if (state === "RUNNING" || state === "SUCCEEDED") {
      job.attemptCount = 1;
      const started = job.attempts[0]?.startedAt ?? now();
      job.attempts = [attempt(1, "worker-b", state, started, state === "SUCCEEDED" ? now() : null)];
    }
  }
  persist();
}

function page(rows, cursor, limit, key) {
  const position = cursor == null ? 0 : rows.findIndex(row => String(row[key]) === String(cursor)) + 1;
  const items = rows.slice(position, position + limit);
  return { items, nextCursor: position + limit < rows.length ? items.at(-1)?.[key] : null };
}

export function demoApi(session) {
  data ??= read();
  const log = (action, resourceId, detail = null) => {
    data.audit.unshift({
      id: (data.audit[0]?.id ?? 0) + 1, actorId: session.username, action, resourceId, detail,
      requestId: crypto.randomUUID(), occurredAt: now(),
    });
    persist();
  };
  const job = id => {
    settle();
    const item = data.jobs.find(row => row.id === id);
    if (!item) throw new Error("演示任务已不存在。");
    return item;
  };
  const schedule = id => {
    const item = data.schedules.find(row => row.id === id);
    if (!item) throw new Error("演示调度规则已不存在。");
    return item;
  };
  const createJob = (body, key, replayedFromId = null) => {
    if (data.keys[key]) return job(data.keys[key]);
    const id = crypto.randomUUID();
    const item = {
      ...body, id, state: body.runAt && Date.parse(body.runAt) > Date.now() ? "SCHEDULED" : "QUEUED",
      runAt: body.runAt ?? now(), updatedAt: now(), createdAt: now(), attempts: [], attemptCount: 0,
      scheduleId: null, demoProgress: true, replayedFromId,
    };
    data.jobs.unshift(item);
    data.keys[key] = id;
    log(replayedFromId ? "JOB_REPLAYED" : "JOB_SUBMITTED", id, replayedFromId);
    return item;
  };
  return {
    overview: async () => {
      settle();
      return Object.fromEntries(states.map(state => [state, data.jobs.filter(item => item.state === state).length]));
    },
    jobs: async ({ state, cursor, limit }) => {
      settle();
      return page(data.jobs.filter(item => !state || item.state === state), cursor, limit, "id");
    },
    job: async id => job(id),
    createJob: async (body, key) => createJob(body, key),
    cancelJob: async id => {
      const item = job(id);
      if (["SUCCEEDED", "FAILED"].includes(item.state)) throw new Error("当前状态无法取消。");
      item.state = "CANCELLED";
      item.updatedAt = now();
      item.attempts = item.attempts.map(row => row.outcome === "RUNNING" ?
        { ...row, outcome: "CANCELLED", finishedAt: now() } : row);
      log("JOB_CANCELLED", id);
      return item;
    },
    replayJob: async (id, key) => {
      const { fromDate, toDate, reportType, maxAttempts } = job(id);
      return createJob({ fromDate, toDate, reportType, maxAttempts }, key, id);
    },
    result: async id => {
      const item = job(id);
      if (item.state !== "SUCCEEDED") throw new Error("任务尚未生成可下载的报表。");
      return new Blob(["date,gross_amount,order_count\n2026-01-01,481.62,8\n2026-01-02,732.23,12\n"], { type: "text/csv;charset=utf-8" });
    },
    schedules: async ({ cursor, limit }) => {
      const sorted = [...data.schedules].sort((a,b) => a.name.localeCompare(b.name));
      const result = page(sorted, cursor, limit, "name");
      return { items: result.items, nextAfterName: result.nextCursor };
    },
    schedule: async id => schedule(id),
    saveSchedule: async (body, id) => {
      if (data.schedules.some(row => row.name === body.name && row.id !== id)) throw new Error("这条规则名称已存在。");
      const values = { ...body, nextFireAt: null };
      const item = id ? Object.assign(schedule(id), values) : { ...values, id: crypto.randomUUID(), enabled: true, lastFireAt: null };
      if (!id) data.schedules.push(item);
      log(id ? "SCHEDULE_UPDATED" : "SCHEDULE_CREATED", item.id);
      return item;
    },
    setSchedule: async (id, enabled) => {
      const item = schedule(id);
      item.enabled = enabled;
      item.nextFireAt = null;
      log(enabled ? "SCHEDULE_RESUMED" : "SCHEDULE_PAUSED", id);
      return item;
    },
    audit: async ({ cursor, limit }) => {
      const result = page(data.audit, cursor, limit, "id");
      return { items: result.items, nextBefore: result.nextCursor };
    },
    members: async ({ cursor, limit }) => page([...data.members].sort((a,b) => a.username.localeCompare(b.username)), cursor, limit, "username"),
    createMember: async ({ username, role }) => {
      if (data.members.some(row => row.username === username)) throw new Error("账号已存在。");
      const item = { id: crypto.randomUUID(), username, role, enabled: true };
      data.members.push(item);
      log("MEMBER_CREATED", item.id);
      return item;
    },
    updateMember: async (id, values) => {
      const item = data.members.find(row => row.id === id);
      if (!item || item.username === session.username) throw new Error("不能修改当前会话的权限。");
      Object.assign(item, values);
      log("MEMBER_ACCESS_CHANGED", id);
      return item;
    },
  };
}
