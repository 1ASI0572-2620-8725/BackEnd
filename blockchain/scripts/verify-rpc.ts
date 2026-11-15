import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createPublicClient, decodeEventLog, http, type Abi } from "viem";
import { uuidToBytes16, bytes16ToUuid, sha256ToBytes32 } from "./evidence.ts";
import { LOCAL_CHAIN_ID, LOCAL_RPC_URL } from "./local-network.ts";
import { artifactFile, proofFile, type LocalProof } from "./local-proof.ts";

const proof = JSON.parse(await readFile(proofFile, "utf8")) as LocalProof;
const { abi } = JSON.parse(await readFile(artifactFile, "utf8")) as { abi: Abi };
const rpc = createPublicClient({ transport: http(LOCAL_RPC_URL, { retryCount: 0, timeout: 8_000 }) });

assert.equal(await rpc.getChainId(), LOCAL_CHAIN_ID);
assert.equal(proof.chainId, LOCAL_CHAIN_ID);
const evidenceId = uuidToBytes16(proof.evidenceId);
const lotId = uuidToBytes16(proof.lotId);
assert.equal(sha256ToBytes32(proof.freshSenseSha256), proof.digest);
assert.notEqual(proof.digest, proof.alteredDigest);

const deployment = await rpc.getTransactionReceipt({ hash: proof.deploymentTransaction });
assert.equal(deployment.status, "success");
assert.equal(deployment.contractAddress?.toLowerCase(), proof.contract.toLowerCase());
const receipt = await rpc.getTransactionReceipt({ hash: proof.transaction });
assert.equal(receipt.status, "success");
assert.equal(receipt.from.toLowerCase(), proof.writer.toLowerCase());
assert.equal(receipt.to?.toLowerCase(), proof.contract.toLowerCase());
const blockNumber = BigInt(proof.blockNumber);
assert.equal(receipt.blockNumber, blockNumber);
const block = await rpc.getBlock({ blockNumber });
assert.equal(block.hash, receipt.blockHash);
assert.ok(block.transactions.includes(proof.transaction));

const logs = await rpc.getLogs({ address: proof.contract, fromBlock: blockNumber, toBlock: blockNumber });
const transactionLogs = logs.filter((log) => log.transactionHash === proof.transaction);
assert.equal(transactionLogs.length, 1);
const [log] = transactionLogs;
assert.equal(log.address.toLowerCase(), proof.contract.toLowerCase());
assert.ok(receipt.logs.some((entry) => entry.logIndex === log.logIndex && entry.data === log.data &&
  entry.address.toLowerCase() === proof.contract.toLowerCase()));
const event = decodeEventLog({ abi, data: log.data, topics: log.topics });
assert.equal(event.eventName, "EvidenceRecorded");
assert.deepEqual(event.args, {
  evidenceId, lotId, digest: proof.digest, eventType: proof.eventType, schemaVersion: proof.schemaVersion,
});

const target = { address: proof.contract, abi } as const;
const stored = await rpc.readContract({ ...target, functionName: "getEvidence", args: [evidenceId] });
assert.deepEqual(stored, {
  lotId, eventType: proof.eventType, schemaVersion: proof.schemaVersion, exists: true, digest: proof.digest,
});
const original = await rpc.readContract({
  ...target, functionName: "verifyEvidence", args: [evidenceId, proof.digest],
});
const altered = await rpc.readContract({
  ...target, functionName: "verifyEvidence", args: [evidenceId, proof.alteredDigest],
});
assert.deepEqual(original, [true, true]);
assert.deepEqual(altered, [true, false]);

console.log(JSON.stringify({
  rpc: LOCAL_RPC_URL, chainId: LOCAL_CHAIN_ID, contract: proof.contract, transaction: proof.transaction,
  blockNumber: proof.blockNumber, blockHash: receipt.blockHash, receipt: receipt.status, logIndex: log.logIndex,
  event: event.eventName, evidenceId: bytes16ToUuid(evidenceId), lotId: bytes16ToUuid(lotId),
  digest: proof.digest, alteredDigest: proof.alteredDigest, original, altered,
}, null, 2));
