import { createHash } from "node:crypto";
import type { Hex } from "viem";

export const LOT_REGISTERED = 1;
export const SCHEMA_V1 = 1;
export const ZERO_ID: Hex = "0x00000000000000000000000000000000";
export const ZERO_DIGEST: Hex = "0x" + "00".repeat(32) as Hex;

export interface FreshSenseEvidence {
  id: string;
  lotId: string;
  businessEventId: string;
  type: string;
  schemaVersion: number;
  canonicalSnapshot: string;
  sha256: string;
  status: string;
}

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Java UUID textual order, without separators. No hashing, padding or byte-order changes.
export function uuidToBytes16(uuid: string): Hex {
  if (!uuidPattern.test(uuid)) throw new Error("UUID inválido: se espera el formato 8-4-4-4-12");
  const bytes = "0x" + uuid.replaceAll("-", "").toLowerCase() as Hex;
  if (bytes === ZERO_ID) throw new Error("UUID nulo");
  return bytes;
}

export function bytes16ToUuid(bytes: Hex): string {
  if (!/^0x[0-9a-f]{32}$/i.test(bytes) || bytes.toLowerCase() === ZERO_ID) {
    throw new Error("Se esperan 16 bytes no nulos");
  }
  const hex = bytes.slice(2).toLowerCase();
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

export function sha256ToBytes32(value: string): Hex {
  const hex = value.replace(/^0x/i, "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(hex)) throw new Error("Se esperan 64 dígitos hexadecimales de SHA-256");
  return "0x" + hex as Hex;
}

export function hashSnapshot(canonicalSnapshot: string): Hex {
  return sha256ToBytes32(createHash("sha256").update(canonicalSnapshot, "utf8").digest("hex"));
}

// Phase 1 v1 is a flat JSON object with ASCII keys and only text, null and the integer schemaVersion.
export function canonicalizeV1(snapshot: Record<string, unknown>): string {
  if (snapshot.schemaVersion !== SCHEMA_V1 || snapshot.domain !== "freshsense.trace.lot-registered") {
    throw new Error("Esquema canónico FreshSense no soportado");
  }
  const entries = Object.keys(snapshot).sort().map((key) => {
    const value = snapshot[key];
    if (!/^[A-Za-z][A-Za-z0-9]*$/.test(key) ||
        (key !== "schemaVersion" && !(key === "expiresOn" && value === null) &&
          (typeof value !== "string" || Buffer.from(value, "utf8").toString("utf8") !== value))) {
      throw new Error("Campo incompatible con la evidencia v1: " + key);
    }
    return [key, value];
  });
  return JSON.stringify(Object.fromEntries(entries));
}

export function prepareEvidence(evidence: FreshSenseEvidence) {
  if (evidence.type !== "LOT_REGISTERED" || evidence.schemaVersion !== SCHEMA_V1 ||
      evidence.status !== "LOCAL_PENDING_BLOCKCHAIN") {
    throw new Error("Solo se admiten evidencias locales LOT_REGISTERED v1");
  }

  const snapshot = JSON.parse(evidence.canonicalSnapshot) as Record<string, unknown>;
  if (snapshot.evidenceId !== evidence.id || snapshot.lotId !== evidence.lotId ||
      snapshot.businessEventId !== evidence.businessEventId || snapshot.eventType !== evidence.type ||
      canonicalizeV1(snapshot) !== evidence.canonicalSnapshot) {
    throw new Error("Los metadatos o bytes canónicos de la evidencia son inconsistentes");
  }

  const digest = sha256ToBytes32(evidence.sha256);
  if (digest === ZERO_DIGEST || digest !== hashSnapshot(evidence.canonicalSnapshot)) {
    throw new Error("El SHA-256 no coincide con los bytes UTF-8 de FreshSense");
  }

  const initialQuantity = snapshot.initialQuantity;
  if (typeof initialQuantity !== "string" || !/^\d{1,12}\.\d{3}$/.test(initialQuantity)) {
    throw new Error("Cantidad canónica v1 inválida");
  }
  const tamperedSnapshot = { ...snapshot, initialQuantity: initialQuantity === "1.000" ? "2.000" : "1.000" };
  return {
    args: [uuidToBytes16(evidence.id), uuidToBytes16(evidence.lotId), digest, LOT_REGISTERED, SCHEMA_V1] as const,
    tamperedDigest: hashSnapshot(canonicalizeV1(tamperedSnapshot)),
  };
}
