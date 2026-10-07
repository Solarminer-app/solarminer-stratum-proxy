(() => {
  const $ = id => document.getElementById(id);
  const state = { coins: [], overview: null, view: 'overview', selected: null, paused: false, events: [], rateHistory: new Map(), revenueHistory: [] };
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = (value, digits = 1) => value == null || !Number.isFinite(Number(value)) ? '—' : Number(value).toLocaleString('en-US', {maximumFractionDigits: digits});
  const compact = value => value == null ? '—' : Intl.NumberFormat('en-US', {notation:'compact', maximumFractionDigits:2}).format(value);
  function money(value, digits = 2) {
    if (value == null || !Number.isFinite(Number(value))) return '—';
    const amount = Number(value);
    const digitsUsed = amount >= 1000 ? 0 : amount >= 1 ? digits : 4;
    return `$${amount.toLocaleString('en-US', {maximumFractionDigits: digitsUsed, minimumFractionDigits: Math.min(digitsUsed, 2)})}`;
  }
  function rate(hps) {
    if (hps == null || !Number.isFinite(hps)) return '—';
    const units = [['EH/s',1e18],['PH/s',1e15],['TH/s',1e12],['GH/s',1e9],['MH/s',1e6],['KH/s',1e3]];
    const [unit, factor] = units.find(([, factor]) => Math.abs(hps) >= factor) || ['H/s',1];
    return `${number(hps / factor, 2)} <small>${unit}</small>`;
  }
  // Realised USD per day per hash-per-second is tiny; express it in the hashrate unit the row already shows.
  function profitability(value, hps) {
    if (value == null || !Number.isFinite(Number(value))) return '—';
    const amount = Number(value);
    if (amount <= 0) return '$0 <small>/24h per H/s</small>';
    const units = [['E',1e18],['P',1e15],['T',1e12],['G',1e9],['M',1e6],['K',1e3],['',1]];
    const own = hps != null && Number.isFinite(Number(hps)) ? units.find(([, factor]) => Math.abs(Number(hps)) >= factor) : null;
    const [prefix, factor] = own || units.find(([, f]) => amount * f >= 0.01) || units[units.length - 1];
    return `${money(amount * factor, 4)} <small>/24h per ${prefix}H/s</small>`;
  }
  function ago(iso) {
    if (!iso) return 'no snapshot';
    const at = new Date(iso);
    if (Number.isNaN(at.getTime())) return 'no snapshot';
    const minutes = Math.max(0, Math.round((Date.now() - at.getTime()) / 60000));
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes} min ago`;
    const hours = Math.round(minutes / 60);
    return hours < 48 ? `${hours} h ago` : at.toLocaleDateString('en-US');
  }
  const coinLabel = name => ({bitcoin:'Bitcoin',monero:'Monero',pearl:'Pearl',decred:'Decred',quantus:'Quantus',ravencoin:'Ravencoin',ethereumclassic:'Ethereum Classic'}[name] || name.replace(/\b\w/g,c=>c.toUpperCase()));
  const ticker = name => ({bitcoin:'BTC',monero:'XMR',pearl:'PRL',decred:'DCR',quantus:'QTC',ravencoin:'RVN',ethereumclassic:'ETC'}[name] || name.slice(0,3).toUpperCase());
  function renderNav() {
    $('portfolio-nav').querySelector('button').classList.toggle('active', state.view === 'overview');
    $('nav-revenue').textContent = state.overview?.grossUsdPer24h == null ? '—' : money(state.overview.grossUsdPer24h, 0);
    $('coin-nav').innerHTML = state.coins.map(coin => `<button type="button" data-coin="${esc(coin.coin)}" class="${coin.coin === state.selected && state.view === 'coin' ? 'active' : ''}"><span class="coin-symbol">${esc(ticker(coin.coin))}</span>${esc(coinLabel(coin.coin))}<span class="nav-right">:${esc(coin.port)}</span></button>`).join('');
    $('coin-nav').querySelectorAll('button').forEach(button => button.addEventListener('click', () => { state.view = 'coin'; state.selected = button.dataset.coin; render(); fetchConsole(); }));
  }
  function renderRoutes(coin) {
    const rows = coin.targets || [];
    if (!rows.length) {
      $('routes').innerHTML = '<tr><td colspan="8" class="empty">No routing targets configured.</td></tr>';
      $('mobile-routes').innerHTML = '<div class="empty">No routing targets configured.</div>';
      return;
    }
    $('routes').innerHTML = rows.map(target => {
      const active = target.connectedWorkers > 0;
      const isUser = target.targetId === 'USER';
      const status = isUser ? (active ? 'CONNECTED' : 'WAITING') : (active ? 'CONNECTED' : 'IDLE');
      const hash = target.estimatedHashrateHps == null ? '—' : rate(target.estimatedHashrateHps);
      return `<tr><td><div class="route-name"><span class="route-mark">${isUser?'↗':'◇'}</span><span>${esc(target.label)}<div class="route-sub">${esc(target.targetId)}${target.house === true?' · HOUSE':''}</div></span></div></td><td>${esc(target.pool || 'Pool not identified')}</td><td><div class="meter"><span class="meter-track"><span class="meter-fill" style="width:${Math.min(100,Math.max(0,target.routedJobPercent||0))}%"></span></span><b>${number(target.routedJobPercent,1)}%</b></div></td><td>${hash}</td><td><span class="good">${compact(target.acceptedShares)}</span> / <span class="bad">${compact(target.rejectedShares)}</span></td><td>${isUser?'—':`${number(target.configuredFeePercent,2)}%`}</td><td>${target.estimatedUsdPerHour == null?'—':`$${number(target.estimatedUsdPerHour,4)}`}</td><td><span class="status-tag ${active?'':'off'}">${status}</span></td></tr>`;
    }).join('');
    $('mobile-routes').innerHTML = rows.map(target => {
      const isUser = target.targetId === 'USER', active = target.connectedWorkers > 0;
      return `<article class="mobile-route"><div class="mobile-route-top"><div class="route-name"><span class="route-mark">${isUser?'↗':'◇'}</span><span>${esc(target.label)}<div class="route-sub">${esc(target.pool || target.targetId)}</div></span></div><span class="status-tag ${active?'':'off'}">${active?'CONNECTED':'IDLE'}</span></div><div class="meter"><span class="meter-track"><span class="meter-fill" style="width:${Math.min(100,Math.max(0,target.routedJobPercent||0))}%"></span></span><b>${number(target.routedJobPercent,1)}%</b></div><div class="mobile-route-meta"><span>${rate(target.estimatedHashrateHps)} · ${compact(target.connectedWorkers)} workers</span><span><i class="good">${compact(target.acceptedShares)}</i> / <i class="bad">${compact(target.rejectedShares)}</i> shares</span></div></article>`;
    }).join('');
    const sum = rows.reduce((total, target) => total + (Number(target.routedJobPercent)||0), 0);
    $('route-total').textContent = `${rows.length} DESTINATIONS · ${number(sum,1)}% ALLOCATED`;
  }
  function renderSplit(overview) {
    const parts = [
      {key:'user', label:'User pools', value:overview.userPoolUsdPer24h, className:'user'},
      {key:'house', label:'House fee', value:overview.houseFeeUsdPer24h, className:'house'},
      {key:'referrer', label:'Referrer fee', value:overview.referrerFeeUsdPer24h, className:'referrer'}
    ];
    const total = parts.reduce((sum, part) => sum + (Number(part.value) || 0), 0);
    if (!total) {
      $('split-bar').innerHTML = '';
      $('split-legend').innerHTML = '<div class="empty">No accepted share work in the last 24h, or no currency snapshot available.</div>';
      return;
    }
    $('split-bar').innerHTML = parts.filter(part => Number(part.value) > 0)
      .map(part => `<span class="split-segment ${part.className}" style="width:${Number(part.value) / total * 100}%" title="${esc(part.label)} ${money(part.value)}"></span>`).join('');
    $('split-legend').innerHTML = parts.map(part => `<div class="split-row ${part.className}"><span class="split-mark"></span><span class="split-label">${esc(part.label)}</span><span class="split-value">${money(part.value)}</span><span class="split-share">${number(Number(part.value || 0) / total * 100, 1)}%</span></div>`).join('');
  }
  function renderOverview() {
    const overview = state.overview;
    if (!overview) return;
    const coins = overview.coins || [];
    $('ov-revenue').textContent = overview.grossUsdPer24h == null ? '—' : money(overview.grossUsdPer24h);
    $('ov-revenue-detail').textContent = overview.grossUsdPer24h == null ? 'No accepted work or no currency snapshot' : `Across ${coins.length} running network${coins.length === 1 ? '' : 's'}`;
    $('ov-projected').textContent = overview.projectedUsdPer24h == null ? '—' : money(overview.projectedUsdPer24h);
    $('ov-hashrate').innerHTML = rate(overview.totalHashrateHps);
    $('ov-hashrate-detail').textContent = overview.totalHashrateHps == null ? 'Waiting for accepted shares' : `${coins.length} running port${coins.length === 1 ? '' : 's'}`;
    $('ov-coins').textContent = `${overview.activeCoins} / ${overview.configuredCoins}`;
    $('ov-workers').textContent = `${number(overview.connectedWorkers,0)} connected workers`;
    $('ov-net').textContent = overview.userPoolUsdPer24h == null ? '—' : money(overview.userPoolUsdPer24h);
    const fees = (Number(overview.houseFeeUsdPer24h) || 0) + (Number(overview.referrerFeeUsdPer24h) || 0);
    $('ov-fee-detail').textContent = overview.grossUsdPer24h == null ? 'After house and referrer fees' : `${money(fees)} routed to fee targets`;
    renderSplit(overview);

    if (!coins.length) {
      $('ov-coins-body').innerHTML = '<tr><td colspan="9" class="empty">No miner is mining through this proxy right now.</td></tr>';
      $('ov-coins-mobile').innerHTML = '<div class="empty">No miner is mining through this proxy right now.</div>';
      $('ov-total').textContent = '0 RUNNING NETWORKS';
    } else {
      $('ov-coins-body').innerHTML = coins.map((coin, index) => `<tr><td><span class="rank">${index + 1}</span></td><td><div class="route-name"><span class="route-mark">${esc(ticker(coin.coin))}</span><span>${esc(coinLabel(coin.coin))}<div class="route-sub">:${coin.port} · ${coin.connectedWorkers} workers</div></span></div></td><td>${rate(coin.estimatedHashrateHps)}</td><td class="strong">${coin.realUsdPer24h == null ? '—' : money(coin.realUsdPer24h)}</td><td>${coin.projectedUsdPer24h == null ? '—' : money(coin.projectedUsdPer24h)}</td><td>${profitability(coin.profitabilityUsdPerDayPerHps, coin.estimatedHashrateHps)}</td><td>${coin.priceUsd == null ? '—' : `$${number(coin.priceUsd, coin.priceUsd >= 1 ? 2 : 5)}`}</td><td><span class="good">${compact(coin.acceptedShares)}</span> / <span class="bad">${compact(coin.rejectedShares)}</span></td><td>${number(coin.connectedWorkers,0)}</td></tr>`).join('');
      $('ov-coins-mobile').innerHTML = coins.map((coin, index) => `<article class="mobile-route"><div class="mobile-route-top"><div class="route-name"><span class="route-mark">${index + 1}</span><span>${esc(coinLabel(coin.coin))}<div class="route-sub">:${coin.port} · ${esc(ticker(coin.coin))}</div></span></div><span class="status-tag">${coin.realUsdPer24h == null ? 'UNPRICED' : money(coin.realUsdPer24h)}</span></div><div class="mobile-route-meta"><span>${rate(coin.estimatedHashrateHps)} · ${number(coin.connectedWorkers,0)} workers</span><span>${profitability(coin.profitabilityUsdPerDayPerHps, coin.estimatedHashrateHps)}</span></div></article>`).join('');
      const priced = coins.filter(coin => coin.realUsdPer24h != null).length;
      $('ov-total').textContent = `${coins.length} RUNNING NETWORKS · ${priced} PRICED`;
    }
    const stale = coins.filter(coin => coin.currencyDataStale).length;
    $('market-chip-text').textContent = state.currencyError ? 'Currency Service unavailable' : stale === 0 ? 'Currency snapshots fresh' : 'Currency data partially stale';
    $('market-chip-age').textContent = state.currencyError ? 'ERROR' : ago(coins.map(coin => coin.currencyDataUpdatedAt).filter(Boolean).sort().pop());
    $('market-chip').classList.toggle('offline', Boolean(state.currencyError) || stale > 0);
    drawSpark('ov-spark-line', 'ov-spark-area', state.revenueHistory);
  }
  function drawSpark(lineId, areaId, history) {
    if (history.length < 2) { $(lineId).setAttribute('d',''); $(areaId).setAttribute('d',''); return; }
    const min = Math.min(...history), range = Math.max(...history) - min || 1;
    const path = history.map((sample, index) => `${index ? 'L' : 'M'}${(index / (history.length - 1) * 320).toFixed(1)},${(42 - (sample - min) / range * 34).toFixed(1)}`).join(' ');
    $(lineId).setAttribute('d', path); $(areaId).setAttribute('d', `${path} L320,48 L0,48 Z`);
  }
  function renderCoin() {
    const coin = state.coins.find(item => item.coin === state.selected);
    if (!coin) return;
    $('coin-title').textContent = coinLabel(coin.coin);
    $('listener-port').textContent = `:${coin.port}`;
    $('listener-text').textContent = coin.listenerStatus === 'online' ? 'Listener online' : 'Listener offline';
    $('listener-chip').classList.toggle('offline', coin.listenerStatus !== 'online');
    $('workers').textContent = number(coin.connectedWorkers,0);
    $('upstreams').textContent = `${number(coin.upstreamConnections,0)} active upstream connections`;
    $('hashrate').innerHTML = rate(coin.estimatedHashrateHps);
    if (coin.estimatedHashrateHps != null) {
      const history = state.rateHistory.get(coin.coin) || [];
      history.push(Number(coin.estimatedHashrateHps));
      state.rateHistory.set(coin.coin, history.slice(-40));
    }
    $('rate-foot').textContent = coin.estimatedHashrateHps == null ? 'Waiting for accepted shares' : 'Estimated from accepted shares';
    $('shares').textContent = compact(coin.acceptedShares);
    $('share-detail').textContent = `${compact(coin.acceptedShares)} accepted · ${compact(coin.rejectedShares)} rejected`;
    $('difficulty').textContent = number(coin.averageAcceptedShareDifficulty,2);
    $('value').textContent = coin.estimatedUsdPerHour == null ? '—' : `$${number(coin.estimatedUsdPerHour,4)}`;
    const stale = coin.currencyDataStale == null || coin.currencyDataStale;
    $('market-state').textContent = coin.priceUsd > 0
      ? `${stale ? 'PRICE ONLY' : 'QUOTE'} ${coin.ticker || ticker(coin.coin)} · $${number(coin.priceUsd,5)}`
      : 'PRICE UNAVAILABLE';
    $('value-detail').textContent = coin.estimatedUsdPerHour == null ? 'Currency snapshot unavailable or stale' : `${coin.ticker || ticker(coin.coin)} gross value · public snapshot`;
    renderRoutes(coin);
    drawSpark('spark-line', 'spark-area', state.rateHistory.get(coin.coin) || []);
  }
  function render() {
    renderNav();
    $('overview-view').hidden = state.view !== 'overview';
    $('coin-view').hidden = state.view !== 'coin';
    if (state.view === 'overview') renderOverview();
    else renderCoin();
    const sampledAt = state.overview?.sampledAt || state.coins[0]?.sampledAt;
    if (sampledAt) $('updated').textContent = `Updated ${new Date(sampledAt).toLocaleTimeString('en-US',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}`;
    $('runtime-state').textContent = state.coins.some(item=>item.listenerStatus==='online') ? 'OPERATIONAL' : 'CHECK LISTENERS';
    $('runtime-state').style.color = state.coins.some(item=>item.listenerStatus==='online') ? 'var(--accent)' : 'var(--amber)';
  }
  async function fetchDashboard() {
    try {
      const response = await fetch('api/dashboard', {cache:'no-store'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json();
      state.coins = payload.coins || [];
      state.overview = payload.overview || null;
      state.currencyError = payload.currencyServiceError || null;
      if (state.overview?.grossUsdPer24h != null) state.revenueHistory.push(Number(state.overview.grossUsdPer24h));
      state.revenueHistory = state.revenueHistory.slice(-40);
      if (!state.selected || !state.coins.some(coin=>coin.coin===state.selected)) state.selected = state.coins[0]?.coin || null;
      render();
    } catch (error) { $('runtime-state').textContent = 'API UNAVAILABLE'; $('updated').textContent = `Connection error · ${error.message}`; }
  }
  async function fetchConsole() {
    if (state.paused) return;
    const level = $('level-filter').value;
    const coin = state.view === 'coin' ? state.selected : 'all';
    try {
      const url = `api/dashboard/console?coin=${encodeURIComponent(coin || 'all')}&level=${encodeURIComponent(level)}&limit=100`;
      const response = await fetch(url, {cache:'no-store'}); if (!response.ok) return;
      const events = await response.json(); state.events = events;
      const container = $('console');
      if (!events.length) container.innerHTML = '<div class="empty">No events for this filter.</div>';
      else container.innerHTML = events.map(event => `<div class="event-row"><span class="event-time">${esc(new Date(event.at).toLocaleTimeString('en-US',{hour12:false}))}</span><span class="event-level ${esc(event.level)}">${esc(event.level)}</span><span class="event-coin">${esc(ticker(event.coin))}</span><span class="event-message">${esc(event.message)}</span></div>`).join('');
      $('event-count').textContent = `${events.length} EVENTS`;
    } catch (_) { /* A transient console poll does not interrupt the dashboard. */ }
  }
  $('portfolio-nav').querySelector('button').addEventListener('click', () => { state.view = 'overview'; render(); fetchConsole(); });
  $('level-filter').addEventListener('change', fetchConsole);
  $('pause-console').addEventListener('click', event => { state.paused = !state.paused; event.currentTarget.textContent = state.paused ? 'Resume stream' : 'Pause stream'; event.currentTarget.setAttribute('aria-pressed',String(state.paused)); if (!state.paused) fetchConsole(); });
  fetchDashboard(); fetchConsole(); setInterval(fetchDashboard,3000); setInterval(fetchConsole,3000);
})();
