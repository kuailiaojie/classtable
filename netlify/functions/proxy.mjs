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

export default async (req) => {
  const url = new URL(req.url);
  const rest = url.pathname.startsWith(PREFIX) ? url.pathname.slice(PREFIX.length) : url.pathname;

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
