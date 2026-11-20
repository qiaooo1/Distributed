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
  return offer.capacity - data.reservations.filter(row => row.offerId === offer.id && row.state === "LOCKED").length;
}
function conflict(detail) {
  return Object.assign(new Error(detail), { status:409, detail });
}

export function openTicketDemo(session) {
  data ??= load();
  return {
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
      if (mine.some(row => row.eventId === eventId && row.state === "LOCKED")) throw conflict("You already have an active reservation for this concert");
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
