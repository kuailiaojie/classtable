// 将官网源文件(netlify/site)与仓库里的真实素材装配进 Netlify 发布目录(netlify/static)。
//
// 用法: node tools/build-site.mjs
// 产出: netlify/static/{index.html,404.html,robots.txt,_headers,assets/**}
//
// 边界:本脚本只维护上面列出的产物,warehouse/ 归 tools/build-netlify.mjs —— 两者互不覆盖,
// 在 netlify.toml 里的先后顺序无所谓。
//
// 素材不复制进仓库,而是构建期从原始位置取,避免同一张截图/图标在仓库里存两份:
//   · 截图  docs/screenshots/*.jpg           (README 用的同一批)
//   · 图标  app/src/main/res/drawable-nodpi/app_icon_*.png
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const siteDir = path.join(root, 'netlify/site');
const outDir = path.join(root, 'netlify/static');
const imgOut = path.join(outDir, 'assets/img');

const screenshotDir = path.join(root, 'docs/screenshots');
const iconDir = path.join(root, 'app/src/main/res/drawable-nodpi');

/** 本脚本负责的产物 —— 重建前先删掉,保证产物与源码一致(不留上一次的孤儿文件)。 */
const OWNED = ['index.html', '404.html', 'robots.txt', '_headers', 'assets'];

/** 构建期从仓库取材的文件;缺失即视为错误,免得部署出 404 的图片。 */
const SCREENSHOTS = ['week.jpg', 'courses.jpg', 'rain-classroom.jpg', 'import.jpg'];

function fail(message) {
  console.error(`build-site: ${message}`);
  process.exit(1);
}

function copyFile(from, to) {
  if (!fs.existsSync(from)) fail(`缺少素材 ${path.relative(root, from)}`);
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
  return fs.statSync(to).size;
}

// 1 · 清掉上次的产物(warehouse/ 不在 OWNED 里,不受影响)
fs.mkdirSync(outDir, { recursive: true });
for (const name of OWNED) {
  fs.rmSync(path.join(outDir, name), { recursive: true, force: true });
}

// 2 · 拷贝站点骨架
fs.cpSync(siteDir, outDir, { recursive: true });

// 3 · 取真实素材
let bytes = 0;

for (const name of SCREENSHOTS) {
  bytes += copyFile(path.join(screenshotDir, name), path.join(imgOut, name));
}

const icons = fs.existsSync(iconDir)
  ? fs.readdirSync(iconDir).filter((f) => /^app_icon_\d+\.png$/.test(f)).sort()
  : [];
if (icons.length === 0) fail(`没有找到应用图标(${path.relative(root, iconDir)})`);
for (const name of icons) {
  bytes += copyFile(path.join(iconDir, name), path.join(imgOut, name));
}

// 4 · 校验页面引用的本地资源都在发布目录里(漏一个就是线上 404)
const html = fs.readFileSync(path.join(siteDir, 'index.html'), 'utf8');
// 引用可能带缓存破冰的查询串(assets/site.js?v=2),落到磁盘上的是去掉查询串的路径
const refs = [...html.matchAll(/(?:src|href)="(assets\/[^"?]+)/g)].map((m) => m[1]);
const missing = refs.filter((rel) => !fs.existsSync(path.join(outDir, rel)));
if (missing.length > 0) fail(`index.html 引用了不存在的资源:${missing.join(', ')}`);

console.log(`screenshots: ${SCREENSHOTS.length}`);
console.log(`icons: ${icons.length}`);
console.log(`assets: ${(bytes / 1024 / 1024).toFixed(2)} MB`);
console.log(`refs checked: ${refs.length}`);
console.log(`site -> ${path.relative(root, outDir)}`);
