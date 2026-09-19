// 将已编译的适配器索引与脚本打包为单个 bundle,供 Netlify CDN 静态分发。
// 用法: node tools/build-netlify.mjs
// 产出: netlify/static/warehouse/bundle.json(Netlify 构建时生成,已在 .gitignore 中)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const srcDir = path.join(root, 'app/src/main/assets/warehouse');
const outDir = path.join(root, 'netlify/static/warehouse');

const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

const index = readJson(path.join(srcDir, 'index.json'));
const adapters = readJson(path.join(srcDir, 'adapters.json'));

// resources/<folder>/<file>.js → { "<folder>/<file>": "<源码>" }
const scripts = {};
function walk(dir, rel) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const relPath = rel ? `${rel}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      walk(full, relPath);
    } else if (entry.isFile() && entry.name.endsWith('.js')) {
      scripts[relPath] = fs.readFileSync(full, 'utf8');
    }
  }
}
walk(path.join(srcDir, 'resources'), '');

const bundle = {
  schema: 1,
  generatedAt: new Date().toISOString(),
  schools: index.schools ?? [],
  adapters: adapters.adapters ?? [],
  scripts,
};

fs.mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, 'bundle.json');
const text = JSON.stringify(bundle);
fs.writeFileSync(outFile, text, 'utf8');

console.log(`schools: ${bundle.schools.length}`);
console.log(`adapters: ${bundle.adapters.length}`);
console.log(`scripts: ${Object.keys(scripts).length}`);
console.log(`bundle: ${(Buffer.byteLength(text) / 1024 / 1024).toFixed(2)} MB -> ${path.relative(root, outFile)}`);
