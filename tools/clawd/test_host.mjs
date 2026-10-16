/* clawd_host.html 全链路 Node 模拟：假 DOM + 假 rAF，跑 init() 验证
   小爪子三只 + 大 Clawd 按钮都建出来、动作 CSS 注入正常。
   用法：node tools/clawd/test_host.mjs */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(HERE, '../../app/src/main/assets/clawd/clawd_host.html'), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);

/* ── 极简假 DOM ── */
class StyleDecl {
  constructor() { this._props = {}; this._important = {}; }
  setProperty(k, v, imp) { this._props[k] = v; this._important[k] = !!imp; }
  getPropertyValue(k) { return this._props[k] ?? ''; }
  getPropertyPriority(k) { return this._important[k] ? 'important' : ''; }
  removeProperty(k) { const v = this._props[k]; delete this._props[k]; return v ?? ''; }
}
class El {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.style = new StyleDecl();
    this.dataset = {};
    this.attributes = {};
    this._cls = new Set();
    this.parentElement = null;
    this._anims = [];
  }
  get classList() {
    const self = this;
    return {
      add: (...c) => c.forEach(x => self._cls.add(x)),
      remove: (...c) => c.forEach(x => self._cls.delete(x)),
      contains: c => self._cls.has(c),
      toggle: (c, on) => { on === undefined ? (self._cls.has(c) ? self._cls.delete(c) : self._cls.add(c)) : on ? self._cls.add(c) : self._cls.delete(c); },
    };
  }
  get className() { return [...this._cls].join(' '); }
  set className(v) { this._cls = new Set(v.split(/\s+/).filter(Boolean)); }
  append(...els) { for (const e of els) { if (e && e.parentElement) e.parentElement.children = e.parentElement.children.filter(x => x !== e); e.parentElement = this; this.children.push(e); } }
  appendChild(e) { this.append(e); return e; }
  remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(x => x !== this); this.parentElement = null; }
  setAttribute(k, v) { this.attributes[k] = String(v); }
  getAttribute(k) { return this.attributes[k] ?? null; }
  removeAttribute(k) { delete this.attributes[k]; }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  querySelectorAll(sel) {
    const out = [];
    const walk = n => { for (const c of n.children) { if (match(c, sel)) out.push(c); walk(c); } };
    walk(this);
    return out;
  }
  get isConnected() { let n = this; while (n.parentElement) n = n.parentElement; return n === body; }
  contains(n) { let c = n; while (c) { if (c === this) return true; c = c.parentElement; } return false; }
  getBoundingClientRect() { return { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 }; }
  getAnimations() { return this._anims; }
  animate(frames, opts) { const a = { cancel() {}, playState: 'running', currentTime: 0, playbackRate: 1 }; this._anims.push(a); return a; }
  dispatchEvent(e) { return true; }
  addEventListener() {}
  removeEventListener() {}
  focus() {}
  blur() {}
}
function match(el, sel) {
  // 只支持本宿主用到的简单选择器；tag.class 复合 token 拆开逐个判
  const parts = sel.split(',').map(s => s.trim()).filter(Boolean);
  return parts.some(p => {
    const simple = p.replace(/:scope\s*>\s*/, '').split(/[ >]+/).filter(Boolean);
    let ok = true;
    for (const tok of simple) {
      // 复合 token：开头的 tag 名 + 后面的 .class / #id / [attr]
      const m = tok.match(/^([a-zA-Z][\w-]*)((?:[.#[][\w="'\]-]+)*)$/);
      if (m) {
        const [, tag, rest] = m;
        if (tag !== el.tagName.toLowerCase()) return false;
        for (const sub of rest.match(/[.#[][^.#[]+/g) || []) {
          if (sub.startsWith('.')) { if (!el.classList.contains(sub.slice(1))) return false; }
          else if (sub.startsWith('#')) { if (el.attributes.id !== sub.slice(1)) return false; }
          else if (sub.startsWith('[')) {
            const am = sub.match(/\[(\w+)(?:="([^"]*)")?\]/);
            if (!am) return false;
            const v = el.attributes[am[1]];
            if (am[2] === undefined ? v === undefined : v !== am[2]) return false;
          } else return false;
        }
        continue;
      }
      if (tok.startsWith('.')) { ok = el.classList.contains(tok.slice(1)); if (!ok) return false; continue; }
      if (tok.startsWith('#')) { ok = el.attributes.id === tok.slice(1); if (!ok) return false; continue; }
      if (tok.startsWith('[')) { const am = tok.match(/\[(\w+)(?:="([^"]*)")?\]/); if (!am) return false; const v = el.attributes[am[1]]; ok = am[2] === undefined ? v !== undefined : v === am[2]; if (!ok) return false; continue; }
      ok = false; return false;
    }
    return ok;
  });
}
const body = new El('body');
body.parentElement = null;
const docEl = new El('html');
const head = new El('head');
body.append(head);
const document = {
  documentElement: docEl,
  head, body,
  readyState: 'complete',
  activeElement: null,
  hidden: false,
  visibilityState: 'visible',
  getElementById: id => (id === 'send_form' || id === 'sheld' || id === 'send_textarea' ? byId[id] : null),
  createElement: tag => new El(tag),
  createTextNode: text => ({ text, isConnected: false }),
  querySelector: sel => docQuery(document, sel)[0] || null,
  querySelectorAll: sel => docQuery(document, sel),
  addEventListener() {}, removeEventListener() {},
};
const byId = {
  send_form: (() => { const e = new El('div'); e.attributes.id = 'send_form'; return e; })(),
  sheld: (() => { const e = new El('div'); e.attributes.id = 'sheld'; return e; })(),
  send_textarea: (() => { const e = new El('textarea'); e.attributes.id = 'send_textarea'; e.value = ''; return e; })(),
};
body.append(byId.send_form, byId.sheld, byId.send_textarea);
// chat mes_text
const chat = new El('div'); chat.attributes.id = 'chat';
const mes = new El('div'); mes.className = 'mes last_mes'; mes.setAttribute('is_user', 'false');
const mesText = new El('div'); mesText.className = 'mes_text';
mes.append(mesText); chat.append(mes); byId.sheld.append(chat);

function docQuery(root, sel) {
  const pools = [body, docEl];
  const out = [];
  for (const pool of pools) {
    const walk = n => { for (const c of n.children) { if (match(c, sel)) out.push(c); walk(c); } };
    walk(pool);
  }
  return out;
}

/* ── 假 window ── */
const timers = [];
let rafId = 0;
const window = {
  devicePixelRatio: 2.75,
  innerWidth: 1080, innerHeight: 2200,
  requestAnimationFrame: fn => ++rafId,
  cancelAnimationFrame() {},
  setTimeout: (fn, ms) => { timers.push(fn); return timers.length; },
  clearTimeout() {},
  setInterval: (fn, ms) => { timers.push(fn); return timers.length; },
  clearInterval() {},
  requestIdleCallback: null,
  matchMedia: () => ({ matches: false }),
  addEventListener() {},
  performance: { now: () => Date.now() },
  MutationObserver: class { observe() {} disconnect() {} },
  InputEvent: class { constructor(t, o) { this.type = t; Object.assign(this, o); } },
  Event: class { constructor(t, o) { this.type = t; Object.assign(this, o); } },
  localStorage: { getItem: () => null, setItem() {} },
  getComputedStyle: () => ({ opacity: '1', boxShadow: 'none', left: '0px', top: '0px', zIndex: 'auto', transform: 'none', transformOrigin: '0px 0px' }),
  AsterClawd: { pushRects() {} },
};

/* ── 跑宿主脚本 ── */
const logs = [];
const capture = { log: (...a) => logs.push('log: ' + a.join(' ')), error: (...a) => logs.push('error: ' + a.join(' ')) };
const sandboxCode = scripts.join('\n;\n');
const out = (...a) => process.stdout.write(a.join(' ') + '\n');
try {
  const fn = new Function('window', 'document', 'body', 'console', `
    ${sandboxCode}
    return {
      layoutForm: window.__clawdLayout,
      layoutChat: window.__clawdChat,
      syncInput: window.__clawdInput,
      syncGen: window.__clawdGen,
      syncLastText: window.__clawdLastText,
      rects: () => window.__clawdRects(),
      night: window.__clawdNight,
      doc,
    };
  `);
  const api = fn(window, document, body, capture);
  // 布局同步
  api.layoutForm(0, 2000, 1080, 200);
  api.layoutChat(0, 0, 1080, 2000);
  api.syncInput('你好！', true);
  api.syncGen(true, false, '');
  api.syncLastText('hello');
  api.night(true);
  const rects = JSON.parse(api.rects());
  const pileCount = document.querySelectorAll('.clawd-pile-c').length;
  const rigCount = document.querySelectorAll('.clawd-rig').length;
  const styleEl = document.querySelectorAll('style').length;
  // dump send_form 子树
  const dump = (n, d = 0) => {
    out('  '.repeat(d) + `<${n.tagName.toLowerCase()} class="${n.className}" id="${n.attributes.id || ''}">`);
    for (const c of n.children) dump(c, d + 1);
  };
  out('── send_form 子树 ──');
  dump(document.getElementById('send_form'));
  out('按钮查询:', document.querySelectorAll('button.clawd-signoff-button').length,
      '| .clawd-composer-clawd:', document.querySelectorAll('.clawd-composer-clawd').length);
  out('小爪子:', pileCount, '| rig:', rigCount, '| style 节点:', styleEl, '| rects:', rects.length);
  const errs = logs.filter(l => l.startsWith('error'));
  out(errs.join('\n') || '(无错误)');
  out(logs.filter(l => l.startsWith('log')).join('\n'));
  if (pileCount !== 3) { out('FAIL: 小爪子不是 3 只'); process.exit(1); }
  if (rigCount !== 1) { out('FAIL: 大 Clawd rig 没建出来'); process.exit(1); }
  out('PASS');
} catch (e) {
  out('异常:', e.message, '\n', (e.stack || '').split('\n').slice(0, 6).join('\n'));
  process.exit(1);
}
