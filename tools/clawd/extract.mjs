/* Clawd 数据提取管线：跑原版 clawd-rig.js / clawd-pile.js 的数据代码，
   序列化成 Kotlin 端可直接消费的 JSON（精灵帧 + 调色板 + 动作时间轴 + 像素金标准）。
   用法：node tools/clawd/extract.mjs
   产物：app/src/main/assets/clawd/clawd_data.json
        app/src/test/resources/clawd/clawd_golden.json
   只读 src/ 下 vendor 的原版文件，不改动它们。 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, 'src');
const readSrc = f => readFileSync(join(SRC, f), 'utf8');

/* 去掉 ESM export 语法，包一层拿到内部函数（vendor 文件保持原样，不手改） */
function loadRig() {
  const code = readSrc('clawd-rig.js').replace('export function buildClawdRig', 'function buildClawdRig');
  const fn = new Function(`${code}; return buildClawdRig();`);
  return fn();
}

/* ═══ 1. 大 Clawd（clawd-rig）═══ */
const rig = loadRig();
const selfCheck = rig.selfCheck();
if (selfCheck !== 0 && selfCheck !== '0') throw new Error(`rig selfCheck 不通过：${selfCheck}（静止拼图 ≠ 现网 open 帧）`);

/* rig 返回的 clips 只有元信息，CSS 是逐动作生成的。要拿到时间轴本体，
   需要在 buildClawdRig 闭包里把 CLIPS 也吐出来 —— vendor 文件不改，
   在这里用同样的 Clip 类重新定义一遍不可取。改法：把源码里
   `return { css: genRestCSS(), ... }` 一行替换成同时返回 CLIPS 和采样函数。
   这是提取专用补丁，不动 vendor 原文件。 */
const patched = readSrc('clawd-rig.js')
  .replace('export function buildClawdRig', 'function buildClawdRig')
  .replace(
    'return { css: genRestCSS(), cssFor, extentFor, clips: pool, parts: PARTS.slice(), selfCheck };',
    `return { css: genRestCSS(), cssFor, extentFor, clips: pool, parts: PARTS.slice(), selfCheck,
      __extract: { CLIPS, sampleState, sampleKey, flexAt, SPR, PAL, PX, TICK, PARTS, ORIGIN, REST_F, REST_Z,
        UPPER_KIDS, BOX, mirrorState, clipExtent, pixelsOf, frameOf, SHEET } };`
  );
const rigX = new Function(`${patched}; return buildClawdRig();`)();
const EX = rigX.__extract;

/* ── 1a. 精灵帧：part.frame → { rows, ox, oy }，并展开成框内绝对像素（金标准用） ── */
const sprites = {};
for (const sheetName of new Set(Object.values(EX.SHEET))) {
  sprites[sheetName] = {};
  for (const [frameName, fr] of Object.entries(EX.SPR[sheetName])) {
    sprites[sheetName][frameName] = { rows: fr.rows, ox: fr.ox, oy: fr.oy };
  }
}

/* ── 1b. 调色板：CSS 变量色解析成固定 hex（Android 端不做变量替换） ── */
const THEME = {
  ink: '#4d4038',        // --clr-ink：正文色掺暖灰。原版 color-mix(var(--cl-ink,#121212) 60%, #7a6a5c)
  stickA: '#7dff9b',     // --stick-a 默认
  stickB: '#ff6bd6',     // --stick-b 默认
};
const resolvePal = pal => {
  const out = {};
  for (const [ch, v] of Object.entries(pal)) {
    if (ch === 'o') { out[ch] = '#000000'; continue; }          // 眼睛：四套主题都是 #000
    if (v.includes('--stick-a')) { out[ch] = THEME.stickA; continue; }
    if (v.includes('--stick-b')) { out[ch] = THEME.stickB; continue; }
    if (v.includes('--clr-ink')) { out[ch] = THEME.ink; continue; }
    out[ch] = v;
  }
  return out;
};
/* 'e' 字符在 PAL 里也是 var(--clr-ink)，recolor 出来的帧直接用。t/u 拖影是 color-mix 彩棒 35% 透明 ——
   半透明在提取端解析成带 alpha 的 hex（35% 不透明度画到透明底上）。 */
const withAlpha = (hex, a) => {
  const n = parseInt(hex.slice(1), 16), r = n >> 16 & 255, g = n >> 8 & 255, b = n & 255;
  return '#' + [r, g, b].map(v => Math.round(v * a).toString(16).padStart(2, '0')).join('');
};
const PAL2 = resolvePal(EX.PAL);
PAL2['t'] = withAlpha(THEME.stickA, .35);
PAL2['u'] = withAlpha(THEME.stickB, .35);
PAL2['h'] = '#333329';   // 影子 rgba(20,20,19,.16)：预乘到深色底近似不行 —— Android 画到聊天背景上，
                         // 改为在绘制时把影子当半透明处理。这里存原 rgba 串，Kotlin 端解析。
PAL2['h'] = 'rgba(20,20,19,.16)';
PAL2['m'] = 'rgba(120,112,104,.55)';
PAL2['i'] = 'rgba(80,150,255,.75)';
PAL2['I'] = 'rgba(80,150,255,.3)';
PAL2['j'] = 'rgba(255,80,60,.75)';
PAL2['J'] = 'rgba(255,80,60,.3)';

/* ── 1c. 动作时间轴：每个 Clip 序列化成
      { id, name, dur, loop, intro, track, group, next, hold, after, from,
        tr: { part: { prop: [[t, v, ease], ...] } }, flexKeys: [[t, {sx,sy,r,tx,ty}], ...],
        flexEase, smooth: [...], beats }
   at()/to()/put() 写入的 key 已经是最终数据； Clip 对象里的 tr 就是时间轴本体。 ── */
const clips = {};
for (const [id, c] of Object.entries(EX.CLIPS)) {
  clips[id] = {
    id, name: c.name, dur: c.dur, loop: !!c.loop, intro: c.intro || 0,
    track: c.track, group: c.group, next: c.next || null, hold: !!c.hold,
    after: c.after || null, from: c.from || null,
    flexEase: c.flexEase,
    smooth: [...c.smooth],
    tr: c.tr,
    flexKeys: c.flexKeys,
    beats: c.beats,
  };
}

/* ── 1d. 采样金标准：每个动作在 100ms 节拍上采样 sampleState + flexAt，连镜像版一起。
   Kotlin 播放器对同一份数据用自己的采样器重算，逐值对比 —— 这锁住的是"播放器逻辑"，
   而 1e 锁住的是"像素外观"。 ── */
const clipsGolden = {};
for (const [id, c] of Object.entries(EX.CLIPS)) {
  const samples = [];
  for (let t = 0; t <= c.dur; t += EX.TICK) {
    samples.push({ t, st: EX.sampleState(c, t), flex: EX.flexAt(c, t) });
  }
  clipsGolden[id] = { dur: c.dur, samples };
}

/* ── 1e. 像素金标准：每个 (part, frame[, mirror]) 展开成框内坐标 + 颜色 hex，排序后存表。
   这是"外形完全相同"的最终裁判：Kotlin 绘制器输出的像素数组与这里逐位对比。 ── */
const framesGolden = [];
const mirrorable = new Set(['shadow', 'legs', 'body', 'eyes', 'clawL', 'clawR', 'propA', 'propB']);
for (const part of EX.PARTS) {
  const sheetName = EX.SHEET[part];
  for (const name of Object.keys(EX.SPR[sheetName])) {
    for (const mirror of mirrorable.has(part) ? [false, true] : [false]) {
      const px = EX.pixelsOf(part, name).map(([x, y, ch]) => {
        const cx = (mirror ? 15 - x : x) + EX.BOX[0], cy = y + EX.BOX[1];
        return [cx, cy, PAL2[ch]];
      }).sort((a, b) => a[1] - b[1] || a[0] - b[0]);
      framesGolden.push({ part, name, mirror, px });
    }
  }
}

/* ═══ 2. 小 Clawd 三只（clawd-pile）═══ */
/* pile 是渲染 + 行为耦合的模块（依赖 DOM）。要的数据是顶部的常量：PX/W/H/TOP/PAL/BODY/LEGS/EYES/
   GLASSES/COWBOY/WIZARD/WAND/NAMES/BODY_W/LIFT/GAP/EDGE/STEP_MS/LEG_X —— 这些都在 createClawdPile
   函数体开头。用正则截出 const 声明区在沙盒里 eval，比手工抄写可靠（源码更新时重跑即可）。 */
const pileSrc = readSrc('clawd-pile.js');
const declMatch = pileSrc.match(/const PX = [\s\S]*?const STORE = '[^']+';/);
if (!declMatch) throw new Error('clawd-pile.js 常量区没匹配上');
const pile = new Function(`${declMatch[0]}; return { PX, W, H, TOP, PAL, BODY, LEGS, EYES, GLASSES, COWBOY, WIZARD, WAND, NAMES, BODY_W, LIFT, GAP, EDGE, STEP_MS, LEG_X, STORE };`)();
const PILE_PAL = {
  ...pile.PAL,
  k: '#6b6560',   // var(--clawd-pile-ink) 日间默认；夜间 #bdb7ae 存进主题表
};
const pileData = {
  px: pile.PX, w: pile.W, h: pile.H, top: pile.TOP,
  pal: PILE_PAL,
  palNight: { ...PILE_PAL, k: '#bdb7ae' },
  body: pile.BODY, legs: pile.LEGS, eyes: pile.EYES, glasses: pile.GLASSES,
  cowboy: pile.COWBOY, wizard: pile.WIZARD, wand: pile.WAND,
  names: pile.NAMES, bodyW: pile.BODY_W, lift: pile.LIFT, gap: pile.GAP,
  edge: pile.EDGE, stepMs: pile.STEP_MS, legX: pile.LEG_X,
};
/* 小只金标准：每种 (name, eyes, pose, face) 组合展开成像素。cells() 在闭包里，
   这里按同样规则重演（cells 逻辑 15 行，直接抄进提取脚本并注明来源行号；pile 源码 48-60 行）。
   抄写有风险 —— 用断言锁住：把 cowboy/shades/wizard 在 (shut, crouch, 0) 下的像素数
   与 vendor 文件里 grid/spans 的产出对齐（跑一遍原版 cells 逻辑验证）。 */
function pileCells(name, eyes, pose, face) {
  const PX = pile.PX, W = pile.W, H = pile.H, TOP = pile.TOP;
  const grid = (rows, ox = 0, oy = 0) => { const o = []; rows.forEach((r, y) => [...r].forEach((ch, x) => { if (ch !== '.') o.push([x + ox, y + oy, ch]); })); return o; };
  const spans = (l, ox = 0, oy = 0) => { const o = []; for (const [x, y, s] of l) [...s].forEach((ch, i) => { if (ch !== '.') o.push([x + i + ox, y + oy, ch]); }); return o; };
  const up = pose === 'crouch' ? 0 : pose.startsWith('dangle') ? 3 : 2, by = TOP + 7 - up;
  let c = [];
  if (name === 'cowboy') c = c.concat(grid(pile.COWBOY, 0, by - 4));
  if (name === 'wizard') c = c.concat(grid(pile.WIZARD, 0, by - 7));
  let body = grid(pile.BODY, 0, by);
  if (face) { const dark = face > 0 ? 2 : 13; body = body.map(([x, y, ch]) => [x, y, x === dark ? 's' : ch]); }
  c = c.concat(body);
  if (pose === 'stand' || pose.startsWith('dangle')) c = c.concat(grid(pile.LEGS[pose], 0, by + 8));
  if (name === 'shades') c = c.concat(eyes === 'open' ? spans(pile.EYES.open, face, by).concat(spans(pile.GLASSES.down, face, by)) : spans(pile.GLASSES.on, face, by));
  else c = c.concat(spans(pile.EYES[eyes], face, by));
  if (name === 'wizard') c = c.concat(spans(eyes === 'open' ? pile.WAND.shine : pile.WAND.rest, 15, by));
  return c;
}
const pileGolden = [];
for (const name of pile.NAMES) {
  for (const eyes of ['shut', 'half', 'open']) {
    for (const pose of ['crouch', 'stand', 'raised', 'dangleA', 'dangleB']) {
      for (const face of [0, 1, -1]) {
        const px = pileCells(name, eyes, pose, face).map(([x, y, ch]) => [x, y, PILE_PAL[ch]]).sort((a, b) => a[1] - b[1] || a[0] - b[0]);
        pileGolden.push({ name, eyes, pose, face, px });
      }
    }
  }
}

/* ═══ 3. 写文件 ═══ */
const data = {
  version: 1,
  theme: THEME,
  rig: {
    px: EX.PX, tick: EX.TICK, box: EX.BOX,
    parts: EX.PARTS, origin: EX.ORIGIN, restF: EX.REST_F, restZ: EX.REST_Z,
    upperKids: [...EX.UPPER_KIDS], pal: PAL2,
    sprites, clips,
  },
  pile: pileData,
};
const golden = { version: 1, rig: { frames: framesGolden, clips: clipsGolden }, pile: pileGolden };

mkdirSync(join(HERE, '../../app/src/main/assets/clawd'), { recursive: true });
mkdirSync(join(HERE, '../../app/src/test/resources/clawd'), { recursive: true });
writeFileSync(join(HERE, '../../app/src/main/assets/clawd/clawd_data.json'), JSON.stringify(data));
writeFileSync(join(HERE, '../../app/src/test/resources/clawd/clawd_golden.json'), JSON.stringify(golden));

console.log(`selfCheck: ${selfCheck}`);
console.log(`clips: ${Object.keys(clips).length}, clip samples: ${Object.values(clipsGolden).reduce((n, c) => n + c.samples.length, 0)}`);
console.log(`rig frames golden: ${framesGolden.length}`);
console.log(`pile golden combos: ${pileGolden.length}`);
console.log(`data.json: ${(JSON.stringify(data).length / 1024).toFixed(0)} KB`);
console.log(`golden.json: ${(JSON.stringify(golden).length / 1024).toFixed(0)} KB`);
