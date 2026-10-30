#!/usr/bin/env node
/**
 * Cross-platform end-to-end staging smoke test (S0-08).
 * Exercises: frontend SPA shell + headers -> actuator port isolation -> register (Secure cookie)
 * -> login -> synthetic CSV statement import -> dashboard analytics & recommendations.
 */
import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");

const WEB_URL = process.env.STAGING_WEB_URL ?? "http://127.0.0.1:14173";
const API_URL = process.env.STAGING_API_URL ?? "http://127.0.0.1:18080";

function assert(condition, message) {
  if (!condition) {
    throw new Error(`Staging smoke assertion failed: ${message}`);
  }
}

async function main() {
  console.log(`[1/6] Checking frontend container (${WEB_URL})...`);
  const spaRes = await fetch(`${WEB_URL}/`);
  assert(spaRes.status === 200, `Expected 200 from frontend root, got ${spaRes.status}`);
  assert(spaRes.headers.get("x-content-type-options") === "nosniff", "Missing X-Content-Type-Options: nosniff");
  assert(spaRes.headers.get("x-frame-options") === "DENY", "Missing X-Frame-Options: DENY");
  assert(Boolean(spaRes.headers.get("content-security-policy")), "Missing Content-Security-Policy header");
  const html = await spaRes.text();
  assert(html.includes('<div id="root">'), "SPA HTML missing #root mount point");

  console.log(`[2/6] Verifying /actuator/* is rejected on public API port (${API_URL})...`);
  for (const path of ["/actuator", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"]) {
    const actRes = await fetch(`${API_URL}${path}`);
    assert(actRes.status === 401, `Expected 401 for ${path} on public port, got ${actRes.status}`);
  }

  const email = `smoke-${Date.now()}@example.com`;
  const password = "SyntheticPassword123!";

  console.log(`[3/6] Registering synthetic user (${email}) via frontend /api proxy...`);
  const regRes = await fetch(`${WEB_URL}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "Staging Smoke User", email, password }),
  });
  assert(regRes.status === 200 || regRes.status === 201, `Register failed with ${regRes.status}`);
  const setCookie = regRes.headers.get("set-cookie") ?? "";
  assert(setCookie.includes("refreshToken="), "Register response missing refreshToken cookie");
  assert(setCookie.toLowerCase().includes("secure"), "Register refreshToken cookie missing Secure attribute in prod profile");
  assert(setCookie.toLowerCase().includes("httponly"), "Register refreshToken cookie missing HttpOnly attribute");

  console.log(`[4/6] Logging in as synthetic user...`);
  const loginRes = await fetch(`${WEB_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  assert(loginRes.status === 200, `Login failed with ${loginRes.status}`);
  const { accessToken } = await loginRes.json();
  assert(typeof accessToken === "string" && accessToken.length > 20, "Missing accessToken from login");
  const authHeaders = { Authorization: `Bearer ${accessToken}` };

  console.log(`[5/6] Importing synthetic HDFC statement fixture...`);
  const csvPath = resolve(ROOT, "backend/src/test/resources/fixtures/statements/hdfc.csv");
  const csvBytes = readFileSync(csvPath);
  const form = new FormData();
  form.append("file", new Blob([csvBytes], { type: "text/csv" }), "hdfc.csv");

  const uploadRes = await fetch(`${WEB_URL}/api/upload/csv`, {
    method: "POST",
    headers: authHeaders,
    body: form,
  });
  assert(uploadRes.status === 202, `CSV upload expected 202, got ${uploadRes.status}`);
  const { jobId } = await uploadRes.json();
  assert(Boolean(jobId), "Upload response missing jobId");

  let jobStatus = null;
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const statusRes = await fetch(`${WEB_URL}/api/upload/status/${jobId}`, { headers: authHeaders });
    assert(statusRes.status === 200, `Upload status poll failed with ${statusRes.status}`);
    jobStatus = await statusRes.json();
    if (jobStatus.status === "COMPLETED" || jobStatus.status === "FAILED") {
      break;
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  assert(jobStatus?.status === "COMPLETED", `Expected import COMPLETED, got ${JSON.stringify(jobStatus)}`);

  console.log(`[6/6] Verifying ledger, analytics summary, and recommendations...`);
  const txRes = await fetch(`${WEB_URL}/api/transactions`, { headers: authHeaders });
  assert(txRes.status === 200, `Transactions GET failed with ${txRes.status}`);
  const txList = await txRes.json();
  assert(Array.isArray(txList) && txList.length === 3, `Expected 3 imported transactions, got ${txList?.length}`);

  const summaryRes = await fetch(`${WEB_URL}/api/analytics/summary`, { headers: authHeaders });
  assert(summaryRes.status === 200, `Analytics summary GET failed with ${summaryRes.status}`);

  const recRes = await fetch(`${WEB_URL}/api/recommendations`, { headers: authHeaders });
  assert(recRes.status === 200, `Recommendations GET failed with ${recRes.status}`);

  console.log("Staging smoke checks passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
