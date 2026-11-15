import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { network } from "hardhat";
import { prepareEvidence } from "./evidence.ts";
import { generateFreshSenseEvidence } from "./freshsense-local.ts";
import { LOCAL_CHAIN_ID } from "./local-network.ts";
import { proofFile, type LocalProof } from "./local-proof.ts";

const connection = await network.create("localhost");
try {
  const { viem } = connection;
  const publicClient = await viem.getPublicClient();
  assert.equal(await publicClient.getChainId(), LOCAL_CHAIN_ID, "El RPC no usa el chain ID local");
  const [admin, writer] = await viem.getWalletClients();
  assert.ok(admin?.account && writer?.account, "El nodo debe exponer dos cuentas efímeras");

  const evidence = await generateFreshSenseEvidence();
  const { args, tamperedDigest } = prepareEvidence(evidence);
  const { deploymentTransaction, contract } = await viem.sendDeploymentTransaction(
    "FreshSenseTraceability",
    [admin.account.address, writer.account.address],
    { client: { wallet: admin } },
  );
  const deployed = await publicClient.waitForTransactionReceipt({ hash: deploymentTransaction.hash });
  assert.equal(deployed.status, "success");
  assert.equal(deployed.contractAddress?.toLowerCase(), contract.address.toLowerCase());

  const transaction = await contract.write.recordEvidence(args, { account: writer.account });
  const receipt = await publicClient.waitForTransactionReceipt({ hash: transaction });
  assert.equal(receipt.status, "success");
  const proof: LocalProof = {
    chainId: LOCAL_CHAIN_ID,
    contract: contract.address,
    admin: admin.account.address,
    writer: writer.account.address,
    deploymentTransaction: deploymentTransaction.hash,
    transaction,
    blockNumber: receipt.blockNumber.toString(),
    evidenceId: evidence.id,
    lotId: evidence.lotId,
    eventType: args[3],
    schemaVersion: args[4],
    freshSenseSha256: evidence.sha256,
    digest: args[2],
    alteredDigest: tamperedDigest,
  };
  await mkdir(new URL("./", proofFile), { recursive: true });
  await writeFile(proofFile, JSON.stringify(proof, null, 2) + "\n");
  console.log(JSON.stringify(proof, null, 2));
} finally {
  await connection.close();
}
