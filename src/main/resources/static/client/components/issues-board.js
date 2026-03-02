class IssuesBoard {
  constructor(root) {
    this.root = root;
    this.fragmentUrl = root?.dataset?.fragmentUrl || '/issues/board/fragment';
    this.wsTopic = root?.dataset?.wsTopic || 'activity:feed';
    this.dragIssueId = null;
    this.ws = null;

    this.bindDragDrop();
    this.connectWs();
  }

  bindDragDrop() {
    this.root.addEventListener('dragstart', (event) => {
      const card = event.target.closest('[data-issue-id]');
      if (!card) return;
      this.dragIssueId = card.dataset.issueId || null;
      event.dataTransfer?.setData('text/plain', this.dragIssueId || '');
      event.dataTransfer.effectAllowed = 'move';
      card.classList.add('opacity-70');
    });

    this.root.addEventListener('dragend', (event) => {
      const card = event.target.closest('[data-issue-id]');
      card?.classList.remove('opacity-70');
      this.dragIssueId = null;
      this.clearHighlights();
    });

    this.root.querySelectorAll('[data-drop-status]').forEach((column) => {
      column.addEventListener('dragover', (event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        column.classList.add('ring-2', 'ring-indigo-400/60');
      });

      column.addEventListener('dragleave', () => {
        column.classList.remove('ring-2', 'ring-indigo-400/60');
      });

      column.addEventListener('drop', async (event) => {
        event.preventDefault();
        column.classList.remove('ring-2', 'ring-indigo-400/60');

        const status = column.dataset.dropStatus || '';
        const issueId = this.dragIssueId || event.dataTransfer?.getData('text/plain') || '';
        if (!status || !issueId) return;

        await this.patchIssueStatus(issueId, status);
        this.refreshBoard();
      });
    });
  }

  clearHighlights() {
    this.root.querySelectorAll('[data-drop-status]').forEach((column) => {
      column.classList.remove('ring-2', 'ring-indigo-400/60');
    });
  }

  async patchIssueStatus(issueId, status) {
    const payload = { status: this.toIssueStatus(status) };

    const card = this.root.querySelector(`[data-issue-id="${CSS.escape(issueId)}"]`);
    const currentAgent = card?.dataset?.assignedAgent || '';
    if (currentAgent.trim()) payload.agentName = currentAgent.trim();

    if (status === 'completed') payload.resultData = 'Status updated from board';
    if (status === 'failed') payload.reason = 'Marked failed from board';

    try {
      await fetch(`/api/issues/${encodeURIComponent(issueId)}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(payload),
      });
    } catch (_ignored) {
      // Best effort; board refresh will keep server as source of truth
    }
  }

  toIssueStatus(statusToken) {
    switch (String(statusToken || '').toLowerCase()) {
      case 'open': return 'Open';
      case 'assigned': return 'Assigned';
      case 'in_progress': return 'InProgress';
      case 'completed': return 'Completed';
      case 'failed': return 'Failed';
      default: return 'Open';
    }
  }

  refreshBoard() {
    if (window.htmx?.ajax) {
      window.htmx.ajax('GET', this.fragmentUrl, {
        target: this.root,
        swap: 'innerHTML',
      }).then(() => {
        this.bindDragDrop();
      });
      return;
    }

    fetch(this.fragmentUrl)
      .then((response) => response.ok ? response.text() : Promise.reject(new Error('refresh failed')))
      .then((html) => {
        this.root.innerHTML = html;
        this.bindDragDrop();
      })
      .catch(() => {});
  }

  connectWs() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);
    this.ws.onopen = () => {
      this.ws?.send(JSON.stringify({ Subscribe: { topic: this.wsTopic, params: {} } }));
    };
    this.ws.onmessage = (event) => this.onWsMessage(event.data);
  }

  onWsMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    const evt = parsed?.Event;
    if (!evt || evt.topic !== this.wsTopic) return;
    if (evt.eventType === 'activity-feed') {
      this.refreshBoard();
    }
  }
}

document.querySelectorAll('#issues-board-root').forEach((root) => {
  if (!root.__issuesBoard) {
    root.__issuesBoard = new IssuesBoard(root);
  }
});
