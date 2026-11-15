package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.ChainProblem;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.BlockchainSettings.Target;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox.Confirmation;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox.Signed;
import okhttp3.OkHttpClient;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeEncoder;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.ClientConnectionException;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Verification.*;
import static org.web3j.protocol.core.DefaultBlockParameterName.LATEST;
import static org.web3j.protocol.core.DefaultBlockParameterName.PENDING;

public class Web3jEvidenceChain implements AutoCloseable {
    record Deployment(Target target, BigInteger firstBlock) {}
    record OnChain(boolean exists, boolean digestMatches, boolean metadataMatches) {
        boolean matches() { return exists && digestMatches && metadataMatches; }
    }

    private final Web3j rpc;
    private final Credentials writer;

    Web3jEvidenceChain(BlockchainSettings settings) {
        this.writer = Credentials.create(settings.writerPrivateKey());
        if (!writer.getAddress().equalsIgnoreCase(settings.writerAddress())) {
            throw new IllegalArgumentException("La clave del escritor no corresponde a su dirección");
        }
        var client = new OkHttpClient.Builder().callTimeout(settings.rpcTimeout())
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        this.rpc = Web3j.build(new HttpService(settings.rpcUrl().toString(), client));
    }

    Deployment deployment(Target target) {
        if (!BigInteger.valueOf(target.chainId()).equals(send(rpc.ethChainId()).getChainId())) {
            throw new ChainProblem(CHAIN_MISMATCH);
        }
        String code = send(rpc.ethGetCode(target.contract(), LATEST)).getCode();
        if (code == null || code.equals("0x")) throw new ChainProblem(CONTRACT_MISSING);
        if (!FreshSenseContractAbi.RUNTIME_CODE_HASH.equalsIgnoreCase(Hash.sha3(code))) {
            throw new ChainProblem(CONTRACT_INCOMPATIBLE);
        }
        var receipt = receipt(target.deploymentTransaction()).orElseThrow(() -> new ChainProblem(DEPLOYMENT_MISSING));
        if (!successful(receipt) || !target.contract().equalsIgnoreCase(receipt.getContractAddress())) {
            throw new ChainProblem(CONTRACT_INCOMPATIBLE);
        }
        return new Deployment(target, receipt.getBlockNumber());
    }

    OnChain lookup(Deployment deployment, TraceEvidence evidence) {
        var contract = deployment.target().contract();
        // Both calls read the same height, even if a new transaction is mined between them.
        var block = DefaultBlockParameter.valueOf(send(rpc.ethBlockNumber()).getBlockNumber());
        var stored = (FreshSenseContractAbi.EvidenceTuple) call(contract, FreshSenseContractAbi.get(evidence.id()), block).get(0);
        boolean exists = stored.exists.getValue();
        var verified = call(contract, FreshSenseContractAbi.verify(evidence), block);
        boolean digestMatches = exists && Arrays.equals(stored.digest.getValue(), FreshSenseContractAbi.digest(evidence.sha256()).getValue());
        if (!verified.get(0).getValue().equals(exists) || !verified.get(1).getValue().equals(digestMatches)) {
            throw new ChainProblem(RPC_REJECTED);
        }
        boolean metadata = stored.lotId.equals(FreshSenseContractAbi.uuid(evidence.lotId()))
                && stored.eventType.equals(FreshSenseContractAbi.eventType(evidence))
                && stored.schemaVersion.equals(new Uint32(evidence.schemaVersion()));
        return new OnChain(exists, digestMatches, metadata);
    }

    Signed prepare(Deployment deployment, TraceEvidence evidence) {
        var target = deployment.target();
        var role = (Bytes32) call(target.contract(), FreshSenseContractAbi.writerRole(), LATEST).get(0);
        var permitted = (Bool) call(target.contract(), FreshSenseContractAbi.hasRole(role, writer.getAddress()), LATEST).get(0);
        if (!permitted.getValue()) throw new ChainProblem(WRITER_UNAUTHORIZED);

        String data = FunctionEncoder.encode(FreshSenseContractAbi.record(evidence));
        var nonce = send(rpc.ethGetTransactionCount(writer.getAddress(), PENDING)).getTransactionCount();
        var gas = send(rpc.ethEstimateGas(Transaction.createFunctionCallTransaction(
                writer.getAddress(), null, null, null, target.contract(), data))).getAmountUsed();
        gas = gas.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
        var gasPrice = send(rpc.ethGasPrice()).getGasPrice();
        var transaction = RawTransaction.createTransaction(nonce, gasPrice, gas, target.contract(), data);
        String raw = Numeric.toHexString(TransactionEncoder.signMessage(transaction, target.chainId(), writer));
        return new Signed(Hash.sha3(raw), nonce, raw);
    }

    void broadcast(Signed signed) {
        var result = send(rpc.ethSendRawTransaction(signed.raw()));
        if (!signed.hash().equalsIgnoreCase(result.getTransactionHash())) throw new ChainProblem(RPC_REJECTED);
    }

    Optional<TransactionReceipt> receipt(String hash) {
        return send(rpc.ethGetTransactionReceipt(hash)).getTransactionReceipt();
    }

    boolean transactionKnown(String hash) {
        return send(rpc.ethGetTransactionByHash(hash)).getTransaction().isPresent();
    }

    boolean nonceConsumed(Signed signed) {
        var latest = send(rpc.ethGetTransactionCount(writer.getAddress(), LATEST)).getTransactionCount();
        return latest.compareTo(signed.nonce()) > 0;
    }

    Optional<Confirmation> reconcile(Deployment deployment, TraceEvidence evidence) {
        var filter = new EthFilter(DefaultBlockParameter.valueOf(deployment.firstBlock()), LATEST,
                deployment.target().contract());
        filter.addSingleTopic(FreshSenseContractAbi.RECORDED_TOPIC);
        filter.addSingleTopic("0x" + TypeEncoder.encode(FreshSenseContractAbi.uuid(evidence.id())));
        for (var result : send(rpc.ethGetLogs(filter)).getLogs()) {
            if (result.get() instanceof Log log && recorded(log, deployment.target(), evidence)) {
                var proof = receipt(log.getTransactionHash()).flatMap(found -> confirmation(found, deployment, evidence));
                if (proof.isPresent()) return proof;
            }
        }
        return Optional.empty();
    }

    Optional<Confirmation> confirmation(TransactionReceipt receipt, Deployment deployment, TraceEvidence evidence) {
        var target = deployment.target();
        if (!successful(receipt) || !target.contract().equalsIgnoreCase(receipt.getTo())
                || receipt.getBlockNumber().compareTo(deployment.firstBlock()) < 0) return Optional.empty();
        boolean event = receipt.getLogs().stream().anyMatch(log -> recorded(log, target, evidence)
                && receipt.getTransactionHash().equalsIgnoreCase(log.getTransactionHash())
                && receipt.getBlockHash().equalsIgnoreCase(log.getBlockHash()));
        return event ? Optional.of(new Confirmation(receipt.getTransactionHash(), receipt.getBlockNumber().longValueExact(),
                receipt.getBlockHash())) : Optional.empty();
    }

    static boolean successful(TransactionReceipt receipt) {
        return "0x1".equalsIgnoreCase(receipt.getStatus());
    }

    private boolean recorded(Log log, Target target, TraceEvidence evidence) {
        var expectedTopics = List.of(FreshSenseContractAbi.RECORDED_TOPIC,
                "0x" + TypeEncoder.encode(FreshSenseContractAbi.uuid(evidence.id())),
                "0x" + TypeEncoder.encode(FreshSenseContractAbi.uuid(evidence.lotId())));
        var expectedData = "0x" + TypeEncoder.encode(FreshSenseContractAbi.digest(evidence.sha256()))
                + TypeEncoder.encode(FreshSenseContractAbi.eventType(evidence))
                + TypeEncoder.encode(new Uint32(evidence.schemaVersion()));
        return !log.isRemoved() && target.contract().equalsIgnoreCase(log.getAddress())
                && expectedTopics.equals(log.getTopics()) && expectedData.equalsIgnoreCase(log.getData());
    }

    @SuppressWarnings("rawtypes")
    private List<Type> call(String contract, Function function, DefaultBlockParameter block) {
        var response = send(rpc.ethCall(Transaction.createEthCallTransaction(
                writer.getAddress(), contract, FunctionEncoder.encode(function)), block));
        if (response.isReverted() || response.getValue() == null) throw new ChainProblem(RPC_REJECTED);
        try {
            var values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.size() != function.getOutputParameters().size()) throw new ChainProblem(CONTRACT_INCOMPATIBLE);
            return values;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new ChainProblem(CONTRACT_INCOMPATIBLE);
        }
    }

    private static <T extends Response<?>> T send(Request<?, T> request) {
        try {
            T response = request.send();
            if (response == null || response.hasError()) throw new ChainProblem(RPC_REJECTED);
            return response;
        } catch (IOException | ClientConnectionException e) {
            throw new ChainProblem(RPC_UNAVAILABLE);
        }
    }

    @Override public void close() { rpc.shutdown(); }
}
