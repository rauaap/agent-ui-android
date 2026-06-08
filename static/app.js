"use strict";

/*
 * Front-end for agent-ui.
 *
 * Talks to the backend over the same protocol as before — nothing here depends
 * on internal adapter details, only on the AgentEvent message contract:
 *
 *   client -> server : { type: "input", text }
 *                      { type: "approval_response", request_id, behavior }
 *
 *   server -> client : { type: "status",            status }
 *                      { type: "input",             text }
 *                      { type: "output",            text }
 *                      { type: "tool_use",          tool, input }
 *                      { type: "approval_request",  request_id, tool, input }
 *                      { type: "approval_response", request_id, behavior }
 *                      { type: "done" }
 *                      { type: "error",             message }
 *
 * REST: GET /sessions, POST /sessions, DELETE /sessions/{id}
 */

const state = {
  sessions: [],
  current: null,
  socket: null,
  reconnectTimer: null,
  reconnectAttempt: 0,
  agentBubble: null, // text node we coalesce consecutive `output` chunks into
  pendingApproval: null, // { id, el }
};

const el = {};

document.addEventListener("DOMContentLoaded", () => {
  const ids = [
    "view-list", "view-session", "session-list", "session-count", "btn-new",
    "btn-back", "session-title", "session-dir", "session-status", "btn-kill",
    "transcript", "activity", "composer", "prompt-input", "btn-send",
    "dialog-new", "form-new", "input-name", "input-dir", "select-agent",
    "btn-close-dialog",
  ];
  for (const id of ids) el[id] = document.getElementById(id);

  bindEvents();
  loadSessions();
});

function bindEvents() {
  el["btn-new"].addEventListener("click", () => {
    el["dialog-new"].showModal();
    el["input-name"].focus();
  });
  el["btn-close-dialog"].addEventListener("click", () => el["dialog-new"].close());
  el["form-new"].addEventListener("submit", (e) => {
    e.preventDefault();
    createSession();
  });

  el["btn-back"].addEventListener("click", leaveSession);
  el["btn-kill"].addEventListener("click", () => {
    if (state.current) fetch(`/sessions/${state.current.id}/stop`, { method: "POST" });
  });

  el["composer"].addEventListener("submit", (e) => {
    e.preventDefault();
    sendPrompt();
  });

  const input = el["prompt-input"];
  input.addEventListener("input", autosize);
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendPrompt();
    }
  });
}

function autosize() {
  const input = el["prompt-input"];
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 140)}px`;
}

/* ------------------------------------------------------------------ */
/* session list                                                        */
/* ------------------------------------------------------------------ */

async function loadSessions() {
  try {
    const res = await fetch("/sessions");
    if (!res.ok) throw new Error("Unable to load sessions");
    state.sessions = await res.json();
    renderSessionList();
  } catch (err) {
    state.sessions = [];
    renderSessionList(String(err.message || err));
  }
}

function renderSessionList(error = "") {
  const n = state.sessions.length;
  el["session-count"].textContent = `${n} session${n === 1 ? "" : "s"}`;
  el["session-list"].replaceChildren();

  if (error || n === 0) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = error || "No sessions yet";
    el["session-list"].append(empty);
    return;
  }

  for (const session of state.sessions) {
    const card = document.createElement("div");
    card.className = "session-card";
    card.setAttribute("role", "button");
    card.tabIndex = 0;
    card.addEventListener("click", () => openSession(session.id));
    card.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        openSession(session.id);
      }
    });

    const head = document.createElement("div");
    head.className = "session-card-head";
    const name = document.createElement("span");
    name.className = "session-name";
    name.textContent = session.name;

    const actions = document.createElement("div");
    actions.className = "session-card-actions";
    const del = document.createElement("button");
    del.type = "button";
    del.className = "session-delete";
    del.setAttribute("aria-label", `Delete ${session.name}`);
    del.textContent = "🗑";
    del.addEventListener("click", (e) => {
      e.stopPropagation();
      deleteSession(session);
    });
    actions.append(statusBadge(session.status), del);
    head.append(name, actions);

    const path = document.createElement("p");
    path.className = "session-path mono";
    path.textContent = session.working_dir;

    const meta = document.createElement("p");
    meta.className = "session-meta";
    meta.textContent = `${formatAgent(session.agent)} · ${formatTime(session.last_active_at)}`;

    card.append(head, path, meta);
    el["session-list"].append(card);
  }
}

async function createSession() {
  const payload = {
    name: el["input-name"].value.trim(),
    working_dir: el["input-dir"].value.trim(),
    agent: el["select-agent"].value,
  };

  const res = await fetch("/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => ({}));
    alert(detail.detail || "Unable to create session");
    return;
  }

  const session = await res.json();
  el["dialog-new"].close();
  el["form-new"].reset();
  el["input-dir"].value = "/projects/";
  await loadSessions();
  state.sessions = [session, ...state.sessions.filter((s) => s.id !== session.id)];
  openSession(session.id);
}

async function deleteSession(session) {
  if (!window.confirm(`Delete session "${session.name}"? This removes its history.`)) {
    return;
  }
  const res = await fetch(`/sessions/${session.id}`, { method: "DELETE" });
  if (!res.ok) {
    alert("Unable to delete session");
    return;
  }
  if (state.current && state.current.id === session.id) {
    leaveSession();
    return;
  }
  await loadSessions();
}

/* ------------------------------------------------------------------ */
/* session view                                                        */
/* ------------------------------------------------------------------ */

function openSession(id) {
  const session = state.sessions.find((s) => s.id === id);
  if (!session) return;

  state.current = session;
  state.pendingApproval = null;
  state.agentBubble = null;

  el["view-list"].classList.add("hidden");
  el["view-session"].classList.remove("hidden");
  el["transcript"].replaceChildren();
  el["session-title"].textContent = session.name;
  el["session-dir"].textContent = session.working_dir;
  applyStatus(session.status);
  connectSocket();
}

function leaveSession() {
  disconnectSocket();
  state.current = null;
  state.pendingApproval = null;
  state.agentBubble = null;
  el["view-session"].classList.add("hidden");
  el["view-list"].classList.remove("hidden");
  loadSessions();
}

function applyStatus(status) {
  if (state.current) {
    state.current.status = status;
    const saved = state.sessions.find((s) => s.id === state.current.id);
    if (saved) saved.status = status;
  }

  const badge = el["session-status"];
  badge.className = `status-badge ${status}`;
  badge.textContent = status.replace("_", " ");

  const busy = status === "running" || status === "awaiting_approval";
  el["prompt-input"].disabled = busy;
  el["btn-send"].disabled = busy;
  el["btn-kill"].classList.toggle("hidden", !busy);

  const activity = el["activity"];
  if (status === "running") {
    activity.className = "activity working";
    activity.textContent = "Working…";
  } else if (status === "awaiting_approval") {
    activity.className = "activity awaiting";
    activity.textContent = "Waiting for your approval";
  } else {
    activity.className = "activity hidden";
    activity.textContent = "";
  }

  // If a turn ends while an approval card is still open, retire its buttons.
  if (status !== "awaiting_approval" && state.pendingApproval) {
    finalizeApproval(state.pendingApproval.el, null);
    state.pendingApproval = null;
  }
}

/* ------------------------------------------------------------------ */
/* websocket                                                           */
/* ------------------------------------------------------------------ */

function connectSocket() {
  disconnectSocket(false);
  if (!state.current) return;

  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${proto}://${window.location.host}/ws/sessions/${state.current.id}`);
  state.socket = socket;
  el["transcript"].replaceChildren();
  state.agentBubble = null;
  state.pendingApproval = null;

  socket.addEventListener("open", () => {
    state.reconnectAttempt = 0;
  });
  socket.addEventListener("message", (event) => handleMessage(JSON.parse(event.data)));
  socket.addEventListener("close", () => {
    if (state.socket !== socket || !state.current) return;
    const delay = Math.min(1000 * 2 ** state.reconnectAttempt, 10000);
    state.reconnectAttempt += 1;
    state.reconnectTimer = window.setTimeout(connectSocket, delay);
  });
}

function disconnectSocket(clearTimer = true) {
  if (clearTimer && state.reconnectTimer) {
    window.clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
  }
  if (state.socket) {
    const socket = state.socket;
    state.socket = null;
    socket.close();
  }
}

function handleMessage(msg) {
  switch (msg.type) {
    case "status":
      applyStatus(msg.status);
      return;
    case "input":
      state.agentBubble = null;
      addUserMessage(msg.text || "");
      return;
    case "output":
      addAgentOutput(msg.text || "");
      return;
    case "tool_use":
      state.agentBubble = null;
      addToolUse(msg.tool || "tool", msg.input || {});
      return;
    case "approval_request":
      state.agentBubble = null;
      addApprovalRequest(msg);
      return;
    case "approval_response":
      resolveApproval(msg.request_id, msg.behavior);
      return;
    case "done":
      state.agentBubble = null;
      return;
    case "error":
      state.agentBubble = null;
      addError(msg.message || "Unknown error");
      return;
    default:
      return;
  }
}

/* ------------------------------------------------------------------ */
/* transcript rendering                                                */
/* ------------------------------------------------------------------ */

function scrollToBottom() {
  const t = el["transcript"];
  t.scrollTop = t.scrollHeight;
}

function append(node) {
  el["transcript"].append(node);
  scrollToBottom();
}

function addUserMessage(text) {
  const msg = document.createElement("div");
  msg.className = "msg user";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  msg.append(bubble);
  append(msg);
}

function addAgentOutput(text) {
  if (!text) return;
  if (state.agentBubble) {
    state.agentBubble.append(document.createTextNode(text));
    scrollToBottom();
    return;
  }
  const msg = document.createElement("div");
  msg.className = "msg agent";
  const label = document.createElement("span");
  label.className = "msg-label";
  label.textContent = "Claude";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  msg.append(label, bubble);
  append(msg);
  state.agentBubble = bubble;
}

function addToolUse(tool, input) {
  const wrap = document.createElement("div");
  wrap.className = "tool";

  const head = document.createElement("button");
  head.type = "button";
  head.className = "tool-head";

  const caret = document.createElement("span");
  caret.className = "tool-caret";
  caret.textContent = "▸";
  const name = document.createElement("span");
  name.className = "tool-name";
  name.textContent = tool;
  const summary = document.createElement("span");
  summary.className = "tool-summary mono";
  summary.textContent = toolSummary(tool, input);

  head.append(caret, name, summary);

  const body = document.createElement("pre");
  body.className = "tool-body mono hidden";
  body.textContent = JSON.stringify(input, null, 2);

  head.addEventListener("click", () => {
    const open = body.classList.toggle("hidden");
    wrap.classList.toggle("open", !open);
  });

  wrap.append(head, body);
  append(wrap);
}

function addApprovalRequest(msg) {
  const card = document.createElement("div");
  card.className = "approval";
  card.dataset.requestId = msg.request_id || "";

  const head = document.createElement("div");
  head.className = "approval-head";
  const tag = document.createElement("span");
  tag.className = "approval-tag";
  tag.textContent = "Approval required";
  const tool = document.createElement("span");
  tool.className = "approval-tool";
  tool.textContent = msg.tool || "tool";
  head.append(tag, tool);

  const pre = document.createElement("pre");
  pre.className = "approval-input mono";
  pre.textContent = JSON.stringify(msg.input || {}, null, 2);

  const actions = document.createElement("div");
  actions.className = "approval-buttons";
  const deny = document.createElement("button");
  deny.type = "button";
  deny.className = "btn btn-ghost";
  deny.textContent = "Deny";
  deny.addEventListener("click", () => respondApproval(msg.request_id, "deny", card));
  const allow = document.createElement("button");
  allow.type = "button";
  allow.className = "btn btn-primary";
  allow.textContent = "Allow";
  allow.addEventListener("click", () => respondApproval(msg.request_id, "allow", card));
  actions.append(deny, allow);

  card.append(head, pre, actions);
  append(card);
  state.pendingApproval = { id: msg.request_id, el: card };
}

function respondApproval(id, behavior, card) {
  if (state.socket && state.socket.readyState === WebSocket.OPEN) {
    state.socket.send(JSON.stringify({ type: "approval_response", request_id: id, behavior }));
  }
  finalizeApproval(card, behavior);
  if (state.pendingApproval && state.pendingApproval.id === id) state.pendingApproval = null;
}

function resolveApproval(id, behavior) {
  const card = el["transcript"].querySelector(`.approval[data-request-id="${cssEscape(id)}"]`);
  if (card) finalizeApproval(card, behavior);
  if (state.pendingApproval && state.pendingApproval.id === id) state.pendingApproval = null;
}

function finalizeApproval(card, behavior) {
  if (!card || card.classList.contains("resolved")) return;
  card.classList.add("resolved");
  const actions = card.querySelector(".approval-buttons");
  if (!actions) return;
  const result = document.createElement("div");
  if (behavior === "allow") {
    result.className = "approval-result allowed";
    result.textContent = "✓ Allowed";
  } else if (behavior === "deny") {
    result.className = "approval-result denied";
    result.textContent = "✕ Denied";
  } else {
    result.className = "approval-result expired";
    result.textContent = "— No longer pending";
  }
  actions.replaceWith(result);
}

function addError(message) {
  const msg = document.createElement("div");
  msg.className = "error-msg";
  const label = document.createElement("span");
  label.className = "error-label";
  label.textContent = "Error";
  const text = document.createElement("div");
  text.className = "error-text";
  text.textContent = message;
  msg.append(label, text);
  append(msg);
}

/* ------------------------------------------------------------------ */
/* sending                                                             */
/* ------------------------------------------------------------------ */

function sendPrompt() {
  const text = el["prompt-input"].value.trim();
  if (!text || !state.socket || state.socket.readyState !== WebSocket.OPEN) return;
  if (el["prompt-input"].disabled) return;

  state.socket.send(JSON.stringify({ type: "input", text }));
  el["prompt-input"].value = "";
  el["prompt-input"].style.height = "auto";
}

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

function statusBadge(status) {
  const badge = document.createElement("span");
  badge.className = `status-badge ${status}`;
  badge.textContent = status.replace("_", " ");
  return badge;
}

function toolSummary(tool, input) {
  if (!input || typeof input !== "object") return "";
  const str = (...keys) => {
    for (const k of keys) if (typeof input[k] === "string") return input[k];
    return "";
  };
  switch (tool) {
    case "Bash": return input.command || "";
    case "Read": case "Write": case "Edit": case "MultiEdit":
      return str("file_path", "path");
    case "Glob": case "Grep": return str("pattern", "query");
    case "WebFetch": case "WebSearch": return str("url", "query");
    case "Task": return str("description");
    default: {
      const direct = str("command", "file_path", "path", "pattern", "query", "url", "description");
      if (direct) return direct;
      const firstString = Object.values(input).find((v) => typeof v === "string");
      return firstString || "";
    }
  }
}

function cssEscape(value) {
  if (window.CSS && CSS.escape) return CSS.escape(value);
  return String(value).replace(/["\\]/g, "\\$&");
}

function formatAgent(agent) {
  return agent === "claude-code" ? "Claude Code" : agent;
}

function formatTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(date);
}
