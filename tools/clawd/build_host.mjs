/* 宿主页生成：把模板占位符换成原版 JS（去掉 ESM export），写出
   app/src/main/assets/clawd/clawd_host.html。
   用法：node tools/clawd/build_host.mjs */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const read = f => readFileSync(join(HERE, 'src', f), 'utf8');

const strip = (name, src) => {
  const marker = `export function ${name}`;
  if (!src.includes(marker)) throw new Error(`${name} 的 export 未找到`);
  return src.replace(marker, `function ${name}`);
};

const template = readFileSync(join(HERE, 'host-template.html'), 'utf8');
const out = template
  .replace('{{CLAWD_PILE_JS}}', strip('createClawdPile', read('clawd-pile.js')))
  .replace('{{CLAWD_RIG_JS}}', strip('buildClawdRig', read('clawd-rig.js')))
  .replace('{{CLAWD_RHYTHM_JS}}', strip('installClawdRhythm', read('clawd-rhythm.js')));

mkdirSync(join(HERE, '../../app/src/main/assets/clawd'), { recursive: true });
writeFileSync(join(HERE, '../../app/src/main/assets/clawd/clawd_host.html'), out);
console.log(`clawd_host.html: ${(out.length / 1024).toFixed(0)} KB`);
