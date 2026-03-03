function parseJsonSafe(raw, fallback) {
  try {
    return JSON.parse(raw);
  } catch (_) {
    return fallback;
  }
}

function buildPoints(values) {
  if (!Array.isArray(values) || values.length === 0) return '';
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const span = max - min || 1;
  return values
    .map((value, index) => {
      const x = values.length === 1 ? 50 : (index / (values.length - 1)) * 100;
      const y = 36 - (((value - min) / span) * 32);
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');
}

function renderSparkline(svg, values, colorClass) {
  if (!svg) return;
  const points = buildPoints(values);
  if (!points) {
    svg.innerHTML = '';
    return;
  }

  svg.classList.add(colorClass);
  svg.innerHTML = `
    <polyline fill="none" stroke="currentColor" stroke-width="2" points="${points}" />
  `;
}

function initDetailSparklines() {
  document.querySelectorAll('[data-agent-metrics-history="true"]').forEach((root) => {
    const history = parseJsonSafe(root.dataset.historyPoints || '[]', []);
    if (!Array.isArray(history) || history.length === 0) return;

    const successSvg = root.querySelector('[data-sparkline="success-rate"]');
    const runSvg = root.querySelector('[data-sparkline="run-count"]');

    const successValues = history.map((point) => Number(point.successRate || 0) * 100);
    const runValues = history.map((point) => Number(point.totalRuns || 0));

    renderSparkline(successSvg, successValues, 'text-emerald-300');
    renderSparkline(runSvg, runValues, 'text-indigo-300');
  });
}

async function fetchJson(url) {
  const response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

async function renderDashboardWidget(container) {
  let metrics = [];
  try {
    metrics = await fetchJson('/api/agents/metrics');
  } catch (_) {
    container.textContent = 'Unable to load agent metrics.';
    return;
  }

  if (!Array.isArray(metrics) || metrics.length === 0) {
    container.textContent = 'No agents available.';
    return;
  }

  const rowsHtml = metrics
    .map((item) => {
      const m = item.metrics || {};
      const success = Math.round((Number(m.successRate || 0) || 0) * 1000) / 10;
      const avg = Number(m.averageDurationSeconds || 0);
      const avgLabel = avg < 60 ? `${avg}s` : `${Math.floor(avg / 60)}m ${avg % 60}s`;
      return `
        <tr class="border-b border-white/10 last:border-b-0">
          <td class="px-2 py-2 text-slate-200"><a href="/agents/registry/${item.agentId}" class="hover:text-indigo-300">${item.agentName}</a></td>
          <td class="px-2 py-2 text-slate-300">${m.totalRuns || 0}</td>
          <td class="px-2 py-2 text-slate-300">${success}%</td>
          <td class="px-2 py-2 text-slate-300">${avgLabel}</td>
          <td class="px-2 py-2 text-slate-300">${m.activeRuns || 0}</td>
          <td class="px-2 py-2"><svg class="h-8 w-24 text-indigo-300" viewBox="0 0 100 40" preserveAspectRatio="none" data-dashboard-sparkline="runs" data-agent-id="${item.agentId}"></svg></td>
          <td class="px-2 py-2"><svg class="h-8 w-24 text-emerald-300" viewBox="0 0 100 40" preserveAspectRatio="none" data-dashboard-sparkline="success" data-agent-id="${item.agentId}"></svg></td>
        </tr>
      `;
    })
    .join('');

  container.innerHTML = `
    <div class="overflow-x-auto">
      <table class="min-w-full text-xs">
        <thead>
          <tr class="border-b border-white/10 text-slate-400">
            <th class="px-2 py-2 text-left">Agent</th>
            <th class="px-2 py-2 text-left">Runs</th>
            <th class="px-2 py-2 text-left">Success</th>
            <th class="px-2 py-2 text-left">Avg Duration</th>
            <th class="px-2 py-2 text-left">Active</th>
            <th class="px-2 py-2 text-left">Run Trend</th>
            <th class="px-2 py-2 text-left">Success Trend</th>
          </tr>
        </thead>
        <tbody>${rowsHtml}</tbody>
      </table>
    </div>
  `;

  const sparklineNodes = Array.from(container.querySelectorAll('[data-dashboard-sparkline]'));
  await Promise.all(
    sparklineNodes.map(async (node) => {
      const agentId = node.dataset.agentId;
      if (!agentId) return;
      try {
        const history = await fetchJson(`/api/agents/${encodeURIComponent(agentId)}/metrics/history?period=7d`);
        const values = (Array.isArray(history) ? history : []).map((point) => {
          if (node.dataset.dashboardSparkline === 'success') {
            return Number(point.successRate || 0) * 100;
          }
          return Number(point.totalRuns || 0);
        });
        renderSparkline(node, values, node.dataset.dashboardSparkline === 'success' ? 'text-emerald-300' : 'text-indigo-300');
      } catch (_) {
        renderSparkline(node, [], '');
      }
    }),
  );
}

function initDashboardWidget() {
  const container = document.querySelector('[data-dashboard-agent-metrics="true"]');
  if (!container) return;

  let inFlight = false;
  const run = async () => {
    if (inFlight) return;
    inFlight = true;
    try {
      await renderDashboardWidget(container);
    } finally {
      inFlight = false;
    }
  };

  run();
  setInterval(run, 10000);
}

initDetailSparklines();
initDashboardWidget();
