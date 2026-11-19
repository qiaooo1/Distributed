import { action, badge, escape, iconButton, MISFIRE, OVERLAP, reports, roles, status, time } from "./views.js";

const reportChoices = reports;
const roleChoices = roles;

function label(text, control, full = false, note = "") {
  return '<label class="field' + (full ? " wide" : "") + '">' + text + control + (note ? "<small>" + note + "</small>" : "") + "</label>";
}
function input(name, value = "", options = {}) {
  return '<input name="' + name + '" value="' + escape(value) + '"' +
    (options.type ? ' type="' + options.type + '"' : "") +
    (options.min != null ? ' min="' + options.min + '"' : "") +
    (options.max != null ? ' max="' + options.max + '"' : "") +
    (options.maxlength != null ? ' maxlength="' + options.maxlength + '"' : "") +
    (options.minlength != null ? ' minlength="' + options.minlength + '"' : "") +
    (options.placeholder ? ' placeholder="' + escape(options.placeholder) + '"' : "") +
    (options.type === "password" ? ' autocomplete="new-password"' : "") +
    (options.optional ? "" : " required") + ">";
}
function select(name, values, selected) {
  return '<select name="' + name + '">' +
    Object.entries(values).map(([key,value]) => '<option value="' + key + '"' +
      (key === selected ? " selected" : "") + ">" + escape(value) + "</option>").join("") + "</select>";
}
export const dialogHead = title => '<header class="dialog-heading"><h2 id="dialog-title">' + escape(title) +
  '</h2>' + iconButton("关闭", "close-dialog", "close") + "</header>";
const cancel = () => action("关闭", "close-dialog", {tone:"ghost"});
function form(title, id, fields, confirm, value) {
  return dialogHead(title) + '<form id="' + id + '"' + (value ? ' data-value="' + escape(value) + '"' : "") + '>' +
    '<div class="dialog-main form-grid">' + fields + '<p class="form-error" role="alert" hidden></p></div>' +
    '<footer class="dialog-footer">' + cancel() + '<button type="submit" class="btn primary">' +
    escape(confirm) + '</button></footer></form>';
}

export function jobForm(demo) {
  const from = demo ? "2026-01-01" : "";
  const to = demo ? "2026-01-03" : "";
  return form("新建报表任务", "new-job-form",
    label("报表类型", select("reportType", reportChoices, "DAILY_TOTALS"), true) +
    label("起始日期", input("fromDate",from,{type:"date"})) +
    label("结束日期", input("toDate",to,{type:"date"})) +
    label("计划执行时间", input("runAt","",{type:"datetime-local",optional:true}), true, "按浏览器本地时区填写，留空立即执行。") +
    label("最大尝试次数", input("maxAttempts",3,{type:"number",min:1,max:5})),
    "提交任务");
}

export function scheduleForm(row = null) {
  const existing = row ?? {};
  return form(row ? "编辑周期规则" : "新建周期规则", "schedule-form",
    label("规则名称",input("name",existing.name,{maxlength:80,placeholder:"每日销售日报"}),true) +
    label("Cron 表达式",input("cronExpression",existing.cronExpression ?? "0 0 9 * * *",{maxlength:80}),true,
      "包含秒字段，例如 0 0 9 * * * 表示每天上午 9 点。") +
    label("时区",input("timeZone",existing.timeZone ?? "Asia/Shanghai",{maxlength:64})) +
    label("报表类型",select("reportType",reportChoices,existing.reportType ?? "DAILY_TOTALS")) +
    label("回看天数",input("lookbackDays",existing.lookbackDays ?? 3,{type:"number",min:1,max:31})) +
    label("最大尝试次数",input("maxAttempts",existing.maxAttempts ?? 3,{type:"number",min:1,max:5})) +
    label("错过触发",select("misfirePolicy",MISFIRE,existing.misfirePolicy ?? "FIRE_ONCE")) +
    label("上一轮仍在运行",select("overlapPolicy",OVERLAP,existing.overlapPolicy ?? "SKIP_IF_ACTIVE")),
    row ? "保存修改" : "创建规则", existing.id);
}

const fieldValue = (labelText, content) => '<div><dt>' + labelText + '</dt><dd>' + content + '</dd></div>';
export function scheduleDetails(row) {
  return dialogHead(row.name) + '<div class="dialog-main">' +
    badge(row.enabled ? "已启用" : "已暂停", row.enabled ? "green" : "slate", true) +
    '<dl class="detail-grid">' +
    fieldValue("Cron", '<span class="mono">' + escape(row.cronExpression) + '</span>') +
    fieldValue("时区",escape(row.timeZone)) +
    fieldValue("报表类型",escape(reports[row.reportType] ?? row.reportType)) +
    fieldValue("回看天数",escape(row.lookbackDays)) +
    fieldValue("下次触发",time(row.nextFireAt,true)) +
    fieldValue("上次触发",time(row.lastFireAt,true)) +
    fieldValue("错过触发",escape(MISFIRE[row.misfirePolicy] ?? row.misfirePolicy)) +
    fieldValue("上一轮仍在运行",escape(OVERLAP[row.overlapPolicy] ?? row.overlapPolicy)) +
    '</dl></div><footer class="dialog-footer">' + cancel() + '</footer>';
}

export function memberForm(row = null) {
  const fields = row
    ? '<p class="field wide">账号 ' + escape(row.username) + '</p>'
    : label("用户名",input("username","",{minlength:3,maxlength:64,placeholder:"report-operator"}),true) +
      label("初始密码",input("password","",{minlength:12,maxlength:72,type:"password"}),true,"至少 12 个字符，最多 72 个 UTF-8 字节。");
  return form(row ? "编辑成员" : "添加租户成员","member-form", fields +
    label("角色",select("role",roleChoices,row?.role ?? "VIEWER"),true) +
    (row ? '<label class="field check wide"><input type="checkbox" name="enabled"' + (row.enabled ? " checked" : "") + '>允许访问工作区</label>' : ""),
    row ? "保存权限" : "添加成员",row?.id);
}
const outcomes = {
  LEASE_EXPIRED: ["租约过期","orange"], SUCCEEDED:["已成功","green"], TASK_FAILED:["执行失败","red"],
  CANCELLED:["已取消","slate"], RUNNING:["运行中","blue"],
};
export function jobDetails(row, canEdit) {
  const attempts = row.attempts ?? [];
  const actions = [action("刷新详情","refresh-job",{symbol:"refresh",value:row.id,tone:"ghost"})];
  if (row.state === "SUCCEEDED") actions.push(action("下载 CSV","download",{value:row.id,tone:"primary",symbol:"download"}));
  if (canEdit && row.state === "FAILED") actions.push(action("重新提交","replay-job",{value:row.id,tone:"primary"}));
  if (canEdit && ["SCHEDULED","QUEUED","RUNNING"].includes(row.state)) actions.push(action("取消任务","cancel-job",{value:row.id,tone:"danger"}));
  const history = attempts.length ?
    '<ol class="attempts">' + attempts.map(item => {
      const outcome = outcomes[item.outcome] ?? [item.outcome, "slate"];
      return '<li><div class="attempt-head"><strong>第 ' + escape(item.attempt) + ' 次尝试</strong>' + badge(...outcome) +
        '</div><small>' + escape(item.workerId ?? "—") + ' · ' + time(item.startedAt,true) +
        (item.finishedAt ? " 至 " + time(item.finishedAt,true) : "") + '</small>' +
        (item.error ? '<small>' + escape(item.error) + '</small>' : "") + '</li>';
    }).join("") + '</ol>'
    : '<p class="empty">任务尚未被 Worker 领取。</p>';
  return dialogHead(reports[row.reportType] ?? "任务详情") + '<div class="dialog-main">' +
    status(row.state) + '<dl class="detail-grid">' +
    fieldValue("任务编号",'<span class="mono">' + escape(row.id) + '</span>') +
    fieldValue("报表日期",escape(row.fromDate ?? "—") + " 至 " + escape(row.toDate ?? "—")) +
    fieldValue("计划执行",time(row.runAt,true)) + fieldValue("创建时间",time(row.createdAt,true)) +
    fieldValue("已尝试次数",escape(row.attemptCount ?? 0) + " / " + escape(row.maxAttempts ?? "—")) +
    (row.replayedFromId ? fieldValue("原任务编号",'<span class="mono">' + escape(row.replayedFromId) + '</span>') : "") +
    '</dl><h3>执行历史</h3>' + history + '</div><footer class="dialog-footer">' + actions.join("") + '</footer>';
}
export function confirmDialog(title, message, button, danger) {
  return dialogHead(title) + '<form id="confirm-form"><div class="dialog-main"><p>' + escape(message) +
    '</p><p class="form-error" role="alert" hidden></p></div><footer class="dialog-footer">' + cancel() +
    '<button type="submit" class="btn ' + (danger ? "danger" : "primary") + '">' + escape(button) +
    '</button></footer></form>';
}
