import { ApiError, openApi, request } from "./api.js";
import { action, audits, escape, jobs, layout, loginView, members, overview, pages, roles, schedules, states } from "./views.js";
import { checkoutError, checkoutReceipt, checkoutView, updateCheckout } from "./concert-checkout.js";
import { ticketError, ticketEvents, ticketForm, ticketOrders, updateTicketTimers } from "./concert.js";
import { confirmDialog, dialogHead, jobDetails, jobForm, memberForm, scheduleDetails, scheduleForm } from "./forms.js";

const app = document.querySelector("#app");
const dialog = document.querySelector("#dialog");
const toasts = document.querySelector("#toasts");
const sessionKey = "joblab.console.session";
const pageLimit = 12;
let session = readSession();
let api = session ? openApi(session) : null;
let route = null;
let accountVersion = 0;
let loadVersion = 0;
let mobileOpen = false;
let jobFilter = "";
let cursors = [null];
let cursorIndex = 0;
let nextCursor = null;
let currentRows = [];
let modalAction = null;
let modalSequence = 0;
let currentConcerts = [];
let ticketClockOffset = 0;

function readSession() {
  try {
    const entry = JSON.parse(sessionStorage.getItem(sessionKey) ?? "null");
    if (entry && entry.accessToken && typeof entry.username === "string" && typeof entry.tenant === "string" &&
        Object.hasOwn(roles, entry.role) && Date.now() < entry.expiresAt) return entry;
  } catch {
    return null;
  }
  return null;
}

function toast(text, error = false) {
  const node = document.createElement("p");
  node.className = "toast" + (error ? " error" : "");
  node.textContent = text;
  toasts.append(node);
  setTimeout(() => node.remove(), 4700);
}
const canEdit = () => ["ADMIN","OPERATOR"].includes(session?.role);
const canBuy = () => canEdit() || session?.role === "BUYER";
const ticketPage = page => page === "tickets" || page === "ticket-orders";
const canAdmin = () => session?.role === "ADMIN";
const failure = error => error instanceof Error ? error.message : "操作失败，请稍后重试。";
const idempotencyKey = () => Array.from(crypto.getRandomValues(new Uint8Array(16)),
  byte => byte.toString(16).padStart(2, "0")).join("");

function resetCursor() {
  cursors = [null];
  cursorIndex = 0;
  nextCursor = null;
  currentRows = [];
  currentConcerts = [];
}
function activeRoute() {
  const value = location.hash.slice(1);
  if (session.role === "BUYER") return ticketPage(value) ? value : "tickets";
  if (!Object.hasOwn(pages, value) || (!canAdmin() && ["audit","members"].includes(value))) return "overview";
  return value;
}
function setHash(value) {
  const url = new URL(location.href);
  url.hash = value;
  history.replaceState(null, "", url);
}
function openPage(value, reset = false) {
  if (!session) return;
  setHash(value);
  if (reset && route === value) {
    resetCursor();
    loadPage();
  } else {
    changeRoute();
  }
}
function changeRoute() {
  if (!session) return;
  const active = activeRoute();
  if (active === route) return;
  route = active;
  mobileOpen = false;
  resetCursor();
  app.innerHTML = layout(session, route, mobileOpen);
  loadPage();
}

function setSession(value) {
  accountVersion++;
  loadVersion++;
  session = value;
  api = openApi(value);
  try {
    if (value.demo) sessionStorage.removeItem(sessionKey);
    else sessionStorage.setItem(sessionKey, JSON.stringify(value));
  } catch { /* Session works until the page reloads. */ }
  route = null;
  changeRoute();
}
function enterDemo() {
  const params = new URL(location.href).searchParams;
  const selected = params.get("role");
  const role = Object.hasOwn(roles, selected) ? selected : "ADMIN";
  const username = { ADMIN:"admin", OPERATOR:"operator", VIEWER:"viewer", BUYER:"buyer" }[role];
  setSession({ demo: true, username, tenant: "acme", role });
}
function closeDialog() {
  modalSequence++;
  modalAction = null;
  if (dialog.open) dialog.close();
}
function logout(message = "") {
  accountVersion++;
  loadVersion++;
  closeDialog();
  session = null;
  api = null;
  route = null;
  jobFilter = "";
  mobileOpen = false;
  resetCursor();
  try { sessionStorage.removeItem(sessionKey); } catch { /* Storage can be disabled. */ }
  const url = new URL(location.href);
  url.searchParams.delete("demo");
  url.searchParams.delete("role");
  url.hash = "";
  history.replaceState(null, "", url);
  app.innerHTML = loginView(message);
}
function handleExpired(error, version) {
  if (session && version === accountVersion && error instanceof ApiError && error.status === 401) {
    logout(error.message);
    return true;
  }
  return false;
}

async function loadPage(quiet = false) {
  if (!api) return;
  const current = ++loadVersion;
  const version = accountVersion;
  const view = route;
  const host = document.querySelector("#page-content");
  const filter = jobFilter;
  const active = api;
  const index = cursorIndex;
  const cursor = cursors[index];
  if (!quiet) host.innerHTML = '<p class="loading" role="status">正在加载…</p>';
  try {
    let response;
    if (view === "overview") {
      response = await Promise.all([active.overview(), active.jobs({limit:5})]);
    } else if (ticketPage(view)) {
      response = view === "tickets" ? await active.ticketEvents() : await active.ticketOrders();
    } else {
      response = await active[view]({ state:filter, cursor, limit:pageLimit });
    }
    if (current !== loadVersion) return;
    if (ticketPage(view)) {
      const serverTime = Date.parse(response.serverTime);
      if (Number.isFinite(serverTime)) ticketClockOffset = serverTime - Date.now();
      if (view === "tickets") currentConcerts = response.events;
      host.innerHTML = view === "tickets" ? ticketEvents(currentConcerts,canBuy()) : ticketOrders(response.items,canBuy());
      updateTicketTimers(ticketClockOffset);
      return;
    }
    if (view === "overview") {
      host.innerHTML = overview(response[0],response[1].items);
      return;
    }
    currentRows = response.items;
    nextCursor = response.nextCursor ?? response.nextAfterName ?? response.nextBefore ?? null;
    const table = {
      jobs: () => jobs(currentRows,filter,index,nextCursor),
      schedules: () => schedules(currentRows,index,nextCursor,canEdit()),
      audit: () => audits(currentRows,index,nextCursor),
      members: () => members(currentRows,index,nextCursor,session.username),
    };
    host.innerHTML = table[view]();
  } catch (error) {
    if (current !== loadVersion || handleExpired(error,version)) return;
    if (!quiet || !host.innerHTML) host.innerHTML = '<div class="error-box" role="alert"><span>' +
      escape(failure(error)) + '</span>' + action("重试","refresh") + '</div>';
  }
}

function showModal(content, command = null) {
  modalSequence++;
  modalAction = command;
  dialog.innerHTML = content;
  if (!dialog.open) dialog.showModal();
  const first = dialog.querySelector("form input, form select") ?? dialog.querySelector(".dialog-heading button");
  first?.focus();
}
async function details(id, kind) {
  showModal(dialogHead(kind === "job" ? "任务详情" : "周期规则") + '<p class="loading" role="status">正在加载详情…</p>');
  const sequence = modalSequence;
  const version = accountVersion;
  try {
    const row = await api[kind](id);
    if (modalSequence !== sequence || !dialog.open) return;
    if (kind === "job") showModal(jobDetails(row,canEdit()));
    else showModal(canEdit() ? scheduleForm(row) : scheduleDetails(row));
  } catch (error) {
    if (modalSequence !== sequence || handleExpired(error,version)) return;
    showModal(dialogHead("加载失败") + '<div class="dialog-main"><p role="alert">' + escape(failure(error)) + "</p></div>");
  }
}
function showFormError(form, message) {
  const element = form.querySelector(".form-error");
  if (!element) return;
  element.textContent = message;
  element.hidden = !message;
}
function dataType(form) {
  const fields = Object.fromEntries(new FormData(form));
  if (form.id === "login-form") return fields;
  if (form.id === "new-job-form") {
    const start = Date.parse(fields.fromDate);
    const end = Date.parse(fields.toDate);
    if (end < start || (end - start) / 86400000 >= 31) throw new Error("报表日期范围最多为 31 天，结束日期须晚于或等于起始日期。");
    if (fields.runAt) fields.runAt = new Date(fields.runAt).toISOString();
    else delete fields.runAt;
    fields.maxAttempts = Number(fields.maxAttempts);
  }
  if (form.id === "schedule-form") {
    for (const name of ["name","cronExpression","timeZone"]) fields[name] = fields[name].trim();
    if (!fields.name || !fields.timeZone || fields.cronExpression.split(/\s+/).length !== 6) {
      throw new Error("请填写规则名称、时区和六字段 Cron 表达式。");
    }
    fields.lookbackDays = Number(fields.lookbackDays);
    fields.maxAttempts = Number(fields.maxAttempts);
  }
  if (form.id === "member-form") {
    if (form.dataset.value) fields.enabled = Boolean(fields.enabled);
    else if (!/^[a-z0-9_.-]{3,64}$/.test(fields.username) || new TextEncoder().encode(fields.password).length > 72) {
      throw new Error("请检查用户名格式和密码长度。");
    }
  }
  return fields;
}

async function submit(form) {
  const button = form.querySelector('button[type="submit"]');
  if (button?.disabled) return;
  showFormError(form,"");
  const version = accountVersion;
  const sequence = modalSequence;
  try {
    const fields = dataType(form);
    if (button) button.disabled = true;
    const value = form.dataset.value;
    if (form.id === "login-form") {
      const result = await request("/api/auth/token",{method:"POST",body:fields});
      if (version !== accountVersion || !form.isConnected) return;
      if (!result?.accessToken || !Object.hasOwn(roles,result.role)) throw new Error("登录响应无效。");
      setSession({ accessToken:result.accessToken, expiresAt:Date.now() + Number(result.expiresIn || 900) * 1000,
        tenant:result.tenant, username:fields.username, role:result.role });
      return;
    }
    let message;
    let payment;
    if (form.id === "ticket-payment-form" && modalAction && canBuy()) {
      form.dataset.key ||= idempotencyKey();
      payment = await modalAction({merchandiseId:fields.merchandiseId || null,pickupPointId:fields.pickupPointId || null},form.dataset.key);
      message = "模拟付款已确认。";
    } else if (form.id === "ticket-reserve-form" && modalAction && canBuy()) {
      form.dataset.key ||= idempotencyKey();
      const result = await modalAction(form.dataset.key);
      message = result.reservation.state === "LOCKED" ? "抢票成功，已锁票 5 分钟。" : "已经找到这次抢票记录。";
    } else if (form.id === "new-job-form" && canEdit()) {
      form.dataset.key ||= idempotencyKey();
      await api.createJob(fields,form.dataset.key);
      message = "任务已提交。";
    } else if (form.id === "schedule-form" && canEdit()) {
      await api.saveSchedule(fields,value);
      message = value ? "调度规则已更新。" : "调度规则已创建。";
    } else if (form.id === "member-form" && canAdmin()) {
      if (value) await api.updateMember(value,fields);
      else await api.createMember(fields);
      message = value ? "成员权限已更新。" : "成员已添加。";
    } else if (form.id === "confirm-form" && modalAction && canEdit()) {
      message = await modalAction();
    } else {
      return;
    }
    if (version !== accountVersion) return;
    if (sequence === modalSequence) {
      if (payment) showModal(checkoutReceipt(payment));
      else closeDialog();
    }
    toast(message);
    if (form.id === "ticket-reserve-form") {
      openPage("ticket-orders",true);
    } else if (form.id === "new-job-form") {
      jobFilter = "";
      openPage("jobs",true);
    } else {
      loadPage(true);
    }
  } catch (error) {
    if (version !== accountVersion) return;
    if (form.id !== "login-form" && handleExpired(error,version)) return;
    const message = form.id === "login-form" && error.status === 401 ? "登录失败，请核对租户、用户名和密码。"
      : form.id === "ticket-payment-form" ? checkoutError(error)
      : form.id === "ticket-reserve-form" ? ticketError(error) : failure(error);
    if (form.isConnected) showFormError(form,message);
    else toast(message,true);
  } finally {
    if (button) button.disabled = false;
  }
}

async function download(id) {
  const blob = await api.result(id);
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = "report-" + id + ".csv";
  link.click();
  setTimeout(() => URL.revokeObjectURL(objectUrl),1000);
}
function confirm(actionId, value) {
  if (!canEdit()) return;
  const target = api;
  if (actionId === "cancel-job") {
    showModal(confirmDialog("取消任务","确认取消这个任务？已领取的执行会通过租约状态结束。","确认取消",true),
      async () => { await target.cancelJob(value); return "任务已取消。"; });
  } else {
    const key = idempotencyKey();
    showModal(confirmDialog("重新提交任务","将创建一个关联到原任务的新任务。","重新提交",false),
      async () => { await target.replayJob(value,key); return "任务已重新提交。"; });
  }
}
async function showCheckout(id) {
  if (!session) return;
  const active = api;
  const version = accountVersion;
  showModal(dialogHead("订单详情") + '<p class="loading" role="status">正在加载结算信息…</p>');
  const sequence = modalSequence;
  try {
    const data = await active.ticketCheckout(id);
    if (sequence !== modalSequence || version !== accountVersion || !dialog.open) return;
    showModal(checkoutView(data),(selection,key) => active.payTicket(id,selection,key));
  } catch (error) {
    if (sequence !== modalSequence || handleExpired(error,version)) return;
    showModal(dialogHead("加载失败") + '<div class="dialog-main"><p role="alert">' + escape(failure(error)) + '</p></div>');
  }
}
function click(actionId, value, button) {
  switch (actionId) {
    case "demo": enterDemo(); break;
    case "fill-demo":
    case "fill-buyer": {
      const form = document.querySelector("#login-form");
      for (const [name, field] of Object.entries({tenant:"acme",username:actionId === "fill-buyer" ? "buyer" : "operator",password:"JobLab-Demo-2026!"})) {
        form.elements.namedItem(name).value = field;
      }
      form.querySelector('button[type="submit"]').focus();
      break;
    }
    case "menu": {
      mobileOpen = !mobileOpen;
      document.querySelector(".sidebar")?.classList.toggle("open",mobileOpen);
      break;
    }
    case "logout": logout(); break;
    case "ticket-list": openPage("tickets"); break;
    case "ticket-checkout": showCheckout(value); break;
    case "ticket-buy": {
      const event = currentConcerts.find(item => item.offers.some(offer => offer.id === value));
      const offer = event?.offers.find(item => item.id === value);
      if (canBuy() && event && offer) {
        const active = api;
        showModal(ticketForm(event,offer), key => active.reserveTicket(event.id,offer.id,key));
      }
      break;
    }
    case "refresh": if (session) loadPage(); break;
    case "filter-jobs":
      jobFilter = Object.hasOwn(states,value) ? value : "";
      openPage("jobs",true);
      break;
    case "prev":
      if (cursorIndex > 0) { cursorIndex--; loadPage(); }
      break;
    case "next":
      if (nextCursor) { cursors[++cursorIndex] = nextCursor; loadPage(); }
      break;
    case "new-job": if (canEdit()) showModal(jobForm(session.demo)); break;
    case "new-schedule": if (canEdit()) showModal(scheduleForm()); break;
    case "new-member": if (canAdmin()) showModal(memberForm()); break;
    case "edit-member": {
      const row = currentRows.find(item => item.id === value);
      if (canAdmin() && row && row.username !== session.username) showModal(memberForm(row));
      break;
    }
    case "job":
    case "refresh-job":
      if (session) details(value,"job");
      break;
    case "schedule":
      if (session) details(value,"schedule");
      break;
    case "pause-schedule":
    case "resume-schedule":
      if (canEdit()) runButton(button,async () => {
        await api.setSchedule(value, actionId === "resume-schedule");
        toast(actionId === "resume-schedule" ? "调度规则已启用。" : "调度规则已暂停。");
        loadPage(true);
      });
      break;
    case "cancel-job":
    case "replay-job":
      confirm(actionId,value);
      break;
    case "download": if (session) runButton(button,() => download(value)); break;
    case "close-dialog": closeDialog(); break;
  }
}
async function runButton(button, operation) {
  const version = accountVersion;
  button.disabled = true;
  try { await operation(); } catch (error) {
    if (version === accountVersion && !handleExpired(error,version)) toast(failure(error),true);
  } finally { button.disabled = false; }
}

document.addEventListener("click",event => {
  const button = event.target.closest("button[data-act]");
  if (button && !button.disabled) click(button.dataset.act,button.dataset.value,button);
});
document.addEventListener("change",event => {
  if (event.target.form?.id === "ticket-payment-form") updateCheckout(event.target.form);
});
document.addEventListener("submit",event => {
  if (!(event.target instanceof HTMLFormElement)) return;
  event.preventDefault();
  submit(event.target);
});
dialog.addEventListener("cancel",() => { modalSequence++; modalAction = null; });
window.addEventListener("hashchange",() => { if (location.hash !== "#content") changeRoute(); });
setInterval(() => {
  if (!session) return;
  if (!session.demo && Date.now() >= session.expiresAt) { logout("会话已过期，请重新登录。"); return; }
  if (!document.hidden && !dialog.open && ["overview","jobs","tickets","ticket-orders"].includes(route)) loadPage(true);
},7000);

setInterval(() => { if (route === "ticket-orders") updateTicketTimers(ticketClockOffset); },1000);

if (new URL(location.href).searchParams.get("demo") === "1") enterDemo();
else if (session) changeRoute();
else app.innerHTML = loginView();
