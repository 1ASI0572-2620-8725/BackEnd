import type { Address, Hash, Hex } from "viem";

export const proofFile = new URL("../.local/anchor.json", import.meta.url);
export const artifactFile = new URL(
  "../artifacts/contracts/FreshSenseTraceability.sol/FreshSenseTraceability.json", import.meta.url,
);

export interface LocalProof {
  chainId: number;
  contract: Address;
  admin: Address;
  writer: Address;
  deploymentTransaction: Hash;
  transaction: Hash;
  blockNumber: string;
  evidenceId: string;
  lotId: string;
  eventType: number;
  schemaVersion: number;
  freshSenseSha256: string;
  digest: Hex;
  alteredDigest: Hex;
}
