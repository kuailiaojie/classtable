// 校验内置适配器数据(assets/warehouse)是否自洽。
// 用法: node tools/verify-warehouse.mjs
//
// 检查项:
//   1. 索引可解析,学校/适配器数量非零
//   2. 每个适配器引用的脚本真实存在(排除上游开发者自检目录)
//   3. 每所学校至少有一个适配器(否则用户点进去没有可选方案,无法导入)
//   4. 适配器目录都登记在 root_index(否则导入页搜不到)
//   5. adapter_id 唯一
//   6. 关键字段非空
//   7. 没有未被任何适配器引用的脚本
//   8. 中文未因编码损坏
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const assets = path.join(root, 'app/src/main/assets/warehouse');

/** 上游的开发者自检/工具目录:App 侧由 WarehouseIndex.SELF_CHECK_FOLDERS 隐藏,不面向用户。 */
const SELF_CHECK_FOLDERS = new Set(['GLOBAL_TOOLS']);

const index = JSON.parse(fs.readFileSync(path.join(assets, 'index.json'), 'utf8'));
const adapters = JSON.parse(fs.readFileSync(path.join(assets, 'adapters.json'), 'utf8'));
const schools = index.schools ?? [];
const list = adapters.adapters ?? [];

let failed = 0;
const check = (ok, msg, info = '') => {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${msg}${info ? '  ' + info : ''}`);
};

check(schools.length > 0 && list.length > 0, `索引可解析: ${schools.length} 所学校 / ${list.length} 个适配器`);

// 2) 脚本存在
const missing = list.filter((a) => {
  const p = path.join(assets, 'resources', a.folder ?? '', a.asset_js_path ?? '');
  return !a.asset_js_path || !fs.existsSync(p);
});
const missingUser = missing.filter((m) => !SELF_CHECK_FOLDERS.has(m.folder));
check(
  missingUser.length === 0,
  '面向用户的适配器脚本都存在',
  missingUser.length ? '缺失: ' + missingUser.slice(0, 5).map((m) => `${m.folder}/${m.asset_js_path}`).join(', ') : '',
);
if (missing.length !== missingUser.length) {
  console.log(
    `INFO  自检目录缺失脚本(上游数据自身缺陷,App 不展示): ` +
      missing.filter((m) => SELF_CHECK_FOLDERS.has(m.folder)).map((m) => `${m.folder}/${m.asset_js_path}`).join(', '),
  );
}

// 3) 每所学校都有适配器
const withAdapters = new Set(list.map((a) => a.folder));
const emptySchools = schools.filter((s) => !withAdapters.has(s.resource_folder)).map((s) => s.resource_folder);
check(emptySchools.length === 0, '每所学校都有适配器', emptySchools.length ? '空缺: ' + emptySchools.join(', ') : '');

// 4) 适配器目录已登记
const knownFolders = new Set(schools.map((s) => s.resource_folder));
const orphans = [...withAdapters].filter((f) => !knownFolders.has(f));
check(orphans.length === 0, '适配器目录都登记在 root_index', orphans.length ? '未登记: ' + orphans.join(', ') : '');

// 5) adapter_id 唯一
const ids = list.map((a) => a.adapter_id);
const dups = [...new Set(ids.filter((v, i) => ids.indexOf(v) !== i))];
check(dups.length === 0, 'adapter_id 唯一', dups.length ? '重复: ' + dups.join(', ') : '');

// 6) 关键字段非空
const badFields = list.filter((a) => !a.adapter_id || !a.adapter_name || !a.asset_js_path);
check(badFields.length === 0, 'adapter_id / adapter_name / asset_js_path 均非空', badFields.length ? `${badFields.length} 条缺字段` : '');

// 7) 无未引用脚本
const allJs = [];
const walk = (dir, rel) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const r = rel ? `${rel}/${entry.name}` : entry.name;
    if (entry.isDirectory()) walk(path.join(dir, entry.name), r);
    else if (entry.name.endsWith('.js')) allJs.push(r);
  }
};
walk(path.join(assets, 'resources'), '');
const used = new Set(list.map((a) => `${a.folder}/${a.asset_js_path}`));
const unused = allJs.filter((p) => !used.has(p));
check(unused.length === 0, '没有未被引用的脚本', unused.length ? '未引用: ' + unused.slice(0, 5).join(', ') : '');
console.log(`INFO  内置脚本总数: ${allJs.length}`);
console.log(`INFO  需手填教务网址(无固定入口)的适配器: ${list.filter((a) => !a.import_url).length}`);

// 8) 中文未损坏
const sample = list.find((a) => /[\u4e00-\u9fa5]/.test(a.adapter_name ?? ''));
check(!!sample, `中文正常: ${sample?.adapter_name ?? '未找到中文适配器名'}`);

console.log(failed === 0 ? '\nALL CHECKS PASSED' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
