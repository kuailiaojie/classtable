/*
 * 余课 官网 · 交互
 *
 * 五件事,没有别的:
 *   1. 主题切换(浅 / 深 / 跟随系统);
 *   2. 从既有反代取最新版本与安装包地址,把下载入口填活;
 *   3. 超过反代单响应上限的包,用 Range 分段取回再存盘(整包请求会被反代回 409);
 *   4. 窄屏导航开合;
 *   5. 页脚年份。
 *
 * 版本数据来自本站已有的 Netlify Function(/.netlify/functions/proxy/version),
 * 与 Android 客户端走同一条链路 —— 大陆可达,不额外依赖别的服务。
 * 取不到时静默保持 HTML 里的静态兜底(指向 GitHub Releases),不影响下载。
 */
(() => {
  'use strict';

  const PROXY = '/.netlify/functions/proxy';
  const root = document.documentElement;

  /* ---------- 1 · 主题 ---------- */

  const THEME_KEY = 'yohaku-theme';
  const media = window.matchMedia('(prefers-color-scheme: dark)');

  function stored() {
    try {
      return localStorage.getItem(THEME_KEY);
    } catch {
      return null;
    }
  }

  function applyTheme(theme, persist) {
    const resolved = theme === 'system' ? (media.matches ? 'dark' : 'light') : theme;
    root.dataset.theme = resolved;
    root.dataset.themeChoice = theme;
    root.style.colorScheme = resolved;
    if (persist) {
      try {
        localStorage.setItem(THEME_KEY, theme);
      } catch {
        /* 隐私模式下写不进去,忽略 */
      }
    }
    const btn = document.querySelector('[data-theme-toggle]');
    if (btn) {
      const names = { system: '跟随系统', dark: '深色', light: '浅色' };
      const label = names[theme];
      btn.setAttribute('aria-label', `切换主题(当前:${label})`);
      btn.title = `当前:${label} · 点按切换`;
      const text = btn.querySelector('[data-theme-label]');
      if (text) text.textContent = theme === 'system' ? '跟随' : theme === 'dark' ? '深色' : '浅色';
    }
  }

  applyTheme(stored() || 'system', false);

  media.addEventListener('change', () => {
    if (!stored() || stored() === 'system') applyTheme('system', false);
  });

  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-theme-toggle]');
    if (!btn) return;
    const order = ['light', 'dark', 'system'];
    const current = stored() || 'system';
    applyTheme(order[(order.indexOf(current) + 1) % order.length], true);
  });

  /* ---------- 2 · 版本与下载 ---------- */

  // 安装包一律经本站反代下发(大陆不用连 GitHub)。服务端 /apk 按 ?abi= 取对应架构的包,
  // 缺省给通吃包。所以页面先问 /version 拿到「这一版实际有哪些包」,再据此铺下载入口。
  const ABI_ORDER = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'];
  const ABI_LABELS = {
    'arm64-v8a': '近几年的 64 位机型',
    'armeabi-v7a': '老一些的 32 位机型',
    'x86_64': '模拟器与 x86 设备',
    x86: '较老的模拟器',
  };

  const byTemplate = (name) => document.querySelectorAll(`[data-${name}]`);

  function setText(name, value) {
    byTemplate(name).forEach((el) => {
      el.textContent = value;
      el.hidden = false;
    });
  }

  function setHref(name, value) {
    byTemplate(name).forEach((el) => {
      el.href = value;
    });
  }

  function humanSize(bytes) {
    if (!bytes || bytes <= 0) return '';
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  function humanDate(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  }

  /**
   * 先要正式版(反代默认走 GitHub 的 releases/latest),没有正式版时再要预发行版。
   * 项目还在 RC 阶段时前者会 502,退回后者才拿得到当前版本。
   */
  async function loadVersion() {
    for (const query of ['', '?prerelease=1']) {
      try {
        const resp = await fetch(`${PROXY}/version${query}`, {
          headers: { Accept: 'application/json' },
        });
        if (!resp.ok) continue;
        const data = await resp.json();
        if (data && data.versionName) {
          return { data, prerelease: query !== '' };
        }
      } catch {
        /* 离线或反代不可用,试下一个 */
      }
    }
    return null;
  }

  /** 一行「架构 / 适合 / 包」。内容来自自家反代,仍走 DOM 接口逐个建节点,不拼 HTML。 */
  function packageRow({ abi, label, size, bytes, file, href }) {
    const tr = document.createElement('tr');

    const cellAbi = document.createElement('td');
    const code = document.createElement('code');
    code.textContent = abi;
    cellAbi.appendChild(code);

    const cellFor = document.createElement('td');
    cellFor.textContent = label;

    const cellGet = document.createElement('td');
    const link = document.createElement('a');
    link.className = 'link-underline text-label-12';
    link.href = href;
    link.textContent = size ? `下载 ${size}` : '下载';
    markApkLink(link, bytes, file);
    cellGet.appendChild(link);

    tr.append(cellAbi, cellFor, cellGet);
    return tr;
  }

  /** 按「这一版实际存在的包」重建表格:先列各架构,通吃包放最后。 */
  function renderPackages(data) {
    const body = document.querySelector('[data-abi-rows]');
    if (!body) return;

    const asList = data.apks && typeof data.apks === 'object' ? data.apks : {};
    const rows = [];

    for (const abi of ABI_ORDER) {
      const asset = asList[abi];
      if (!asset || !asset.apkUrl) continue;
      rows.push({
        abi,
        label: ABI_LABELS[abi] || abi,
        size: humanSize(asset.apkSize),
        bytes: asset.apkSize,
        file: fallbackName(data.versionName, abi),
        href: PROXY + asset.apkUrl,
      });
    }

    // 顶层那个包始终是通吃包(不带 abi 的旧版本也靠它先升上来)
    if (data.apkUrl) {
      rows.push({
        abi: 'universal',
        label: '拿不准就选它,通吃',
        size: humanSize(data.apkSize),
        bytes: data.apkSize,
        file: fallbackName(data.versionName, 'universal'),
        href: PROXY + data.apkUrl,
      });
    }

    if (rows.length === 0) return;

    body.replaceChildren(...rows.map(packageRow));
    return rows;
  }

  function renderVersion(result) {
    const { data, prerelease } = result;

    setText('version-name', data.versionName);
    setText('version-date', humanDate(data.publishedAt));
    setText('version-size', humanSize(data.apkSize));
    const hash = String(data.apkSha256 || '');
    if (hash) setText('version-sha', `${hash.slice(0, 16)}…`);

    const chip = document.querySelector('[data-version-chip]');
    if (chip) {
      chip.dataset.kind = prerelease ? 'prerelease' : 'stable';
      const label = chip.querySelector('[data-version-kind]');
      if (label) label.textContent = prerelease ? '预发行版' : '正式版';
    }

    // 通吃包 —— 与 README 的建议一致,拿不准就下它
    const universal = data.apkUrl ? PROXY + data.apkUrl : '';
    if (universal) {
      setHref('dl-primary', universal);
      const file = fallbackName(data.versionName, 'universal');
      byTemplate('dl-primary').forEach((el) => markApkLink(el, data.apkSize, file));
      const primary = document.querySelector('[data-dl-primary]');
      if (primary) primary.textContent = '下载安装包';
    }

    if (data.releaseUrl) setHref('release-url', data.releaseUrl);

    const rows = renderPackages(data);
    const status = document.querySelector('[data-dl-status]');
    if (status) {
      status.textContent = rows
        ? `共 ${rows.length} 个包,都经本站代理下发,大陆不用连 GitHub。`
        : '安装包经本站代理下发,大陆不用连 GitHub。';
    }

    const versionBadge = document.querySelector('[data-hero-version]');
    if (versionBadge) versionBadge.textContent = `v${data.versionName}`;
  }

  /* ---------- 3 · 大包分段下载 ---------- */

  // 反代是 Netlify Function:单次响应超过约 5MB 会被边缘截断(Content-Length 还在,内容却少了),
  // 所以服务端对不带 Range 的整包请求直接回 409,要求分段取。这里按同一条约定办:每段 4MB
  // (与 Android 端 CHUNK_BYTES 一致),取完拼成 Blob 再存盘。
  // 不超过上限的包不拦,仍旧交给浏览器原生下载 —— 进度、续传、下载管理器都是现成的。
  const CHUNK_BYTES = 4 * 1024 * 1024;
  const MAX_INLINE_BYTES = 5 * 1024 * 1024; // 需与 netlify/functions/proxy.mjs 的 MAX_INLINE_BYTES 一致

  /** 兜底文件名:Content-Disposition 拿不到时才用。 */
  function fallbackName(version, abi) {
    return `classtable-${version || 'latest'}-${abi || 'universal'}.apk`;
  }

  /** 把下载入口标记成受管链接:分段取回需要知道包多大、存成什么名字。 */
  function markApkLink(el, size, file) {
    el.dataset.apkLink = '';
    el.dataset.apkSize = String(size || 0);
    el.dataset.apkFile = file;
  }

  /** 服务端错误正文里的 message 优先透出(与 Android 端同一套约定),否则给通用文案。 */
  async function errorMessage(resp) {
    try {
      const body = await resp.json();
      const message = body && body.error && body.error.message;
      if (message) return message;
    } catch {
      /* 不是 JSON,落到通用文案 */
    }
    return `下载失败(HTTP ${resp.status})`;
  }

  /** Content-Range 里的总长(`bytes 0-4194303/6144894`);拿不到返回 0。 */
  function totalOf(resp) {
    const match = /\/(\d+)\s*$/.exec(resp.headers.get('content-range') || '');
    return match ? Number(match[1]) : 0;
  }

  /** 取 Release 里的原始资产名 —— 存到用户「下载」目录里认得出是哪个包。 */
  function fileNameOf(resp, fallback) {
    const disposition = resp.headers.get('content-disposition') || '';
    const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    const plain = /filename="?([^";]+)"?/i.exec(disposition);
    let name = '';
    try {
      name = utf8 ? decodeURIComponent(utf8[1]) : plain ? plain[1] : '';
    } catch {
      name = '';
    }
    return name || fallback;
  }

  /**
   * 分段取回整个安装包:每段 4MB,远低于服务端上限,又不至于把请求数拉得太碎。
   * 总长未知时(服务端没给 apkSize)从第一段的 Content-Range 里补出来;实在补不到,
   * 就一直取到空段、或服务端直接回整包为止。
   */
  async function fetchApk(url, size, onProgress) {
    const parts = [];
    let received = 0;
    let name = '';
    while (size <= 0 || received < size) {
      const end = size > 0 ? Math.min(received + CHUNK_BYTES, size) - 1 : received + CHUNK_BYTES - 1;
      const resp = await fetch(url, {
        // identity:压缩响应的长度与解压后不一致,进度会错位
        headers: { Range: `bytes=${received}-${end}`, 'Accept-Encoding': 'identity' },
        cache: 'no-store',
      });
      if (!resp.ok) throw new Error(await errorMessage(resp));
      if (!name) name = fileNameOf(resp, '');
      const ranged = resp.status === 206;
      // 服务端忽略 Range 时回 200 + 整包:只有第一段能接,之后拿到的都是从头开始的数据
      if (!ranged && received > 0) throw new Error('服务端未按分段返回,请稍后重试');
      // 服务端没给 apkSize 时,从 Content-Range 的「/总长」里补出来,后面才收得住尾
      if (size <= 0 && ranged) size = totalOf(resp);
      const chunk = await resp.arrayBuffer();
      if (chunk.byteLength === 0) break;
      parts.push(chunk);
      received += chunk.byteLength;
      onProgress(received);
      if (!ranged) break; // 整包一次给全,已到文件尾
    }
    if (size > 0 && received !== size) {
      throw new Error(`安装包不完整(已取 ${received} / ${size} 字节),请重试`);
    }
    return { blob: new Blob(parts, { type: 'application/vnd.android.package-archive' }), name };
  }

  /** 与浏览器原生下载一样落到「下载」目录:blob 存完再回收 URL,免得把下载掐断。 */
  function saveBlob(blob, name) {
    const href = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = href;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(href), 60000);
  }

  let downloading = false;

  async function startDownload(link) {
    const status = document.querySelector('[data-dl-status]');
    const size = Number(link.dataset.apkSize || 0);
    const file = link.dataset.apkFile;
    const label = link.textContent;
    const isButton = link.classList.contains('btn');
    const say = (text) => {
      if (status) status.textContent = text;
    };

    downloading = true;
    link.setAttribute('aria-busy', 'true');
    if (isButton) link.textContent = '正在下载…';
    say(`正在下载 ${humanSize(size) || '安装包'}…`);

    try {
      const result = await fetchApk(link.href, size, (received) => {
        const percent = size > 0 ? ` · ${Math.floor((received / size) * 100)}%` : '';
        say(`正在下载 ${humanSize(received)} / ${humanSize(size) || '—'}${percent}`);
      });
      const name = result.name || file;
      saveBlob(result.blob, name);
      say(`已下载 ${name}(${humanSize(result.blob.size)})。装不上就换通吃包再试。`);
    } catch (error) {
      say(`下载失败:${error.message}`);
    } finally {
      downloading = false;
      link.removeAttribute('aria-busy');
      if (isButton) link.textContent = label;
    }
  }

  // 下载链接本身就指向反代 /apk:超限的包直接点下去只会看到 409 的 JSON,所以由这里接管。
  document.addEventListener('click', (e) => {
    const link = e.target.closest('a[data-apk-link]');
    if (!link) return;
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return; // 新标签页之类的打开方式交给浏览器
    const size = Number(link.dataset.apkSize || 0);
    if (size > 0 && size <= MAX_INLINE_BYTES) return; // 小包走浏览器原生下载
    e.preventDefault();
    if (downloading) {
      const status = document.querySelector('[data-dl-status]');
      if (status) status.textContent = '上一个包还在下载,请稍候…';
      return;
    }
    startDownload(link);
  });

  loadVersion()
    .then((result) => {
      if (result) {
        renderVersion(result);
        return;
      }
      const status = document.querySelector('[data-dl-status]');
      if (status) {
        status.textContent = '没读到版本信息,下面的按钮会带你去 GitHub Releases。';
      }
    })
    .catch(() => {
      /* 保持静态兜底 */
    });

  /* ---------- 4 · 窄屏导航 ---------- */

  const navToggle = document.querySelector('[data-nav-toggle]');
  const nav = document.querySelector('.site-nav');

  if (navToggle && nav) {
    navToggle.addEventListener('click', () => {
      const open = nav.dataset.open === 'true';
      nav.dataset.open = open ? 'false' : 'true';
      navToggle.setAttribute('aria-expanded', String(!open));
    });

    nav.addEventListener('click', (e) => {
      if (e.target.closest('a')) {
        nav.dataset.open = 'false';
        navToggle.setAttribute('aria-expanded', 'false');
      }
    });
  }

  /* ---------- 5 · 年份 ---------- */

  document.querySelectorAll('[data-year]').forEach((el) => {
    el.textContent = String(new Date().getFullYear());
  });
})();
