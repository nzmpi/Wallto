package com.nzm.wallet.chain

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger

object Ens {
    private const val UNIVERSAL_RESOLVER = "0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe"
    private const val ZERO = "0x0000000000000000000000000000000000000000"
    private val ETH_COIN_TYPE = BigInteger.valueOf(60)
    private val DEFAULT_COIN_TYPE = BigInteger.valueOf(0x80000000L)
    private val LABEL = Regex("^[a-z0-9_-]{1,63}$")

    /** Anything with a dot that is not a hex address is treated as an ENS name. */
    fun looksLikeName(input: String): Boolean {
        val s = input.trim()
        return s.contains('.') && !s.startsWith("0x", ignoreCase = true)
    }

    /**
     * Only plain ASCII names (a-z, 0-9, - and _) are accepted. Full ENS normalization (emoji,
     * accents) is large, and look-alike characters are how address-spoofing works, so reject them.
     */
    private fun normalize(input: String): String? {
        val n = input.trim().lowercase()
        val labels = n.split('.')
        if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return null
        return n
    }

    private fun namehash(name: String): ByteArray {
        var node = ByteArray(32)
        for (label in name.split('.').reversed()) node = Hash.sha3(node + Hash.sha3(label.toByteArray()))
        return node
    }

    private fun dnsEncode(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.split('.').forEach { val b = it.toByteArray(); out.write(b.size); out.write(b) }
        out.write(0)
        return out.toByteArray()
    }

    private suspend fun callUr(rpcUrl: String, f: Function): List<Type<*>> {
        val out = ContractCall.read(
            rpcUrl, ZERO, UNIVERSAL_RESOLVER,
            Numeric.hexStringToByteArray(FunctionEncoder.encode(f))
        )
        return FunctionReturnDecoder.decode(Numeric.toHexString(out), f.outputParameters)
    }

    /** name -> checksummed address, or null if the name has no address. Throws if it cannot be resolved. */
    suspend fun resolve(rpcUrl: String, input: String): String? {
        val name = normalize(input)
            ?: throw IllegalArgumentException("Only plain names (a-z, 0-9, -) are supported, like name.eth")
        val addrCall = FunctionEncoder.encode(
            Function("addr", listOf<Type<*>>(Bytes32(namehash(name))), emptyList<TypeReference<*>>())
        )
        val f = Function(
            "resolve",
            listOf<Type<*>>(DynamicBytes(dnsEncode(name)), DynamicBytes(Numeric.hexStringToByteArray(addrCall))),
            listOf<TypeReference<*>>(object : TypeReference<DynamicBytes>() {}, object : TypeReference<Address>() {})
        )
        val result = try {
            (callUr(rpcUrl, f)[0] as DynamicBytes).value
        } catch (_: IOException) {
            throw IOException("Could not resolve $name (not registered, or it needs an offchain lookup)")
        }
        if (result.size < 32) return null
        val a = result.copyOfRange(result.size - 20, result.size)
        if (a.all { it == 0.toByte() }) return null
        return Keys.toChecksumAddress(Numeric.toHexString(a))
    }

    /** address -> primary name, or null. The resolver checks that the name points back to the address. */
    suspend fun reverse(rpcUrl: String, address: String): String? {
        val raw = Numeric.hexStringToByteArray(address)
        for (coinType in listOf(ETH_COIN_TYPE, DEFAULT_COIN_TYPE)) {
            val f = Function(
                "reverse",
                listOf<Type<*>>(DynamicBytes(raw), Uint256(coinType)),
                listOf<TypeReference<*>>(
                    object : TypeReference<Utf8String>() {},
                    object : TypeReference<Address>() {},
                    object : TypeReference<Address>() {}
                )
            )
            val name = try {
                (callUr(rpcUrl, f)[0] as Utf8String).value
            } catch (_: IOException) {
                continue
            }
            if (name.isNotEmpty() && name.length <= 64 && name.none { it.isISOControl() || it.isWhitespace() }) {
                return name
            }
        }
        return null
    }
}
