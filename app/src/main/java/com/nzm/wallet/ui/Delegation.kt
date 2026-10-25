package com.nzm.wallet.ui

import com.nzm.wallet.chain.Fee
import com.nzm.wallet.chain.TxPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.Sign
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.rlp.RlpEncoder
import org.web3j.rlp.RlpList
import org.web3j.rlp.RlpString
import org.web3j.rlp.RlpType
import org.web3j.utils.Numeric
import java.math.BigInteger

object Delegation {
    class Known(val name: String, val address: String)

    val KNOWN = listOf(
        Known("Uniswap Calibur v1.0.0", "0x000000009B1D0aF20D8C6d0A44e162d11F9b8f00"),
        Known("Uniswap Calibur v1.1.0", "0x000000005c84F8Fd50b21CAC312528A64437030e"),
        Known("Rainbow Calibur", "0x612373D7003d694220f7800EeaF8E3924c0951D3")
    )

    /** Delegating to the zero address removes the delegation. */
    const val ZERO = "0x0000000000000000000000000000000000000000"

    /** Intrinsic gas for one authorization is 25000 on top of 21000. The unused part is not charged. */
    val GAS_LIMIT: BigInteger = BigInteger.valueOf(80_000)

    fun knownName(address: String?): String? =
        KNOWN.firstOrNull { it.address.equals(address, true) }?.name

    private fun yParity(sig: Sign.SignatureData): BigInteger {
        val v = sig.v[0].toInt() and 0xff
        return BigInteger.valueOf((if (v >= 27) v - 27 else v).toLong())
    }

    /**
     * Signs and sends a type-4 transaction from the account to itself.
     * The account is also the authority, so its nonce is bumped before the authorization is checked:
     * the authorization nonce is the transaction nonce + 1.
     */
    suspend fun signAndSend(plan: TxPlan, delegate: String, creds: Credentials): String =
        withContext(Dispatchers.IO) {
            val fee = plan.fee as? Fee.Eip1559
                ?: throw IllegalStateException("This chain uses legacy fees, so it cannot send a 7702 transaction")
            val chainId = BigInteger.valueOf(plan.chain.chainId)
            require(chainId.signum() > 0) { "Invalid chain" }
            require(
                plan.from.equals(
                    creds.address,
                    ignoreCase = true
                )
            ) { "Wallet key does not match the account" }
            require(
                plan.to.equals(
                    plan.from,
                    ignoreCase = true
                ) && plan.value.signum() == 0
            ) { "Unexpected transaction" }

            val delegateBytes = Numeric.hexStringToByteArray(delegate)
            require(delegateBytes.size == 20) { "Invalid delegate address" }
            val authNonce = plan.nonce + BigInteger.ONE

            // authorization: sign keccak(0x05 || rlp([chain_id, address, nonce])). Always this chain, never 0.
            val authBody = RlpList(
                RlpString.create(chainId),
                RlpString.create(delegateBytes),
                RlpString.create(authNonce)
            )
            val authHash = Hash.sha3(byteArrayOf(0x05) + RlpEncoder.encode(authBody))
            val authSig = Sign.signMessage(authHash, creds.ecKeyPair, false)
            val authTuple = RlpList(
                RlpString.create(chainId),
                RlpString.create(delegateBytes),
                RlpString.create(authNonce),
                RlpString.create(yParity(authSig)),
                RlpString.create(BigInteger(1, authSig.r)),
                RlpString.create(BigInteger(1, authSig.s))
            )

            // transaction: type 4
            val fields = mutableListOf<RlpType>(
                RlpString.create(chainId),
                RlpString.create(plan.nonce),
                RlpString.create(fee.maxPriorityFeePerGas),
                RlpString.create(fee.maxFeePerGas),
                RlpString.create(plan.gasLimit),
                RlpString.create(Numeric.hexStringToByteArray(plan.to)),
                RlpString.create(BigInteger.ZERO),
                RlpString.create(ByteArray(0)),
                RlpList(),                       // access list
                RlpList(authTuple)               // authorization list
            )
            val txHash = Hash.sha3(byteArrayOf(0x04) + RlpEncoder.encode(RlpList(fields)))
            val sig = Sign.signMessage(txHash, creds.ecKeyPair, false)
            fields.add(RlpString.create(yParity(sig)))
            fields.add(RlpString.create(BigInteger(1, sig.r)))
            fields.add(RlpString.create(BigInteger(1, sig.s)))
            val raw = byteArrayOf(0x04) + RlpEncoder.encode(RlpList(fields))

            val web3 = Web3j.build(HttpService(plan.chain.rpcUrl))
            try {
                val res = web3.ethSendRawTransaction(Numeric.toHexString(raw)).send()
                if (res.hasError()) throw IllegalStateException(
                    res.error.message ?: "Node rejected the transaction"
                )
                res.transactionHash
            } finally {
                web3.shutdown()
            }
        }
}
