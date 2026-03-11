class IssuesBoard {
  constructor(root) {
    this.root = root;
    this.fragmentUrl = root?.dataset?.fragmentUrl || '/board/fragment';
    this.wsTopic = root?.dataset?.wsTopic || 'activity:feed';
    this.dragIssueId = null;
    this.dragCard = null;
    this.ghost = null;          // semi-transparent placeholder in source column
    this.placeholder = null;    // dashed drop-target shown in hovered column
    this.sourceColumn = null;   // column where drag started
    this.ws = null;
    this._refreshInFlight = false;
    this._refreshPending = false;
    this._pointerDragging = false;

    this.bindDragDrop();
    this.bindPointerDrag();
    this.bindCollapse();
    this.bindQuickAdd();
    this.bindQuickDispatch();
    this.bindRefreshGuards();
    this.connectWs();
  }

  // ---------------------------------------------------------------------------
  // HTML5 drag & drop
  // ---------------------------------------------------------------------------

  bindDragDrop() {
    this.root.addEventListener('dragstart', (event) => {
      const card = event.target.closest('[data-issue-id]');
      if (!card) return;

      this.dragCard = card;
      this.dragIssueId = card.dataset.issueId || null;
      this.sourceColumn = card.closest('[data-drop-status]');

      event.dataTransfer?.setData('text/plain', this.dragIssueId || '');
      event.dataTransfer.effectAllowed = 'move';

      // Card lift effect applied after paint so browser captures the un-lifted image
      requestAnimationFrame(() => {
        card.classList.add('opacity-40', '-translate-y-0.5', 'shadow-xl');
      });

      this._insertSourceGhost(card);
    });

    this.root.addEventListener('dragend', (event) => {
      const card = event.target.closest('[data-issue-id]');
      card?.classList.remove('opacity-40', '-translate-y-0.5', 'shadow-xl');
      this._removePlaceholder();
      this._removeSourceGhost();
      this.dragCard = null;
      this.dragIssueId = null;
      this.sourceColumn = null;
      this.clearHighlights();
      this._flushPendingRefresh();
    });

    this._bindColumnListeners();
  }

  _bindColumnListeners() {
    this.root.querySelectorAll('[data-drop-status]').forEach((column) => {
      column.addEventListener('dragover', (event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        this._highlightColumn(column);
        this._movePlaceholderTo(column);
      });

      column.addEventListener('dragleave', (event) => {
        // Only clear if leaving the column entirely (not entering a child)
        if (!column.contains(event.relatedTarget)) {
          this._unhighlightColumn(column);
          if (this._placeholderColumn() === column) {
            this._removePlaceholder();
          }
        }
      });

      column.addEventListener('drop', async (event) => {
        event.preventDefault();
        this._unhighlightColumn(column);
        this._removePlaceholder();

        const status = column.dataset.dropStatus || '';
        const issueId = this.dragIssueId || event.dataTransfer?.getData('text/plain') || '';
        if (!status || !issueId) return;

        await this.patchIssueStatus(issueId, status);
        this.refreshBoard(issueId);
      });
    });
  }

  // Insert a ghost (dashed placeholder) where the card was in the source column
  _insertSourceGhost(card) {
    this.ghost = document.createElement('div');
    this.ghost.className = 'rounded-lg border border-dashed border-white/20 bg-white/5 h-[4.5rem] pointer-events-none';
    this.ghost.dataset.boardGhost = 'source';
    card.after(this.ghost);
  }

  _removeSourceGhost() {
    this.ghost?.remove();
    this.ghost = null;
  }

  // Insert/move a dashed placeholder into the hovered destination column
  _movePlaceholderTo(column) {
    if (this._placeholderColumn() === column) return;
    this._removePlaceholder();
    this.placeholder = document.createElement('div');
    this.placeholder.className = 'rounded-lg border-2 border-dashed border-indigo-400/50 bg-indigo-500/10 h-[4.5rem] pointer-events-none';
    this.placeholder.dataset.boardGhost = 'target';
    const cardsArea = column.querySelector('[data-role="column-cards"]');
    if (cardsArea) cardsArea.appendChild(this.placeholder);
    else column.appendChild(this.placeholder);
  }

  _removePlaceholder() {
    this.placeholder?.remove();
    this.placeholder = null;
  }

  _placeholderColumn() {
    return this.placeholder?.closest('[data-drop-status]') || null;
  }

  _highlightColumn(column) {
    this.clearHighlights();
    column.classList.add('ring-2', 'ring-indigo-400/60', 'bg-indigo-500/5');
  }

  _unhighlightColumn(column) {
    column.classList.remove('ring-2', 'ring-indigo-400/60', 'bg-indigo-500/5');
  }

  clearHighlights() {
    this.root.querySelectorAll('[data-drop-status]').forEach((column) => {
      column.classList.remove('ring-2', 'ring-indigo-400/60', 'bg-indigo-500/5');
    });
  }

  // ---------------------------------------------------------------------------
  // Quick-add inline form
  // ---------------------------------------------------------------------------

  bindQuickAdd() {
    // Use event delegation on this.root so it works after HTMX injects content.
    // Guard with a flag so we only register once (constructor + refreshBoard both call this).
    if (this._quickAddBound) return;
    this._quickAddBound = true;

    this.root.addEventListener('click', (event) => {
      // Toggle button
      const toggleBtn = event.target.closest('[data-quick-add-toggle]');
      if (toggleBtn) {
        event.stopPropagation();
        this._openQuickAdd(toggleBtn.dataset.quickAddToggle);
        return;
      }
      // Submit button
      const submitBtn = event.target.closest('[data-quick-add-submit]');
      if (submitBtn) {
        this._submitQuickAdd(submitBtn.dataset.quickAddSubmit);
        return;
      }
      // Cancel button
      const cancelBtn = event.target.closest('[data-quick-add-cancel]');
      if (cancelBtn) {
        this._closeQuickAdd(cancelBtn.dataset.quickAddCancel);
        return;
      }
    });

    this.root.addEventListener('keydown', (event) => {
      const input = event.target.closest('[data-quick-add-title]');
      if (!input) return;
      if (event.key === 'Enter') this._submitQuickAdd(input.dataset.quickAddTitle);
      if (event.key === 'Escape') this._closeQuickAdd(input.dataset.quickAddTitle);
    });

    this.root.addEventListener('focusout', (event) => {
      const input = event.target.closest('[data-quick-add-title]');
      if (!input) return;
      requestAnimationFrame(() => this._flushPendingRefresh());
    });

    // Outside-click dismissal on document
    this._quickAddOutsideHandler = (event) => {
      if (!event.target.closest('[data-quick-add-form]') && !event.target.closest('[data-quick-add-toggle]')) {
        this.root.querySelectorAll('[data-quick-add-form]:not(.hidden)').forEach((form) => {
          form.classList.add('hidden');
          const titleInput = form.querySelector('[data-quick-add-title]');
          if (titleInput) titleInput.value = '';
        });
        this._flushPendingRefresh();
      }
    };
    document.addEventListener('click', this._quickAddOutsideHandler);
  }

  bindQuickDispatch() {
    if (this._quickAssignBound) return;
    this._quickAssignBound = true;

    this.root.addEventListener('click', async (event) => {
      const btn = event.target.closest('[data-quick-assign-action]');
      if (!btn) return;

      const issueId = btn.dataset.quickAssignAction || '';
      if (!issueId) return;

      const select = this.root.querySelector(`[data-quick-assign-agent="${CSS.escape(issueId)}"]`);
      const agentName = select?.value?.trim() || '';
      if (!agentName) return;

      btn.disabled = true;
      const original = btn.textContent;
      btn.textContent = '...';
      try {
        await this.quickAssign(issueId, agentName);
        this.refreshBoard(issueId);
      } finally {
        btn.disabled = false;
        btn.textContent = original || 'Assign';
      }
    });
  }

  _openQuickAdd(statusToken) {
    // Close any other open forms first
    this.root.querySelectorAll('[data-quick-add-form]').forEach((form) => {
      form.classList.add('hidden');
    });
    const form = this.root.querySelector(`[data-quick-add-form="${CSS.escape(statusToken)}"]`);
    if (!form) return;
    form.classList.remove('hidden');
    const titleInput = form.querySelector('[data-quick-add-title]');
    titleInput?.focus();
  }

  _closeQuickAdd(statusToken) {
    const form = this.root.querySelector(`[data-quick-add-form="${CSS.escape(statusToken)}"]`);
    if (!form) return;
    form.classList.add('hidden');
    const titleInput = form.querySelector('[data-quick-add-title]');
    if (titleInput) titleInput.value = '';
    this._flushPendingRefresh();
  }

  async _submitQuickAdd(statusToken) {
    const form = this.root.querySelector(`[data-quick-add-form="${CSS.escape(statusToken)}"]`);
    if (!form) return;

    const titleInput     = form.querySelector('[data-quick-add-title]');
    const prioritySelect = form.querySelector('[data-quick-add-priority]');
    const title    = titleInput?.value?.trim() || '';
    const priority = prioritySelect?.value || 'Medium';

    if (!title) {
      titleInput?.focus();
      return;
    }

    this._closeQuickAdd(statusToken);

    try {
      await fetch('/api/issues', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          title,
          priority,
          status: this.toIssueStatus(statusToken),
          description: title,
          issueType: 'task',
        }),
      });
    } catch (_ignored) {
      // Best effort; board refresh will show current state
    }

    this.refreshBoard();
  }

  bindRefreshGuards() {
    if (this._refreshGuardsBound) return;
    this._refreshGuardsBound = true;

    this.root.addEventListener('htmx:beforeRequest', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      if (!this._shouldDeferRefresh()) return;
      event.preventDefault();
      this._refreshPending = true;
    });

    this.root.addEventListener('htmx:beforeSwap', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      if (!this._shouldDeferRefresh()) return;
      event.preventDefault();
      this._refreshPending = true;
    });
  }

  // ---------------------------------------------------------------------------
  // Column collapse / expand (persisted in localStorage)
  // ---------------------------------------------------------------------------

  bindCollapse() {
    this.root.querySelectorAll('[data-collapse-toggle]').forEach((btn) => {
      const statusToken = btn.dataset.collapseToggle;
      const cardsArea = this.root.querySelector(`[data-column-cards="${statusToken}"]`);
      if (!cardsArea) return;

      // Restore persisted state
      if (this._isCollapsed(statusToken)) {
        cardsArea.classList.add('hidden');
      }

      btn.addEventListener('click', () => {
        const collapsed = cardsArea.classList.toggle('hidden');
        this._setCollapsed(statusToken, collapsed);
        this._renderHiddenPanel();
      });
    });

    this._renderHiddenPanel();
  }

  _collapseKey(statusToken) {
    return `board-col-collapsed:${statusToken}`;
  }

  _isCollapsed(statusToken) {
    return localStorage.getItem(this._collapseKey(statusToken)) === 'true';
  }

  _setCollapsed(statusToken, collapsed) {
    if (collapsed) localStorage.setItem(this._collapseKey(statusToken), 'true');
    else localStorage.removeItem(this._collapseKey(statusToken));
  }

  _renderHiddenPanel() {
    let panel = this.root.parentElement?.querySelector('[data-hidden-columns-panel]');

    const collapsedColumns = [];
    this.root.querySelectorAll('[data-column-status]').forEach((col) => {
      const statusToken = col.dataset.columnStatus;
      const cardsArea = col.querySelector(`[data-column-cards="${statusToken}"]`);
      if (cardsArea?.classList.contains('hidden')) {
        const label = col.dataset.columnLabel || statusToken;
        const count = col.querySelector(`[data-column-count="${statusToken}"]`)?.textContent || '0';
        collapsedColumns.push({ statusToken, label, count });
      }
    });

    if (collapsedColumns.length === 0) {
      panel?.remove();
      return;
    }

    if (!panel) {
      panel = document.createElement('div');
      panel.dataset.hiddenColumnsPanel = 'true';
      panel.className = 'mt-3 flex flex-wrap gap-2 px-1';
      this.root.after(panel);
    }

    // Build chips for each hidden column
    panel.textContent = ''; // clear safely
    const label = document.createElement('span');
    label.className = 'text-xs text-slate-400 self-center';
    label.textContent = 'Hidden:';
    panel.appendChild(label);

    collapsedColumns.forEach(({ statusToken, label: colLabel, count }) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'flex items-center gap-1 rounded-full border border-white/15 bg-slate-800/70 px-3 py-1 text-xs text-slate-300 hover:bg-slate-700';
      chip.dataset.uncollapse = statusToken;
      chip.textContent = `${colLabel} (${count})`;
      chip.addEventListener('click', () => {
        const cardsArea = this.root.querySelector(`[data-column-cards="${statusToken}"]`);
        if (cardsArea) {
          cardsArea.classList.remove('hidden');
          this._setCollapsed(statusToken, false);
          this._renderHiddenPanel();
        }
      });
      panel.appendChild(chip);
    });
  }

  // ---------------------------------------------------------------------------
  // Touch / pointer drag (basic support for mobile/tablet)
  // ---------------------------------------------------------------------------

  bindPointerDrag() {
    let dragging = false;
    let pointerCard = null;
    let clone = null;
    let startX = 0, startY = 0;
    let offsetX = 0, offsetY = 0;

    const onPointerDown = (event) => {
      if (event.pointerType === 'mouse') return; // handled by native DnD
      const card = event.target.closest('[data-issue-id]');
      if (!card) return;

      dragging = false;
      pointerCard = card;
      startX = event.clientX;
      startY = event.clientY;

      const rect = card.getBoundingClientRect();
      offsetX = event.clientX - rect.left;
      offsetY = event.clientY - rect.top;
    };

    const onPointerMove = (event) => {
      if (!pointerCard) return;
      const dx = Math.abs(event.clientX - startX);
      const dy = Math.abs(event.clientY - startY);
      if (!dragging && dx < 8 && dy < 8) return;

      if (!dragging) {
        dragging = true;
        this._pointerDragging = true;
        this.dragIssueId = pointerCard.dataset.issueId || null;
        this.dragCard = pointerCard;
        this.sourceColumn = pointerCard.closest('[data-drop-status]');

        pointerCard.classList.add('opacity-40');
        this._insertSourceGhost(pointerCard);

        // Create floating visual clone
        clone = pointerCard.cloneNode(true);
        clone.style.cssText = `position:fixed;pointer-events:none;z-index:9999;width:${pointerCard.offsetWidth}px;opacity:0.9;box-shadow:0 8px 32px rgba(0,0,0,0.5);`;
        document.body.appendChild(clone);
      }

      if (clone) {
        clone.style.left = `${event.clientX - offsetX}px`;
        clone.style.top = `${event.clientY - offsetY}px`;
      }

      // Highlight column under pointer
      const el = document.elementFromPoint(event.clientX, event.clientY);
      const col = el?.closest('[data-drop-status]');
      if (col) {
        this._highlightColumn(col);
        this._movePlaceholderTo(col);
      }
    };

    const onPointerUp = async (event) => {
      if (!dragging || !pointerCard) {
        pointerCard = null;
        return;
      }

      clone?.remove();
      clone = null;
      pointerCard.classList.remove('opacity-40');
      this._removePlaceholder();
      this._removeSourceGhost();
      this.clearHighlights();

      const el = document.elementFromPoint(event.clientX, event.clientY);
      const col = el?.closest('[data-drop-status]');
      const status = col?.dataset?.dropStatus || '';
      const issueId = this.dragIssueId || '';

      dragging = false;
      this._pointerDragging = false;
      pointerCard = null;
      this.dragIssueId = null;
      this.dragCard = null;
      this.sourceColumn = null;

      if (status && issueId) {
        await this.patchIssueStatus(issueId, status);
        this.refreshBoard(issueId);
      } else {
        this._flushPendingRefresh();
      }
    };

    this.root.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
  }

  // ---------------------------------------------------------------------------
  // API & board refresh
  // ---------------------------------------------------------------------------

  async patchIssueStatus(issueId, status) {
    const payload = { status: this.toIssueStatus(status) };

    const card = this.root.querySelector(`[data-issue-id="${CSS.escape(issueId)}"]`);
    const currentAgent = card?.dataset?.assignedAgent || '';
    if (currentAgent.trim()) payload.agentName = currentAgent.trim();

    if (status === 'done' || status === 'completed') payload.resultData = 'Status updated from board';
    if (status === 'rework' || status === 'failed') payload.reason = 'Marked rework from board';
    if (status === 'canceled') payload.reason = 'Canceled from board';
    if (status === 'duplicated') payload.reason = 'Marked duplicated from board';

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

  async quickAssign(issueId, agentName) {
    try {
      await fetch(`/api/issues/${encodeURIComponent(issueId)}/assign`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ agentName }),
      });
    } catch (_ignored) {
      // Best effort; next board refresh remains source of truth
    }
  }

  toIssueStatus(statusToken) {
    switch (String(statusToken || '').toLowerCase()) {
      case 'backlog': return 'Backlog';
      case 'todo': return 'Todo';
      case 'open': return 'Open';
      case 'assigned': return 'Assigned';
      case 'in_progress': return 'InProgress';
      case 'human_review': return 'HumanReview';
      case 'rework': return 'Rework';
      case 'merging': return 'Merging';
      case 'done': return 'Done';
      case 'canceled': return 'Canceled';
      case 'duplicated': return 'Duplicated';
      case 'completed': return 'Completed';
      case 'failed': return 'Failed';
      default: return 'Backlog';
    }
  }

  _flashLandedCard(issueId) {
    if (!issueId) return;
    const landed = this.root.querySelector(`[data-issue-id="${CSS.escape(issueId)}"]`);
    if (landed) {
      landed.classList.add('bg-white/10');
      setTimeout(() => landed.classList.remove('bg-white/10'), 300);
    }
  }

  _shouldDeferRefresh() {
    if (this.dragIssueId || this.dragCard || this.sourceColumn || this.placeholder || this._pointerDragging) {
      return true;
    }

    const activeElement = document.activeElement;
    if (activeElement && activeElement.closest && activeElement.closest('[data-quick-add-form]')) {
      return true;
    }

    const openForms = this.root.querySelectorAll('[data-quick-add-form]:not(.hidden)');
    for (const form of openForms) {
      const titleInput = form.querySelector('[data-quick-add-title]');
      if (titleInput?.value?.trim()) return true;
    }

    return false;
  }

  _flushPendingRefresh() {
    if (!this._refreshPending) return;
    if (this._refreshInFlight) return;
    if (this._shouldDeferRefresh()) return;
    this._refreshPending = false;
    this.refreshBoard();
  }

  refreshBoard(landedIssueId = null) {
    if (this._refreshInFlight) {
      this._refreshPending = true;
      return;
    }

    if (this._shouldDeferRefresh()) {
      this._refreshPending = true;
      return;
    }

    this._refreshInFlight = true;

    const onSettled = () => {
      this._refreshInFlight = false;
      this._flushPendingRefresh();
    };

    if (window.htmx?.ajax) {
      window.htmx.ajax('GET', this.fragmentUrl, {
        target: this.root,
        swap: 'innerHTML',
      }).then(() => {
        this.bindDragDrop();
        this.bindCollapse();
        this.bindQuickAdd();
        this.bindQuickDispatch();
        this._flashLandedCard(landedIssueId);
      }).catch(() => {}).finally(onSettled);
      return;
    }

    fetch(this.fragmentUrl)
      .then((response) => response.ok ? response.text() : Promise.reject(new Error('refresh failed')))
      .then((html) => {
        // html is server-rendered markup from our own trusted endpoint
        this.root.innerHTML = html; // nosec: trusted server HTML, same origin
        this.bindDragDrop();
        this.bindCollapse();
        this.bindQuickAdd();
        this.bindQuickDispatch();
        this._flashLandedCard(landedIssueId);
      })
      .catch(() => {})
      .finally(onSettled);
  }

  // ---------------------------------------------------------------------------
  // WebSocket
  // ---------------------------------------------------------------------------

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
