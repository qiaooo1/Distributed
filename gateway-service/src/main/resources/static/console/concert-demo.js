const KEY = "joblab.console.ticket-demo.v1";
let data;

function seed() {
  const anchor = Date.now();
  const day = 86400000;
  const date = days => new Date(anchor + days * day).toISOString();
  return {
    events: [
      {
        id: "demo-neon", title: "霓虹余响 · 城市回声演唱会", venue: "滨海演艺中心 · 主馆",
        description: "本场开放早鸟、看台与内场票。", startsAt: date(120), saleStartsAt: date(-1), saleEndsAt: date(90),
        offers: [
          { id: "demo-early", label: "早鸟票", priceFen: 19900, capacity: 15 },
          { id: "demo-stand", label: "看台票", priceFen: 36900, capacity: 45 },
          { id: "demo-floor", label: "内场票", priceFen: 59900, capacity: 35 },
        ],
      },
      {
        id: "demo-orbit", title: "光轨计划 · 夏夜专场", venue: "星港音乐公园 · 户外舞台",
        description: "以开售时间为准。", startsAt: date(135), saleStartsAt: date(7), saleEndsAt: date(105),
        offers: [{ id: "demo-orbit-stand", label: "看台票", priceFen: 29900, capacity: 40 }],
      },
    ],
    reservations: [],
  };
}
function load() {
  try {
    const stored = sessionStorage.getItem(KEY);
    return stored ? JSON.parse(stored) : seed();
  } catch {
    return seed();
  }
}
function save() {
  try { sessionStorage.setItem(KEY, JSON.stringify(data)); } catch { /* The current tab still works. */ }
}
function sale(event, now) {
  if (now < Date.parse(event.saleStartsAt)) return "UPCOMING";
  return now < Date.parse(event.saleEndsAt) ? "ON_SALE" : "CLOSED";
}
function update(now) {
  for (const row of data.reservations) {
    if (row.state === "LOCKED" && Date.parse(row.expiresAt) <= now) row.state = "EXPIRED";
  }
  save();
}
function available(offer) {
  return offer.capacity - data.reservations.filter(row => row.offerId === offer.id && row.state !== "EXPIRED").length;
}
function conflict(detail) {
  return Object.assign(new Error(detail), { status:409, detail });
}

function extras(eventId) {
  if (eventId !== "demo-neon") return {merchandise:[],pickupPoints:[]};
  return {merchandise:[{id:"demo-scarf",label:"城市回声限定围巾",priceFen:6900},{id:"demo-pin",label:"城市回声徽章",priceFen:2400}],
    pickupPoints:[{id:"demo-east",label:"东门周边取货点"},{id:"demo-west",label:"西门周边取货点"}]};
}
function demoFulfillment() {
  return data.reservations.filter(row => row.state === "PAID" && row.payment?.merchandiseId).map(row => {
    const createdAt = row.payment.paidAt;
    const starts = data.events.find(e => e.id === row.eventId).startsAt;
    const order = {
      id:row.payment.id,concertTitle:row.eventTitle,merchandiseLabel:row.payment.merchandiseLabel,
      pickupLabel:row.payment.pickupLabel,warehouseLabel:"星港中转仓",quantity:1,state:"ACCEPTED",note:"ACCEPTED",
      deadlineAt:new Date(Date.parse(starts)-1800000).toISOString(),createdAt,
    };
    const attempts = [
      {id:row.payment.id+"-a",warehouseLabel:"滨海前置仓",state:"REJECTED",reason:"WAREHOUSE_REJECTED",createdAt},
      {id:row.payment.id+"-b",warehouseLabel:"星港中转仓",state:"ACCEPTED",reason:"ACCEPTED",createdAt},
    ];
    return {order,attempts};
  });
}
export function openTicketDemo(session) {
  data ??= load();
  return {
    fulfillmentOrders: async () => demoFulfillment().map(item => item.order),
    fulfillmentOrder: async id => {
      const item = demoFulfillment().find(value => value.order.id === id);
      if (!item) throw new Error("找不到这笔演示补货单。");
      return item;
    },
    ticketEvents: async () => {
      const now = Date.now();
      update(now);
      return { serverTime: new Date(now).toISOString(), events: data.events.map(e => ({
        ...e, saleState: sale(e,now), offers: e.offers.map(offer => ({ ...offer, available: available(offer) })),
      })) };
    },
    ticketOrders: async () => {
      const now = Date.now();
      update(now);
      return { serverTime:new Date(now).toISOString(), items:data.reservations.filter(row => row.username === session.username) };
    },
    ticketCheckout: async id => {
      const now = Date.now();
      update(now);
      const reservation = data.reservations.find(row => row.id === id && row.username === session.username);
      if (!reservation) throw new Error("找不到这笔锁票。");
      return {serverTime:new Date(now).toISOString(),reservation,...extras(reservation.eventId),payment:reservation.payment ?? null};
    },
    payTicket: async (id,selection,key) => {
      const now = Date.now();
      update(now);
      const mine = data.reservations.filter(row => row.username === session.username);
      const prior = mine.find(row => row.paymentKey === key);
      if (prior) {
        if (prior.id !== id || (prior.payment.merchandiseId || null) !== selection.merchandiseId ||
            (prior.payment.pickupPointId || null) !== selection.pickupPointId) throw conflict("Payment key was used for a different request");
        return prior.payment;
      }
      const row = mine.find(r => r.id === id);
      if (row?.state === "PAID") throw conflict("This ticket was already paid");
      if (row?.state !== "LOCKED") throw conflict("Ticket hold has expired");
      const options = extras(row.eventId);
      const merchandise = options.merchandise.find(m => m.id === selection.merchandiseId);
      const pickup = options.pickupPoints.find(p => p.id === selection.pickupPointId);
      if ((selection.merchandiseId || selection.pickupPointId) && (!merchandise || !pickup)) {
        throw conflict("Merchandise or pickup point is unavailable for this concert");
      }
      row.state = "PAID";
      row.paymentKey = key;
      row.payment = {id:crypto.randomUUID(),reservationId:id,merchandiseId:merchandise?.id ?? null,pickupPointId:pickup?.id ?? null,
        merchandiseLabel:merchandise?.label,pickupLabel:pickup?.label,ticketPriceFen:row.priceFen,
        merchandisePriceFen:merchandise?.priceFen ?? 0,totalFen:row.priceFen+(merchandise?.priceFen ?? 0),paidAt:new Date(now).toISOString()};
      save();
      return row.payment;
    },
    reserveTicket: async (eventId, offerId, key) => {
      const now = Date.now();
      update(now);
      const serverTime = new Date(now).toISOString();
      const mine = data.reservations.filter(row => row.username === session.username);
      const previous = mine.find(row => row.key === key);
      if (previous) {
        if (previous.eventId !== eventId || previous.offerId !== offerId) throw conflict("Idempotency-Key was used for a different offer");
        return { serverTime, reservation: previous };
      }
      const event = data.events.find(item => item.id === eventId);
      const offer = event?.offers.find(item => item.id === offerId);
      if (!event || !offer || sale(event, now) !== "ON_SALE") throw conflict("This concert is outside its ticket sale window");
      if (mine.some(row => row.eventId === eventId && row.state !== "EXPIRED")) throw conflict("You already have an active reservation for this concert");
      if (!available(offer)) throw conflict("This ticket tier is sold out");
      const reservation = {
        id: crypto.randomUUID(), eventId, offerId, username: session.username, key,
        eventTitle: event.title, offerLabel: offer.label, priceFen: offer.priceFen, state: "LOCKED",
        createdAt: serverTime, expiresAt: new Date(now + 300000).toISOString(),
      };
      data.reservations.unshift(reservation);
      save();
      return { serverTime, reservation };
    },
  };
}
