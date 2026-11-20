import { action, badge, escape, time } from "./views.js";
import { dialogHead } from "./forms.js";

const currency = new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" });
const money = fen => currency.format(Number(fen) / 100);

function receiptContent(receipt, eventTitle) {
  return '<div class="dialog-main">' + badge("模拟支付成功", "green", true) +
    (eventTitle ? '<p class="checkout-event">' + escape(eventTitle) + '</p>' : '') +
    '<dl class="detail-grid"><div><dt>回执编号</dt><dd class="mono">' + escape(receipt.id) +
    '</dd></div><div><dt>确认时间</dt><dd>' + time(receipt.paidAt,true) +
    '</dd></div><div><dt>门票</dt><dd>' + money(receipt.ticketPriceFen) +
    '</dd></div><div><dt>周边</dt><dd>' + (receipt.merchandiseId ? escape(receipt.merchandiseLabel) + ' · ' + money(receipt.merchandisePriceFen) : "—") +
    '</dd></div><div><dt>取货点</dt><dd>' + escape(receipt.pickupLabel || "—") +
    '</dd></div><div><dt>合计</dt><dd><strong>' + money(receipt.totalFen) + '</strong></dd></div></dl></div>';
}
const finish = '<div class="dialog-footer">' + action("完成","close-dialog",{tone:"primary"}) + '</div>';

export function checkoutReceipt(receipt, eventTitle = "") {
  return dialogHead("支付回执") + receiptContent(receipt,eventTitle) + finish;
}

export function checkoutView(data) {
  const ticket = data.reservation;
  if (data.payment) return checkoutReceipt(data.payment,ticket.eventTitle);
  if (ticket.state !== "LOCKED" || Date.parse(ticket.expiresAt) <= Date.parse(data.serverTime)) {
    return dialogHead("锁票已过期") + '<div class="dialog-main"><p>回到演出列表即可查看可售余票。</p></div>' + finish;
  }
  const hasMerch = data.merchandise.length > 0 && data.pickupPoints.length > 0;
  const options = data.merchandise.map(m => '<option value="' + escape(m.id) +
    '" data-price="' + escape(m.priceFen) + '">' + escape(m.label) + ' · ' + money(m.priceFen) + '</option>').join("");
  const pickups = data.pickupPoints.map(p => '<option value="' + escape(p.id) + '">' + escape(p.label) + '</option>').join("");
  return dialogHead("模拟结算") + '<form id="ticket-payment-form" data-ticket-price="' +
    escape(ticket.priceFen) + '"><div class="dialog-main"><p>' + escape(ticket.eventTitle) +
    ' · ' + escape(ticket.offerLabel) + '</p><p class="checkout-note">模拟支付用于本地演示。请在锁票到期前确认。</p>' +
    '<div class="form-grid">' + (hasMerch ?
      '<label class="field">加购周边<select name="merchandiseId"><option value="">只购门票</option>' + options +
      '</select></label><label class="field">取货点<select name="pickupPointId" disabled><option value="">请选择</option>' + pickups + '</select></label>'
      : "") +
    '</div><div class="checkout-total"><span>合计</span><strong data-checkout-total>' + money(ticket.priceFen) +
    '</strong></div><p class="form-error" role="alert" hidden></p></div><div class="dialog-footer">' +
    action("返回","close-dialog",{tone:"ghost"}) + '<button type="submit" class="btn primary">确认模拟支付</button></div></form>';
}

export function updateCheckout(form) {
  const merch = form.elements.namedItem("merchandiseId");
  const pickup = form.elements.namedItem("pickupPointId");
  if (!merch || !pickup) return;
  pickup.disabled = !merch.value;
  pickup.required = Boolean(merch.value);
  if (!merch.value) pickup.value = "";
  const price = Number(merch.selectedOptions[0]?.dataset.price ?? 0);
  form.querySelector("[data-checkout-total]").textContent = money(Number(form.dataset.ticketPrice) + price);
}

const errors = {
  "Ticket hold has expired": "锁票已过期，请重新抢票。",
  "This ticket was already paid": "这张票已支付，可在「我的票」查看回执。",
  "Choose both merchandise and pickup point": "选择周边后，请选择取货点。",
  "Merchandise or pickup point is unavailable for this concert": "周边或取货点已不可用，请刷新后重新选择。",
  "Payment key was used for a different request": "本次支付请求已被使用，请查看订单状态。",
};
export const checkoutError = error => errors[error.detail] ?? error.message;
