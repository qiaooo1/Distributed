import { action, badge, escape, icon, time } from "./views.js";
import { dialogHead } from "./forms.js";

const stateLabels = {
  PENDING: ["待派单","orange"],
  DISPATCHING: ["派单中","blue"],
  ACCEPTED: ["仓库已接单","green"],
  MANUAL: ["需人工处理","red"],
  REQUESTED: ["待回执","blue"],
  REJECTED: ["已拒单","red"],
};

const notes = {
  NO_ELIGIBLE_WAREHOUSE: "没有能在截止前送达且库存足够的候选仓。",
  WAREHOUSE_REJECTED: "仓库明确拒单。",
  INSUFFICIENT_STOCK: "仓库库存不足。",
  DEADLINE_MISSED: "该仓库预计无法在截止前送达。",
  ACCEPTED: "仓库已确认接单。",
};

function state(value) {
  const [label,tone] = stateLabels[value] ?? [value,"slate"];
  return badge(label,tone,true);
}

function metric(rows, key, symbol, tone) {
  const count = rows.filter(order => order.state === key).length;
  return '<article class="metric ' + tone + '"><div class="metric-head"><span class="metric-icon">' +
    icon(symbol,18) + '</span>' + stateLabels[key][0] + '</div><strong>' + count + '</strong></article>';
}
export function fulfillmentView(rows) {
  const statuses = '<section class="metrics" aria-label="最近补货单状态">' +
    metric(rows,"PENDING","queue","orange") + metric(rows,"DISPATCHING","pulse","blue") +
    metric(rows,"ACCEPTED","success","green") + metric(rows,"MANUAL","alert","red") + '</section>';
  const head = '<div class="panel-head"><div><h2>最近补货单</h2><p>按订单查看到货截止和仓库回执。</p></div></div>';
  if (!rows.length) return statuses + '<section class="panel">' + head +
    '<p class="empty">有观众完成带周边的模拟付款并产生缺口后，补货单会出现在这里。</p></section>';
  const table = '<div class="table-wrap" role="region" aria-label="补货单" tabindex="0"><table><thead><tr>' +
    '<th scope="col">周边与演出</th><th scope="col">取货点</th><th scope="col">数量</th><th scope="col">到货截止</th>' +
    '<th scope="col">接单仓</th><th scope="col">状态</th></tr></thead><tbody>' +
    rows.map(row => '<tr><td>' + action(row.merchandiseLabel,"fulfillment-detail",{value:row.id,tone:"link"}) +
      '<small class="row-meta" style="display:block">' + escape(row.concertTitle) + '</small></td><td>' + escape(row.pickupLabel) +
      '</td><td>' + escape(row.quantity) + '</td><td>' + time(row.deadlineAt) + '</td><td>' +
      escape(row.warehouseLabel || "—") + '</td><td>' + state(row.state) + '</td></tr>').join("") +
    '</tbody></table></div>';
  return statuses + '<section class="panel">' + head + table + '</section>';
}

const field = (name,value) => '<div><dt>' + name + '</dt><dd>' + value + '</dd></div>';
export function fulfillmentDetails({order,attempts}) {
  const history = attempts.length ? '<ol class="attempts">' + attempts.map(a =>
    '<li><div class="attempt-head"><strong>' + escape(a.warehouseLabel) + '</strong>' + state(a.state) +
    '</div><small>' + time(a.createdAt,true) + (a.reason ? ' · ' + escape(notes[a.reason] ?? a.reason) : "") +
    '</small></li>').join("") + '</ol>' : '<p class="empty">尚未记录仓库派单尝试。</p>';
  return dialogHead("补货单详情") + '<div class="dialog-main">' + state(order.state) +
    '<dl class="detail-grid">' + field("编号",'<span class="mono">' + escape(order.id) + '</span>') +
    field("演出",escape(order.concertTitle)) +
    field("周边",escape(order.merchandiseLabel)) + field("数量",escape(order.quantity)) +
    field("取货点",escape(order.pickupLabel)) + field("到货截止",time(order.deadlineAt,true)) +
    field("接单仓",escape(order.warehouseLabel || "—")) +
    field("当前处理",escape(notes[order.note] || "—")) + '</dl><h3>仓库回执</h3>' + history +
    '</div><div class="dialog-footer">' + action("关闭","close-dialog",{tone:"ghost"}) + '</div>';
}
