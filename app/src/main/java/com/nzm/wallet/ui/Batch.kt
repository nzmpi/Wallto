package com.nzm.wallet.ui

import com.nzm.wallet.chain.Rpc.ethCall
import com.nzm.wallet.dbs.Chain
import com.nzm.wallet.dbs.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.math.BigInteger

class BatchItem(
    val line: String,                 // "Send 1 USDC", "Approve 5 USDC", "Call deposit"
    val to: String,
    val value: BigInteger,
    val data: ByteArray,
    val warnings: List<String> = emptyList(),
    val recipient: String? = null,    // token transfers and approvals: the person the call is about
    val name: String? = null,         // ENS name you typed, if any
    val token: Token? = null,         // set for token transfers
    val spend: BigInteger = BigInteger.ZERO,   // token amount, for the "what is left" maths
    val link: String = "to"           // "to", "for" or "on"
)

class BatchStatus(val delegate: String?, val supportsBatch: Boolean)

private class CallStruct(to: Address, value: Uint256, data: DynamicBytes) :
    DynamicStruct(to, value, data)

/**
 * Batches for EIP-7702 accounts that implement ERC-7821 (Uniswap Calibur does).
 * A batch is one normal transaction from your account to itself: execute(bytes32 mode, bytes executionData).
 */
object Batch {
    /** ERC-7821 modes: 0x01 00.. reverts everything if one call fails. 0x01 01.. lets a call fail. */
    private const val MODE_ALL_OR_NOTHING =
        "0x0100000000000000000000000000000000000000000000000000000000000000"
    private const val MODE_CAN_FAIL =
        "0x0101000000000000000000000000000000000000000000000000000000000000"

    private fun mode(allOrNothing: Boolean) =
        Bytes32(Numeric.hexStringToByteArray(if (allOrNothing) MODE_ALL_OR_NOTHING else MODE_CAN_FAIL))

    /** Reads the delegation marker (0xef0100 + delegate) and asks the account whether it supports batch mode. */
    suspend fun status(chain: Chain, account: String): BatchStatus = withContext(Dispatchers.IO) {
        val web3 = Web3j.build(HttpService(chain.rpcUrl))
        val code = try {
            web3.ethGetCode(account, DefaultBlockParameterName.LATEST).send().code ?: "0x"
        } finally {
            web3.shutdown()
        }
        val delegate = if (code.startsWith("0xef0100") && code.length == 8 + 40)
            org.web3j.crypto.Keys.toChecksumAddress("0x" + code.substring(8)) else null
        if (delegate == null) return@withContext BatchStatus(null, false)
        val fn = Function(
            "supportsExecutionMode", listOf(mode(true)),
            listOf(org.web3j.abi.TypeReference.create(org.web3j.abi.datatypes.Bool::class.java))
        )
        val ok = try {
            val raw = ethCall(chain.rpcUrl, account, account, fn = fn)
            raw.any { it != 0.toByte() }
        } catch (_: Exception) {
            false
        }
        BatchStatus(delegate, ok)
    }

    fun encode(items: List<BatchItem>, allOrNothing: Boolean): String {
        val structs = items.map {
            CallStruct(Address(it.to), Uint256(it.value), DynamicBytes(it.data))
        }
        val executionData = Numeric.hexStringToByteArray(
            FunctionEncoder.encodeConstructor(listOf(DynamicArray(CallStruct::class.java, structs)))
        )
        val fn = Function(
            "execute",
            listOf(mode(allOrNothing), DynamicBytes(executionData)),
            emptyList()
        )
        return FunctionEncoder.encode(fn)
    }

    fun encodeApprove(spender: String, amount: BigInteger): ByteArray =
        Numeric.hexStringToByteArray(
            FunctionEncoder.encode(
                Function(
                    "approve",
                    listOf(Address(spender), Uint256(amount)),
                    emptyList()
                )
            )
        )
}
