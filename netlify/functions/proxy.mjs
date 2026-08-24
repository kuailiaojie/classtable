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

const AUTH_UPSTREAM = "https://identitytoolkit.googleapis.com";
const FIRESTORE_UPSTREAM = "https://firestore.googleapis.com";
const PREFIX = "/.netlify/functions/proxy";

export default async (event) => {
  const rest = event.path.startsWith(PREFIX) ? event.path.slice(PREFIX.length) : event.path;

  let upstream = null;
  if (rest.startsWith("/auth/")) {
    upstream = AUTH_UPSTREAM + rest.slice("/auth".length);
  } else if (rest.startsWith("/firestore/")) {
    upstream = FIRESTORE_UPSTREAM + rest.slice("/firestore".length);
  }

  if (!upstream) {
    return json(404, { error: { code: 404, message: "unknown path: " + rest } });
  }

  const query = event.rawQuery ? `?${event.rawQuery}` : "";
  const method = (event.httpMethod || "GET").toUpperCase();

  const headers = { "Content-Type": "application/json" };
  if (event.headers.authorization) headers.Authorization = event.headers.authorization;

  const hasBody = event.body != null && event.body.length > 0 && method !== "GET" && method !== "HEAD";

  try {
    const resp = await fetch(upstream + query, {
      method,
      headers,
      body: hasBody ? event.body : undefined,
    });
    const text = await resp.text();
    return {
      statusCode: resp.status,
      headers: {
        "Content-Type": resp.headers.get("content-type") || "application/json",
        "Access-Control-Allow-Origin": "*", // 预留 Web 端直连;移动端不需要但无害
      },
      body: text,
    };
  } catch (e) {
    return json(502, { error: { code: 502, message: "upstream error: " + e.message } });
  }
};

function json(statusCode, body) {
  return {
    statusCode,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  };
}
