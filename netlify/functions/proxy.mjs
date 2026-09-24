// 课程表 Firebase 反代中间层(Netlify Function)
//
// 职责:把客户端的 /auth/* 与 /firestore/* 请求透明转发到 Firebase 官方 API:
//   /auth/*      → https://identitytoolkit.googleapis.com/*
//   /firestore/* → https://firestore.googleapis.com/*
//
// 转发原则:方法 / query(含 API key) / body / Authorization 头原样透传。
// Firestore 请求带 "Authorization: Bearer <ID token>",远端仍按安全规则评估
// (request.auth.uid == userId),安全模型与直连完全一致。
//
// 大陆可达性:客户端只访问本站点域名,不再触碰被墙的 Google 域名。
//
// 注意:Netlify 以 v2 格式加载本函数(default export),入参是标准 Request 对象
// (不是 v1 的 {path, httpMethod, headers, body}),故用 URL / req.method /
// req.headers.get / await req.text() 取字段,返回 Response。

const AUTH_UPSTREAM = "https://identitytoolkit.googleapis.com";
const FIRESTORE_UPSTREAM = "https://firestore.googleapis.com";
const PREFIX = "/.netlify/functions/proxy";

// 应用更新:服务端代为查询 GitHub Release(客户端不直连 GitHub,规避大陆可达性问题)。
// 可选环境变量 GITHUB_TOKEN:提升 GitHub API 限流额度(未授权 60 次/时)。
const GITHUB_REPO = "kuailiaojie/classtable";
const RELEASE_API = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`;
const RELEASES_LIST_API = `https://api.github.com/repos/${GITHUB_REPO}/releases`;
// 安装包按 CPU 架构拆开,每台设备只该拿到自己那一种。顺序有意义:"x86" 是 "x86_64" 的子串。
const ABI_NAMES = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"];

// Live Updates 发送端:按 uid 读 Firestore devices 集合,定向发 FCM data 消息。
// 需要 Netlify 环境变量:SERVICE_ACCOUNT(Firebase 服务账号 JSON)、PUSH_API_KEY(自定义管理密钥)。
// 注意:firebase-admin 是重依赖,必须「按需动态加载」——只在 /push 路径才 import,
// 避免顶层静态导入导致整个函数(含 /auth、/firestore 反代)因依赖缺失而 502。
// 依赖声明见仓库根 package.json(Netlify 部署时自动安装)。
let _admin = null;
async function getAdmin() {
  if (_admin) return _admin;
  const { default: admin } = await import("firebase-admin");
  const sa = JSON.parse(process.env.SERVICE_ACCOUNT);
  _admin = admin.initializeApp({ credential: admin.credential.cert(sa) });
  return _admin;
}

async function handlePush(req) {
  const key = process.env.PUSH_API_KEY;
  if (!key || req.headers.get("x-push-key") !== key) {
    return json(401, { error: { code: 401, message: "invalid or missing X-Push-Key" } });
  }
  if (!process.env.SERVICE_ACCOUNT) {
    return json(500, { error: { code: 500, message: "SERVICE_ACCOUNT env not configured (Firebase service account JSON)" } });
  }
  let payload;
  try {
    payload = JSON.parse(await req.text());
  } catch {
    return json(400, { error: { code: 400, message: "invalid JSON body" } });
  }
  const { uid, messageType = "marketing", title = "", body = "", data = {} } = payload;
  if (!uid) {
    return json(400, { error: { code: 400, message: "uid required" } });
  }
  try {
    const app = await getAdmin();
    const snap = await app.firestore().collection("users").doc(uid).collection("devices").get();
    const tokens = snap.docs.map((d) => d.id);
    if (tokens.length === 0) {
      return json(200, { sent: 0, message: "no devices for uid" });
    }
    // FCM data 消息:所有值必须是字符串
    const dataMap = { messageType, title, body };
    for (const [k, v] of Object.entries(data)) dataMap[k] = String(v);
    const result = await app.messaging().sendEachForMulticast({
      tokens,
      data: dataMap,
      android: { priority: "high" },
    });
    return json(200, { sent: result.successCount, failed: result.failureCount });
  } catch (e) {
    return json(500, { error: { code: 500, message: "push failed: " + e.message } });
  }
}

function githubHeaders() {
  const headers = {
    Accept: "application/vnd.github+json",
    "User-Agent": "classtable-proxy",
  };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  return headers;
}

async function latestRelease() {
  const resp = await fetch(RELEASE_API, { headers: githubHeaders() });
  if (!resp.ok) throw new Error(`GitHub API HTTP ${resp.status}`);
  return resp.json();
}

// 预发行版开关打开时 /releases/latest 用不上 —— GitHub 的 latest 永远不含预发布项,
// 所以改取 releases 列表(GitHub 按发布时间倒序返回),挑第一个非草稿。
async function pickRelease(includePrerelease) {
  if (!includePrerelease) return latestRelease();
  const resp = await fetch(`${RELEASES_LIST_API}?per_page=20`, { headers: githubHeaders() });
  if (!resp.ok) throw new Error(`GitHub API HTTP ${resp.status}`);
  const list = (await resp.json()).filter((r) => !r.draft);
  if (list.length === 0) throw new Error("没有可用的 Release");
  return list[0];
}

/** Release 里可下发给用户的安装包:一律先滤掉 debug 包,绝不把调试包装到用户手机上。 */
function releaseApks(rel) {
  return (rel.assets || []).filter((a) => a.name.endsWith(".apk") && !/debug/i.test(a.name));
}

/** 资产名里的 CPU 架构(`app-arm64-v8a-release.apk` → `arm64-v8a`);认不出返回空串。 */
function abiOf(name) {
  return ABI_NAMES.find((abi) => name.includes(abi)) || "";
}

// 从 Release 资产中挑安装包:先按客户端 CPU 架构精确匹配,再退回通吃包 / 普通 release 包。
// (未配置签名时 CI 产出 app-release-unsigned.apk,旧的「取第一个 .apk」会误选 app-debug.apk)
function pickApk(rel, abi) {
  const apks = releaseApks(rel);
  if (abi) {
    const matched = apks.find((a) => abiOf(a.name) === abi);
    if (matched) return matched;
  }
  return apks.find((a) => /universal/i.test(a.name))
    || apks.find((a) => a.name === "app-release.apk")
    || apks.find((a) => /release/i.test(a.name))
    || apks[0];
}

/** 一个安装包在 /version 里的呈现:地址带上 abi / prerelease,之后 /apk 才取得回同一个包。 */
function apkInfo(asset, { abi, includePrerelease }) {
  if (!asset) return { apkUrl: "", apkSize: 0, apkSha256: "" };
  const params = new URLSearchParams();
  if (abi) params.set("abi", abi);
  if (includePrerelease) params.set("prerelease", "1");
  const query = params.toString();
  return {
    apkUrl: "/apk" + (query ? `?${query}` : ""),
    apkSize: asset.size || 0,
    apkSha256: String(asset.digest || "").replace(/^sha256:/, ""),
  };
}

// 按架构列出全部安装包,由客户端认领本机那一个 ——
// 这样 /version 的响应不随架构变化,CDN 缓存与 GitHub 配额都不会被拆成好几份。
function apksByAbi(rel, includePrerelease) {
  const map = {};
  for (const asset of releaseApks(rel)) {
    const abi = abiOf(asset.name);
    if (abi && !map[abi]) map[abi] = apkInfo(asset, { abi, includePrerelease });
  }
  return map;
}

// GET /version → 最新版本信息(客户端据此判断是否更新)。
// `apks` 按架构分别给出安装包,顶层字段是通吃包(不带 abi 的老客户端兜底升级用)。
// 安装包大小与 SHA-256 一并给出:客户端据此显示进度、校验完整性(代理响应的长度不可靠)。
//
// ?prerelease=1 时把预发行版也纳入比较(否则 GitHub 的 latest 永远只有正式版)。
// 预发行版的响应不进缓存:万一 CDN 的缓存键没带上查询串,也不至于把 RC 混给正式版用户。
async function handleVersion(url) {
  const includePrerelease = url.searchParams.get("prerelease") === "1";
  try {
    const rel = await pickRelease(includePrerelease);
    return jsonWithCache(200, {
      versionName: String(rel.tag_name || "").replace(/^v/, ""),
      notes: rel.body || "",
      releaseUrl: rel.html_url || `https://github.com/${GITHUB_REPO}/releases`,
      apks: apksByAbi(rel, includePrerelease),
      ...apkInfo(pickApk(rel, ""), { includePrerelease }),
      publishedAt: rel.published_at || "",
    }, includePrerelease ? 0 : 600);
  } catch (e) {
    return json(502, { error: { code: 502, message: "获取最新版本失败: " + e.message } });
  }
}

// 单个响应的安全上限:Netlify 函数响应超过 ~6MB 会被边缘截断(Content-Length 还在,内容却少了),
// 客户端会因此拿到损坏的安装包。因此:整体下载超出上限时,由客户端用 Range 分段拉取,这里透传。
const MAX_INLINE_BYTES = 5 * 1024 * 1024;

// GET /apk → 代理对应版本的 APK(?abi= 指定架构,缺省给通吃包)。
// - 带 Range 时透传 Range,并把上游的 206 / Content-Range 原样返回(每段都远小于响应上限);
// - 不带 Range 且整包超过上限时明确报错,绝不返回半截文件。
async function handleApk(req, url) {
  const includePrerelease = url.searchParams.get("prerelease") === "1";
  const abi = (url.searchParams.get("abi") || "").trim();
  try {
    const rel = await pickRelease(includePrerelease);
    const asset = pickApk(rel, abi);
    if (!asset) {
      return json(404, { error: { code: 404, message: "最新版本没有可下载的 APK" } });
    }
    const range = req.headers.get("range");
    if (!range && (asset.size || 0) > MAX_INLINE_BYTES) {
      return json(409, {
        error: {
          code: 409,
          message: `安装包 ${asset.size} 字节超过单次响应上限,请带 Range 分段下载`,
        },
      });
    }
    const upstreamHeaders = {
      "User-Agent": "classtable-proxy",
      // 明确要求不压缩:压缩后长度与解压后不一致,客户端的完整性校验会误判
      "Accept-Encoding": "identity",
    };
    if (range) upstreamHeaders.Range = range;

    const apkResp = await fetch(asset.browser_download_url, {
      redirect: "follow",
      headers: upstreamHeaders,
    });
    if (!apkResp.ok || !apkResp.body) {
      return json(502, { error: { code: 502, message: `下载 APK 失败(HTTP ${apkResp.status})` } });
    }
    const headers = {
      "Content-Type": "application/vnd.android.package-archive",
      "Accept-Ranges": "bytes",
      // 分段响应不进缓存:边缘缓存住的内容可能已是被截断的
      "Cache-Control": range ? "no-store" : "public, max-age=600",
    };
    const len = apkResp.headers.get("content-length");
    if (len) headers["Content-Length"] = len;
    const contentRange = apkResp.headers.get("content-range");
    if (contentRange) headers["Content-Range"] = contentRange;
    return new Response(apkResp.body, {
      status: apkResp.status === 206 ? 206 : 200,
      headers,
    });
  } catch (e) {
    return json(502, { error: { code: 502, message: "下载 APK 失败: " + e.message } });
  }
}

export default async (req) => {
  const url = new URL(req.url);
  const rest = url.pathname.startsWith(PREFIX) ? url.pathname.slice(PREFIX.length) : url.pathname;

  if (rest === "/push") {
    return handlePush(req);
  }
  if (rest === "/version") {
    return handleVersion(url);
  }
  if (rest === "/apk") {
    return handleApk(req, url);
  }

  let upstream = null;
  if (rest.startsWith("/auth/")) {
    upstream = AUTH_UPSTREAM + rest.slice("/auth".length);
  } else if (rest.startsWith("/firestore/")) {
    upstream = FIRESTORE_UPSTREAM + rest.slice("/firestore".length);
  }

  if (!upstream) {
    return json(404, { error: { code: 404, message: "unknown path: " + rest } });
  }

  const method = req.method || "GET";
  const headers = { "Content-Type": "application/json" };
  const authorization = req.headers.get("authorization");
  if (authorization) headers.Authorization = authorization;

  // GET/HEAD 无 body;其余方法读取请求体(可能为空串)
  let body;
  if (method !== "GET" && method !== "HEAD") {
    body = await req.text();
  }

  try {
    const resp = await fetch(upstream + (url.search || ""), {
      method,
      headers,
      body: body || undefined,
    });
    const text = await resp.text();
    return new Response(text, {
      status: resp.status,
      headers: {
        "Content-Type": resp.headers.get("content-type") || "application/json",
        "Access-Control-Allow-Origin": "*", // 预留 Web 端直连;移动端不需要但无害
      },
    });
  } catch (e) {
    return json(502, { error: { code: 502, message: "upstream error: " + e.message } });
  }
};

function json(status, obj) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

/** maxAge <= 0 表示不进缓存(用于会随开关变化的 /version 响应)。 */
function jsonWithCache(status, obj, maxAge) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": maxAge > 0 ? `public, max-age=${maxAge}` : "no-store",
    },
  });
}
