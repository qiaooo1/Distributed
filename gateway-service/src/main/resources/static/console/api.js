import { demoApi } from "./demo.js";
import { openTicketDemo } from "./concert-demo.js";

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const statusMessages = {
  400: "请求有误，请检查输入。",
  401: "会话已过期，请重新登录。",
  403: "当前账号没有此操作权限。",
  404: "资源不存在或不可访问。",
  409: "任务状态已变化，请刷新后重试。",
  429: "请求过于频繁，请稍后再试。",
  503: "调度服务暂时不可用，请检查 JobLab 服务状态。",
};

export async function request(path, { method = "GET", token, body, key, download = false } = {}) {
  const headers = new Headers({ Accept: download ? "text/csv" : "application/json" });
  if (token) headers.set("Authorization", "Bearer " + token);
  if (key) headers.set("Idempotency-Key", key);
  if (body !== undefined) headers.set("Content-Type", "application/json");
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    let response;
    try {
      response = await fetch(path, {
        method, headers, credentials: "omit", cache: "no-store", signal: controller.signal,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (error) {
      if (error.name === "AbortError") throw new ApiError(0, "请求超时，请稍后重试。");
      throw new ApiError(0, "无法连接 JobLab 网关，请检查服务是否启动。");
    }
    if (!response.ok) {
      const payload = await response.json().catch(() => null);
      const detail = payload?.detail;
      const error = new ApiError(response.status, statusMessages[response.status] ||
        (typeof detail === "string" ? detail : "请求失败，请稍后重试。"));
      error.detail = typeof detail === "string" ? detail : null;
      throw error;
    }
    if (download) return await response.blob();
    if (response.status === 204) return null;
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}

const query = (path, params) => {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value !== "" && value != null) search.set(name, value);
  }
  const value = search.toString();
  return value ? path + "?" + value : path;
};

export function openApi(session) {
  if (session.demo) return { ...demoApi(session), ...openTicketDemo(session) };
  const call = (path, options) => request(path, { ...options, token: session.accessToken });
  const jobs = "/api/jobs";
  const schedules = "/api/schedules";
  const members = "/api/admin/members";
  const item = (path, id) => path + "/" + encodeURIComponent(id);
  return {
    fulfillmentOrders: () => call("/api/operations/concert-fulfillment/orders"),
    fulfillmentOrder: id => call(item("/api/operations/concert-fulfillment/orders",id)),
    ticketEvents: () => call("/api/tickets/events"),
    ticketOrders: () => call("/api/tickets/reservations"),
    ticketCheckout: id => call(item("/api/tickets/reservations",id) + "/checkout"),
    payTicket: (id, body, key) => call(item("/api/tickets/reservations",id) + "/payments", {method:"POST",body,key}),
    reserveTicket: (eventId, offerId, key) => call(item("/api/tickets/events", eventId) + "/reservations",
      { method: "POST", body: { offerId }, key }),
    overview: () => call("/api/operations/overview"),
    jobs: ({ state, cursor, limit }) => call(query(jobs, { state, cursor, limit })),
    job: id => call(item(jobs, id)),
    createJob: (body, key) => call(jobs, { method: "POST", body, key }),
    cancelJob: id => call(item(jobs, id) + "/cancel", { method: "POST" }),
    replayJob: (id, key) => call(item(jobs, id) + "/replay", { method: "POST", key }),
    result: id => call(item(jobs, id) + "/result", { download: true }),
    schedules: ({ cursor, limit }) => call(query(schedules, { afterName: cursor, limit })),
    schedule: id => call(item(schedules, id)),
    saveSchedule: (body, id) => call(id ? item(schedules, id) : schedules, { method: id ? "PUT" : "POST", body }),
    setSchedule: (id, enabled) => call(item(schedules, id) + "/state", { method: "PUT", body: { enabled } }),
    audit: ({ cursor, limit }) => call(query("/api/operations/audit", { before: cursor, limit })),
    members: async ({ cursor, limit }) => {
      const items = await call(query(members, { afterUsername: cursor, limit: limit + 1 }));
      const page = items.slice(0, limit);
      return { items: page, nextCursor: items.length > limit ? page.at(-1)?.username : null };
    },
    createMember: body => call(members, { method: "POST", body }),
    updateMember: (id, body) => call(item(members, id), { method: "PUT", body }),
  };
}
