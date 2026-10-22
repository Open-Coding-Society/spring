// Shared helpers for the gradebook, AI queue, grade request and assignment tracker pages.
window.Grading = (function () {
  function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }

  /** Fetches JSON; throws an Error carrying the server's message on a non-2xx response. */
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

  function postJson(url, data) {
    return api(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  }

  function formatEta(seconds) {
    if (seconds == null) return '';
    if (seconds < 60) return '< 1 min';
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `~${minutes} min`;
    const hours = Math.floor(minutes / 60);
    return `~${hours} h ${minutes % 60} min`;
  }

  function formatPct(pct) {
    return pct == null ? '—' : `${pct}%`;
  }

  function formatGrade(grade, points) {
    if (grade == null) return '—';
    const g = Math.round(grade * 100) / 100;
    return points ? `${g} / ${points}` : `${g}`;
  }

  /** Badge for a gradebook submission status (graded / pending_ai / ungraded). */
  function statusBadge(status, etaSeconds) {
    if (status === 'graded') return '<span class="g-badge ok">Graded</span>';
    if (status === 'pending_ai') {
      const eta = etaSeconds != null ? ` · ${formatEta(etaSeconds)}` : '';
      return `<span class="g-badge warn">AI queue${esc(eta)}</span>`;
    }
    return '<span class="g-badge muted">Ungraded</span>';
  }

  function pctClass(pct) {
    if (pct == null) return '';
    return pct >= 80 ? 'text-success' : pct >= 60 ? 'text-warning' : 'text-danger';
  }

  function debounce(fn, ms = 200) {
    let timer;
    return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
  }

  /** Best readable form of a submission's content map (link, file name, text, …). */
  function contentText(content) {
    if (!content) return 'No content';
    if (typeof content === 'string') return content;
    return content.url || content.filename || content.fileName || content.path
      || content.text || content.answer || content.response || content.value || JSON.stringify(content);
  }

  /**
   * Submission details (submitter, grade, AI quality, late status, work, comment, feedback, AI summary),
   * shared by the assignment tracker and the student grade card. {@code s} needs grade, qualityScore,
   * isLate, content, comment, feedback and aiSummary.
   */
  function submissionDetailHtml(s, submitter, points) {
    const field = (label, value) => `<div class="g-panel"><div class="g-sub mb-1">${label}</div>
      <div style="white-space:pre-wrap;word-break:break-word">${esc(value == null || value === '' ? 'Not provided' : value)}</div></div>`;
    return `
      <div class="g-stats">
        <div class="g-stat"><b>${esc(submitter)}</b><span>submitter</span></div>
        <div class="g-stat"><b>${esc(formatGrade(s.grade, points))}</b><span>grade</span></div>
        <div class="g-stat"><b>${s.qualityScore == null ? '—' : s.qualityScore + '/5'}</b><span>AI quality</span></div>
        <div class="g-stat"><b>${s.isLate ? 'Late' : 'On time'}</b><span>status</span></div>
      </div>
      ${field('Submitted work', contentText(s.content))}
      ${field('Student comment', s.comment)}
      ${field('Feedback', s.feedback)}
      ${field('AI summary', s.aiSummary)}`;
  }

  let toastTimer;
  function toast(message, ok = true) {
    let el = document.getElementById('g-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'g-toast';
      el.setAttribute('role', 'status');
      document.body.appendChild(el);
    }
    el.className = `g-toast ${ok ? 'ok' : 'bad'}`;
    el.textContent = message;
    el.style.display = 'block';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.style.display = 'none'; }, 4000);
  }

  return { esc, api, postJson, formatEta, formatPct, formatGrade, statusBadge, pctClass, debounce, toast,
    contentText, submissionDetailHtml };
})();
