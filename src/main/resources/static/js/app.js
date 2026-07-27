document.addEventListener('DOMContentLoaded', () => {
    /* ── Wheel-delta normaliser ─────────────────────────────────────────
       Some mice (hi-res scroll, gaming mice, certain Logitech models)
       send huge deltaY values per single notch. We cap each wheel event
       so the page never jumps more than MAX_PX pixels at a time.        */
    const MAX_PX = 150;
    window.addEventListener('wheel', e => {
        const absY = Math.abs(e.deltaY);
        const absX = Math.abs(e.deltaX);
        if (absY <= MAX_PX && absX <= MAX_PX) return;          // normal delta, let it through

        e.preventDefault();

        const target = e.target.closest('.chat-messages, .terminal-body') || document.scrollingElement;
        const clampedY = Math.sign(e.deltaY) * Math.min(absY, MAX_PX);
        const clampedX = Math.sign(e.deltaX) * Math.min(absX, MAX_PX);
        target.scrollBy({left: clampedX, top: clampedY});
    }, {passive: false});

    initUIElements();
    organizeModelLists();
    window.addEventListener("error", event => {
        reportClientError(event.error || event.message, "window error");
    });
    window.addEventListener("unhandledrejection", event => {
        reportClientError(event.reason, "unhandled promise rejection");
    });
    document.querySelectorAll(".ollama-endpoint").forEach(element => { element.textContent = window.location.origin; });
    initChat();
    const requestedTab = window.location.hash.slice(1);
    if (['dashboard', 'models', 'chat', 'settings'].includes(requestedTab)) {
        switchTab(requestedTab, false);
    }
    fetchHardwareAndStatus();
    fetchTelemetry();
    fetchModels();
    refreshInferenceApiState();
    loadSettings();
    refreshRockchipQuantizationCards();
    setInterval(() => {
        if (document.getElementById('view-dashboard')?.classList.contains('active')) {
            fetchHardwareAndStatus();
        }
    }, 5000);
    setInterval(() => {
        if (document.getElementById('view-models')?.classList.contains('active')) {
            fetchModels();
        }
    }, 3000);
    setInterval(() => {
        const dashboardActive = document.getElementById('view-dashboard')?.classList.contains('active');
        const chatActive = document.getElementById('view-chat')?.classList.contains('active');
        if (dashboardActive || chatActive) {
            fetchTelemetry();
        }
    }, 1000);
    setInterval(refreshInferenceApiState, 3000);
    setInterval(() => {
        if (document.getElementById('terminal-drawer')?.classList.contains('active')) {
            pollTerminalLogs();
        }
    }, 1000);

    // Handle model actions from Thymeleaf rendered buttons
    document.addEventListener('click', e => {
        const btn = e.target.closest('button[data-action]');
        if (!btn) return;

        const action = btn.dataset.action;
        const modelId = btn.dataset.modelId;
        const backend = btn.dataset.backend;
        const card = btn.closest('.model-card');
        const quantization = card?.querySelector('.model-quantization-select')?.value || null;

        if (action === 'load') {
            loadModel(modelId, backend, quantization, card);
        } else if (action === 'unload') {
            unloadModel(card);
        } else if (action === 'delete') {
            deleteModel(modelId, quantization);
        } else if (action === 'download') {
            downloadFromSource(modelId, quantization);
        }
    });

    document.addEventListener('change', e => {
        if (!e.target.matches('.model-quantization-select')) return;
        const card = e.target.closest('.model-card');
        if (card) refreshRockchipQuantizationCard(card);
    });
});

let toastContainer;
let currentActiveNpu = 'ROCKCHIP';
let lastLogId = 0;
let backendSelectionMode = 'AUTO';
let backendSelectionState = 'AUTO';
let chatHistory = [];
let chatAbortController = null;
let chatLoadedModelName = '';
let chatModelLimitsName = '';
let inferenceApiEnabled = false;
let telemetryRequestInFlight = false;

const BACKEND_PRIORITY = ['OPENVINO', 'RYZENAI', 'ROCKCHIP', 'QUALCOMM'];

function isKnownBackend(backend) {
    return BACKEND_PRIORITY.includes(String(backend || '').toUpperCase());
}

function setUnsupportedBackendState(select, unsupported) {
    if (!select) return;

    Array.from(select.options).forEach(option => {
        if (option.value === 'UNSUPPORTED') {
            option.remove();
        }
        option.disabled = unsupported;
    });

    if (unsupported) {
        const ghostOption = document.createElement('option');
        ghostOption.value = 'UNSUPPORTED';
        ghostOption.textContent = 'Unsupported';
        ghostOption.disabled = true;
        select.prepend(ghostOption);
        select.value = 'UNSUPPORTED';
        select.disabled = true;
        select.classList.add('is-unsupported');
    } else {
        select.disabled = false;
        select.classList.remove('is-unsupported');
    }
}

function applyTheme(backend) {
    const target = (backend || currentActiveNpu || 'ROCKCHIP').toLowerCase();
    document.body.classList.remove('theme-rockchip', 'theme-openvino', 'theme-qualcomm', 'theme-ryzenai');
    document.body.classList.add(`theme-${target}`);
}

function toggleTerminalDrawer(forceOpen) {
    const drawer = document.getElementById('terminal-drawer');
    if (!drawer) return;
    if (forceOpen === true) {
        drawer.classList.add('active');
    } else if (forceOpen === false) {
        drawer.classList.remove('active');
    } else {
        drawer.classList.toggle('active');
    }
}

function initUIElements() {
    toastContainer = document.createElement('div');
    toastContainer.className = 'toast-container';
    document.body.appendChild(toastContainer);

    const modalHtml = `
        <div id="custom-confirm-modal" class="modal-overlay">
            <div class="modal-dialog">
                <div class="modal-title" id="confirm-title">Confirm Action</div>
                <div class="modal-body" id="confirm-message">Are you sure?</div>
                <div class="modal-actions">
                    <button class="btn btn-secondary" id="confirm-cancel">Cancel</button>
                    <button class="btn btn-danger" id="confirm-ok">Proceed</button>
                </div>
            </div>
        </div>
    `;
    document.body.insertAdjacentHTML('beforeend', modalHtml);

    const backendSelect = document.getElementById('setting-backend');
    if (backendSelect) {
        backendSelect.addEventListener('change', (e) => {
            backendSelectionMode = 'MANUAL';
            applyTheme(e.target.value);
        });
    }
}

function reportClientError(error, context = "client error", notify = true) {
    const message = error instanceof Error ? error.message : String(error || "Unknown error");
    console.error(`[${context}]`, error);
    if (notify && message) showToast(message, "error");
}

function showToast(message, type = 'success') {
    message = String(message || "Unknown error");
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;

    let icon = type === 'success' ?
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--success)" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>' :
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--danger)" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>';

    toast.innerHTML = `${icon} <span class="toast-message">${message}</span>`;
    toastContainer.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

function showConfirm(title, message) {
    return new Promise((resolve) => {
        const modal = document.getElementById('custom-confirm-modal');
        document.getElementById('confirm-title').innerText = title;
        document.getElementById('confirm-message').innerText = message;

        const btnOk = document.getElementById('confirm-ok');
        const btnCancel = document.getElementById('confirm-cancel');

        const cleanup = () => {
            modal.classList.remove('active');
            btnOk.removeEventListener('click', onOk);
            btnCancel.removeEventListener('click', onCancel);
        };

        const onOk = () => {
            cleanup();
            resolve(true);
        };
        const onCancel = () => {
            cleanup();
            resolve(false);
        };

        btnOk.addEventListener('click', onOk);
        btnCancel.addEventListener('click', onCancel);

        modal.classList.add('active');
    });
}

function toggleSettingsDrawer(forceOpen) {
    const drawer = document.getElementById('settings-drawer');
    const backdrop = document.getElementById('settings-backdrop');
    if (!drawer) return;
    const shouldOpen = (forceOpen !== undefined) ? forceOpen : !drawer.classList.contains('active');
    drawer.classList.toggle('active', shouldOpen);
    if (backdrop) backdrop.classList.toggle('active', shouldOpen);
}

function switchTab(tabId, updateUrl = true) {
    if (tabId === 'settings') {
        toggleSettingsDrawer(true);
        return;
    }
    if (!['dashboard', 'models', 'chat'].includes(tabId)) return;

    document.querySelectorAll('.tab-view').forEach(view => view.classList.remove('active'));
    document.querySelectorAll('.nav-btn').forEach(btn => btn.classList.remove('active'));

    document.getElementById(`view-${tabId}`)?.classList.add('active');
    document.getElementById(`nav-${tabId}`)?.classList.add('active');
    document.body.classList.toggle('view-chat-active', tabId === 'chat');
    document.querySelectorAll('.nav-btn').forEach(btn => btn.removeAttribute('aria-current'));
    document.getElementById(`nav-${tabId}`)?.setAttribute('aria-current', 'page');

    const titles = {
        'dashboard': 'NPU control panel',
        'models': 'Model registry',
        'chat': 'Lightweight chat'
    };
    const subtitles = {
        'dashboard': 'Live hardware telemetry and strict execution status.',
        'models': 'Manage deployable models across every supported NPU runtime.',
        'chat': 'Stream responses from the local model without running Open WebUI.'
    };
    const sections = {
        'dashboard': 'SYSTEM / OVERVIEW',
        'models': 'LIBRARY / MODELS',
        'chat': 'INFERENCE / CHAT',
        'settings': 'SYSTEM / SETTINGS'
    };
    document.getElementById('page-title').innerText = titles[tabId] || 'NPU Control Panel';
    document.getElementById('page-subtitle').innerText = subtitles[tabId] || subtitles.dashboard;
    document.getElementById('page-section').innerText = sections[tabId] || sections.dashboard;
    if (updateUrl) {
        window.history.replaceState(null, '', `#${tabId}`);
    }
    if (tabId === 'chat') {
        refreshChatModels();
        window.setTimeout(() => document.getElementById('chat-input')?.focus(), 0);
    }
}

function initChat() {
    const form = document.getElementById('chat-form');
    const input = document.getElementById('chat-input');
    const messages = document.getElementById('chat-messages');
    const clearButton = document.getElementById('chat-clear');
    const modelSelect = document.getElementById('chat-model');

    if (!form || !input || !messages) return;

    form.addEventListener('submit', sendChatMessage);
    document.getElementById("chat-send")?.addEventListener("click", event => {
        if (chatAbortController) {
            event.preventDefault();
            chatAbortController.abort();
        }
    });
    input.addEventListener('input', () => {
        const count = document.getElementById('chat-character-count');
        if (count) count.textContent = `${input.value.length} chars`;
        input.style.height = 'auto';
        input.style.height = `${Math.min(input.scrollHeight, 230)}px`;
    });
    input.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            if (!chatAbortController) form.requestSubmit();
        }
    });
    messages.addEventListener('click', event => {
        const suggestion = event.target.closest('[data-chat-prompt]');
        if (!suggestion) return;
        input.value = suggestion.dataset.chatPrompt || '';
        input.dispatchEvent(new Event('input'));
        input.focus();
    });
    clearButton?.addEventListener('click', clearChatConversation);
    modelSelect?.addEventListener('change', () => {
        applyChatModelLimits();
        updateChatModelState();
    });
    document.getElementById('chat-context')?.addEventListener('input', () => {
        updateChatOutputLimit(false);
    });

    refreshChatModels();
}

async function fetchJsonWithTimeout(url, timeoutMs = 7000) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    try {
        const response = await fetch(url, {signal: controller.signal});
        if (!response.ok) {
            throw new Error(`${response.status} ${response.statusText}`);
        }
        return await response.json();
    } finally {
        window.clearTimeout(timeout);
    }
}

async function refreshChatModels() {
    const select = document.getElementById('chat-model');
    if (!select || chatAbortController) return;

    const previousSelection = select.value;
    const [runningResult, tagsResult] = await Promise.allSettled([
        fetchJsonWithTimeout('/api/ps', 4000),
        fetchJsonWithTimeout('/api/tags', 7000)
    ]);
    const runningModels = runningResult.status === 'fulfilled'
        ? runningResult.value.models || []
        : [];
    const localModels = tagsResult.status === 'fulfilled'
        ? tagsResult.value.models || []
        : [];
    const runningName = runningModels[0]?.name || runningModels[0]?.model || '';
    const matchingLocalModel = localModels.find(model => {
        const localName = model.name || model.model || '';
        return localName && (runningName === localName || runningName.startsWith(`${localName}:`));
    });
    chatLoadedModelName = matchingLocalModel?.name || matchingLocalModel?.model || runningName;

    const names = new Map();
    localModels.forEach(model => {
        const name = model.name || model.model;
        if (name) names.set(name, model);
    });
    runningModels.forEach(model => {
        const name = model.name || model.model;
        const representedByLocalModel = Array.from(names.keys()).some(localName =>
            name === localName || name?.startsWith(`${localName}:`)
        );
        if (name && !representedByLocalModel) names.set(name, model);
    });

    select.replaceChildren();
    if (names.size === 0) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No local models available';
        select.appendChild(option);
        select.disabled = true;
        chatModelLimitsName = '';
        applyChatModelLimits();
        updateChatModelState();
        return;
    }

    names.forEach((model, name) => {
        const option = document.createElement('option');
        const details = model.details || {};
        const runningModel = runningModels.find(candidate => {
            const runningCandidateName = candidate.name || candidate.model || '';
            return name === runningCandidateName
                || runningCandidateName.startsWith(`${name}:`)
                || name.startsWith(`${runningCandidateName}:`);
        });
        const modelContext = Number(
            model.model_context_length
            || model.context_length
            || details.context_length
            || runningModel?.model_context_length
            || runningModel?.context_length
        );
        const loadedContext = Number(runningModel?.context_length);
        const maxOutput = Number(
            runningModel?.max_output_tokens
            || model.max_output_tokens
        );
        const suffix = [
            details.parameter_size,
            details.quantization_level
        ].filter(Boolean).join(' · ');
        option.value = name;
        option.dataset.loaded = String(name === chatLoadedModelName);
        option.dataset.modelContext = Number.isFinite(modelContext) ? String(modelContext) : '';
        option.dataset.loadedContext = Number.isFinite(loadedContext) ? String(loadedContext) : '';
        option.dataset.maxOutput = Number.isFinite(maxOutput) ? String(maxOutput) : '';
        option.textContent = `${name}${name === chatLoadedModelName ? ' · loaded' : ''}${suffix ? ` · ${suffix}` : ''}`;
        select.appendChild(option);
    });

    select.value = names.has(previousSelection)
        ? previousSelection
        : names.has(chatLoadedModelName)
            ? chatLoadedModelName
            : names.keys().next().value;
    select.disabled = false;
    applyChatModelLimits();
    updateChatModelState();
}

function formatTokenLimit(value) {
    return new Intl.NumberFormat('en-US').format(Math.max(0, Math.round(value)));
}

function applyChatModelLimits() {
    const select = document.getElementById('chat-model');
    const contextInput = document.getElementById('chat-context');
    const outputInput = document.getElementById('chat-max-tokens');
    const contextHint = document.getElementById('chat-context-hint');
    if (!select || !contextInput || !outputInput) return;

    const option = select.selectedOptions[0];
    const selectedName = select.value;
    const modelContext = Number(option?.dataset.modelContext);
    const loadedContext = Number(option?.dataset.loadedContext);
    if (!selectedName || !Number.isFinite(modelContext) || modelContext <= 0) {
        contextInput.value = '';
        outputInput.value = '';
        contextInput.disabled = true;
        outputInput.disabled = true;
        if (contextHint) contextHint.textContent = 'Context metadata unavailable for this model';
        return;
    }

    const isLoaded = selectedName === chatLoadedModelName;
    const effectiveContext = isLoaded && Number.isFinite(loadedContext) && loadedContext > 0
        ? Math.min(modelContext, loadedContext)
        : modelContext;
    const limitsProfile = `${selectedName}:${effectiveContext}:${isLoaded}`;
    contextInput.disabled = false;
    outputInput.disabled = false;
    contextInput.max = String(effectiveContext);
    contextInput.dataset.modelContext = String(modelContext);
    contextInput.dataset.loadedContext = Number.isFinite(loadedContext) ? String(loadedContext) : '';

    if (chatModelLimitsName !== limitsProfile) {
        contextInput.value = String(effectiveContext);
        chatModelLimitsName = limitsProfile;
        updateChatOutputLimit(true);
    } else {
        const current = Number(contextInput.value);
        if (!Number.isFinite(current) || current > effectiveContext) {
            contextInput.value = String(effectiveContext);
        }
        updateChatOutputLimit(false);
    }

    if (contextHint) {
        contextHint.textContent = isLoaded && Number.isFinite(loadedContext) && loadedContext > 0
            ? `Loaded: ${formatTokenLimit(effectiveContext)} · model maximum: ${formatTokenLimit(modelContext)} tokens`
            : `Model maximum: ${formatTokenLimit(modelContext)} tokens`;
    }
}

function updateChatOutputLimit(resetValue) {
    const contextInput = document.getElementById('chat-context');
    const outputInput = document.getElementById('chat-max-tokens');
    const outputHint = document.getElementById('chat-max-tokens-hint');
    if (!contextInput || !outputInput) return;

    const context = Number(contextInput.value);
    if (!Number.isFinite(context) || context <= 0) {
        outputInput.value = '';
        outputInput.disabled = true;
        return;
    }

    const option = document.getElementById('chat-model')?.selectedOptions[0];
    const declaredOutput = Number(option?.dataset.maxOutput);
    const outputLimit = Math.min(
        32_768,
        Math.max(1, context - 256),
        Number.isFinite(declaredOutput) && declaredOutput > 0 ? declaredOutput : 32_768
    );
    outputInput.max = String(outputLimit);
    outputInput.disabled = false;

    const currentOutput = Number(outputInput.value);
    if (resetValue || !Number.isFinite(currentOutput) || currentOutput > outputLimit) {
        const recommendedOutput = Math.min(
            outputLimit,
            Math.max(128, Math.floor(context / 4))
        );
        outputInput.value = String(recommendedOutput);
    }
    if (outputHint) {
        outputHint.textContent =
            `Up to ${formatTokenLimit(outputLimit)} tokens within the selected context`;
    }
}

function updateChatModelState() {
    const select = document.getElementById('chat-model');
    const state = document.getElementById('chat-model-state');
    const stateLabel = document.getElementById('chat-model-state-label');
    const sendButton = document.getElementById('chat-send');
    if (!select || !state || !stateLabel) return;

    const selected = select.value;
    const loaded = selected && selected === chatLoadedModelName;
    const ready = Boolean(loaded && inferenceApiEnabled);
    state.classList.toggle('is-loaded', Boolean(loaded));
    state.classList.toggle('is-error', !selected);
    stateLabel.textContent = !inferenceApiEnabled
        ? 'API STOPPED'
        : !selected
            ? 'NO MODEL'
            : loaded
                ? 'ON NPU'
                : 'LOAD REQUIRED';
    state.title = !inferenceApiEnabled
        ? 'Start the inference API from the control panel'
        : !selected
            ? 'No downloaded model found'
            : loaded
                ? 'Loaded on NPU and ready to stream'
                : 'Load this model manually from the Models page before chatting';
    if (sendButton) sendButton.disabled = Boolean(chatAbortController) ? false : !ready;
}

async function refreshInferenceApiState() {
    try {
        const response = await fetch('/api/v1/control/api/status', {cache: 'no-store'});
        if (!response.ok) return;
        const data = await response.json();
        inferenceApiEnabled = Boolean(data.enabled);
        const toggle = document.getElementById('api-toggle');
        const badge = document.getElementById('ollama-badge');
        const headerStatus = document.getElementById('api-header-status');
        const headerEndpoint = document.getElementById('api-header-endpoint');
        const serviceIndicator = document.getElementById('api-service-indicator');
        const serviceMeta = document.getElementById('api-service-meta');
        if (toggle) {
            toggle.disabled = false;
            toggle.setAttribute('aria-pressed', String(inferenceApiEnabled));
            toggle.querySelector('span').textContent = inferenceApiEnabled ? 'Stop server' : 'Start server';
            toggle.classList.toggle('btn-primary', !inferenceApiEnabled);
            toggle.classList.toggle('btn-secondary', inferenceApiEnabled);
        }
        badge?.classList.toggle('offline', !inferenceApiEnabled);
        if (headerStatus) headerStatus.textContent = inferenceApiEnabled ? 'RUNNING' : 'STOPPED';
        if (headerEndpoint) headerEndpoint.textContent = window.location.host;
        if (serviceIndicator) serviceIndicator.textContent = inferenceApiEnabled ? 'API LIVE' : 'API STOPPED';
        if (serviceIndicator) serviceIndicator.classList.toggle('offline', !inferenceApiEnabled);
        if (serviceMeta) serviceMeta.textContent = inferenceApiEnabled ? 'INFERENCE READY' : 'INFERENCE OFFLINE';
        const apiLabel = document.getElementById('chat-api-label');
        if (apiLabel) apiLabel.textContent = inferenceApiEnabled ? 'API RUNNING' : 'API STOPPED';
        document.getElementById('chat-api-state')?.classList.toggle('is-error', !inferenceApiEnabled);
        updateChatModelState();
    } catch (error) {
        reportClientError(error, "frontend operation", false);
        console.warn('Unable to read inference API state:', error);
        inferenceApiEnabled = false;
        const toggle = document.getElementById('api-toggle');
        if (toggle) {
            toggle.disabled = true;
            toggle.querySelector('span').textContent = 'Unavailable';
        }
        document.getElementById('ollama-badge')?.classList.add('offline');
        const headerStatus = document.getElementById('api-header-status');
        const headerEndpoint = document.getElementById('api-header-endpoint');
        if (headerStatus) headerStatus.textContent = 'UNAVAILABLE';
        if (headerEndpoint) headerEndpoint.textContent = window.location.host;
        document.getElementById('api-service-indicator')?.classList.add('offline');
        const serviceIndicator = document.getElementById('api-service-indicator');
        const serviceMeta = document.getElementById('api-service-meta');
        const apiLabel = document.getElementById('chat-api-label');
        if (serviceIndicator) serviceIndicator.textContent = 'UNKNOWN';
        if (serviceMeta) serviceMeta.textContent = 'STATUS UNAVAILABLE';
        if (apiLabel) apiLabel.textContent = 'API UNKNOWN';
        updateChatModelState();
    }
}

async function toggleInferenceApi() {
    const action = inferenceApiEnabled ? 'stop' : 'start';
    const toggle = document.getElementById('api-toggle');
    try {
        if (toggle) {
            toggle.disabled = true;
            toggle.querySelector('span').textContent =
                inferenceApiEnabled ? 'Stopping…' : 'Starting…';
        }
        const response = await fetch(`/api/v1/control/api/${action}`, {method: 'POST'});
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || `Unable to ${action} the inference API`);
        inferenceApiEnabled = Boolean(data.enabled);
        showToast(inferenceApiEnabled ? 'Inference API started' : 'Inference API stopped', 'success');
    } catch (error) {
        reportClientError(error, "frontend operation", false);
        showToast(error.message, 'error');
    } finally {
        refreshInferenceApiState();
    }
}

function refreshControlPanel() {
    fetchHardwareAndStatus();
    fetchTelemetry();
    fetchModels();
    refreshInferenceApiState();
}

function setChatSessionState(mode, label) {
    const state = document.querySelector('.chat-session-state');
    const stateLabel = document.getElementById('chat-session-label');
    if (!state || !stateLabel) return;
    state.classList.toggle('is-generating', mode === 'generating');
    state.classList.toggle('is-error', mode === 'error');
    stateLabel.textContent = label;
}

function setChatGenerating(generating) {
    const select = document.getElementById("chat-model");
    const sendButton = document.getElementById("chat-send");
    const clearButton = document.getElementById("chat-clear");
    if (select) select.disabled = generating;
    if (sendButton) {
        sendButton.disabled = generating ? false : !select?.value || !inferenceApiEnabled || select.value !== chatLoadedModelName;
        sendButton.classList.toggle("btn-primary", !generating);
        sendButton.classList.toggle("btn-secondary", generating);
        sendButton.classList.toggle("chat-stop-btn", generating);
        sendButton.setAttribute("aria-label", generating ? "Stop generation" : "Send message");
        sendButton.innerHTML = generating
            ? '<span class="stop-square" aria-hidden="true"></span><span>Stop</span>'
            : '<span>Send to NPU</span><svg viewBox="0 0 24 24" width="15" height="15" stroke="currentColor" stroke-width="2" fill="none" aria-hidden="true"><path d="M22 2 11 13"></path><path d="m22 2-7 20-4-9-9-4Z"></path></svg>';
    }
    if (clearButton) clearButton.disabled = generating;
    if (generating) setChatSessionState("generating", "GENERATING");
}

function renderChatMarkdown(element, markdown) {
    if (!element) return;
    if (!markdown) {
        element.replaceChildren();
        return;
    }
    if (!window.marked?.parse || !window.DOMPurify?.sanitize) {
        element.textContent = markdown;
        return;
    }

    const rendered = window.marked.parse(markdown, {
        gfm: true,
        breaks: true
    });
    element.innerHTML = window.DOMPurify.sanitize(rendered, {
        USE_PROFILES: {html: true}
    });
    element.classList.add('is-markdown');
    element.querySelectorAll('a[href]').forEach(link => {
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
    });
}

function appendChatMessage(role, content = '') {
    const messages = document.getElementById('chat-messages');
    const empty = document.getElementById('chat-empty');
    if (!messages) return null;
    if (empty) empty.hidden = true;

    const row = document.createElement('article');
    row.className = `chat-message is-${role}`;

    const avatar = document.createElement('span');
    avatar.className = 'chat-message-avatar';
    avatar.setAttribute('aria-hidden', 'true');
    avatar.textContent = role === 'user' ? 'YOU' : 'NPU';

    const body = document.createElement('div');
    body.className = 'chat-message-content';
    const roleLabel = document.createElement('span');
    roleLabel.className = 'chat-message-role';
    roleLabel.textContent = role === 'user' ? 'YOU' : 'LOCAL MODEL';
    const text = document.createElement('div');
    text.className = 'chat-message-text';
    if (content) renderChatMarkdown(text, content);
    const meta = document.createElement('span');
    meta.className = 'chat-message-meta';
    meta.textContent = role === 'user' ? 'Session message' : 'Waiting for first token…';

    body.append(roleLabel, text, meta);
    row.append(avatar, body);
    messages.appendChild(row);
    messages.scrollTop = messages.scrollHeight;
    return {row, text, meta};
}

function readChatOption(id, fallback, minimum, maximum) {
    const value = Number(document.getElementById(id)?.value);
    if (!Number.isFinite(value)) return fallback;
    return Math.min(maximum, Math.max(minimum, value));
}

function setMetricValue(id, value, unit) {
    const element = document.getElementById(id);
    if (!element) return;
    const small = document.createElement('small');
    small.textContent = unit;
    element.replaceChildren(document.createTextNode(value), small);
}

function updateChatMetrics(finalChunk, startedAt, firstTokenAt, streamedTokens) {
    const endedAt = performance.now();
    const reportedTokens = Number(finalChunk?.eval_count);
    const tokens = Number.isFinite(reportedTokens) && reportedTokens > 0
        ? reportedTokens
        : streamedTokens;
    const ttftMs = firstTokenAt === null ? endedAt - startedAt : firstTokenAt - startedAt;
    const reportedEvalMs = Number(finalChunk?.eval_duration) / 1_000_000;
    const clientDecodeMs = firstTokenAt === null ? endedAt - startedAt : endedAt - firstTokenAt;
    const decodeMs = tokens > 1 && clientDecodeMs > 0
        ? clientDecodeMs
        : reportedEvalMs > 0
            ? reportedEvalMs
            : endedAt - startedAt;
    const tokensPerSecond = tokens > 0 && decodeMs > 0 ? tokens * 1000 / decodeMs : 0;

    setMetricValue('chat-metric-ttft', Math.round(ttftMs).toString(), 'ms');
    setMetricValue('chat-metric-tps', tokensPerSecond.toFixed(1), 'tok/s');
    setMetricValue('chat-metric-tokens', tokens.toString(), 'out');
    setMetricValue('ttft-value', Math.round(ttftMs).toString(), 'ms');
    setMetricValue('tps-value', tokensPerSecond.toFixed(1), 'tok/s');
    return {tokens, totalMs: endedAt - startedAt};
}

async function sendChatMessage(event) {
    event.preventDefault();
    if (chatAbortController) return;

    const input = document.getElementById('chat-input');
    const modelSelect = document.getElementById('chat-model');
    const prompt = input?.value.trim() || '';
    const model = modelSelect?.value || '';
    if (!prompt) {
        input?.focus();
        return;
    }
    if (!model) {
        showToast('Download or load a model before opening a chat.', 'error');
        return;
    }

    const userMessage = {role: 'user', content: prompt};
    chatHistory.push(userMessage);
    appendChatMessage('user', prompt);
    const assistant = appendChatMessage('assistant');
    assistant?.row.classList.add('is-streaming');
    input.value = '';
    input.style.height = '';
    input.dispatchEvent(new Event('input'));

    const systemPrompt = document.getElementById('chat-system-prompt')?.value.trim();
    const requestMessages = systemPrompt
        ? [{role: 'system', content: systemPrompt}, ...chatHistory]
        : [...chatHistory];
    const payload = {
        model,
        messages: requestMessages,
        stream: true,
        keep_alive: -1,
        options: {
            num_ctx: Math.round(readChatOption(
                'chat-context',
                4096,
                512,
                Number(document.getElementById('chat-context')?.max) || 131072
            )),
            num_predict: Math.round(readChatOption(
                'chat-max-tokens',
                256,
                1,
                Number(document.getElementById('chat-max-tokens')?.max) || 32768
            )),
            temperature: readChatOption('chat-temperature', 0.7, 0, 2)
        }
    };

    chatAbortController = new AbortController();
    setChatGenerating(true);
    setMetricValue('chat-metric-ttft', '--', 'ms');
    setMetricValue('chat-metric-tps', '--', 'tok/s');
    setMetricValue('chat-metric-tokens', '--', 'out');

    const startedAt = performance.now();
    let firstTokenAt = null;
    let streamedTokens = 0;
    let assistantText = '';
    let finalChunk = null;

    const consumeLine = line => {
        if (!line.trim()) return;
        const chunk = JSON.parse(line);
        if (chunk.error) throw new Error(chunk.error);
        const content = chunk.message?.content || '';
        if (content) {
            if (firstTokenAt === null) firstTokenAt = performance.now();
            streamedTokens += 1;
            assistantText += content;
            if (assistant) renderChatMarkdown(assistant.text, assistantText);
            const messages = document.getElementById('chat-messages');
            if (messages) messages.scrollTop = messages.scrollHeight;
        }
        if (chunk.done) finalChunk = chunk;
    };

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload),
            signal: chatAbortController.signal
        });
        if (!response.ok) {
            const body = await response.text();
            throw new Error(body || `HTTP ${response.status}`);
        }
        if (!response.body) throw new Error('Streaming response is unavailable in this browser');

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let pending = '';
        while (true) {
            const {value, done} = await reader.read();
            pending += decoder.decode(value || new Uint8Array(), {stream: !done});
            const lines = pending.split('\n');
            pending = lines.pop() || '';
            lines.forEach(consumeLine);
            if (done) break;
        }
        consumeLine(pending);

        assistant?.row.classList.remove('is-streaming');
        if (!assistantText) {
            assistantText = '(No response returned)';
            if (assistant) renderChatMarkdown(assistant.text, assistantText);
        }
        chatHistory.push({role: 'assistant', content: assistantText});
        const metrics = updateChatMetrics(finalChunk, startedAt, firstTokenAt, streamedTokens);
        if (assistant) {
            assistant.meta.textContent = `${metrics.tokens} tokens · ${(metrics.totalMs / 1000).toFixed(2)} s`;
        }
        chatLoadedModelName = model;
        setChatSessionState('ready', 'READY');
        updateChatModelState();
    } catch (error) {
        reportClientError(error, "frontend operation", false);
        assistant?.row.classList.remove('is-streaming');
        if (error.name === 'AbortError') {
            if (assistantText) {
                chatHistory.push({role: 'assistant', content: assistantText});
                if (assistant) assistant.meta.textContent = 'Generation stopped';
            } else {
                assistant?.row.remove();
            }
            setChatSessionState('ready', 'STOPPED');
        } else {
            if (assistant) {
                assistant.row.classList.add('is-error');
                assistant.text.classList.remove('is-markdown');
                assistant.text.textContent = `Request failed: ${error.message}`;
                assistant.meta.textContent = 'Check the model and runtime logs';
            }
            setChatSessionState('error', 'ERROR');
            showToast(error.message, 'error');
        }
    } finally {
        chatAbortController = null;
        setChatGenerating(false);
        updateChatModelState();
        input?.focus();
    }
}

function clearChatConversation() {
    if (chatAbortController) return;
    chatHistory = [];
    document.querySelectorAll('#chat-messages .chat-message').forEach(message => message.remove());
    const empty = document.getElementById('chat-empty');
    if (empty) empty.hidden = false;
    setMetricValue('chat-metric-ttft', '--', 'ms');
    setMetricValue('chat-metric-tps', '--', 'tok/s');
    setMetricValue('chat-metric-tokens', '--', 'out');
    setChatSessionState('ready', 'READY');
    document.getElementById('chat-input')?.focus();
}

async function fetchHardwareAndStatus() {
    try {
        const hwResp = await fetch('/api/v1/control/hardware');

        if (hwResp.ok) {
            const hwList = await hwResp.json();
            const activeNpu = BACKEND_PRIORITY.find(backend =>
                hwList.some(hw => hw.available && hw.type === backend)
            ) || 'No NPU Online';

            const activeLabel = document.getElementById('active-driver-label');
            const engineStateLabel = document.getElementById('engine-state-label');
            const connectionState = document.querySelector('.connection-state');
            const engineReady = activeNpu !== 'No NPU Online';
            const runtimeUnavailable = hwList.some(hw => {
                const details = String(hw.statusDetails || '').toLowerCase();
                return details.includes('runtime is not loaded')
                    || details.includes('could not initialize')
                    || details.includes('health check failed');
            });
            const unsupportedPlatform = !engineReady
                && !runtimeUnavailable
                && hwList.length > 0
                && hwList.every(hw => String(hw.statusDetails || '').toLowerCase().includes('not detected'));
            if (activeLabel) {
                activeLabel.innerText = activeNpu === 'No NPU Online'
                    ? (unsupportedPlatform ? 'Unsupported' : 'Not detected')
                    : activeNpu;
            }
            if (engineStateLabel) {
                engineStateLabel.innerText = engineReady
                    ? 'ONLINE'
                    : runtimeUnavailable ? 'RUNTIME OFF' : unsupportedPlatform ? 'UNSUPPORTED' : 'NO NPU';
            }
            if (connectionState) {
                connectionState.classList.toggle('offline', !engineReady);
            }

            if (backendSelectionMode === 'AUTO'
                && activeNpu !== 'No NPU Online'
                && !unsupportedPlatform
                && activeNpu !== currentActiveNpu) {
                currentActiveNpu = activeNpu;
                applyTheme(currentActiveNpu);
            }
            if (unsupportedPlatform) {
                currentActiveNpu = 'Unsupported';
                applyTheme('Unsupported');
            }
        }
    } catch (err) {
        console.error('Error fetching hardware status:', err);
    }
}

function clampUsage(value) {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return 0;
    return Math.min(100, Math.max(0, parsed));
}

function updateUsageMeter(
    valueId,
    fillId,
    trackId,
    value,
    available = true
) {
    const percentage = clampUsage(value);
    const valueElement = document.getElementById(valueId);
    const fill = document.getElementById(fillId);
    const track = document.getElementById(trackId);
    if (valueElement) {
        valueElement.textContent = available ? `${percentage.toFixed(1)}%` : '--%';
    }
    if (fill) fill.style.width = available ? `${percentage}%` : '0%';
    if (track) {
        track.setAttribute('aria-valuenow', available ? percentage.toFixed(1) : '0');
        track.setAttribute('aria-valuetext', available
            ? `${percentage.toFixed(1)} percent`
            : 'Utilization counter unavailable');
    }
}

function updateMetricFooter(id, text) {
    const footer = document.getElementById(id);
    if (!footer) return;
    const tick = document.createElement('span');
    tick.className = 'metric-tick';
    footer.replaceChildren(tick, document.createTextNode(text));
}

function formatMemory(mb) {
    const value = Number(mb);
    if (!Number.isFinite(value)) return '--';
    return value >= 1024 ? `${(value / 1024).toFixed(1)} GB` : `${Math.round(value)} MB`;
}

function updateMemoryMetric(diag) {
    const usedMb = Number(diag.systemMemoryUsedMb);
    const totalMb = Number(diag.systemMemoryTotalMb);
    const memoryValue = document.getElementById('memory-value');
    if (memoryValue && Number.isFinite(usedMb) && Number.isFinite(totalMb) && totalMb > 0) {
        const useGigabytes = totalMb >= 1024;
        const divisor = useGigabytes ? 1024 : 1;
        const decimals = useGigabytes ? 1 : 0;
        setMetricValue(
            'memory-value',
            `${(usedMb / divisor).toFixed(decimals)} / ${(totalMb / divisor).toFixed(decimals)}`,
            useGigabytes ? 'GB' : 'MB'
        );
    }
    updateMetricFooter(
        'memory-sub',
        `${clampUsage(diag.ramUsagePercent).toFixed(1)}% used · JVM ${formatMemory(diag.jvmUsedMemoryMb)}`
    );
}

function updateInferenceMetrics(diag) {
    if (!diag.generationMetricsAvailable && !diag.generationActive) return;

    const tokensPerSecond = Math.max(0, Number(diag.generationTokensPerSecond) || 0);
    const ttft = Math.max(0, Number(diag.generationTimeToFirstTokenMs) || 0);
    const completionTokens = Math.max(0, Number(diag.generationCompletionTokens) || 0);
    const active = Boolean(diag.generationActive);
    const backend = diag.generationBackend || 'NPU';

    setMetricValue('tps-value', tokensPerSecond > 0 ? tokensPerSecond.toFixed(1) : '--', 'tok/s');
    setMetricValue('ttft-value', ttft > 0 ? Math.round(ttft).toString() : '--', 'ms');
    updateMetricFooter('tps-sub', active ? `${backend} · generation active` : `${backend} · last response`);
    updateMetricFooter('ttft-sub', active ? `${completionTokens} tokens streaming` : `${completionTokens} output tokens`);

    setMetricValue('chat-metric-tps', tokensPerSecond > 0 ? tokensPerSecond.toFixed(1) : '--', 'tok/s');
    setMetricValue('chat-metric-ttft', ttft > 0 ? Math.round(ttft).toString() : '--', 'ms');
    setMetricValue('chat-metric-tokens', completionTokens.toString(), 'out');
}

async function fetchTelemetry() {
    if (telemetryRequestInFlight) return;
    telemetryRequestInFlight = true;
    try {
        const response = await fetch('/api/v1/control/diagnostics', {cache: 'no-store'});
        if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
        const diag = await response.json();

        const cpu = clampUsage(diag.cpuUsagePercent);
        const npu = clampUsage(diag.npuUsagePercent);
        const ram = clampUsage(diag.ramUsagePercent);
        const npuAvailable = Boolean(diag.npuUtilizationAvailable);

        updateUsageMeter('cpu-usage-value', 'cpu-usage-fill', 'cpu-usage-track', cpu);
        updateUsageMeter('npu-usage-value', 'npu-usage-fill', 'npu-usage-track', npu, npuAvailable);
        updateUsageMeter('ram-usage-value', 'ram-usage-fill', 'ram-usage-track', ram);
        updateUsageMeter('chat-cpu-usage', 'chat-cpu-fill', 'chat-cpu-track', cpu);
        updateUsageMeter('chat-npu-usage', 'chat-npu-fill', 'chat-npu-track', npu, npuAvailable);
        updateUsageMeter('chat-ram-usage', 'chat-ram-fill', 'chat-ram-track', ram);

        const cpuDetail = document.getElementById('cpu-usage-detail');
        const npuDetail = document.getElementById('npu-usage-detail');
        const ramDetail = document.getElementById('ram-usage-detail');
        if (cpuDetail) cpuDetail.textContent = `${diag.availableProcessors} logical cores · system wide`;
        if (npuDetail) {
            npuDetail.textContent = npuAvailable
                ? `${diag.npuActiveCores} / ${diag.npuCoreCount} cores powered · runtime occupancy`
                : diag.npuUtilizationSource || 'Kernel utilization counter unavailable';
        }
        if (ramDetail) {
            ramDetail.textContent =
                `${formatMemory(diag.systemMemoryUsedMb)} used · ${formatMemory(diag.systemMemoryAvailableMb)} available`;
        }

        updateMemoryMetric(diag);
        updateInferenceMetrics(diag);
    } catch (error) {
        reportClientError(error, "frontend operation", false);
        console.error('Error fetching live telemetry:', error);
    } finally {
        telemetryRequestInFlight = false;
    }
}

async function fetchModels() {
    try {
        const resp = await fetch(`/api/v1/control/models?all=true`);
        if (resp.ok) {
            const models = await resp.json();
            updateModelsDOM(models);
        }
    } catch (err) {
        console.error('Error fetching models:', err);
    }
}

function organizeModelLists(models = []) {
    const root = document.getElementById("models-container");
    if (!root) return;

    const cards = Array.from(root.querySelectorAll(".model-card"));
    if (!cards.length) return;
    cards.forEach((card, index) => {
        if (card.dataset.catalogIndex === undefined) card.dataset.catalogIndex = String(index);
    });

    const states = new Map((Array.isArray(models) ? models : []).map(model => [String(model.id), model]));
    const labels = {
        ROCKCHIP: "Rockchip NPU",
        OPENVINO: "Intel OpenVINO",
        QUALCOMM: "Qualcomm QAIRT",
        RYZENAI: "AMD Ryzen AI"
    };
    const backends = ["ROCKCHIP", "OPENVINO", "QUALCOMM", "RYZENAI"];

    if (root.dataset.listsInitialized !== "true") {
        root.innerHTML = "";
        [
            ["downloaded", "Downloaded models"],
            ["available", "Available for download"]
        ].forEach(([kind, title]) => {
            const section = document.createElement("section");
            section.className = "model-list-section";
            section.dataset.modelList = kind;
            section.innerHTML = "<header class=\"model-list-header\"><h2>" + title + "</h2><span></span></header><div class=\"model-list-groups\"></div>";
            root.appendChild(section);
        });
        root.dataset.listsInitialized = "true";
    }

    const buckets = {downloaded: new Map(), available: new Map()};
    cards.sort((a, b) => Number(a.dataset.catalogIndex) - Number(b.dataset.catalogIndex));
    cards.forEach(card => {
        const state = states.get(String(card.dataset.modelId));
        const downloaded = state ? Boolean(state.downloaded) : card.dataset.downloaded === "true";
        card.dataset.downloaded = String(downloaded);
        const kind = downloaded ? "downloaded" : "available";
        const backend = String(card.dataset.backend || "").toUpperCase();
        if (!buckets[kind].has(backend)) buckets[kind].set(backend, []);
        buckets[kind].get(backend).push(card);
    });

    ["downloaded", "available"].forEach(kind => {
        const section = root.querySelector("[data-model-list=\"" + kind + "\"]");
        const groupsRoot = section.querySelector(".model-list-groups");
        groupsRoot.innerHTML = "";
        let total = 0;
        backends.forEach(backend => {
            const groupCards = buckets[kind].get(backend) || [];
            if (!groupCards.length) return;
            total += groupCards.length;
            const group = document.createElement("section");
            group.className = "model-group";
            group.innerHTML = "<header class=\"model-group-header\"><div><span class=\"runtime-marker\"></span><h3>" + (labels[backend] || backend) + "</h3></div><span>" + groupCards.length + " MODELS</span></header><div class=\"models-grid\"></div>";
            const grid = group.querySelector(".models-grid");
            groupCards.forEach(card => grid.appendChild(card));
            groupsRoot.appendChild(group);
        });
        section.hidden = total === 0;
        const count = section.querySelector(".model-list-header span");
        if (count) count.textContent = total + (total === 1 ? " MODEL" : " MODELS");
    });
}

function updateModelsDOM(models) {
    models.forEach(m => {
        const card = document.querySelector(`.model-card[data-model-id="${m.id}"]`);
        if (!card) return;

        if (card.querySelector('.model-quantization-select')) {
            card.classList.toggle('active-model', Boolean(m.loaded));
            const badge = card.querySelector('.badge');
            if (badge) {
                if (m.loaded) {
                    badge.textContent = 'LOADED';
                    badge.className = 'badge badge-primary';
                } else if (m.downloaded) {
                    badge.textContent = 'LOCAL';
                    badge.className = 'badge badge-primary';
                } else {
                    badge.textContent = (m.id.startsWith('OpenVINO/') || m.id.startsWith('unsloth/')) ? 'HUGGING FACE' : 'MODEL SCOPE';
                    badge.className = 'badge';
                }
            }
            refreshRockchipQuantizationCard(card);
            return;
        }

        // Update card active class and badge
        card.classList.toggle('active-model', Boolean(m.loaded));
        const badge = card.querySelector('.badge');
        if (badge) {
            if (m.loaded) {
                badge.textContent = 'LOADED';
                badge.className = 'badge badge-primary';
            } else if (m.downloaded) {
                badge.textContent = 'LOCAL';
                badge.className = 'badge badge-primary';
            } else {
                badge.textContent = (m.id.startsWith('OpenVINO/') || m.id.startsWith('unsloth/')) ? 'HUGGING FACE' : 'MODEL SCOPE';
                badge.className = 'badge';
            }
        }

        // Update progress if downloading
        const progressSection = card.querySelector('.progress-section');
        if (progressSection && !progressSection.classList.contains("operation-progress")) {
            if (m.downloadStatus === 'DOWNLOADING') {
                progressSection.style.display = 'block';
                const bar = progressSection.querySelector('.progress-bar-fill');
                const text = progressSection.querySelector('.progress-bar-text');
                if (bar) bar.style.width = `${m.downloadProgress}%`;
                if (text) text.innerText = `${m.downloadProgress.toFixed(1)}% completed`;
            } else {
                progressSection.style.display = 'none';
            }
        }

        // Update action buttons dynamically
        const loadBtn = card.querySelector('button[data-action="load"]');
        if (loadBtn) {
            loadBtn.disabled = !m.downloaded || m.loaded;
            loadBtn.className = m.loaded ? 'btn btn-secondary' : 'btn btn-primary';
            const span = loadBtn.querySelector('span');
            if (span) span.textContent = m.loaded ? 'Loaded on NPU' : 'Load on NPU';
        }

        let unloadBtn = card.querySelector('button[data-action="unload"]');
        if (m.loaded) {
            if (!unloadBtn) {
                unloadBtn = document.createElement('button');
                unloadBtn.className = 'btn btn-secondary';
                unloadBtn.type = 'button';
                unloadBtn.dataset.action = 'unload';
                unloadBtn.innerHTML = '<span>Unload from NPU</span>';
                if (loadBtn) {
                    loadBtn.after(unloadBtn);
                } else {
                    card.querySelector('.model-actions')?.appendChild(unloadBtn);
                }
            }
        } else if (unloadBtn) {
            unloadBtn.remove();
        }

        const deleteBtn = card.querySelector('button[data-action="delete"]');
        if (deleteBtn) {
            deleteBtn.disabled = !m.downloaded || m.loaded;
        }

        const downloadBtn = card.querySelector('button[data-action="download"]');
        if (downloadBtn) {
            downloadBtn.disabled = false;
            downloadBtn.hidden = false;
            const span = downloadBtn.querySelector('span');
            if (span) span.textContent = m.downloaded ? 'Re-download' : 'Download model';
        }
    });
    organizeModelLists(models);
}

function refreshRockchipQuantizationCards() {
    document.querySelectorAll('.model-card .model-quantization-select').forEach(select => {
        const card = select.closest('.model-card');
        if (card) refreshRockchipQuantizationCard(card);
    });
}

async function refreshRockchipQuantizationCard(card) {
    const select = card.querySelector('.model-quantization-select');
    if (!select) return;

    const modelId = card.dataset.modelId;
    const quantization = select.value;
    const state = card.querySelector('.quantization-state');
    const stateLabel = card.querySelector('.quantization-state-label');
    const progressSection = card.querySelector('.progress-section');
    const loadButton = card.querySelector('button[data-action="load"]');
    const downloadButton = card.querySelector('button[data-action="download"]');
    const downloadLabel = downloadButton?.querySelector('span');
    const deleteButton = card.querySelector('button[data-action="delete"]');

    try {
        const query = new URLSearchParams({modelId, quantization});
        const response = await fetch(`/api/v1/control/models/download/status?${query}`);
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "Unable to inspect selected quantization");

        const downloading = data.status === "DOWNLOADING";
        const local = Boolean(data.isDownloaded);

        state?.classList.toggle("is-local", local);
        state?.classList.toggle("is-downloading", downloading);
        state?.classList.toggle("is-error", data.status === "FAILED");
        if (stateLabel) {
            stateLabel.textContent = downloading
                ? `${quantization} · downloading ${Number(data.progress).toFixed(1)}%`
                : local
                    ? `${quantization} is available locally`
                    : data.status === "FAILED"
                        ? `${quantization} download failed`
                        : `${quantization} is not downloaded`;
        }

        if (loadButton) loadButton.disabled = !local || downloading;
        if (deleteButton) {
            deleteButton.disabled = !local || downloading || card.classList.contains("active-model");
        }
        if (downloadButton) downloadButton.disabled = downloading;
        if (downloadLabel) {
            downloadLabel.textContent = downloading
                ? "Downloading selected…"
                : local ? "Re-download selected" : "Download selected";
        }

        if (progressSection && !progressSection.classList.contains("operation-progress")) {
            progressSection.style.display = downloading ? "block" : "none";
            const bar = progressSection.querySelector(".progress-bar-fill");
            const text = progressSection.querySelector(".progress-bar-text");
            if (bar) bar.style.width = `${data.progress}%`;
            if (text) text.innerText = `${Number(data.progress).toFixed(1)}% completed`;
        }
    } catch (error) {
        reportClientError(error, "frontend operation", false);
        state?.classList.add("is-error");
        if (stateLabel) stateLabel.textContent = error.message;
    }
}

 function setModelOperationProgress(card, operation) {
    if (!card) return;
    const section = card.querySelector(".progress-section");
    const bar = section?.querySelector(".progress-bar-fill");
    const text = section?.querySelector(".progress-bar-text");
    if (!section) return;
    const active = Boolean(operation);
    section.classList.toggle("operation-progress", active);
    section.style.display = active ? "block" : "none";
    if (active) {
        if (bar) bar.style.width = "35%";
        if (text) text.textContent = operation === "load" ? "Loading model on NPU…" : "Unloading model from NPU…";
        card.querySelectorAll(".model-actions button").forEach(button => { button.disabled = true; });
    } else {
        if (bar) bar.style.width = "0";
        if (text) text.textContent = "0.0% completed";
    }
}

async function downloadFromSource(modelId, quantization = null) {
    try {
        toggleTerminalDrawer(true);
        const resp = await fetch("/api/v1/control/models/download", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ modelId, quantization })
        });
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.error || "Unable to start model download");
        showToast(
            quantization
                ? "Downloading only " + quantization + " for " + modelId
                : "Downloading " + modelId,
            "success"
        );
        fetchModels();
    } catch (err) {
        reportClientError(err, "model download");
    }
}

async function deleteModel(modelId, quantization = null) {
    const target = quantization ? `${modelId} · ${quantization}` : modelId;
    const confirmed = await showConfirm(
        quantization ? 'Delete selected quantization' : 'Delete Model',
        `Are you sure you want to permanently delete ${target}?`
    );
    if (!confirmed) return;

    try {
        const resp = await fetch("/api/v1/control/models/delete", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ modelId, quantization })
        });
        const data = await resp.json();
        if (resp.ok) {
            showToast(`Deleted ${target}`, 'success');
            fetchModels();
        } else {
            throw new Error(data.error || `Unable to delete ${target}`);
        }
    } catch (err) {
        console.error('Error deleting model:', err);
        showToast(err.message, 'error');
    }
}

async function loadModel(modelId, preferredBackend, quantization = null, card = null) {
    try {
        const activeCard = document.querySelector('.model-card.active-model');
        const activeModelId = activeCard?.dataset.modelId || '';
        if (activeModelId && activeModelId !== modelId) {
            const replace = window.confirm(
                `Model ${activeModelId} is already loaded. Replace it with ${modelId}?`
            );
            if (!replace) return;
            setModelOperationProgress(activeCard, "unload");

            const unloadResp = await fetch('/api/v1/control/models/unload', {method: 'POST'});
            const unloadData = await unloadResp.json();
            if (!unloadResp.ok) {
                setModelOperationProgress(activeCard, null);
                throw new Error(unloadData.error || 'Unable to unload the active model');
            }
            setModelOperationProgress(activeCard, null);
        }

        setModelOperationProgress(card, "load");
        const resp = await fetch('/api/v1/control/models/load', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({modelId, preferredBackend, quantization})
        });
        const data = await resp.json();
        if (resp.ok) {
            const variant = quantization ? ` (${quantization})` : '';
            showToast(`Model ${modelId}${variant} loaded on NPU ${preferredBackend}!`, 'success');
            await fetchModels();
            await refreshInferenceApiState();
            setModelOperationProgress(card, null);
        } else {
            setModelOperationProgress(card, null);
            showToast(data.error || `Error: NPU ${preferredBackend} unavailable`, 'error');
        }
    } catch (err) {
        setModelOperationProgress(card, null);
        reportClientError(err, "model load");
    }
}

async function unloadModel(card = null) {
    setModelOperationProgress(card, "unload");
    try {
        const resp = await fetch('/api/v1/control/models/unload', {method: 'POST'});
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.error || 'Unable to unload the active model');
        showToast('Model unloaded from NPU', 'success');
        setModelOperationProgress(card, null);
        await fetchModels();
        await refreshInferenceApiState();
    } catch (err) {
        setModelOperationProgress(card, null);
        reportClientError(err, "model unload");
    }
}

async function loadSettings() {
    try {
        const resp = await fetch('/api/v1/control/settings');
        if (resp.ok) {
            const data = await resp.json();
            const backendSelect = document.getElementById('setting-backend');
            const recommendationHint = document.getElementById('backend-recommendation-hint');
            const availableBackends = Array.from(backendSelect.options, option => option.value);
            const selectionState = String(data.selectionState || data.backendSelectionMode || 'AUTO');
            const unsupported = selectionState === 'UNSUPPORTED';
            const configuredBackend = isKnownBackend(data.configuredBackend)
                ? String(data.configuredBackend).toUpperCase()
                : '';
            const recommendedBackend = !unsupported && availableBackends.includes(data.recommendedBackend)
                ? data.recommendedBackend
                : null;
            const preferredBackend = !unsupported && availableBackends.includes(data.preferredBackend)
                ? data.preferredBackend
                : configuredBackend || recommendedBackend || availableBackends[0];

            backendSelectionMode = data.backendSelectionMode === 'MANUAL' ? 'MANUAL' : 'AUTO';
            backendSelectionState = selectionState;
            setUnsupportedBackendState(backendSelect, unsupported);

            if (unsupported) {
                currentActiveNpu = 'Unsupported';
                backendSelect.value = 'UNSUPPORTED';
                applyTheme('Unsupported');
            } else {
                backendSelect.value = preferredBackend;
                currentActiveNpu = preferredBackend;
                applyTheme(preferredBackend);
            }

            if (recommendationHint) {
                if (unsupported) {
                    recommendationHint.textContent = 'Unsupported on this platform';
                } else if (recommendedBackend) {
                    const recommendedLabel = backendSelect.querySelector(
                        `option[value="${recommendedBackend}"]`
                    ).textContent;
                    recommendationHint.textContent = backendSelectionMode === 'AUTO'
                        ? `System recommended · ${recommendedLabel}`
                        : `System recommendation · ${recommendedLabel}`;
                } else {
                    recommendationHint.textContent = 'No healthy NPU detected · fallback selection shown';
                }
            }
            if (data.modelsDirectory) document.getElementById('setting-models-dir').value = data.modelsDirectory;
            if (data.ollamaPort) document.getElementById('setting-port').value = data.ollamaPort;
            if (data.defaultContextWindow) document.getElementById('setting-context').value = data.defaultContextWindow;

        }
    } catch (err) {
        console.error('Error loading settings:', err);
    }
}

async function saveSettings() {
    const backendSelect = document.getElementById('setting-backend');
    const payload = {
        preferredBackend: backendSelectionState === 'UNSUPPORTED' ? 'auto' : backendSelect.value,
        modelsDirectory: document.getElementById('setting-models-dir').value,
        ollamaPort: parseInt(document.getElementById('setting-port').value, 10),
        defaultContextWindow: parseInt(document.getElementById('setting-context').value, 10),

    };

    try {
        const resp = await fetch('/api/v1/control/settings', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        if (resp.ok) {
            if (backendSelectionState !== 'UNSUPPORTED') {
                backendSelectionMode = 'MANUAL';
                backendSelectionState = 'MANUAL';
            }
            showToast('Settings saved successfully!', 'success');
            applyTheme(backendSelectionState === 'UNSUPPORTED' ? 'Unsupported' : payload.preferredBackend);
            fetchHardwareAndStatus();
            fetchModels();
        } else {
            showToast('Error saving settings.', 'error');
        }
    } catch (err) {
        console.error('Error saving settings:', err);
    }
}

async function triggerIntelDriverInstall() {
    try {
        toggleTerminalDrawer(true);
        const resp = await fetch('/api/v1/control/setup/intel-driver', {method: 'POST'});
        if (resp.ok) {
            showToast('Started Intel NPU Driver Installation task...', 'success');
            pollSetupTask('intel-driver', 'btn-setup-intel-driver');
        }
    } catch (err) {
        showToast('Error triggering Intel driver installation', 'error');
    }
}

async function triggerWorkerBuild(workerType) {
    try {
        toggleTerminalDrawer(true);
        const resp = await fetch('/api/v1/control/setup/build-worker', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({workerType})
        });
        if (resp.ok) {
            showToast(`Started build task for ${workerType} worker...`, 'success');
            pollSetupTask('build-' + workerType, 'btn-setup-' + workerType);
        }
    } catch (err) {
        showToast(`Error starting build for ${workerType} worker`, 'error');
    }
}

async function triggerModelScopeInstall() {
    try {
        toggleTerminalDrawer(true);
        const resp = await fetch('/api/v1/control/setup/modelscope', {method: 'POST'});
        if (resp.ok) {
            showToast('Started ModelScope CLI installation...', 'success');
            pollSetupTask('modelscope-setup', 'btn-setup-modelscope');
        }
    } catch (err) {
        showToast('Error starting ModelScope installation', 'error');
    }
}


function pollSetupTask(taskId, btnId) {
    const btn = document.getElementById(btnId);
    if (btn) {
        btn.disabled = true;
        btn.innerText = 'Running...';
    }

    const interval = setInterval(async () => {
        try {
            const resp = await fetch(`/api/v1/control/setup/status?taskId=${taskId}`);
            if (resp.ok) {
                const data = await resp.json();
                if (data.status === 'COMPLETED') {
                    clearInterval(interval);
                    showToast(`Task ${taskId} completed successfully!`, 'success');
                    if (btn) {
                        btn.disabled = false;
                        btn.innerText = 'Completed';
                    }
                } else if (data.status && data.status.startsWith('FAILED')) {
                    clearInterval(interval);
                    showToast(`Task ${taskId} failed: ${data.status}`, 'error');
                    if (btn) {
                        btn.disabled = false;
                        btn.innerText = 'Retry';
                    }
                } else if (btn) {
                    btn.innerText = `${data.status} (${data.progress.toFixed(0)}%)`;
                }
            }
        } catch (e) {
            console.error('Error polling setup task:', e);
        }
    }, 2000);
}

async function pollTerminalLogs() {
    try {
        const resp = await fetch(`/api/v1/control/logs?afterId=${lastLogId}`);
        if (resp.ok) {
            const logs = await resp.json();
            if (logs.length > 0) {
                const termBody = document.getElementById('terminal-body');
                if (!termBody) return;

                logs.forEach(log => {
                    lastLogId = Math.max(lastLogId, log.id);
                    const levelClass = {
                        ERROR: 'is-error',
                        DOWNLOAD: 'is-download',
                        BUILD: 'is-build',
                        SYSTEM: 'is-system'
                    }[log.level] || 'is-default';

                    const lineDiv = document.createElement('div');
                    lineDiv.className = `terminal-line ${levelClass}`;
                    lineDiv.innerHTML = `<span class="terminal-meta">[${log.timestamp}] [${log.level}]</span>${escapeHtml(log.message)}`;
                    termBody.appendChild(lineDiv);
                });

                termBody.scrollTop = termBody.scrollHeight;
            }
        }
    } catch (e) {
        console.error('Error polling terminal logs:', e);
    }
}

function clearTerminal() {
    const termBody = document.getElementById('terminal-body');
    if (termBody) {
        termBody.innerHTML = '<div class="terminal-command">$ npu-hub-engine --stream-logs</div>';
    }
}

function escapeHtml(text) {
    return text
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}
