/* Aster tavern_helper compatible runtime (clean-room, API contract per JS-Slash-Runner docs).
 * Runs inside every card frame (message iframes and the hidden script engine). Talks to native through
 * the bounded Aster bridge: rpc (sync), rpcAsync (promise) and broadcast (cross-frame events). */
(function () {
  if (window.__TH_SHIM) return;
  window.__TH_SHIM = true;

  function findBridge() {
    try { if (window.Aster && window.Aster.rpc) return window.Aster; } catch (e) {}
    try { if (window.parent && window.parent.Aster && window.parent.Aster.rpc) return window.parent.Aster; } catch (e) {}
    return null;
  }
  var bridge = findBridge();
  var frameId = window.__TH_FRAME_ID || ('f' + Math.random().toString(36).slice(2));
  window.__TH_FRAME_ID = frameId;

  function rpc(op) {
    if (!bridge || !bridge.rpc) return null;
    try { return JSON.parse(bridge.rpc(JSON.stringify(op))); } catch (e) { return null; }
  }
  var pending = {}, callSeq = 0;
  function rpcAsync(op) {
    return new Promise(function (resolve, reject) {
      if (!bridge || !bridge.rpcAsync) { resolve(rpc(op)); return; }
      var id = frameId + ':' + (++callSeq);
      pending[id] = { resolve: resolve, reject: reject };
      try { bridge.rpcAsync(JSON.stringify(op), id); } catch (e) { delete pending[id]; reject(e); }
    });
  }
  function clone(o) { return o == null ? o : JSON.parse(JSON.stringify(o)); }

  /* ---- delivery from native: {t:'e', name, args} events, {t:'r', id, result, error} replies ---- */
  var ready = false, queue = [];
  window.__thReceive = function (payload) {
    if (!ready) { queue.push(payload); return; }
    try {
      if (payload.t === 'r') {
        var p = pending[payload.id];
        if (!p) return;
        delete pending[payload.id];
        if (payload.error) p.reject(new Error(payload.error)); else p.resolve(payload.result);
      } else if (payload.t === 'e') {
        if (payload.from && payload.from === frameId) return;
        emitLocal(payload.name, payload.args || []);
      }
    } catch (e) { console.error(e); }
  };
  window.__thNativeEvent = function (name, payload) { window.__thReceive({ t: 'e', name: name, args: [payload] }); };

  /* ---- storage shims (native domStorage is disabled in the sandbox) ---- */
  function makeStorage(persistent) {
    var mem = {};
    function all() { if (!persistent) return mem; var r = rpc({ op: 'storage.all' }); return (r && r.values) || {}; }
    return {
      getItem: function (k) {
        if (persistent) { var r = rpc({ op: 'storage.get', key: String(k) }); return r ? r.value : null; }
        return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null;
      },
      setItem: function (k, v) { if (persistent) rpc({ op: 'storage.set', key: String(k), value: String(v) }); else mem[k] = String(v); },
      removeItem: function (k) { if (persistent) rpc({ op: 'storage.remove', key: String(k) }); else delete mem[k]; },
      clear: function () { if (persistent) rpc({ op: 'storage.clear' }); else mem = {}; },
      key: function (i) { var ks = Object.keys(all()); return ks[i] == null ? null : ks[i]; },
      get length() { return Object.keys(all()).length; }
    };
  }
  try { Object.defineProperty(window, 'localStorage', { value: makeStorage(true), configurable: true }); } catch (e) {}
  try { Object.defineProperty(window, 'sessionStorage', { value: makeStorage(false), configurable: true }); } catch (e) {}

  /* ---- event bus: local listeners + cross-frame fan-out through native ---- */
  var listeners = {};
  function emitLocal(type, args) {
    var fns = (listeners[type] || []).slice();
    var results = [];
    fns.forEach(function (fn) {
      try {
        var r = fn.apply(window, args);
        if (r && typeof r.then === 'function') { r.catch(function (e) { console.error(e); }); results.push(r); }
      } catch (e) { console.error(e); }
    });
    return Promise.all(results);
  }
  function broadcast(type, args) {
    if (!bridge || !bridge.broadcast) return;
    try { bridge.broadcast(JSON.stringify({ type: type, args: clone(args) || [], from: frameId })); } catch (e) {}
  }
  window.eventOn = function (type, fn) {
    var list = (listeners[type] = listeners[type] || []);
    if (list.indexOf(fn) < 0) list.push(fn);
    return { stop: function () { window.eventRemoveListener(type, fn); } };
  };
  window.eventMakeLast = window.eventOn;
  window.eventMakeFirst = function (type, fn) {
    var list = (listeners[type] = listeners[type] || []);
    var i = list.indexOf(fn); if (i >= 0) list.splice(i, 1);
    list.unshift(fn);
    return { stop: function () { window.eventRemoveListener(type, fn); } };
  };
  window.eventOnce = function (type, fn) {
    var wrap = function () { window.eventRemoveListener(type, wrap); return fn.apply(this, arguments); };
    return window.eventOn(type, wrap);
  };
  window.eventEmit = function (type) {
    var args = Array.prototype.slice.call(arguments, 1);
    var done = emitLocal(type, args);
    broadcast(type, args);
    return done.then(function () {});
  };
  window.eventEmitAndWait = window.eventEmit;
  window.eventRemoveListener = function (type, fn) {
    if (typeof type === 'function') { fn = type; Object.keys(listeners).forEach(function (k) { listeners[k] = listeners[k].filter(function (f) { return f !== fn; }); }); return; }
    if (listeners[type]) listeners[type] = listeners[type].filter(function (f) { return f !== fn; });
  };
  window.eventClearEvent = function (type) { delete listeners[type]; };
  window.eventClearListener = function (fn) { window.eventRemoveListener(fn); };
  window.eventClearAll = function () { listeners = {}; };

  window.tavern_events = {
    APP_READY: 'app_ready', MESSAGE_SWIPED: 'message_swiped', MESSAGE_SENT: 'message_sent',
    MESSAGE_RECEIVED: 'message_received', MESSAGE_EDITED: 'message_edited', MESSAGE_DELETED: 'message_deleted',
    MESSAGE_UPDATED: 'message_updated', MESSAGE_SWIPE_DELETED: 'message_swipe_deleted',
    IMPERSONATE_READY: 'impersonate_ready', CHAT_CHANGED: 'chat_id_changed', CHAT_CREATED: 'chat_created',
    GENERATION_STARTED: 'generation_started', GENERATION_STOPPED: 'generation_stopped', GENERATION_ENDED: 'generation_ended',
    GENERATION_AFTER_COMMANDS: 'GENERATION_AFTER_COMMANDS', SETTINGS_UPDATED: 'settings_updated',
    WORLDINFO_UPDATED: 'worldinfo_updated', WORLD_INFO_ACTIVATED: 'world_info_activated',
    USER_MESSAGE_RENDERED: 'user_message_rendered', CHARACTER_MESSAGE_RENDERED: 'character_message_rendered',
    CHARACTER_FIRST_MESSAGE_SELECTED: 'character_first_message_selected',
    STREAM_TOKEN_RECEIVED: 'stream_token_received', SMOOTH_STREAM_TOKEN_RECEIVED: 'stream_token_received',
    CHAT_COMPLETION_PROMPT_READY: 'chat_completion_prompt_ready', GENERATE_AFTER_DATA: 'generate_after_data'
  };
  window.iframe_events = {
    MESSAGE_IFRAME_RENDER_STARTED: 'message_iframe_render_started', MESSAGE_IFRAME_RENDER_ENDED: 'message_iframe_render_ended',
    GENERATION_REQUESTED: 'js_generation_requested', GENERATION_STARTED: 'js_generation_started',
    STREAM_TOKEN_RECEIVED_FULLY: 'js_stream_token_received_fully', STREAM_TOKEN_RECEIVED_INCREMENTALLY: 'js_stream_token_received_incrementally',
    GENERATION_ENDED: 'js_generation_ended'
  };

  /* ---- identity ---- */
  window.getIframeName = function () { return window.name || ''; };
  window.getScriptId = function () { return window.__TH_SCRIPT_ID || ''; };
  window.getScriptName = function () { return window.__TH_SCRIPT_NAME || ''; };
  window.getLastMessageId = function () { var r = rpc({ op: 'chat.lastId' }); return r ? r.id : -1; };
  window.getCurrentMessageId = function () { return window.__TH_MESSAGE_ID != null ? window.__TH_MESSAGE_ID : window.getLastMessageId(); };
  window.getMessageId = function (name) { var m = /^TH-message--(\d+)/.exec(String(name || '')); return m ? Number(m[1]) : -1; };

  /* ---- variables ---- */
  function opt(o) {
    var r = Object.assign({ type: 'chat' }, o || {});
    if (r.type === 'message' && r.message_id == null) r.message_id = window.__TH_MESSAGE_ID != null ? window.__TH_MESSAGE_ID : -1;
    if (r.type === 'script' && !r.script_id) r.script_id = window.getScriptId();
    return r;
  }
  window.getVariables = function (o) { var r = rpc({ op: 'vars.get', option: opt(o) }); return r && r.variables ? r.variables : {}; };
  window.getAllVariables = function () {
    var r = rpc({ op: 'vars.all', message_id: window.__TH_MESSAGE_ID != null ? window.__TH_MESSAGE_ID : null, script_id: window.getScriptId() });
    return r && r.variables ? r.variables : {};
  };
  window.replaceVariables = function (v, o) { rpc({ op: 'vars.replace', variables: clone(v) || {}, option: opt(o) }); };
  window.updateVariablesWith = function (fn, o) {
    var data = window.getVariables(o);
    var r = fn(data);
    var apply = function (res) { var next = res === undefined ? data : res; window.replaceVariables(next, o); return next; };
    return (r && typeof r.then === 'function') ? r.then(apply) : apply(r);
  };
  window.insertOrAssignVariables = function (v, o) { return window.updateVariablesWith(function (d) { return _.merge(d, v); }, o); };
  window.insertVariables = function (v, o) { return window.updateVariablesWith(function (d) { return _.defaultsDeep(d, v); }, o); };
  window.deleteVariable = function (path, o) {
    var deleted = false;
    window.updateVariablesWith(function (d) { deleted = _.unset(d, path); return d; }, o);
    return { variables: window.getVariables(o), delete_occurred: deleted };
  };
  window.registerVariableSchema = function () {};

  /* ---- chat messages (ST shape) ---- */
  window.getChatMessages = function (range, o) {
    o = o || {};
    var r = rpc({ op: 'chat.get', range: range == null ? '0-{{lastMessageId}}' : String(range), role: o.role || 'all',
      hide_state: o.hide_state || 'all', include_swipes: !!(o.include_swipes || o.include_swipe) });
    return r && r.messages ? r.messages : [];
  };
  window.setChatMessages = function (items, o) {
    return rpcAsync({ op: 'chat.set', items: clone(items) || [], refresh: (o && o.refresh) || 'affected' }).then(function () {});
  };
  window.setChatMessage = function (fieldValues, id, o) {
    var item = { message_id: Number(id) };
    if (typeof fieldValues === 'string') item.message = fieldValues;
    else if (fieldValues) { if (fieldValues.message != null) item.message = fieldValues.message; if (fieldValues.data) item.data = fieldValues.data; }
    if (o && o.swipe_id != null && o.swipe_id !== 'current') item.swipe_id = Number(o.swipe_id);
    return window.setChatMessages([item], o);
  };
  window.createChatMessages = function (items, o) {
    return rpcAsync({ op: 'chat.create', items: clone(items) || [], insert_before: o && o.insert_before != null && o.insert_before !== 'end' ? Number(o.insert_before) : null }).then(function () {});
  };
  window.deleteChatMessages = function (ids) { return rpcAsync({ op: 'chat.delete', ids: (ids || []).map(Number) }).then(function () {}); };
  window.rotateChatMessages = function () { return Promise.resolve(); };
  window.refreshOneMessage = function () { return Promise.resolve(); };
  window.formatAsDisplayedMessage = function (text) { return String(text); };
  window.retrieveDisplayedMessage = function () { return window.jQuery ? window.jQuery(document.body) : null; };

  /* ---- slash commands & generation ---- */
  window.triggerSlash = function (cmd) { return rpcAsync({ op: 'slash', command: String(cmd) }).then(function (r) { return r == null ? '' : String(r); }); };
  window.triggerSlashWithResult = window.triggerSlash;
  function generateWith(config, raw) {
    var id = (config && config.generation_id) || ('gen-' + (++callSeq));
    emitLocal('js_generation_started', [id]);
    return rpcAsync({ op: 'generate', raw: !!raw, config: clone(config) || {} }).then(function (text) {
      text = text == null ? '' : String(text);
      emitLocal('js_stream_token_received_fully', [text, id]);
      emitLocal('js_generation_ended', [text, id]);
      return text;
    });
  }
  window.generate = function (config) { return generateWith(config, false); };
  window.generateRaw = function (config) { return generateWith(config, true); };
  window.stopGenerationById = function () { return false; };
  window.stopAllGeneration = function () { return false; };

  /* ---- context / misc ---- */
  window.substitudeMacros = function (t) { var r = rpc({ op: 'macros', text: String(t) }); return r ? r.text : String(t); };
  window.getCharData = function () { var r = rpc({ op: 'char.get' }); return r ? r.card : null; };
  window.getCharAvatarPath = function () { return ''; };
  window.getCurrentCharacterName = function () { var c = rpc({ op: 'context' }); return c ? c.name2 : null; };
  window.getWorldbook = function () { var r = rpc({ op: 'world.entries' }); return Promise.resolve(r && r.entries ? r.entries : []); };
  window.getLorebookEntries = window.getWorldbook;
  window.getCharWorldbookNames = function () { return { primary: 'card', additional: [] }; };
  window.getChatWorldbookName = function () { return null; };
  window.getTavernHelperVersion = function () { return '4.0.0'; };
  window.getFrontendVersion = window.getTavernHelperVersion;
  window.errorCatched = function (fn) { return function () { try { return fn.apply(this, arguments); } catch (e) { console.error(e); } }; };
  window.reloadIframe = function () { location.reload(); };
  var globals = {};
  window.initializeGlobal = function (name, value) { globals[name] = value; window[name] = value; };
  window.waitGlobalInitialized = function (name) {
    return new Promise(function (res) {
      if (window[name] || globals[name]) return res(window[name] || globals[name]);
      var n = 0;
      var iv = setInterval(function () { if (window[name] || ++n > 200) { clearInterval(iv); res(window[name]); } }, 50);
    });
  };
  function toast(kind) { return function (msg, title) { rpc({ op: 'toast', text: [title, msg].filter(Boolean).join('：'), kind: kind }); }; }
  window.toastr = { info: toast('info'), success: toast('success'), warning: toast('warning'), error: toast('error'), clear: function () {}, remove: function () {}, options: {} };
  var context = function () {
    var c = rpc({ op: 'context' }) || {};
    c.chat = window.getChatMessages('0-{{lastMessageId}}').map(function (m) { return { name: m.name, is_user: m.role === 'user', mes: m.message, swipe_id: m.swipe_id }; });
    c.getContext = context;
    c.eventSource = { on: window.eventOn, once: window.eventOnce, emit: window.eventEmit, removeListener: window.eventRemoveListener };
    c.event_types = window.tavern_events;
    c.substituteParams = window.substitudeMacros;
    c.executeSlashCommandsWithOptions = function (cmd) { return window.triggerSlash(cmd).then(function (pipe) { return { pipe: pipe }; }); };
    c.generate = window.generate; c.generateRaw = window.generateRaw;
    return c;
  };
  try { Object.defineProperty(window, 'SillyTavern', { get: context, configurable: true }); } catch (e) {}
  if (window.jsyaml && !window.YAML) window.YAML = { parse: function (t) { return window.jsyaml.load(t); }, stringify: function (v) { return window.jsyaml.dump(v); } };
  if (window.Zod && !window.z) window.z = window.Zod.z || window.Zod;
  window.__VUE_PROD_DEVTOOLS__ = false;
  window.__VUE_OPTIONS_API__ = true;

  /* ---- script buttons ---- */
  function stringHash(str) {
    var h1 = 0xdeadbeef, h2 = 0x41c6ce57;
    for (var i = 0, ch; i < str.length; i++) { ch = str.charCodeAt(i); h1 = Math.imul(h1 ^ ch, 2654435761); h2 = Math.imul(h2 ^ ch, 1597334677); }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return 4294967296 * (2097151 & h2) + (h1 >>> 0);
  }
  window.getButtonEvent = function (name) { return window.getScriptId() + '_' + stringHash(String(name)); };
  window.eventOnButton = function (name, fn) { return window.eventOn(window.getButtonEvent(name), fn); };
  window.getScriptButtons = function (sid) { var r = rpc({ op: 'buttons.get', script_id: sid || window.getScriptId() }); return r && r.buttons ? r.buttons : []; };
  window.replaceScriptButtons = function (a, b) {
    var sid = typeof a === 'string' ? a : window.getScriptId(); var list = typeof a === 'string' ? b : a;
    rpc({ op: 'buttons.set', script_id: sid, buttons: clone(list) || [] });
  };
  window.updateScriptButtonsWith = function (a, b) {
    var sid = typeof a === 'string' ? a : window.getScriptId(); var fn = typeof a === 'string' ? b : a;
    var next = fn(window.getScriptButtons(sid));
    window.replaceScriptButtons(sid, next);
    return next;
  };
  window.appendInexistentScriptButtons = function (a, b) {
    var sid = typeof a === 'string' ? a : window.getScriptId(); var list = typeof a === 'string' ? b : a;
    var cur = window.getScriptButtons(sid);
    (list || []).forEach(function (btn) { if (!cur.some(function (c) { return c.name === btn.name; })) cur.push(btn); });
    window.replaceScriptButtons(sid, cur);
  };

  /* ---- MVU facade (native MagVarUpdate core does the parsing and floor writes) ---- */
  window.Mvu = {
    events: {
      VARIABLE_INITIALIZED: 'mag_variable_initialized', VARIABLE_UPDATE_STARTED: 'mag_variable_update_started',
      COMMAND_PARSED: 'mag_command_parsed', VARIABLE_UPDATE_ENDED: 'mag_variable_update_ended',
      BEFORE_MESSAGE_UPDATE: 'mag_before_message_update'
    },
    getMvuData: function (o) { return window.getVariables(Object.assign({ type: 'message' }, o || {})); },
    replaceMvuData: function (d, o) { window.replaceVariables(d, Object.assign({ type: 'message' }, o || {})); return Promise.resolve(); },
    getMvuVariable: function (data, path, o) { var v = _.get(data && data.stat_data, path); if (Array.isArray(v) && v.length === 2 && typeof v[1] === 'string') v = v[0]; return v === undefined && o ? o.default_value : v; },
    setMvuVariable: function (data, path, value) { var cur = _.get(data.stat_data, path); if (Array.isArray(cur) && cur.length === 2 && typeof cur[1] === 'string') cur[0] = value; else _.set(data.stat_data, path, value); return Promise.resolve(true); },
    parseMessage: function (message, old) {
      var r = rpc({ op: 'mvu.parse', message: String(message), old: clone(old) || {} });
      return Promise.resolve(r && r.changed ? r.data : undefined);
    },
    isDuringExtraAnalysis: function () { return false; },
    reloadInitVar: function () { return Promise.resolve(false); }
  };
  globals.Mvu = window.Mvu;

  /* ---- ready handshake: flush queued native deliveries once our listeners can attach ---- */
  function markReady() {
    if (ready) return;
    ready = true;
    var q = queue; queue = [];
    q.forEach(function (p) { window.__thReceive(p); });
  }
  /* Collapsible sections start closed, even when card scripts open them during their first render. */
  function closeDetails() {
    try { document.querySelectorAll('details[open]').forEach(function (d) { if (!d.hasAttribute('data-keep-open')) d.removeAttribute('open'); }); } catch (e) {}
  }
  window.addEventListener('load', function () { closeDetails(); setTimeout(closeDetails, 50); });
  if (document.readyState === 'complete' || document.readyState === 'interactive') setTimeout(markReady, 0);
  else document.addEventListener('DOMContentLoaded', function () { setTimeout(markReady, 0); });
  window.addEventListener('pagehide', function () { window.eventClearAll(); });
})();
