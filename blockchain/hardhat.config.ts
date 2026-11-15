import { createRequire } from "node:module";
import hardhatNodeTestRunner from "@nomicfoundation/hardhat-node-test-runner";
import hardhatViem from "@nomicfoundation/hardhat-viem";
import { defineConfig } from "hardhat/config";
import { generateMnemonic, english } from "viem/accounts";
import { LOCAL_CHAIN_ID, LOCAL_RPC_URL } from "./scripts/local-network.ts";

const require = createRequire(import.meta.url);

export default defineConfig({
  plugins: [hardhatViem, hardhatNodeTestRunner],
  solidity: {
    version: "0.8.38",
    path: require.resolve("solc/soljson.js"),
    settings: { evmVersion: "cancun" },
  },
  networks: {
    freshsenseLocal: {
      type: "edr-simulated",
      chainType: "l1",
      chainId: LOCAL_CHAIN_ID,
      hardfork: "cancun",
      accounts: { mnemonic: generateMnemonic(english) },
    },
    localhost: {
      type: "http",
      chainType: "l1",
      chainId: LOCAL_CHAIN_ID,
      url: LOCAL_RPC_URL,
      accounts: "remote",
    },
  },
});
