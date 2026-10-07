(() => {
  const $ = id => document.getElementById(id);
  const state = { coins: [], selected: null, paused: false, events: [], rateHistory: new Map() };
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = (value, digits = 1) => value == null || !Number.isFinite(Number(value)) ? '—' : Number(value).toLocaleString('en-US', {maximumFractionDigits: digits});
  const compact = value => value == null ? '—' : Intl.NumberFormat('en-US', {notation:'compact', maximumFractionDigits:2}).format(value);
  function rate(hps) {
    if (hps == null || !Number.isFinite(hps)) return '—';
    const units = [['EH/s',1e18],['PH/s',1e15],['TH/s',1e12],['GH/s',1e9],['MH/s',1e6],['KH/s',1e3]];
    const [unit, factor] = units.find(([, factor]) => Math.abs(hps) >= factor) || ['H/s',1];
    return `${number(hps / factor, 2)} <small>${unit}</small>`;
  }
  const coinLabel = name => ({bitcoin:'Bitcoin',monero:'Monero',pearl:'Pearl',decred:'Decred',quantus:'Quantus',ravencoin:'Ravencoin',ethereumclassic:'Ethereum Classic'}[name] || name.replace(/\b\w/g,c=>c.toUpperCase()));
  const ticker = name => ({bitcoin:'BTC',monero:'XMR',pearl:'PRL',decred:'DCR',quantus:'QTC',ravencoin:'RVN',ethereumclassic:'ETC'}[name] || name.slice(0,3).toUpperCase());
  function renderNav() {
    $('coin-nav').innerHTML = state.coins.map(coin => `<button type="button" data-coin="${esc(coin.coin)}" class="${coin.coin === state.selected ? 'active' : ''}"><span class="coin-symbol">${esc(ticker(coin.coin))}</span>${esc(coinLabel(coin.coin))}<span class="nav-right">:${esc(coin.port)}</span></button>`).join('');
    $('coin-nav').querySelectorAll('button').forEach(button => button.addEventListener('click', () => { state.selected = button.dataset.coin; render(); fetchConsole(); }));
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
  function render() {
    renderNav();
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
    $('updated').textContent = `Updated ${new Date(coin.sampledAt).toLocaleTimeString('en-US',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}`;
    $('runtime-state').textContent = state.coins.some(item=>item.listenerStatus==='online') ? 'OPERATIONAL' : 'CHECK LISTENERS';
    $('runtime-state').style.color = state.coins.some(item=>item.listenerStatus==='online') ? 'var(--accent)' : 'var(--amber)';
    renderRoutes(coin);
    const history = state.rateHistory.get(coin.coin) || [];
    if (history.length > 1) {
      const min = Math.min(...history), range = Math.max(...history) - min || 1;
      const path = history.map((sample, index) => `${index ? 'L' : 'M'}${(index / (history.length - 1) * 320).toFixed(1)},${(42 - (sample - min) / range * 34).toFixed(1)}`).join(' ');
      $('spark-line').setAttribute('d', path); $('spark-area').setAttribute('d', `${path} L320,48 L0,48 Z`);
    } else { $('spark-line').setAttribute('d',''); $('spark-area').setAttribute('d',''); }
  }
  async function fetchDashboard() {
    try {
      const response = await fetch('api/dashboard', {cache:'no-store'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json(); state.coins = payload.coins || [];
      if (!state.selected || !state.coins.some(coin=>coin.coin===state.selected)) state.selected = state.coins[0]?.coin || null;
      render();
    } catch (error) { $('runtime-state').textContent = 'API UNAVAILABLE'; $('updated').textContent = `Connection error · ${error.message}`; }
  }
  async function fetchConsole() {
    if (state.paused) return;
    const level = $('level-filter').value;
    try {
      const url = `api/dashboard/console?coin=${encodeURIComponent(state.selected || 'all')}&level=${encodeURIComponent(level)}&limit=100`;
      const response = await fetch(url, {cache:'no-store'}); if (!response.ok) return;
      const events = await response.json(); state.events = events;
      const container = $('console');
      if (!events.length) container.innerHTML = '<div class="empty">No events for this filter.</div>';
      else container.innerHTML = events.map(event => `<div class="event-row"><span class="event-time">${esc(new Date(event.at).toLocaleTimeString('en-US',{hour12:false}))}</span><span class="event-level ${esc(event.level)}">${esc(event.level)}</span><span class="event-coin">${esc(ticker(event.coin))}</span><span class="event-message">${esc(event.message)}</span></div>`).join('');
      $('event-count').textContent = `${events.length} EVENTS`;
    } catch (_) { /* A transient console poll does not interrupt the dashboard. */ }
  }
  $('level-filter').addEventListener('change', fetchConsole);
  $('pause-console').addEventListener('click', event => { state.paused = !state.paused; event.currentTarget.textContent = state.paused ? 'Resume stream' : 'Pause stream'; event.currentTarget.setAttribute('aria-pressed',String(state.paused)); if (!state.paused) fetchConsole(); });
  fetchDashboard(); fetchConsole(); setInterval(fetchDashboard,3000); setInterval(fetchConsole,3000);
})();
