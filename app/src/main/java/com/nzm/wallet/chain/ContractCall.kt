package com.nzm.wallet.chain

import com.nzm.wallet.abi.AbiCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.IOException

object ContractCall {
    fun describeError(e: Response.Error): String =
        AbiCodec.decodeRevert(e.data) ?: e.message

    suspend fun read(rpcUrl: String, from: String, to: String, data: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            val web3 = Web3j.build(HttpService(rpcUrl))
            try {
                val r = web3.ethCall(
                    Transaction.createEthCallTransaction(from, to, Numeric.toHexString(data)),
                    DefaultBlockParameterName.LATEST
                ).send()
                if (r.hasError()) throw IOException(describeError(r.error))
                Numeric.hexStringToByteArray(r.value ?: "0x")
            } finally {
                web3.shutdown()
            }
        }
}
