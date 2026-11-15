import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { afterEach, beforeEach, describe, it } from "node:test";
import { network } from "hardhat";
import { BaseError, ContractFunctionRevertedError, decodeEventLog, type Hex } from "viem";
import {
  bytes16ToUuid, hashSnapshot, LOT_REGISTERED, prepareEvidence, SCHEMA_V1,
  sha256ToBytes32, uuidToBytes16, ZERO_DIGEST, ZERO_ID,
} from "../scripts/evidence.ts";

const evidenceId = uuidToBytes16("0d4dadba-4f88-44ee-a677-17e690c2a7df");
const lotId = uuidToBytes16("28a93dc1-5337-4c7b-94b3-851a8aa4fdd3");
const nextEvidence = uuidToBytes16("f374efb3-6cbc-4afb-91ec-8a421cd31b77");
const nextLot = uuidToBytes16("7e056ed2-7c42-4ae4-a588-8ffe565a656a");
const digest = hashSnapshot("evidencia de prueba");
const changedDigest = hashSnapshot("evidencia de prueba alterada");
const input = [evidenceId, lotId, digest, LOT_REGISTERED, SCHEMA_V1] as const;

async function deployFixture() {
  const connection = await network.create("freshsenseLocal");
  const { viem } = connection;
  const [admin, writer, outsider] = await viem.getWalletClients();
  const client = await viem.getPublicClient();
  const contract = await viem.deployContract("FreshSenseTraceability", [admin.account.address, writer.account.address]);
  const writerRole = await contract.read.EVIDENCE_WRITER_ROLE();
  return { connection, admin, writer, outsider, client, contract, writerRole };
}

async function reverts(promise: Promise<unknown>, errorName: string) {
  await assert.rejects(promise, (error: unknown) => {
    if (!(error instanceof BaseError)) return false;
    const revert = error.walk((cause) => cause instanceof ContractFunctionRevertedError);
    return revert instanceof ContractFunctionRevertedError && revert.data?.errorName === errorName;
  });
}

describe("FreshSenseTraceability", () => {
  let fixture: Awaited<ReturnType<typeof deployFixture>>;

  beforeEach(async () => { fixture = await deployFixture(); });
  afterEach(async () => { await fixture?.connection.close(); });

  async function record(args: typeof input = input) {
    const transaction = await fixture.contract.write.recordEvidence(args, { account: fixture.writer.account });
    const receipt = await fixture.client.waitForTransactionReceipt({ hash: transaction });
    assert.equal(receipt.status, "success");
    return receipt;
  }

  it("despliega bytecode en Hardhat", async () => {
    const code = await fixture.client.getBytecode({ address: fixture.contract.address });
    assert.ok(code && code.length > 2);
  });

  it("inicializa roles distintos de administrador y escritor", async () => {
    const { contract, admin, writer, outsider, writerRole } = fixture;
    const adminRole = await contract.read.DEFAULT_ADMIN_ROLE();
    assert.equal(await contract.read.hasRole([adminRole, admin.account.address]), true);
    assert.equal(await contract.read.hasRole([writerRole, admin.account.address]), false);
    assert.equal(await contract.read.hasRole([adminRole, writer.account.address]), false);
    assert.equal(await contract.read.hasRole([writerRole, writer.account.address]), true);
    assert.equal(await contract.read.hasRole([writerRole, outsider.account.address]), false);
  });

  it("registra una evidencia mediante una transacción minada", async () => {
    const receipt = await record();
    assert.ok(receipt.blockNumber > 0n);
    assert.equal(receipt.from.toLowerCase(), fixture.writer.account.address.toLowerCase());
  });

  it("devuelve los metadatos almacenados por referencia", async () => {
    await record();
    assert.deepEqual(await fixture.contract.read.getEvidence([evidenceId]), {
      lotId, eventType: LOT_REGISTERED, schemaVersion: SCHEMA_V1, exists: true, digest,
    });
  });

  it("distingue una evidencia inexistente", async () => {
    assert.deepEqual(await fixture.contract.read.getEvidence([evidenceId]), {
      lotId: ZERO_ID, eventType: 0, schemaVersion: 0, exists: false, digest: ZERO_DIGEST,
    });
    assert.deepEqual(await fixture.contract.read.verifyEvidence([evidenceId, digest]), [false, false]);
  });

  it("verifica positivamente el digest original", async () => {
    await record();
    assert.deepEqual(await fixture.contract.read.verifyEvidence([evidenceId, digest]), [true, true]);
  });

  it("devuelve existencia y discrepancia para un digest alterado", async () => {
    await record();
    assert.deepEqual(await fixture.contract.read.verifyEvidence([evidenceId, changedDigest]), [true, false]);
  });

  it("rechaza una referencia duplicada incluso con los mismos datos", async () => {
    await record();
    await reverts(fixture.contract.simulate.recordEvidence(input, { account: fixture.writer.account.address }),
      "EvidenceAlreadyRecorded");
  });

  it("rechaza el digest cero", async () => {
    await reverts(fixture.contract.simulate.recordEvidence(
      [evidenceId, lotId, ZERO_DIGEST, LOT_REGISTERED, SCHEMA_V1], { account: fixture.writer.account.address },
    ), "InvalidDigest");
  });

  it("rechaza referencias nulas de evidencia y lote", async () => {
    const { contract, writer } = fixture;
    await reverts(contract.simulate.recordEvidence(
      [ZERO_ID, lotId, digest, LOT_REGISTERED, SCHEMA_V1], { account: writer.account.address },
    ), "InvalidEvidenceId");
    await reverts(contract.simulate.recordEvidence(
      [evidenceId, ZERO_ID, digest, LOT_REGISTERED, SCHEMA_V1], { account: writer.account.address },
    ), "InvalidLotId");
  });

  it("rechaza tipos y versiones fuera de LOT_REGISTERED v1", async () => {
    const { contract, writer } = fixture;
    await reverts(contract.simulate.recordEvidence(
      [evidenceId, lotId, digest, 0, SCHEMA_V1], { account: writer.account.address },
    ), "UnsupportedEventType");
    await reverts(contract.simulate.recordEvidence(
      [evidenceId, lotId, digest, LOT_REGISTERED, 2], { account: writer.account.address },
    ), "UnsupportedSchemaVersion");
  });

  it("rechaza al administrador y a terceros cuando carecen del rol de escritura", async () => {
    const { contract, admin, outsider } = fixture;
    for (const wallet of [admin, outsider]) {
      await reverts(contract.simulate.recordEvidence(input, { account: wallet.account.address }),
        "AccessControlUnauthorizedAccount");
    }
  });

  it("solo permite al administrador conceder el rol y habilita al nuevo escritor", async () => {
    const { client, contract, admin, writer, outsider, writerRole } = fixture;
    await reverts(contract.simulate.grantRole([writerRole, outsider.account.address], { account: writer.account.address }),
      "AccessControlUnauthorizedAccount");
    const grant = await contract.write.grantRole([writerRole, outsider.account.address], { account: admin.account });
    assert.equal((await client.waitForTransactionReceipt({ hash: grant })).status, "success");
    assert.equal(await contract.read.hasRole([writerRole, outsider.account.address]), true);
    const transaction = await contract.write.recordEvidence(input, { account: outsider.account });
    assert.equal((await client.waitForTransactionReceipt({ hash: transaction })).status, "success");
  });

  it("permite al administrador revocar el rol y conserva las evidencias existentes", async () => {
    const { contract, admin, writer, writerRole, client } = fixture;
    await record();
    const hash = await contract.write.revokeRole([writerRole, writer.account.address], { account: admin.account });
    assert.equal((await client.waitForTransactionReceipt({ hash })).status, "success");
    assert.equal(await contract.read.hasRole([writerRole, writer.account.address]), false);
    assert.deepEqual(await contract.read.verifyEvidence([evidenceId, digest]), [true, true]);
  });

  it("rechaza escrituras después de revocar permisos", async () => {
    const { contract, admin, writer, writerRole, client } = fixture;
    const hash = await contract.write.revokeRole([writerRole, writer.account.address], { account: admin.account });
    await client.waitForTransactionReceipt({ hash });
    await reverts(contract.simulate.recordEvidence(input, { account: writer.account.address }),
      "AccessControlUnauthorizedAccount");
  });

  it("emite un log verificable desde la dirección del contrato y coincide con el estado", async () => {
    const receipt = await record();
    assert.equal(receipt.logs.length, 1);
    const [log] = receipt.logs;
    assert.equal(log.address.toLowerCase(), fixture.contract.address.toLowerCase());
    assert.equal(log.transactionHash, receipt.transactionHash);
    assert.equal(log.blockNumber, receipt.blockNumber);
    assert.equal(log.topics.length, 3);
    const event = decodeEventLog({ abi: fixture.contract.abi, data: log.data, topics: log.topics });
    assert.equal(event.eventName, "EvidenceRecorded");
    assert.deepEqual(event.args, { evidenceId, lotId, digest, eventType: LOT_REGISTERED, schemaVersion: SCHEMA_V1 });
    assert.equal((await fixture.contract.read.getEvidence([evidenceId])).digest, digest);
  });

  it("conserva evidencias anteriores tras registrar una nueva", async () => {
    await record();
    await record([nextEvidence, lotId, changedDigest, LOT_REGISTERED, SCHEMA_V1]);
    assert.deepEqual(await fixture.contract.read.verifyEvidence([evidenceId, digest]), [true, true]);
    assert.deepEqual(await fixture.contract.read.verifyEvidence([nextEvidence, changedDigest]), [true, true]);
  });

  it("impide sobrescribir una evidencia usando un lote y digest diferentes", async () => {
    await record();
    await reverts(fixture.contract.simulate.recordEvidence(
      [evidenceId, nextLot, changedDigest, LOT_REGISTERED, SCHEMA_V1], { account: fixture.writer.account.address },
    ), "EvidenceAlreadyRecorded");
    const stored = await fixture.contract.read.getEvidence([evidenceId]);
    assert.equal(stored.lotId, lotId);
    assert.equal(stored.digest, digest);
  });

  it("ancla sin alterar los bytes del vector verificado por CanonicalEvidenceCodecTest de Java", async () => {
    const fixturePath = new URL("./fixtures/lot-registered-v1.json", import.meta.url);
    const { canonicalSnapshot, sha256 } = JSON.parse(await readFile(fixturePath, "utf8"));
    const { evidenceId: id, lotId: lot, businessEventId, eventType, schemaVersion } =
      JSON.parse(canonicalSnapshot);
    const { args, tamperedDigest } = prepareEvidence({
      id, lotId: lot, businessEventId, type: eventType,
      schemaVersion, canonicalSnapshot, sha256, status: "LOCAL_PENDING_BLOCKCHAIN",
    });
    await record(args);
    assert.equal((await fixture.contract.read.getEvidence([args[0]])).digest, "0x" + sha256);
    assert.deepEqual(await fixture.contract.read.verifyEvidence([args[0], tamperedDigest]), [true, false]);
  });
});

describe("Codificación interoperable", () => {
  it("conserva orden y ceros iniciales de UUID de Java sin importar mayúsculas", () => {
    const uuid = "00112233-4455-6677-8899-AABBCCDDEEFF";
    assert.equal(uuidToBytes16(uuid), "0x00112233445566778899aabbccddeeff");
    assert.equal(bytes16ToUuid(uuidToBytes16(uuid)), uuid.toLowerCase());
    assert.equal(bytes16ToUuid("0xffffffffffffffffffffffffffffffff"), "ffffffff-ffff-ffff-ffff-ffffffffffff");
  });

  it("rechaza UUID nulos o malformados y exige 32 bytes para el SHA-256", () => {
    for (const uuid of ["1-1-1-1-1", "00000000-0000-0000-0000-000000000000", "foo"]) {
      assert.throws(() => uuidToBytes16(uuid), /UUID/);
    }
    assert.throws(() => bytes16ToUuid(ZERO_ID));
    for (const bad of ["xyz", "0x1234", "a".repeat(63), "a".repeat(66)]) {
      assert.throws(() => sha256ToBytes32(bad), /64/);
    }
    const upper = "0x" + "AB".repeat(32) as Hex;
    assert.equal(sha256ToBytes32(upper), "0x" + "ab".repeat(32));
  });
});
