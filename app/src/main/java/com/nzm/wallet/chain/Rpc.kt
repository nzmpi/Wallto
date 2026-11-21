package com.nzm.wallet.chain

import com.nzm.wallet.abi.AbiCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

object Rpc {
    // shortcut: clients of removed or edited chains stay until the app restarts, fine for a handful of chains
    private val clients = ConcurrentHashMap<String, Web3j>()

    /** One shared client per RPC URL: reuses connections. Never shut it down. */
    fun web3(rpcUrl: String): Web3j = clients.computeIfAbsent(rpcUrl) { Web3j.build(HttpService(it)) }

    suspend fun chainId(rpcUrl: String): Long = withContext(Dispatchers.IO) {
        val r = web3(rpcUrl).ethChainId().send()
        if (r.hasError()) throw IOException(r.error.message)
        r.chainId.longValueExact()
    }

    suspend fun balance(rpcUrl: String, address: String): BigInteger = withContext(Dispatchers.IO) {
        val r = web3(rpcUrl).ethGetBalance(address, DefaultBlockParameterName.LATEST).send()
        if (r.hasError()) throw IOException(r.error.message)
        r.balance
    }

    fun describeError(e: Response.Error): String =
        AbiCodec.decodeRevert(e.data) ?: e.message

    suspend fun ethCall(
        rpcUrl: String,
        from: String? = null,
        to: String,
        fn: Function? = null,
        data: ByteArray? = null
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require((fn == null) xor (data == null)) {
                "Provide either fn or data"
            }
            val callData = fn?.let {
                FunctionEncoder.encode(it)
            } ?: Numeric.toHexString(data!!)
            val r = web3(rpcUrl).ethCall(
                Transaction.createEthCallTransaction(from, to, callData),
                DefaultBlockParameterName.LATEST
            ).send()
            if (r.hasError()) throw IOException(describeError(r.error))
            Numeric.hexStringToByteArray(r.value ?: "0x")
        }

    suspend fun isContract(rpcUrl: String, address: String): Boolean =
        withContext(Dispatchers.IO) {
            val r = web3(rpcUrl).ethGetCode(address, DefaultBlockParameterName.LATEST).send()
            if (r.hasError()) throw IOException(describeError(r.error))
            r.code != null && r.code != "0x"
        }
}
