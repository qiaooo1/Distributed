import { action, badge, escape, icon, time } from "./views.js";
import { dialogHead } from "./forms.js";

const currency = new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" });
const number = new Intl.NumberFormat("zh-CN");
const money = fen => currency.format(Number(fen) / 100);

const saleStatus = {
  ON_SALE: ["开放抢票", "green"],
  UPCOMING: ["即将开售", "violet"],
  CLOSED: ["售票结束", "slate"],
};
const orderStatus = {
  LOCKED: ["锁票中", "blue"],
  EXPIRED: ["已过期", "slate"],
  PAID: ["已支付", "green"],
};

export function ticketEvents(events, canBuy) {
  if (!events.length) return '<section class="panel"><p class="empty">当前工作区还没有演出场次。</p></section>';
  return '<div class="concert-list">' + events.map(event => {
    const status = saleStatus[event.saleState] ?? saleStatus.CLOSED;
    const offers = event.offers.map(offer => renderOffer(offer, event.saleState, canBuy)).join("");
    return '<section class="panel concert-card"><div class="concert-cover"><div class="concert-content">' +
      badge(...status, true) + '<h2>' + escape(event.title) + '</h2><p class="concert-venue">' + icon("building", 17) +
      escape(event.venue) + '</p><p class="concert-when">' + icon("calendar", 17) + time(event.startsAt, true) +
      '</p></div><div class="concert-visual" aria-hidden="true"><i></i><i></i><i></i><span></span></div></div>' +
      '<div class="concert-summary"><p>' + escape(event.description) + '</p><small>开售时间 ' + time(event.saleStartsAt,true) +
      '</small><small>售票截止 ' + time(event.saleEndsAt,true) + '</small></div><div class="ticket-offers">' +
      (offers || '<p class="empty">暂无票档。</p>') + '</div></section>';
  }).join("") + '</div>';
}

function renderOffer(offer, saleState, canBuy) {
  const available = Math.max(0, Number(offer.available) || 0);
  const disabled = saleState !== "ON_SALE" || !available;
  const buttonLabel = saleState === "UPCOMING" ? "待开售" : saleState === "CLOSED" ? "已结束" :
    !available ? "已售罄" : "抢票";
  return '<div class="ticket-offer"><div class="ticket-offer-label"><span class="ticket-symbol">' + icon("ticket",19) +
    '</span><div><strong>' + escape(offer.label) + '</strong><small>余票 ' + number.format(available) +
    '</small></div></div><p class="ticket-price">' + money(offer.priceFen) + '</p>' +
    (canBuy ? action(buttonLabel,"ticket-buy",{value:offer.id,tone:disabled ? "secondary" : "primary",disabled}) : "") + "</div>";
}

export function ticketOrders(rows, canPay) {
  const title = '<div class="panel-head"><div><h2>抢票记录</h2><p>每次成功锁票后保留 5 分钟，超时后释放库存。</p></div>' +
    action("查看场次","ticket-list",{tone:"link"}) + '</div>';
  if (!rows.length) return '<section class="panel">' + title +
    '<p class="empty">还没有抢票记录。</p></section>';
  return '<section class="panel">' + title + '<div class="table-wrap" tabindex="0" aria-label="抢票记录">' +
    '<table class="ticket-table"><thead><tr><th scope="col">场次</th><th scope="col">票档</th>' +
    '<th scope="col">票价</th><th scope="col">状态</th><th scope="col">锁票倒计时</th><th scope="col">操作</th></tr></thead><tbody>' +
    rows.map(row => {
      const status = orderStatus[row.state] ?? orderStatus.EXPIRED;
      return '<tr><td><strong class="row-title">' + escape(row.eventTitle) + '</strong><span class="row-meta mono">' +
        escape(row.id) + '</span></td><td>' + escape(row.offerLabel) + '</td><td>' + money(row.priceFen) +
        '</td><td>' + badge(...status, true) + '</td><td>' + timer(row) + '</td><td>' +
        (row.state === "PAID" ? action("查看回执","ticket-checkout",{value:row.id,tone:"ghost"}) :
          row.state === "LOCKED" && canPay ? action("去结算","ticket-checkout",{value:row.id}) : "—") + '</td></tr>';
    }).join("") + '</tbody></table></div></section>';
}

function timer(row) {
  if (row.state !== "LOCKED") return '<span class="row-meta">—</span>';
  return '<span class="ticket-timer mono" data-ticket-expires="' + escape(row.expiresAt) + '">正在同步</span>';
}

export function updateTicketTimers(serverOffset) {
  const now = Date.now() + serverOffset;
  for (const element of document.querySelectorAll("[data-ticket-expires]")) {
    const milliseconds = Date.parse(element.dataset.ticketExpires) - now;
    if (!Number.isFinite(milliseconds)) {
      element.textContent = "—";
      continue;
    }
    const seconds = Math.max(0,Math.ceil(milliseconds / 1000));
    const label = seconds === 0 ? "等待释放" :
      String(Math.trunc(seconds / 60)).padStart(2,"0") + ":" + String(seconds % 60).padStart(2,"0");
    element.textContent = label;
  }
}

export function ticketForm(event, offer) {
  return dialogHead("确认抢票") + '<form id="ticket-reserve-form"><div class="dialog-main">' +
    '<p>' + escape(event.title) + '</p><dl class="detail-grid"><div><dt>票档</dt><dd>' + escape(offer.label) +
    '</dd></div><div><dt>一张票</dt><dd>' + money(offer.priceFen) +
    '</dd></div></dl><p>抢到票后保留 5 分钟，超时自动释放。</p>' +
    '<p class="form-error" role="alert" hidden></p></div><div class="dialog-footer">' +
    action("返回","close-dialog",{tone:"ghost"}) + '<button class="btn primary" type="submit">确认抢票</button></div></form>';
}

const failureCopy = {
  "This concert is outside its ticket sale window": "该场次当前不在售票时段。",
  "You already have an active reservation for this concert": "你在本场已有一张有效锁票，请查看「我的票」。",
  "This ticket tier is sold out": "这个票档已售罄，可刷新后查看其他票档。",
  "Idempotency-Key was used for a different offer": "此请求已用于其他票档，请重新打开抢票窗口。",
};
export function ticketError(error) {
  return failureCopy[error.detail] ?? (error.status === 503 ? "抢票入口暂时不可用，请稍后重试。" : error.message);
}
