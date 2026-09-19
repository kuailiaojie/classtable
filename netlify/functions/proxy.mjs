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

// 从 Release 资产中挑安装包:必须优先 release,绝不把 debug 包发给用户。
// (未配置签名时 CI 产出 app-release-unsigned.apk,旧的「取第一个 .apk」会误选 app-debug.apk)
function pickApk(rel) {
  const apks = (rel.assets || []).filter((a) => a.name.endsWith(".apk"));
  return apks.find((a) => a.name === "app-release.apk")
    || apks.find((a) => /release/i.test(a.name))
    || apks.find((a) => !/debug/i.test(a.name))
    || apks[0];
}

// GET /version → 最新版本信息(客户端据此判断是否更新)。
async function handleVersion() {
  try {
    const rel = await latestRelease();
    const apk = pickApk(rel);
    return jsonWithCache(200, {
      versionName: String(rel.tag_name || "").replace(/^v/, ""),
      notes: rel.body || "",
      releaseUrl: rel.html_url || `https://github.com/${GITHUB_REPO}/releases`,
      apkUrl: apk ? "/apk" : "",
      publishedAt: rel.published_at || "",
    }, 600);
  } catch (e) {
    return json(502, { error: { code: 502, message: "获取最新版本失败: " + e.message } });
  }
}

// GET /apk → 流式代理最新 release 的 APK(下载走 Netlify 域名,规避 GitHub 资产域名不稳)。
async function handleApk() {
  try {
    const rel = await latestRelease();
    const asset = pickApk(rel);
    if (!asset) {
      return json(404, { error: { code: 404, message: "最新版本没有可下载的 APK" } });
    }
    const apkResp = await fetch(asset.browser_download_url, {
      redirect: "follow",
      headers: { "User-Agent": "classtable-proxy" },
    });
    if (!apkResp.ok || !apkResp.body) {
      return json(502, { error: { code: 502, message: `下载 APK 失败(HTTP ${apkResp.status})` } });
    }
    const headers = {
      "Content-Type": "application/vnd.android.package-archive",
      "Cache-Control": "public, max-age=600",
    };
    const len = apkResp.headers.get("content-length");
    if (len) headers["Content-Length"] = len;
    return new Response(apkResp.body, { status: 200, headers });
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
    return handleVersion();
  }
  if (rest === "/apk") {
    return handleApk();
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
    headers: { "Content-Type": "application/json" },
  });
}

function jsonWithCache(status, obj, maxAge) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": `public, max-age=${maxAge}`,
    },
  });
}
