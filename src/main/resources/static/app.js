'use strict';

/* REDxela · interfaz en tiempo real.
 * Recibe un snapshot completo del sistema por WebSocket (/ws) cada medio segundo y repinta los
 * paneles de datos; los formularios son estáticos y hablan con la API REST. */

// ------------------------------------------------------------------ utilidades

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];

/** Escapa texto que viene del servidor antes de insertarlo como HTML (evita XSS). */
const esc = (s) => String(s ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const num = (n, d = 1) => (Number.isFinite(n) ? n.toFixed(d) : '–');

const MODE_HINT = {
  ATOMIC: 'Prevención: el pedido toma todos sus recursos de una vez o ninguno (niega "retener y esperar").',
  ORDERED: 'Prevención: todos piden los recursos en el mismo orden global (niega la espera circular).',
  NATURAL: 'Sin prevención: cada tipo pide en su propio orden; dos tipos en sentido inverso pueden bloquearse.',
};

const RESOURCE_NAMES = {
  EMPAQUE: 'Estaciones de empaque', AREA_CARGA: 'Áreas de carga', ENCARGADO: 'Encargados de bodega',
  MONTACARGAS: 'Montacargas', CONTROL_CALIDAD: 'Control de calidad', ESCANER: 'Sistema de escaneo',
};

const TYPE_PALETTE = ['#2563eb', '#c2410c', '#15803d', '#7c3aed', '#b45309', '#0e7490', '#be185d', '#4d7c0f'];

const STATUS_LABEL = {
  EN_PROCESO: 'En proceso', EN_ESPERA: 'En espera', ESPERANDO_INVENTARIO: 'Sin existencias',
  BLOQUEADO: 'Bloqueado', COMPLETADO: 'Completado',
};

const catalog = { products: [], types: [], levels: [], policies: [], typeColor: {}, productType: {} };
let snap = null;
let session = loadSession();

// ------------------------------------------------------------------ DOM seguro

const lastHtml = new WeakMap();
/** Reemplaza el contenido solo si cambió, para no repintar (ni parpadear) a cada snapshot. */
function setHtml(el, html) {
  if (lastHtml.get(el) !== html) {
    el.innerHTML = html;
    lastHtml.set(el, html);
  }
}

function setText(el, text) {
  if (el.textContent !== text) el.textContent = text;
}

/** Actualiza un control desde el servidor sin pisar lo que el usuario está editando. */
function syncValue(el, value) {
  if (document.activeElement !== el && el.value !== String(value)) el.value = value;
}

function toast(message, kind = 'info') {
  const box = $('#toasts');
  const t = document.createElement('div');
  t.className = 'toast' + (kind === 'error' ? ' error' : '');
  t.textContent = message;
  box.appendChild(t);
  setTimeout(() => t.remove(), kind === 'error' ? 6000 : 3500);
}

// ------------------------------------------------------------------------ API

async function api(path, { method = 'GET', body, auth = false } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && session) headers.Authorization = 'Bearer ' + session.token;
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = text; }
  }
  if (!res.ok) throw new Error((data && data.error) || `Error ${res.status}`);
  return data;
}

/** Ejecuta una acción mostrando el error como aviso y deshabilitando el botón mientras corre. */
async function run(button, action, okMessage) {
  if (button) button.disabled = true;
  try {
    const result = await action();
    if (okMessage) toast(typeof okMessage === 'function' ? okMessage(result) : okMessage);
    return result;
  } catch (e) {
    toast(e.message, 'error');
    return undefined;
  } finally {
    if (button) button.disabled = false;
  }
}

function loadSession() {
  try { return JSON.parse(localStorage.getItem('redxela.session') || 'null'); } catch { return null; }
}

function saveSession(s) {
  session = s;
  try {
    if (s) localStorage.setItem('redxela.session', JSON.stringify(s));
    else localStorage.removeItem('redxela.session');
  } catch { /* almacenamiento no disponible: la sesión vive solo en memoria */ }
}

// ----------------------------------------------------------------- WebSocket

let retryMs = 500;

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.onopen = () => { retryMs = 500; setConn(true); };
  ws.onmessage = (e) => {
    try {
      snap = JSON.parse(e.data);
      render(snap);
    } catch (err) {
      console.error('Snapshot inválido', err);
    }
  };
  ws.onclose = () => {
    setConn(false);
    setTimeout(connect, retryMs);
    retryMs = Math.min(retryMs * 2, 5000);
  };
  ws.onerror = () => ws.close();
}

function setConn(on) {
  const el = $('#conn');
  el.className = 'conn ' + (on ? 'on' : 'off');
  setText(el, on ? 'en vivo' : 'sin conexión');
}

// -------------------------------------------------------------------- render

function render(s) {
  const st = s.state;
  setText($('#clock-now'), num(st.clock.nowMinutes));
  setText($('#clock-speed'), String(st.clock.speed));

  renderDeadlock(s.deadlock);
  renderControls(st, s.reception);
  renderResources(st.resources);
  renderRunning(st.running);
  renderWaiting(st);
  renderStats(s.stats, st);
  renderChannel(s.reception, s.inventory);
  renderWarehouse(s.inventory);
  renderEvents(s.events);
}

function levelBadge(level) {
  return `<span class="lvl lvl-${level.level}" title="${esc(level.name)}">${level.level}</span>`;
}

function statusBadge(status) {
  return `<span class="badge st-${esc(status)}">${esc(STATUS_LABEL[status] || status)}</span>`;
}

function ownerLabel(id) {
  return id > 0 ? `#${id}` : `Lote ${-id}`;
}

function renderControls(st, reception) {
  syncValue($('#ctl-policy'), st.policy.id);
  setText($('#ctl-policy-desc'), st.policy.description);
  syncValue($('#ctl-mode'), st.acquisitionMode);
  setText($('#ctl-mode-desc'), MODE_HINT[st.acquisitionMode] || '');
  syncValue($('#ctl-speed'), st.clock.speed);
  const gen = $('#gen-toggle');
  setText(gen, st.generator.running ? 'Detener' : 'Iniciar');
  gen.classList.toggle('primary', !st.generator.running);
  syncValue($('#gen-rate'), st.generator.ratePerSimMinute);
  setText($('#restock-toggle'), reception.autoRestock ? 'Desactivar' : 'Activar');
}

function renderDeadlock(d) {
  const panel = $('#deadlock-panel');
  panel.classList.toggle('hidden', !d.active);
  if (!d.active) return;
  const r = d.report;
  setText($('#deadlock-explanation'), r.explanation);
  setHtml($('#deadlock-resources'),
    r.resources.map((x) => `<span class="chip">${esc(RESOURCE_NAMES[x] || x)}</span>`).join(''));
  setHtml($('#deadlock-rows'), r.orders.map((o) => `
    <tr>
      <td><strong>#${o.orderId}</strong> <small>${esc(o.serviceLevel)} · ${o.totalUnits} u.</small></td>
      <td>${o.holds.map((h) => esc(RESOURCE_NAMES[h] || h)).join(', ') || '–'}</td>
      <td>${esc(RESOURCE_NAMES[o.waitsFor] || o.waitsFor || '–')}</td>
      <td>${o.blockedBy.map((b) => '#' + b).join(', ') || '–'}</td>
      <td><button class="btn small" data-keep="${o.orderId}">Continuar este pedido</button></td>
    </tr>`).join(''));
}

function renderResources(resources) {
  setHtml($('#resources'), resources.map((r) => {
    const units = Array.from({ length: r.capacity },
      (_, i) => `<span class="unit${i < r.inUse ? ' used' : ''}"></span>`).join('');
    const who = r.holders.length ? 'En uso por ' + r.holders.map(ownerLabel).join(', ') : 'Disponible';
    const wait = r.waiters.length ? `<div class="res-wait">Esperan: ${r.waiters.map(ownerLabel).join(', ')}</div>` : '';
    return `<div class="res${r.available === 0 ? ' full' : ''}">
      <div class="res-head"><span class="res-name">${esc(r.name)}</span>
        <span class="res-num">${r.inUse}/${r.capacity}</span></div>
      <div class="units">${units}</div>
      <div class="res-who">${esc(who)}</div>${wait}</div>`;
  }).join(''));
}

function typesOf(order) {
  return order.merchandiseTypes.map((t) => `<span class="chip"><i style="background:${catalog.typeColor[t] || '#888'}"></i>${esc(typeName(t))}</span>`).join('');
}

function typeName(code) {
  const t = catalog.types.find((x) => x.code === code);
  return t ? t.name : code;
}

function renderRunning(running) {
  setText($('#running-count'), `(${running.length})`);
  if (!running.length) {
    setHtml($('#running-rows'), '<tr class="empty"><td colspan="7">Ningún pedido en proceso</td></tr>');
    return;
  }
  setHtml($('#running-rows'), running.map((o) => {
    const pct = Math.round(o.progress * 100);
    const waiting = o.waitingFor ? `<br><small class="age warn">espera ${esc(RESOURCE_NAMES[o.waitingFor] || o.waitingFor)}</small>` : '';
    return `<tr>
      <td><strong>#${o.id}</strong><br><small>${esc(o.customer)}</small></td>
      <td>${levelBadge(o.serviceLevel)}</td>
      <td class="num">${o.totalUnits}</td>
      <td>${typesOf(o)}</td>
      <td>${statusBadge(o.status)}${waiting}</td>
      <td><div class="bar ok"><span style="width:${pct}%"></span><em>${pct}%</em></div></td>
      <td class="num">${num(o.remainingMinutes)} min</td>
    </tr>`;
  }).join(''));
}

function renderWaiting(st) {
  const list = st.waiting;
  setText($('#waiting-count'), `(${list.length})`);
  setText($('#waiting-criteria'), `${st.policy.name} — ${st.policy.description}`);
  if (!list.length) {
    setHtml($('#waiting-rows'), '<tr class="empty"><td colspan="7">La lista de espera está vacía</td></tr>');
    return;
  }
  setHtml($('#waiting-rows'), list.map((o, i) => {
    const target = Math.max(o.serviceLevel.maxWaitMinutes, 1);
    const ratio = o.waitedMinutes / target;
    const cls = ratio > 1 ? 'bad' : ratio > 0.7 ? 'warn' : 'ok';
    return `<tr>
      <td>${i + 1}</td>
      <td><strong>#${o.id}</strong>${o.requeues ? ` <small title="Veces que regresó a la lista">↺${o.requeues}</small>` : ''}</td>
      <td>${levelBadge(o.serviceLevel)}</td>
      <td class="num">${o.totalUnits}</td>
      <td>${typesOf(o)}</td>
      <td class="age ${cls}">${num(o.waitedMinutes)} / ${target} min</td>
      <td>${statusBadge(o.status)}</td>
    </tr>`;
  }).join(''));
}

function renderStats(stats, st) {
  const awaitingStock = st.waiting.filter((o) => o.status === 'ESPERANDO_INVENTARIO').length;
  const kpi = (v, l) => `<div class="kpi"><div class="v">${v}</div><div class="l">${l}</div></div>`;
  setHtml($('#kpis'), [
    kpi(stats.completed, 'Pedidos completados'),
    kpi(num(stats.averageWaitMinutes) + ' min', 'Espera promedio'),
    kpi(num(stats.averageProcessingMinutes) + ' min', 'Procesamiento promedio'),
    kpi(num(stats.slaCompliancePct, 0) + ' %', 'Cumplen su tiempo objetivo'),
    kpi(num(stats.throughputPerMinute, 2) + ' /min', 'Pedidos por minuto'),
    kpi(st.waiting.length + ' · ' + awaitingStock, 'En espera · sin existencias'),
  ].join(''));

  setHtml($('#stats-levels'), stats.levels.map((l) => `<tr>
    <td>${levelBadge({ level: l.level, name: l.name })} ${esc(l.name)}</td>
    <td class="num">${l.completed}</td>
    <td class="num">${l.completed ? num(l.averageWaitMinutes) + ' min' : '–'}</td>
    <td class="num">${l.completed ? num(l.averageProcessingMinutes) + ' min' : '–'}</td>
    <td class="num">${l.completed ? num(l.slaCompliancePct, 0) + ' %' : '–'} <small>(≤ ${num(l.targetMinutes, 0)} min)</small></td>
  </tr>`).join(''));

  setHtml($('#stats-resources'), '<strong>Ocupación de recursos</strong>' + stats.resources.map((r) => `
    <div class="usage-row"><span>${esc(RESOURCE_NAMES[r.type] || r.type)}</span>
      <div class="bar ${r.currentPct >= 100 ? 'warn' : ''}"><span style="width:${Math.min(100, r.currentPct)}%"></span><em>${num(r.currentPct, 0)}%</em></div>
      <span>prom. ${num(r.averagePct, 0)}%</span></div>`).join(''));
}

function renderChannel(rec, inv) {
  const slots = Array.from({ length: rec.queueCapacity },
    (_, i) => `<span class="slot${i < rec.queueSize ? ' full' : ''}"></span>`).join('');
  const truck = rec.truckLotsPending > 0 ? ` · camión descargando: faltan ${rec.truckLotsPending} lotes` : '';
  setHtml($('#channel'), `
    <div><strong>Canal Recepción → Inventario</strong> (${rec.queueSize}/${rec.queueCapacity} avisos pendientes)${esc(truck)}</div>
    <div class="slots">${slots}</div>
    <div>Recepción envió ${rec.sentLots} lotes · Inventario procesó ${inv.processedMessages} ·
      almacenó ${inv.storedUnits} u. · rechazó ${inv.rejectedUnits} u. por falta de espacio</div>`);
}

function renderWarehouse(inv) {
  const w = inv.warehouse;
  setText($('#warehouse-summary'),
    `Capacidad ${w.capacityUnits} u. (${w.aisles} pasillos × ${w.levels} niveles × ${w.positions} posiciones × ${w.slotCapacity} u.) · ` +
    `ocupado ${w.usedUnits} u. (${num(100 * w.usedUnits / w.capacityUnits, 0)}%)`);

  const slotAt = new Map(w.occupiedSlots.map((s) => [`${s.aisle}-${s.level}-${s.position}`, s]));
  let html = '';
  for (let a = 1; a <= w.aisles; a++) {
    let cells = '';
    for (let l = w.levels; l >= 1; l--) {          // el nivel más alto se dibuja arriba
      for (let p = 1; p <= w.positions; p++) {
        const s = slotAt.get(`${a}-${l}-${p}`);
        if (s) {
          const color = catalog.typeColor[catalog.productType[s.productId]] || '#888';
          const pct = Math.round(100 * s.quantity / w.slotCapacity);
          cells += `<div class="slot-cell" title="${esc(s.label)} · ${esc(s.sku)} · ${s.quantity}/${w.slotCapacity} u."><span style="height:${pct}%;background:${color}"></span></div>`;
        } else {
          cells += `<div class="slot-cell" title="Libre"></div>`;
        }
      }
    }
    html += `<div class="aisle"><h3>Pasillo ${a}</h3>
      <div class="aisle-grid" style="grid-template-columns:repeat(${w.positions},26px)">${cells}</div></div>`;
  }
  setHtml($('#warehouse-map'), html);

  setHtml($('#warehouse-legend'), catalog.types.map((t) =>
    `<span class="chip"><i style="background:${catalog.typeColor[t.code]}"></i>${esc(t.name)}</span>`).join(''));

  setHtml($('#inventory-rows'), inv.products.map((p) => {
    const locs = p.locations.map((l) => `${l.location}×${l.quantity}`).join(', ') || '–';
    return `<tr>
      <td><strong>${esc(p.sku)}</strong> ${esc(p.name)}</td>
      <td>${esc(typeName(p.merchandiseType))}</td>
      <td class="num">${p.onHand}</td><td class="num">${p.reserved}</td>
      <td class="num ${p.available <= 0 ? 'age bad' : ''}">${p.available}</td>
      <td><small>${esc(locs)}</small></td></tr>`;
  }).join(''));
}

function renderEvents(events) {
  setHtml($('#events'), events.map((e) => `
    <li class="ev-${esc(e.type)}"><span class="t">m ${num(e.simMinute)}</span>
      <span class="ty">${esc(e.type)}</span><span>${esc(e.message)}</span></li>`).join(''));
}

// ------------------------------------------------------------ controles (REST)

function bindControls() {
  $('#ctl-policy').addEventListener('change', (e) =>
    run(null, () => api('/api/policy', { method: 'PUT', body: { id: e.target.value } }), 'Política cambiada'));
  $('#ctl-mode').addEventListener('change', (e) =>
    run(null, () => api('/api/mode', { method: 'PUT', body: { mode: e.target.value } }), 'Modo de adquisición cambiado'));
  $('#ctl-speed').addEventListener('change', (e) =>
    run(null, () => api('/api/speed', { method: 'PUT', body: { speed: Number(e.target.value) } })));

  $('#gen-toggle').addEventListener('click', (e) => run(e.currentTarget, () =>
    api(snap && snap.state.generator.running ? '/api/generator/stop' : '/api/generator/start', { method: 'POST' })));
  $('#gen-rate').addEventListener('change', (e) =>
    run(null, () => api('/api/generator', { method: 'PUT', body: { ratePerSimMinute: Number(e.target.value) } })));
  $('#restock-toggle').addEventListener('click', (e) => run(e.currentTarget, () =>
    api('/api/reception/auto-restock', { method: 'PUT', body: { enabled: !(snap && snap.reception.autoRestock) } })));

  $('#scn-deadlock').addEventListener('click', (e) => run(e.currentTarget,
    () => api('/api/scenarios/deadlock', { method: 'POST' }),
    (r) => {
      if (r.warning) toast(r.warning, 'error');
      return `Escenario armado (modo ${r.acquisitionMode}${r.generatorStopped ? ', generador detenido' : ''}): espere a que se forme el ciclo`;
    }));
  $('#scn-contention').addEventListener('click', (e) => run(e.currentTarget,
    () => api('/api/scenarios/contention', { method: 'POST' }), 'Escenario de contención armado'));

  $('#deadlock-rows').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-keep]');
    if (!btn) return;
    run(btn, () => api('/api/deadlock/resolve', { method: 'POST', body: { keepOrderId: Number(btn.dataset.keep) } }),
      `Conflicto resuelto: continúa el pedido #${btn.dataset.keep}`);
  });
}

// ------------------------------------------------------------- recepción

function bindReception() {
  $('#reception-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    run($('button', f), () => api('/api/reception', { method: 'POST', body: {
      productId: Number(f.productId.value), quantity: Number(f.quantity.value) } }),
      (r) => `Lote #${r.id} recibido: ${r.quantity} u. enviadas a Inventario`);
  });
  $('#truck-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    run($('button', f), () => api('/api/reception/truck', { method: 'POST', body: {
      lots: Number(f.lots.value), unitsPerLot: Number(f.unitsPerLot.value) } }), 'Camión en descarga');
  });
}

// ----------------------------------------------------------- cuenta y pedidos

const MAX_LINES = 6;

function levelLabel(l) {
  return `${l.level} · ${l.name} (${l.maxWaitMinutes === 0 ? 'casi instantáneo' : 'máx. ' + l.maxWaitMinutes + ' min en espera'})`;
}

function fillSelect(sel, options, keepFirst = false) {
  const first = keepFirst ? sel.options[0].outerHTML : '';
  sel.innerHTML = first + options.map((o) => `<option value="${esc(o.value)}">${esc(o.label)}</option>`).join('');
}

function productOptions() {
  return catalog.products.map((p) => ({ value: p.id, label: `${p.sku} · ${p.name} (${p.merchandiseTypeName})` }));
}

function addLine() {
  const box = $('#order-lines');
  if (box.children.length >= MAX_LINES) return;
  const div = document.createElement('div');
  div.className = 'line';
  div.innerHTML = `<select name="productId" aria-label="Producto"></select>
    <input name="quantity" type="number" min="1" max="500" value="5" aria-label="Cantidad">
    <button type="button" class="btn link" aria-label="Quitar">✕</button>`;
  fillSelect($('select', div), productOptions());
  $('button', div).addEventListener('click', () => { if (box.children.length > 1) div.remove(); });
  box.appendChild(div);
}

function showSession() {
  const logged = !!session;
  $('#auth-box').classList.toggle('hidden', logged);
  $('#order-box').classList.toggle('hidden', !logged);
  if (logged) {
    setText($('#who-name'), session.customer.name);
    $('#who-level').value = String(session.customer.serviceLevel.level);
    if (!$('#order-lines').children.length) addLine();
  }
}

function bindAccount() {
  $$('.tab').forEach((t) => t.addEventListener('click', () => {
    $$('.tab').forEach((x) => x.classList.toggle('active', x === t));
    $('#login-form').classList.toggle('hidden', t.dataset.tab !== 'login');
    $('#register-form').classList.toggle('hidden', t.dataset.tab !== 'register');
  }));

  $('#register-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    const created = await run($('button', f), () => api('/api/auth/register', { method: 'POST', body: {
      name: f.name.value, email: f.email.value, password: f.password.value,
      serviceLevel: Number(f.serviceLevel.value) } }), 'Cuenta creada');
    if (created) {
      const res = await run(null, () => api('/api/auth/login', { method: 'POST', body: {
        email: f.email.value, password: f.password.value } }));
      if (res) { saveSession(res); f.reset(); showSession(); }
    }
  });

  $('#login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.currentTarget;
    const res = await run($('button', f), () => api('/api/auth/login', { method: 'POST', body: {
      email: f.email.value, password: f.password.value } }));
    if (res) { saveSession(res); f.reset(); showSession(); }
  });

  $('#logout').addEventListener('click', async () => {
    await run(null, () => api('/api/auth/logout', { method: 'POST', auth: true }));
    saveSession(null);
    showSession();
  });

  $('#who-level').addEventListener('change', async (e) => {
    const c = await run(null, () => api('/api/auth/service-level', { method: 'PUT', auth: true,
      body: { serviceLevel: Number(e.target.value) } }), 'Nivel de servicio actualizado');
    if (c) { session.customer = c; saveSession(session); }
  });

  $('#add-line').addEventListener('click', addLine);

  $('#order-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const items = $$('.line', e.currentTarget).map((l) => ({
      productId: Number($('select', l).value), quantity: Number($('input', l).value) }));
    const forced = $('#order-level').value;
    run($('button[type=submit]', e.currentTarget), () => api('/api/orders', { method: 'POST', auth: true,
      body: { items, ...(forced ? { serviceLevel: Number(forced) } : {}) } }),
      (o) => `Pedido #${o.id} registrado · nivel ${o.serviceLevel.name} · ${o.totalUnits} u.`)
      .then(() => {});
  });
}

async function restoreSession() {
  if (!session) return;
  try {
    session.customer = await api('/api/auth/me', { auth: true });
    saveSession(session);
  } catch {
    saveSession(null);   // token vencido (el servidor se reinició)
  }
}

// ------------------------------------------------------------------- arranque

async function init() {
  bindControls();
  bindReception();
  bindAccount();
  try {
    const [products, types, levels, policies] = await Promise.all([
      api('/api/products'), api('/api/merchandise-types'), api('/api/service-levels'), api('/api/policies')]);
    Object.assign(catalog, { products, types, levels, policies });
    types.forEach((t, i) => { catalog.typeColor[t.code] = TYPE_PALETTE[i % TYPE_PALETTE.length]; });
    products.forEach((p) => { catalog.productType[p.id] = p.merchandiseType; });

    fillSelect($('#ctl-policy'), policies.map((p) => ({ value: p.id, label: p.name })));
    fillSelect($('#register-level'), levels.map((l) => ({ value: l.level, label: levelLabel(l) })));
    $('#register-level').value = '3';
    fillSelect($('#who-level'), levels.map((l) => ({ value: l.level, label: levelLabel(l) })));
    fillSelect($('#order-level'), levels.map((l) => ({ value: l.level, label: levelLabel(l) })), true);
    fillSelect($('#reception-product'), productOptions());
  } catch (e) {
    toast('No se pudo cargar el catálogo: ' + e.message, 'error');
  }
  await restoreSession();
  showSession();
  connect();
}

init();
