import assert from "node:assert/strict";
import { network } from "hardhat";
import { parseEther, type Hex } from "viem";
import { privateKeyToAccount } from "viem/accounts";
import { LOCAL_CHAIN_ID } from "./local-network.ts";

const key = process.env.FRESHSENSE_BLOCKCHAIN_WRITER_PRIVATE_KEY;
assert.ok(key && /^(?:0x)?[0-9a-fA-F]{64}$/.test(key), "Configura una clave efímera de laboratorio");
const writer = privateKeyToAccount((key.startsWith("0x") ? key : "0x" + key) as Hex).address;
const connection = await network.create("localhost");
try {
  const client = await connection.viem.getPublicClient();
  assert.equal(await client.getChainId(), LOCAL_CHAIN_ID);
  const [admin] = await connection.viem.getWalletClients();
  assert.notEqual(admin.account.address.toLowerCase(), writer.toLowerCase());
  if (await client.getBalance({ address: writer }) < parseEther("1")) {
    const funding = await admin.sendTransaction({ to: writer, value: parseEther("1") });
    assert.equal((await client.waitForTransactionReceipt({ hash: funding })).status, "success");
  }
  const { contract, deploymentTransaction } = await connection.viem.sendDeploymentTransaction(
    "FreshSenseTraceability", [admin.account.address, writer], { client: { wallet: admin } },
  );
  const receipt = await client.waitForTransactionReceipt({ hash: deploymentTransaction.hash });
  assert.equal(receipt.status, "success");
  assert.equal(receipt.contractAddress?.toLowerCase(), contract.address.toLowerCase());
  console.log(JSON.stringify({
    chainId: LOCAL_CHAIN_ID, contractAddress: contract.address,
    deploymentTransaction: deploymentTransaction.hash, writerAddress: writer,
    deploymentBlock: receipt.blockNumber.toString(),
  }));
} finally {
  await connection.close();
}
