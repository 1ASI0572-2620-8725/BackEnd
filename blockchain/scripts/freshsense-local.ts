import { randomUUID } from "node:crypto";
import type { FreshSenseEvidence } from "./evidence.ts";

const API = "http://127.0.0.1:8080";

async function request<T>(path: string, expectedStatus: number, data?: object, token?: string): Promise<T> {
  const response = await fetch(API + path, {
    method: data === undefined ? "GET" : "POST",
    headers: {
      Accept: "application/json",
      ...(data === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token === undefined ? {} : { Authorization: "Bearer " + token }),
    },
    body: data === undefined ? undefined : JSON.stringify(data),
    redirect: "error",
    signal: AbortSignal.timeout(10_000),
  });
  if (response.status !== expectedStatus) {
    throw new Error(path + " devolvió " + response.status + ", se esperaba " + expectedStatus);
  }
  return await response.json() as T;
}

export async function generateFreshSenseEvidence(): Promise<FreshSenseEvidence> {
  const suffix = randomUUID().slice(0, 8);
  const { token } = await request<{ token: string }>("/api/accounts/register", 201, {
    email: "fase2-" + suffix + "@example.test",
    password: "Lab9-" + randomUUID(),
    fullName: "Prueba Blockchain",
  });
  if (!token) throw new Error("FreshSense no devolvió un token de prueba");

  const product = await request<{ id: number }>("/api/products", 201, {
    name: "Manzana demostracion " + suffix,
    category: "Frutas",
    quantity: 9,
    expirationDate: new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10),
  }, token);

  const root = "/api/traceability";
  const lot = await request<{ id: string; initialEvidenceId: string }>(root + "/lots", 201, {
    code: "FASE2-" + suffix.toUpperCase(),
    productId: product.id,
    initialQuantity: 2.5,
    unit: "KG",
    receivedAt: new Date().toISOString(),
  }, token);
  const path = root + "/evidences/" + encodeURIComponent(lot.initialEvidenceId);
  const evidence = await request<FreshSenseEvidence>(path, 200, undefined, token);
  const verified = await request<{ matches: boolean; status: string }>(path + "/verify", 200, undefined, token);
  if (!verified.matches || evidence.lotId !== lot.id || evidence.id !== lot.initialEvidenceId) {
    throw new Error("FreshSense no verificó la evidencia canónica que acaba de generar");
  }
  return evidence;
}
