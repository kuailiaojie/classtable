// 将 shiguang_warehouse 的 YAML 索引预编译为 JSON(供 Android assets 使用,零运行时 YAML 依赖)
// 用法: node tools/yaml2json.mjs
// 产出: app/src/main/assets/warehouse/index.json + adapters.json
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const warehouseDir = path.join(root, 'warehouse');
const outDir = path.join(root, 'app/src/main/assets/warehouse');

// 只支持本仓库 YAML 的子集:顶层 key + 一层列表项 + 键值对(值可为带引号字符串)
function parseSimpleYaml(text) {
  const items = [];
  let current = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/\s+$/, '');
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#')) continue;
    const m = line.match(/^(\s*)(\S.*)$/);
    if (!m) continue;
    const indent = m[1].length;
    const content = m[2];
    if (indent === 0) { current = null; continue; }
    const listMatch = content.match(/^-\s*(.*)$/);
    if (listMatch) {
      current = {};
      items.push(current);
      const kv = parseKV(listMatch[1]);
      if (kv) current[kv[0]] = kv[1];
      continue;
    }
    if (current) {
      const kv = parseKV(content);
      if (kv) current[kv[0]] = kv[1];
    }
  }
  return items;
}

function parseKV(s) {
  const idx = s.indexOf(':');
  if (idx < 0) return null;
  const key = s.slice(0, idx).trim();
  return [key, parseScalar(s.slice(idx + 1).trim())];
}

/**
 * 解析标量值。此前用 indexOf('"', 1) 找闭合引号,**不识别转义 \"**,
 * 于是遇到 `"…点击\"执行导入\"…"` 这类描述会在第一个转义引号处被截断。
 * 现在改为逐字符扫描,支持双引号(含 \" \\ 等转义)、单引号(YAML 的 '' 转义)与裸值。
 */
function parseScalar(raw) {
  if (raw.startsWith('"')) {
    let out = '';
    for (let i = 1; i < raw.length; i++) {
      const ch = raw[i];
      if (ch === '\\' && i + 1 < raw.length) { out += unescapeChar(raw[++i]); continue; }
      if (ch === '"') return out;
      out += ch;
    }
    return out; // 未闭合:按到行尾处理
  }
  if (raw.startsWith("'")) {
    let out = '';
    for (let i = 1; i < raw.length; i++) {
      if (raw[i] === "'" && raw[i + 1] === "'") { out += "'"; i++; continue; }
      if (raw[i] === "'") return out;
      out += raw[i];
    }
    return out;
  }
  // 裸值:去掉行内注释
  const hash = raw.search(/\s#/);
  return (hash >= 0 ? raw.slice(0, hash) : raw).trim();
}

function unescapeChar(c) {
  switch (c) {
    case 'n': return '\n';
    case 't': return '\t';
    case 'r': return '\r';
    case '"': return '"';
    case '\\': return '\\';
    default: return c;
  }
}

// 1) root_index.yaml → index.json
const rootIndexPath = path.join(warehouseDir, 'index', 'root_index.yaml');
const schools = parseSimpleYaml(fs.readFileSync(rootIndexPath, 'utf8'));
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'index.json'), JSON.stringify({ schools }, null, 1), 'utf8');

// 2) resources/<folder>/adapters.yaml → adapters.json(带 folder 字段)
const resourcesDir = path.join(warehouseDir, 'resources');
const adapters = [];
for (const folder of fs.readdirSync(resourcesDir, { withFileTypes: true })) {
  if (!folder.isDirectory()) continue;
  const yamlPath = path.join(resourcesDir, folder.name, 'adapters.yaml');
  if (!fs.existsSync(yamlPath)) continue;
  for (const a of parseSimpleYaml(fs.readFileSync(yamlPath, 'utf8'))) {
    adapters.push({ folder: folder.name, ...a });
  }
}
fs.writeFileSync(path.join(outDir, 'adapters.json'), JSON.stringify({ adapters }, null, 1), 'utf8');

console.log(`schools: ${schools.length}`);
console.log(`adapters: ${adapters.length}`);
console.log(`sample school: ${JSON.stringify(schools[0])}`);
console.log(`sample adapter: ${JSON.stringify(adapters[0])}`);
