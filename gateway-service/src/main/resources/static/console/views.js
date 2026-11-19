const paths = {
  layers: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.2"/><rect x="14" y="3" width="7" height="7" rx="1.2"/><rect x="3" y="14" width="7" height="7" rx="1.2"/><rect x="14" y="14" width="7" height="7" rx="1.2"/>',
  task: '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h4"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18M8 15h3"/>',
  logs: '<path d="M7 3h11a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Zm-3 15a3 3 0 0 1 3-3h13M8 7h8M8 11h5"/>',
  users: '<circle cx="9" cy="7" r="3"/><path d="M3 20v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2m3-14a3 3 0 0 1 0 6m-1 2a4 4 0 0 1 4 4v2"/>',
  pulse: '<path d="M3 12h4l3-7 4 14 3-7h4"/>',
  success: '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
  alert: '<path d="M10.3 4.2a2 2 0 0 1 3.4 0l8 14A2 2 0 0 1 20 21H4a2 2 0 0 1-1.7-2.8l8-14Z"/><path d="M12 9v4m0 4h.01"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  pause: '<circle cx="12" cy="12" r="9"/><path d="M10 9v6m4-6v6"/>',
  play: '<circle cx="12" cy="12" r="9"/><path d="m10 8 6 4-6 4V8Z"/>',
  add: '<path d="M12 5v14M5 12h14"/>',
  refresh: '<path d="M20 8a8 8 0 1 0 1 7m-1-12v5h-5"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  exit: '<path d="M10 17l5-5-5-5m5 5H3m10-8h6a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  building: '<path d="M4 21V5a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v16M17 10h4v11H3m5-14h5m-5 4h5m-5 4h5"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 1 1 8 0v4m-4 4v2"/>',
  queue: '<path d="M3 6h18M3 12h13M3 18h8"/><circle cx="19" cy="17" r="3"/>',
  dot: '<circle cx="12" cy="12" r="4" fill="currentColor" stroke="none"/>',
};
export const icon = (name, size = 18) => '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths[name] + '</svg>';
export const escape = value => String(value ?? "").replace(/[&<>"']/g, char =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
const dateTime = new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });
const fullTime = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
});
export function time(value, full = false) {
  const date = new Date(value ?? NaN);
  if (!Number.isFinite(date.getTime())) return "—";
  return '<time datetime="' + escape(date.toISOString()) + '">' + (full ? fullTime : dateTime).format(date) + '</time>';
}

export const states = {
  SCHEDULED: { label: "已计划", tone: "violet", color: "#9984ed", icon: "calendar" },
  QUEUED: { label: "排队中", tone: "orange", color: "#f6ad59", icon: "queue" },
  RUNNING: { label: "运行中", tone: "blue", color: "#668bfa", icon: "pulse" },
  SUCCEEDED: { label: "已成功", tone: "green", color: "#51c39f", icon: "success" },
  FAILED: { label: "已失败", tone: "red", color: "#f0879b", icon: "alert" },
  CANCELLED: { label: "已取消", tone: "slate", color: "#aab3c7", icon: "pause" },
};
export const reports = { DAILY_TOTALS: "销售日报汇总", SALES_DETAIL: "销售订单明细" };
export const roles = { ADMIN: "管理员", OPERATOR: "运营人员", VIEWER: "只读成员" };
export const MISFIRE = { FIRE_ONCE: "补跑一次", SKIP: "跳过" };
export const OVERLAP = { SKIP_IF_ACTIVE: "跳过本轮", ALLOW: "允许重叠" };
export const pages = {
  overview: { label: "运行总览", icon: "grid", description: "查看当前租户的任务状态、排队情况和近期执行记录。" },
  jobs: { label: "任务管理", icon: "task", description: "提交报表任务，查看执行历史与结果。" },
  schedules: { label: "周期调度", icon: "calendar", description: "管理 Cron 规则、时区与补偿策略。" },
  audit: { label: "审计记录", icon: "logs", description: "追踪租户内的任务、调度规则与成员操作。" },
  members: { label: "成员管理", icon: "users", description: "为当前租户添加成员并管理访问权限。" },
};

export const badge = (label, tone, dot = false) => '<span class="badge ' + tone + (dot ? ' badge-dot' : '') + '">' + escape(label) + "</span>";
export function status(state) {
  const meta = states[state];
  return badge(meta?.label ?? state, meta?.tone ?? "slate", true);
}

export function action(label, id, options = {}) {
  const { symbol, tone = "secondary", value, title, disabled = false } = options;
  return '<button type="button" class="btn ' + tone + '" data-act="' + id + '"' +
    (value == null ? "" : ' data-value="' + escape(value) + '"') +
    (title ? ' title="' + escape(title) + '"' : "") + (disabled ? " disabled" : "") + ">" +
    (symbol ? icon(symbol, 16) : "") + escape(label) + "</button>";
}
export const iconButton = (label, id, symbol, options = {}) =>
  '<button type="button" class="icon-btn ' + (options.className ?? "") + '" aria-label="' + escape(label) +
  '" title="' + escape(label) + '" data-act="' + id + '">' + icon(symbol, 18) + "</button>";
export const brand = () => '<div class="brand"><span class="brand-symbol">' + icon("layers", 23) +
  '</span><span><strong class="brand-name">JobLab</strong><small class="brand-caption">调度控制台</small></span></div>';

export function loginView(error = "") {
  return '<div class="login"><aside class="login-brand">' + brand() +
    '<div class="login-story"><h1>分布式任务调度</h1><p>在同一工作区管理任务、周期规则和执行记录。</p><div class="pipeline">' +
    [["task","提交任务与周期规则"],["pulse","跟踪执行与租约恢复"],["download","下载报表与查看审计"]].map(([symbol,label]) =>
      '<div class="pipeline-step">' + icon(symbol) + label + "</div>").join("") +
    '</div></div></aside><main id="content" tabindex="-1" class="login-area"><form id="login-form" class="login-form">' +
    '<h2>登录工作区</h2><p>使用你的 JobLab 租户账号。</p>' +
    '<label class="field">租户<input name="tenant" value="acme" autocomplete="organization" maxlength="40" required></label>' +
    '<label class="field">用户名<input name="username" placeholder="operator" autocomplete="username" maxlength="64" required></label>' +
    '<label class="field">密码<input name="password" type="password" autocomplete="current-password" required></label>' +
    '<p class="form-error" role="alert"' + (error ? "" : " hidden") + ">" + escape(error) + "</p>" +
    '<button class="btn primary" type="submit">登录</button>' +
    action("填入本地演示账号", "fill-demo", { tone: "ghost" }) + '<hr>' +
    action("打开演示工作区", "demo", { symbol: "grid" }) + "</form></main></div>";
}

function nav(page, pageId) {
  const item = pages[pageId];
  return '<a href="#' + pageId + '" class="nav-link"' + (page === pageId ? ' aria-current="page"' : '') +
    '>' + icon(item.icon) + '<span>' + item.label + '</span></a>';
}
export function layout(session, page, mobileOpen) {
  const role = roles[session.role] ?? session.role;
  const badgeIcon = session.demo ? "dot" : "lock";
  return '<div class="workspace"><aside id="sidebar" class="sidebar' + (mobileOpen ? " open" : "") + '">' + brand() + iconButton("关闭菜单", "menu", "close", {className:"mobile-nav sidebar-close"}) +
    '<nav class="navigation" aria-label="控制台"><p class="nav-label">工作空间</p>' +
    ["overview","jobs","schedules"].map(id => nav(page,id)).join("") +
    (session.role === "ADMIN" ? '<p class="nav-label">系统管理</p>' + nav(page,"audit") + nav(page,"members") : "") +
    '</nav><div class="account"><span class="avatar" aria-hidden="true">' + escape(session.username.slice(0,1)) +
    '</span><div class="account-info"><span class="account-name">' + escape(session.username) + '</span><span class="account-role">' +
    escape(role) + '</span></div>' + iconButton("退出工作区", "logout", "exit") +
    '</div></aside><div class="stage"><header class="topbar"><div class="crumb">' +
    iconButton(mobileOpen ? "关闭菜单" : "打开菜单", "menu", "menu", {className:"mobile-nav"}) +
    '<span>工作空间</span><span class="positional" aria-hidden="true">/</span><strong>' + pages[page].label +
    '</strong></div><div class="top-actions"><span class="tenant-tag">' + icon("building", 15) + escape(session.tenant) +
    '</span><span class="mode-tag' + (session.demo ? ' demo' : '') + '">' + icon(badgeIcon, 14) +
    (session.demo ? "演示数据" : "已登录") + '</span></div></header><main class="main" id="content" tabindex="-1">' +
    '<div class="page-heading"><div><h1>' + pages[page].label + '</h1><p>' + pages[page].description +
    '</p></div><div class="actions">' + action("刷新", "refresh", {symbol:"refresh"}) + primary(session, page) +
    '</div></div><div id="page-content"></div></main></div></div>';
}

const primary = (session, page) => {
  if (session.role === "VIEWER") return "";
  const options = { tone: "primary", symbol: "add" };
  if (page === "jobs" || page === "overview") return action("新建任务", "new-job", options);
  if (page === "schedules") return action("新建规则", "new-schedule", options);
  if (page === "members") return action("添加成员", "new-member", options);
  return "";
};

function cellValue(label, id, sub, task = true) {
  return '<button type="button" class="table-link row-title" data-act="' + (task ? "job" : "schedule") +
    '" data-value="' + escape(id) + '">' + escape(label) + "</button><span class=\"row-meta\">" + escape(sub) + "</span>";
}
function table(columns, rows, emptyLabel) {
  if (!rows.length) return '<p class="empty">' + escape(emptyLabel) + "</p>";
  return '<div class="table-wrap" tabindex="0" role="region" aria-label="数据表格"><table><thead><tr>' +
    columns.map(column => '<th scope="col">' + escape(column) + "</th>").join("") +
    '</tr></thead><tbody>' + rows.join("") + "</tbody></table></div>";
}
function tr(cells) {
  return "<tr>" + cells.map(content => "<td>" + content + "</td>").join("") + "</tr>";
}
const short = value => String(value ?? "—").length > 14 ? String(value).slice(0, 12) + "…" : String(value ?? "—");
const count = value => new Intl.NumberFormat("zh-CN").format(Math.max(0,Number(value)||0));
const JOB_COLUMNS = ["任务", "执行状态", "计划执行", "创建时间", "尝试"];
const jobRows = items => items.map(row => tr([
  cellValue(reports[row.reportType] ?? row.reportType, row.id, short(row.id)),
  status(row.state), time(row.runAt), time(row.createdAt), count(row.attemptCount),
]));

function metric(label, value, sub, symbol, tone) {
  return '<article class="metric ' + tone + '"><div class="metric-head"><span class="metric-icon">' + icon(symbol,19) +
    '</span>' + label + '</div><strong>' + count(value) + '</strong><small>' + sub + '</small></article>';
}
function donut(data, total) {
  let start = 0;
  const segments = [];
  const order = ["SUCCEEDED","RUNNING","QUEUED","SCHEDULED","FAILED","CANCELLED"];
  for (const state of order) {
    const amount = Math.max(0, Number(data[state]) || 0);
    if (amount && total) {
      const end = start + amount / total * 100;
      segments.push(states[state].color + " " + start.toFixed(3) + "% " + end.toFixed(3) + "%");
      start = end;
    }
  }
  const style = segments.length ? ' style="--segments:conic-gradient(' + segments.join(",") + ')"' : "";
  return '<div class="chart"><div class="ring"' + style + ' aria-hidden="true"><div class="ring-text"><strong>' + count(total) +
    '</strong><small>全部任务</small></div></div><ul class="chart-legend">' +
    order.map(state => '<li class="legend-item"><span class="swatch" style="--dot:' + states[state].color +
      '"></span>' + states[state].label + '<strong>' + count(data[state]) + '</strong></li>').join("") + "</ul></div>";
}
function attn(key, amount, note) {
  const m = states[key];
  return '<button type="button" class="attn ' + m.tone + '" data-act="filter-jobs" data-value="' + key +
    '"><span class="metric-icon">' + icon(m.icon,17) + '</span><span><span class="attn-label">' + m.label +
    '</span><small>' + note + '</small></span><strong>' + count(amount) + '</strong></button>';
}
function head(title, secondary, button = "") {
  return '<div class="panel-head"><div><h2>' + title + '</h2>' +
    (secondary ? '<p>' + secondary + '</p>' : '') + '</div>' + button + '</div>';
}
export function overview(data, items) {
  const total = Object.keys(states).reduce((sum, key) => sum + Math.max(0, Number(data[key]) || 0), 0);
  const rate = total === 0 ? "暂无已创建任务" : "占全部任务 " + Math.round((Number(data.SUCCEEDED) || 0) / total * 100) + "%";
  return '<section class="metrics" aria-label="任务指标">' +
    metric("任务总数", total, "当前租户的全部任务", "layers", "violet") +
    metric("运行中", data.RUNNING, "已领取执行租约", "pulse", "blue") +
    metric("已成功", data.SUCCEEDED, rate, "success", "green") +
    metric("已失败", data.FAILED, "查看失败原因与执行历史", "alert", "red") +
    '</section><div class="analytics"><section class="panel">' +
    head("任务状态分布", "当前租户中各状态的任务数量") + donut(data,total) +
    '</section><section class="panel">' + head("运行关注", "按状态查看相关任务") +
    '<div class="attention-list">' + attn("QUEUED",data.QUEUED,"等待 Worker 领取") +
    attn("SCHEDULED",data.SCHEDULED,"等待计划执行") + attn("FAILED",data.FAILED,"需要检查执行历史") +
    '</div></section></div><section class="panel">' + head("最近任务", "", action("查看全部", "filter-jobs", {value:"",tone:"link"})) +
    table(JOB_COLUMNS, jobRows(items), "还没有任务。") + "</section>";
}
function pager(index, next) {
  return '<div class="pager"><small>第 ' + (index + 1) + ' 页</small>' +
    action("上一页", "prev", {disabled:index === 0}) + action("下一页", "next", {disabled:!next}) + "</div>";
}
export function jobs(items, filter, index, next) {
  return '<section class="panel"><div class="tabbar" aria-label="执行状态">' +
    [["","全部"],...Object.entries(states).map(([key, state]) => [key,state.label])].map(([key,label]) =>
      '<button type="button" class="tab" data-act="filter-jobs" data-value="' + key +
      '" aria-pressed="' + (filter === key) + '">' + label + "</button>").join("") +
    '</div>' + table(JOB_COLUMNS, jobRows(items), "当前筛选条件下没有任务。") + pager(index,next) + "</section>";
}
export function schedules(items, index, next, canEdit) {
  const rows = items.map(row => tr([
    cellValue(row.name, row.id, reports[row.reportType] ?? row.reportType, false),
    '<span class="mono">' + escape(row.cronExpression) + '</span><br><small class="row-meta">' + escape(row.timeZone) + "</small>",
    badge(row.enabled ? "已启用" : "已暂停", row.enabled ? "green" : "slate", true),
    time(row.nextFireAt),
    canEdit ? action(row.enabled ? "暂停" : "启用",row.enabled ? "pause-schedule" : "resume-schedule",{value:row.id,tone:"ghost"}) : time(row.lastFireAt),
  ]));
  return '<section class="panel">' + head("调度规则", "按名称排序") +
    table(["规则","Cron 与时区","状态","下次触发",canEdit ? "操作" : "上次触发"],rows,"还没有周期调度规则。") +
    pager(index,next) + "</section>";
}
const auditLabels = {
  LOGIN:"登录", JOB_SUBMITTED:"提交任务", JOB_CANCELLED:"取消任务", JOB_REPLAYED:"重放任务",
  SCHEDULE_CREATED:"新建规则", SCHEDULE_UPDATED:"更新规则", SCHEDULE_PAUSED:"暂停规则",
  SCHEDULE_RESUMED:"启用规则", SCHEDULE_FIRED:"触发周期任务", SCHEDULE_MISFIRE_SKIPPED:"跳过错过的触发",
  SCHEDULE_OVERLAP_SKIPPED:"跳过重叠执行", MEMBER_CREATED:"添加成员", MEMBER_ACCESS_CHANGED:"更新成员权限",
};
export function audits(items, index, next) {
  return '<section class="panel">' + head("操作日志", "最新操作显示在前") +
    table(["操作","资源","操作者","请求 ID","发生时间"],items.map(row => tr([
      '<strong class="row-title">' + escape(auditLabels[row.action] ?? row.action) + '</strong><small class="row-meta">' + escape(row.detail ?? row.action) + '</small>',
      '<span class="mono" title="' + escape(row.resourceId) + '">' + escape(short(row.resourceId)) + '</span>',
      '<span class="mono" title="' + escape(row.actorId) + '">' + escape(row.actorId ? short(row.actorId) : "系统") + '</span>',
      '<span class="mono" title="' + escape(row.requestId) + '">' + escape(short(row.requestId)) + '</span>',
      time(row.occurredAt),
    ])),"还没有审计记录。") + pager(index,next) + "</section>";
}
export function members(items, index, next, username) {
  return '<section class="panel">' + head("租户成员", "按用户名排序") + table(["用户名","角色","状态","操作"],items.map(row => tr([
    '<strong>' + escape(row.username) + '</strong>' + (row.username === username ? ' <small class="row-meta">当前账号</small>' : ''),
    escape(roles[row.role] ?? row.role), badge(row.enabled ? "已启用" : "已停用", row.enabled ? "green" : "slate", true),
    row.username === username ? "—" : action("编辑", "edit-member", {value:row.id,tone:"ghost"}),
  ])),"还没有成员。") + pager(index,next) + "</section>";
}
