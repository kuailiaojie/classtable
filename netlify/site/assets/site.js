/*
 * 余课 官网 · 交互
 *
 * 三件事,没有别的:
 *   1. 主题切换(浅 / 深 / 跟随系统);
 *   2. 从既有反代取最新版本与安装包地址,把下载入口填活;
 *   3. 窄屏导航开合。
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
  function packageRow({ abi, label, size, href }) {
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
        href: PROXY + asset.apkUrl,
      });
    }

    // 顶层那个包始终是通吃包(不带 abi 的旧版本也靠它先升上来)
    if (data.apkUrl) {
      rows.push({
        abi: 'universal',
        label: '拿不准就选它,通吃',
        size: humanSize(data.apkSize),
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

  /* ---------- 3 · 窄屏导航 ---------- */

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

  /* ---------- 4 · 年份 ---------- */

  document.querySelectorAll('[data-year]').forEach((el) => {
    el.textContent = String(new Date().getFullYear());
  });
})();
