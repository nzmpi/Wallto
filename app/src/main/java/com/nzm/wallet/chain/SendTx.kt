package com.nzm.wallet.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.SignedRawTransaction
import org.web3j.crypto.TransactionDecoder
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.IOException
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds

sealed interface Fee {
    data class Eip1559(val maxFeePerGas: BigInteger, val maxPriorityFeePerGas: BigInteger) : Fee
    data class Legacy(val gasPrice: BigInteger) : Fee
}

data class TxPlan(
    val chain: Chain,
    val from: String,
    val to: String,
    val value: BigInteger,
    val nonce: BigInteger,
    val gasLimit: BigInteger,
    val fee: Fee,
    val data: String = "",
    val recipient: String? = null,
    val token: Token? = null,
    val tokenAmount: BigInteger? = null
) {
    val maxFeeWei: BigInteger
        get() = gasLimit * when (fee) {
            is Fee.Eip1559 -> fee.maxFeePerGas
            is Fee.Legacy -> fee.gasPrice
        }
    val maxTotalWei: BigInteger get() = value + maxFeeWei
}

object SendTx {
    private val GWEI = BigInteger.valueOf(1_000_000_000L)

    fun isValidAddress(input: String): Boolean {
        val s = input.trim()
        if (!Regex("^0x[0-9a-fA-F]{40}$").matches(s)) return false
        val body = s.substring(2)
        return body == body.lowercase() || body == body.uppercase() || Keys.toChecksumAddress(s) == s // no checksum info
    }

    fun parseAmount(text: String, decimals: Int): BigInteger {
        val d = text.trim().replace(',', '.').toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Invalid amount")
        require(d.signum() > 0) { "Amount must be above 0" }
        return try {
            d.movePointRight(decimals).toBigIntegerExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Too many decimals (max $decimals)")
        }
    }

    private fun <T : Response<*>> T.ok(): T {
        if (hasError()) throw IOException(ContractCall.describeError(error))
        return this
    }

    suspend fun plan(
        chain: Chain, from: String, to: String, value: BigInteger,
        data: String = "", recipient: String? = null,
        token: Token? = null, tokenAmount: BigInteger? = null
    ): TxPlan = withContext(Dispatchers.IO) {
        val web3 = Web3j.build(HttpService(chain.rpcUrl))
        try {
            val nonce = web3.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING)
                .send().ok().transactionCount
            val callTx =
                if (data.isEmpty()) Transaction.createEtherTransaction(from, null, null, null, to, value)
                else Transaction.createFunctionCallTransaction(from, null, null, null, to, value, data)
            val est = web3.ethEstimateGas(callTx).send().ok().amountUsed
            val gasLimit =
                if (est == BigInteger.valueOf(21000)) est
                else est * BigInteger.valueOf(12) / BigInteger.TEN // 20% headroom for contract recipients

            val block = web3.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                .send().ok().block
            val fee: Fee = if (block.baseFeePerGasRaw != null) {
                val tip = try {
                    web3.ethMaxPriorityFeePerGas().send().ok().maxPriorityFeePerGas
                } catch (_: Exception) {
                    GWEI
                }
                Fee.Eip1559(block.baseFeePerGas * BigInteger.valueOf(2) + tip, tip)
            } else {
                Fee.Legacy(web3.ethGasPrice().send().ok().gasPrice)
            }

            val plan = TxPlan(chain, from, to, value, nonce, gasLimit, fee, data, recipient, token, tokenAmount)
            val balance = web3.ethGetBalance(from, DefaultBlockParameterName.LATEST).send().ok().balance
            require(balance >= plan.maxTotalWei) {
                "Insufficient funds. Need up to ${Rpc.formatUnits(plan.maxTotalWei, chain.decimals, 8)} " +
                        "${chain.symbol}, have ${Rpc.formatUnits(balance, chain.decimals, 8)}"
            }
            plan
        } finally {
            web3.shutdown()
        }
    }

    /** Returns tx hash. Caller supplies short-lived credentials. */
    suspend fun signAndSend(plan: TxPlan, credentials: Credentials): String =
        withContext(Dispatchers.IO) {
            val c = plan.chain
            check(plan.from.equals(credentials.address, ignoreCase = true)) { "Key does not match wallet address" }
            check(Rpc.chainId(c.rpcUrl) == c.chainId) { "RPC chain ID changed. Aborting." }

            val raw = when (val f = plan.fee) {
                is Fee.Eip1559 -> RawTransaction.createTransaction(
                    c.chainId, plan.nonce, plan.gasLimit, plan.to, plan.value, plan.data,
                    f.maxPriorityFeePerGas, f.maxFeePerGas
                )
                is Fee.Legacy -> RawTransaction.createTransaction(
                    plan.nonce, f.gasPrice, plan.gasLimit, plan.to, plan.value, plan.data
                )
            }
            val signed = TransactionEncoder.signMessage(raw, c.chainId, credentials)
            val hex = Numeric.toHexString(signed)

            val decoded = TransactionDecoder.decode(hex) as SignedRawTransaction
            check(decoded.from.equals(credentials.address, ignoreCase = true)) { "Signature check failed" }
            check(decoded.to.equals(plan.to, ignoreCase = true)) { "Target mismatch" }
            check(decoded.value == plan.value) { "Value mismatch" }
            check(
                Numeric.cleanHexPrefix(decoded.data).equals(Numeric.cleanHexPrefix(plan.data), ignoreCase = true)
            ) { "Data mismatch" }

            val web3 = Web3j.build(HttpService(c.rpcUrl))
            try {
                val r = web3.ethSendRawTransaction(hex).send()
                if (r.hasError()) throw IOException(r.error.message)
                r.transactionHash
            } finally {
                web3.shutdown()
            }
        }

    /** true = success, false = reverted, null = not mined in time. */
    suspend fun waitForReceipt(rpcUrl: String, hash: String, timeoutMs: Long = 120_000): Boolean? =
        withContext(Dispatchers.IO) {
            val web3 = Web3j.build(HttpService(rpcUrl))
            try {
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val receipt = web3.ethGetTransactionReceipt(hash).send().transactionReceipt
                    if (receipt.isPresent) return@withContext receipt.get().isStatusOK
                    delay(3000.milliseconds)
                }
                null
            } finally {
                web3.shutdown()
            }
        }

    /** Fee per gas the same way plan() picks it. Used to compute "Max" for the native coin. */
    suspend fun maxFeePerGas(chain: Chain): BigInteger = withContext(Dispatchers.IO) {
        val web3 = Web3j.build(HttpService(chain.rpcUrl))
        try {
            val block = web3.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().ok().block
            if (block.baseFeePerGasRaw != null) {
                val tip = try {
                    web3.ethMaxPriorityFeePerGas().send().ok().maxPriorityFeePerGas
                } catch (_: Exception) {
                    GWEI
                }
                block.baseFeePerGas * BigInteger.valueOf(2) + tip
            } else {
                web3.ethGasPrice().send().ok().gasPrice
            }
        } finally {
            web3.shutdown()
        }
    }

    /**
     * Native amount to keep back for fees when sending "max". Uses the real gas estimate of a
     * transfer, applies the same x1.2 headroom plan() uses when the estimate is above 21000, then
     * adds 10% for fee movement between now and signing. Some dust may be left over.
     */
    suspend fun feeReserve(chain: Chain, from: String): BigInteger {
        val fee = maxFeePerGas(chain)
        val base = BigInteger.valueOf(21_000)
        val est = withContext(Dispatchers.IO) {
            val web3 = Web3j.build(HttpService(chain.rpcUrl))
            try {
                val r = web3.ethEstimateGas(
                    Transaction.createEtherTransaction(from, null, null, null, from, BigInteger.ONE)
                ).send()
                if (r.hasError()) base else r.amountUsed.max(base)
            } catch (_: Exception) {
                base
            } finally {
                web3.shutdown()
            }
        }
        val gas = if (est == base) est else est * BigInteger.valueOf(12) / BigInteger.TEN
        return gas * fee * BigInteger.valueOf(11) / BigInteger.TEN
    }
}
