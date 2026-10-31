// Scraper results dashboard (teacher/admin, read-only): lists every student's saved scrape
// and opens one to show its exact request/response JSON. Data comes from
// /api/scraper/results/all and /api/scraper/results/{id}.
(function () {
  const $ = id => document.getElementById(id);

  function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }

  async function api(url, options = {}) {
    const response = await fetch(url, { credentials: 'include', ...options });
    const text = await response.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch (e) { body = text; }
    if (!response.ok) {
      const message = body && (body.error || body.message) ? (body.error || body.message)
        : (typeof body === 'string' && body ? body : `Request failed (${response.status})`);
      throw new Error(message);
    }
    return body;
  }

  let results = [];
  let openResultId = null;

  function openModal(id) { $(id).classList.add('open'); }
  function closeModal(el) { el.classList.remove('open'); }

  function matchesSearch(result, query) {
    if (!query) return true;
    const haystack = `${result.personUid} ${result.label} ${result.sourceUrl}`.toLowerCase();
    return haystack.includes(query);
  }

  function renderRows() {
    const query = $('resultSearch').value.trim().toLowerCase();
    const visible = results.filter(result => matchesSearch(result, query));
    $('resultCount').textContent = `${visible.length} of ${results.length} saved scrapes`;

    if (!visible.length) {
      $('resultRows').innerHTML = '<tr><td colspan="5" class="g-empty">No saved scrapes match.</td></tr>';
      return;
    }

    $('resultRows').innerHTML = visible.map(result => `
      <tr class="g-click" data-id="${result.id}" tabindex="0">
        <td>${esc(result.personUid)}</td>
        <td>${esc(result.label)}</td>
        <td class="g-truncate">${esc(result.sourceUrl)}</td>
        <td>${esc(result.itemCount)}</td>
        <td>${esc(new Date(result.createdAt).toLocaleString())}</td>
      </tr>`).join('');
  }

  async function openResult(id) {
    const detail = await api(`/api/scraper/results/${id}`);
    openResultId = detail.id;
    $('rmTitle').textContent = detail.label || 'Saved scrape';
    $('rmSubtitle').textContent = `${detail.personUid} - ${detail.itemCount} item(s) - saved ${new Date(detail.createdAt).toLocaleString()}`;
    $('rmRequest').textContent = JSON.stringify(detail.request, null, 2);
    $('rmResult').textContent = JSON.stringify(detail.response, null, 2);
    openModal('resultModal');
  }

  async function deleteOpenResult() {
    if (openResultId == null) return;
    const result = results.find(r => String(r.id) === String(openResultId));
    const label = result ? `"${result.label}" (${result.personUid})` : 'this saved scrape';
    if (!window.confirm(`Delete ${label}? This cannot be undone.`)) return;

    await api(`/api/scraper/results/${openResultId}`, { method: 'DELETE' });
    results = results.filter(r => String(r.id) !== String(openResultId));
    openResultId = null;
    closeModal($('resultModal'));
    renderRows();
  }

  async function loadResults() {
    try {
      results = await api('/api/scraper/results/all');
      renderRows();
    } catch (error) {
      $('resultRows').innerHTML = `<tr><td colspan="5" class="g-empty">Could not load: ${esc(error.message)}</td></tr>`;
    }
  }

  function bindEvents() {
    $('resultSearch').addEventListener('input', renderRows);

    $('resultRows').addEventListener('click', event => {
      const row = event.target.closest('[data-id]');
      if (row) openResult(row.dataset.id);
    });
    $('resultRows').addEventListener('keydown', event => {
      if (event.key !== 'Enter') return;
      const row = event.target.closest('[data-id]');
      if (row) openResult(row.dataset.id);
    });

    $('rmDelete').addEventListener('click', () => {
      deleteOpenResult().catch(error => window.alert(`Could not delete: ${error.message}`));
    });

    document.querySelectorAll('.g-modal').forEach(modal => {
      modal.addEventListener('click', event => {
        if (event.target === modal || event.target.closest('[data-close]')) closeModal(modal);
      });
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape') document.querySelectorAll('.g-modal.open').forEach(closeModal);
    });
  }

  bindEvents();
  loadResults();
})();
