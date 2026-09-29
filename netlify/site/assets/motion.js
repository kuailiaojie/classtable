/*
 * 余课 官网 · 动效(GSAP)
 *
 * 与 site.js 分工:site.js 管行为(主题 / 下载 / 导航开合),这里只管动效。
 * 只依赖同目录下 vendor/ 里随站分发的 GSAP,不碰任何 CDN —— 大陆也要能放。
 *
 * 几条约定:
 *   1. 优雅降级:GSAP 没加载成功(或用户开了「减少动态效果」)时,**一行都不改**页面,
 *      内容照原样可见可读。首帧的隐藏态由 index.html 里的内联脚本按同一条件加 `anim-ready`,
 *      并留了一个兜底定时器:本文件没跑起来就把类撤掉,绝不留白屏。
 *   2. 时序用 timeline 编排(不是串 delay),同类元素用 stagger —— 见 GSAP 的写法。
 *   3. 只动合成层属性(transform / opacity),不碰会触发布局的 width/height/top/left;
 *      唯一例外是 FAQ 展开,那里用 gsap.from 在下一帧前就把初态压上,不产生跳动。
 *   4. 减动效 = 不注册任何动画,而不是把时长调小。由 gsap.matchMedia() 统一裁决。
 */
(() => {
  'use strict';

  const root = document.documentElement;
  const gsap = window.gsap;
  const ScrollTrigger = window.ScrollTrigger;
  const ScrollToPlugin = window.ScrollToPlugin;

  /* ---------- 0 · 守卫:任一块没到齐就整体不动 ---------- */
  if (!gsap || !ScrollTrigger || !ScrollToPlugin) return;

  gsap.registerPlugin(ScrollTrigger, ScrollToPlugin);

  // 本文件已经接管:撤掉内联脚本留的兜底定时器,别让它把 `anim-ready` 撤回去。
  if (typeof window.__yohakuAnimFallback === 'number') {
    clearTimeout(window.__yohakuAnimFallback);
  }

  // 页面平滑滚动改由 ScrollToPlugin 负责(CSS 的 scroll-behavior 会和 ScrollTrigger 打架)。
  root.style.scrollBehavior = 'auto';

  const HEADER_OFFSET = 76; // 与 CSS 的 scroll-padding-top 对齐

  /* ---------- 1 · 动效主线(减动效时整段不执行) ---------- */

  const mm = gsap.matchMedia();

  mm.add(
    {
      motion: '(prefers-reduced-motion: no-preference)',
      desktop: '(min-width: 761px)',
    },
    (context) => {
      const { motion, desktop } = context.conditions;
      if (!motion) return;

      /* -- 1.1 Hero 入场:一条时间线,不串 delay -- */
      const hero = gsap.timeline({
        defaults: { duration: 0.7, ease: 'power3.out' },
      });
      hero
        .from('.hero .eyebrow', { y: 12, autoAlpha: 0, duration: 0.45 })
        .from('.hero-title', { y: 24, autoAlpha: 0, duration: 0.75 }, '-=0.2')
        .from('.hero-sub', { y: 18, autoAlpha: 0 }, '-=0.5')
        .from('.hero-actions', { y: 16, autoAlpha: 0 }, '-=0.45')
        .from('.hero-meta', { y: 12, autoAlpha: 0, duration: 0.5 }, '-=0.4')
        .from('.hero-phone', { y: 32, autoAlpha: 0, scale: 0.96, duration: 0.9 }, '-=0.6');

      /* -- 1.2 滚动进度条:页头底部一条 1px 强调色,随滚动伸长 -- */
      const header = document.querySelector('.site-header');
      if (header) {
        const bar = document.createElement('div');
        bar.className = 'scroll-progress';
        bar.setAttribute('aria-hidden', 'true');
        header.appendChild(bar);
        gsap.set(bar, { scaleX: 0, transformOrigin: 'left center' });
        ScrollTrigger.create({
          start: 0,
          end: 'max',
          onUpdate: (self) => gsap.set(bar, { scaleX: self.progress }),
        });
      }

      /* -- 1.3 分区进场:进入视口的一批元素一起错峰淡入上移 -- */
      const reveals = gsap.utils.toArray('[data-reveal]');
      if (reveals.length) {
        gsap.set(reveals, { autoAlpha: 0, y: 24 });
        ScrollTrigger.batch(reveals, {
          start: 'top 88%',
          once: true,
          interval: 0.08,
          batchMax: 6,
          onEnter: (batch) =>
            gsap.to(batch, {
              autoAlpha: 1,
              y: 0,
              duration: 0.6,
              ease: 'power3.out',
              stagger: 0.08,
              overwrite: true,
            }),
        });
      }

      /* -- 1.4 Hero 手机视差:滚动时轻轻抬升 -- */
      gsap.to('.hero-phone', {
        yPercent: -8,
        ease: 'none',
        scrollTrigger: {
          trigger: '.hero',
          start: 'top top',
          end: 'bottom top',
          scrub: true,
        },
      });

      /* -- 1.5 截图区:钉住一屏,四台手机随滚动依次就位(仅桌面) -- */
      const shots = gsap.utils.toArray('#shots .shot');
      if (desktop && shots.length) {
        const shotsTl = gsap.timeline({
          scrollTrigger: {
            trigger: '#shots .shots',
            start: 'top 72%',
            end: '+=70%',
            scrub: 1,
            pin: true,
            pinSpacing: true,
          },
        });
        shots.forEach((el, i) => {
          shotsTl.fromTo(
            el,
            { y: 64, autoAlpha: 0, rotate: i % 2 ? 1.6 : -1.6 },
            { y: 0, autoAlpha: 1, rotate: 0, ease: 'none' },
            i * 0.18,
          );
        });
      } else if (shots.length) {
        // 窄屏不做钉住,仍让它们进入视口时错峰出现
        gsap.set(shots, { autoAlpha: 0, y: 24 });
        ScrollTrigger.batch(shots, {
          start: 'top 88%',
          once: true,
          onEnter: (batch) =>
            gsap.to(batch, { autoAlpha: 1, y: 0, duration: 0.6, ease: 'power3.out', stagger: 0.1 }),
        });
      }

      /* -- 1.6 FAQ 展开:纯 CSS 的 details 打开太硬,这里补一段淡入 -- */
      gsap.utils.toArray('#faq details').forEach((details) => {
        const body = details.querySelector('.faq-body');
        if (!body) return;
        details.addEventListener('toggle', () => {
          if (!details.open) return;
          // from() 立即压上初态,浏览器还没画,所以看不到「先满后缩」的闪动
          gsap.from(body, { autoAlpha: 0, y: -6, duration: 0.3, ease: 'power2.out' });
        });
      });

      /* -- 1.7 锚点平滑滚动:交给 ScrollToPlugin,不靠 CSS -- */
      const scrollToHash = (hash) => {
        const el = hash && hash !== '#' ? document.querySelector(hash) : null;
        if (!el) return false;
        gsap.to(window, {
          duration: 0.8,
          ease: 'power2.inOut',
          scrollTo: { y: el, offsetY: HEADER_OFFSET, autoKill: true },
        });
        return true;
      };

      gsap.utils.toArray('a[href^="#"]').forEach((a) => {
        a.addEventListener('click', (event) => {
          if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
          const hash = a.getAttribute('href');
          if (!scrollToHash(hash)) return;
          event.preventDefault();
          history.replaceState(null, '', hash);
        });
      });

      // 清理:条件不再成立(切到减动效 / 窄屏)时,由 matchMedia 自动 revert 这里建的动画;
      // 进度条是手动插进 DOM 的,得自己收。
      return () => {
        document.querySelectorAll('.scroll-progress').forEach((el) => el.remove());
      };
    },
  );

  /* ---------- 2 · 位置重算 ---------- */

  // 图片按 width/height 属性占位,布局本身稳定;但字体、图标解码与异步版本号仍会挪动版面。
  window.addEventListener('load', () => ScrollTrigger.refresh());
  // site.js 读到版本后重建了下载面板 → 通知这里重算。
  window.addEventListener('yohaku:version', () => ScrollTrigger.refresh());
})();
