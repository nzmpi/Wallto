package com.nzm.wallet.abi

import com.nzm.wallet.utils.Constants
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.sanitize
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.io.ByteArrayOutputStream
import java.math.BigInteger

sealed class AbiType(val canonical: String, val isDynamic: Boolean) {
    class Uint(val bits: Int) : AbiType("uint$bits", false)
    class IntN(val bits: Int) : AbiType("int$bits", false)
    object Address : AbiType("address", false)
    object Bool : AbiType("bool", false)
    class FixedBytes(val n: Int) : AbiType("bytes$n", false)
    object Bytes : AbiType("bytes", true)
    object Str : AbiType("string", true)
    class Arr(val elem: AbiType) : AbiType(elem.canonical + "[]", true)
}

class FunctionSig(val name: String, val inputs: List<AbiType>, val outputs: List<AbiType>) {
    val signature: String get() = "$name(${inputs.joinToString(",") { it.canonical }})"
    val selector: ByteArray
        get() = Hash.sha3(signature.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)
}

object AbiCodec {
    // ---------- parsing ----------
    fun parseType(raw: String): AbiType {
        val t = raw.trim()
        if (t.endsWith("[]")) {
            val elem = parseType(t.removeSuffix("[]"))
            require(!elem.isDynamic && elem !is AbiType.Arr) { "Arrays of dynamic or nested types not supported: $t" }
            return AbiType.Arr(elem)
        }
        require(!t.contains('[') && !t.contains('(')) {
            "Unsupported type: $t (fixed arrays and tuples not supported)"
        }
        return when {
            t == "address" -> AbiType.Address
            t == "bool" -> AbiType.Bool
            t == "string" -> AbiType.Str
            t == "bytes" -> AbiType.Bytes
            t == "uint" -> AbiType.Uint(256)
            t == "int" -> AbiType.IntN(256)
            t.startsWith("uint") -> AbiType.Uint(bitsOf(t, 4))
            t.startsWith("int") -> AbiType.IntN(bitsOf(t, 3))
            t.startsWith("bytes") -> {
                val n = t.substring(5).toIntOrNull()
                require(n != null && n in 1..32) { "Bad bytes type: $t" }
                AbiType.FixedBytes(n)
            }

            else -> throw IllegalArgumentException("Unsupported type: $t")
        }
    }

    private fun bitsOf(t: String, prefixLen: Int): Int {
        val b = t.substring(prefixLen).toIntOrNull()
        require(b != null && b in 8..256 && b % 8 == 0) { "Bad integer type: $t" }
        return b
    }

    private fun splitTypes(text: String): List<AbiType> =
        if (text.isBlank()) emptyList()
        else text.split(',').map { part -> parseType(part.trim().split(Regex("\\s+"))[0]) }

    fun parseSignature(text: String): FunctionSig {
        var s = text.trim()
        if (s.startsWith("function ")) s = s.removePrefix("function ").trim()
        val open = s.indexOf('(')
        require(open > 0) { "Use format: name(type,type) returns (type)" }
        val name = s.substring(0, open).trim()
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "Bad function name: $name" }
        val close = s.indexOf(')', open)
        require(close > open) { "Missing )" }
        val argsText = s.substring(open + 1, close)
        require(!argsText.contains('(')) { "Tuples not supported" }
        val inputs = splitTypes(argsText)
        val rest = s.substring(close + 1)
        val ri = rest.indexOf("returns")
        val outputs = if (ri >= 0) {
            val ro = rest.indexOf('(', ri)
            val rc = rest.indexOf(')', ro + 1)
            require(ro in 1..<rc) { "Bad returns (...)" }
            splitTypes(rest.substring(ro + 1, rc))
        } else emptyList()
        return FunctionSig(name, inputs, outputs)
    }

    private fun parseInteger(input: String): BigInteger {
        val s = input.trim().replace("_", "")
        return try {
            when {
                s.startsWith("0x") || s.startsWith("0X") -> BigInteger(s.substring(2), 16)
                s.matches(Regex("-?\\d+")) -> BigInteger(s)
                else -> throw NumberFormatException()
            }
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid number")
        }
    }

    private fun parseHex(input: String): ByteArray {
        val s = input.trim().removePrefix("0x").removePrefix("0X")
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            "Invalid hex (use 0x..., even length)"
        }
        return Numeric.hexStringToByteArray(s)
    }

    fun parseValue(type: AbiType, input: String): Any = when (type) {
        is AbiType.Uint -> {
            val v = parseInteger(input)
            require(v.signum() >= 0 && v.bitLength() <= type.bits) { "Out of range for ${type.canonical}" }
            v
        }

        is AbiType.IntN -> {
            val v = parseInteger(input)
            require(v.bitLength() <= type.bits - 1) { "Out of range for ${type.canonical}" }
            v
        }

        AbiType.Address -> {
            val s = input.trim()
            require(isValidAddress(s)) { "Invalid address or checksum" }
            s
        }

        AbiType.Bool -> when (input.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException("Use true or false")
        }

        is AbiType.FixedBytes -> {
            val b = parseHex(input)
            require(b.size == type.n) { "Need exactly ${type.n} bytes" }
            b
        }

        AbiType.Bytes -> parseHex(input)
        AbiType.Str -> input
        is AbiType.Arr -> {
            val t = input.trim().removePrefix("[").removeSuffix("]").trim()
            if (t.isEmpty()) emptyList() else t.split(',').map { parseValue(type.elem, it) }
        }
    }

    // ---------- encoding ----------
    private fun word(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
            .let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(raw.size <= 32) { "Value too large" }
        return ByteArray(32 - raw.size) + raw
    }

    private fun encodeStatic(type: AbiType, v: Any): ByteArray = when (type) {
        is AbiType.Uint -> word(v as BigInteger)
        is AbiType.IntN -> {
            val n = v as BigInteger
            word(if (n.signum() < 0) n.add(Constants.TWO_256) else n)
        }

        AbiType.Address -> ByteArray(12) + Numeric.hexStringToByteArray(v as String)
        AbiType.Bool -> word(if (v as Boolean) BigInteger.ONE else BigInteger.ZERO)
        is AbiType.FixedBytes -> (v as ByteArray) + ByteArray(32 - type.n)
        else -> throw IllegalStateException("Not static: ${type.canonical}")
    }

    private fun encodeBytes(b: ByteArray): ByteArray =
        word(BigInteger.valueOf(b.size.toLong())) + b + ByteArray((32 - b.size % 32) % 32)

    private fun encodeDynamic(type: AbiType, v: Any): ByteArray = when (type) {
        AbiType.Bytes -> encodeBytes(v as ByteArray)
        AbiType.Str -> encodeBytes((v as String).toByteArray(Charsets.UTF_8))
        is AbiType.Arr -> {
            val list = v as List<*>
            val out = ByteArrayOutputStream()
            out.write(word(BigInteger.valueOf(list.size.toLong())))
            list.forEach { out.write(encodeStatic(type.elem, it!!)) }
            out.toByteArray()
        }

        else -> throw IllegalStateException("Not dynamic: ${type.canonical}")
    }

    fun encodeArgs(types: List<AbiType>, values: List<Any>): ByteArray {
        val headSize = 32 * types.size
        val head = ByteArrayOutputStream()
        val tail = ByteArrayOutputStream()
        for (i in types.indices) {
            if (!types[i].isDynamic) {
                head.write(encodeStatic(types[i], values[i]))
            } else {
                head.write(word(BigInteger.valueOf((headSize + tail.size()).toLong())))
                tail.write(encodeDynamic(types[i], values[i]))
            }
        }
        return head.toByteArray() + tail.toByteArray()
    }

    fun encodeCall(sig: FunctionSig, values: List<Any>): ByteArray =
        sig.selector + encodeArgs(sig.inputs, values)

    // ---------- decoding ----------
    private fun intAt(data: ByteArray, pos: Int): Int {
        require(pos >= 0 && pos + 32 <= data.size) { "Return data too short" }
        val v = BigInteger(1, data.copyOfRange(pos, pos + 32))
        require(v <= BigInteger.valueOf(data.size.toLong())) { "Bad offset or length" }
        return v.toInt()
    }

    private fun decodeStatic(type: AbiType, data: ByteArray, pos: Int): String {
        require(pos >= 0 && pos + 32 <= data.size) { "Return data too short" }
        val w = data.copyOfRange(pos, pos + 32)
        return when (type) {
            is AbiType.Uint -> BigInteger(1, w).toString()
            is AbiType.IntN -> BigInteger(w).toString()
            AbiType.Address -> Keys.toChecksumAddress(
                "0x" + Numeric.toHexStringNoPrefix(
                    w.copyOfRange(
                        12,
                        32
                    )
                )
            )

            AbiType.Bool -> if (BigInteger(1, w).signum() != 0) "true" else "false"
            is AbiType.FixedBytes -> "0x" + Numeric.toHexStringNoPrefix(w.copyOfRange(0, type.n))
            else -> throw IllegalStateException("Not static: ${type.canonical}")
        }
    }

    private fun decodeDynamic(type: AbiType, data: ByteArray, off: Int): String {
        val len = intAt(data, off)
        val start = off + 32
        return when (type) {
            AbiType.Bytes -> {
                require(start + len <= data.size) { "Return data too short" }
                "0x" + Numeric.toHexStringNoPrefix(data.copyOfRange(start, start + len))
            }

            AbiType.Str -> {
                require(start + len <= data.size) { "Return data too short" }
                String(data.copyOfRange(start, start + len), Charsets.UTF_8).sanitize()
            }

            is AbiType.Arr -> {
                require(len <= 500 && start + len * 32 <= data.size) { "Bad array length" }
                (0 until len).joinToString(", ", "[", "]") {
                    decodeStatic(
                        type.elem,
                        data,
                        start + it * 32
                    )
                }
            }

            else -> throw IllegalStateException("Not dynamic: ${type.canonical}")
        }
    }

    fun decodeOutputs(types: List<AbiType>, data: ByteArray): List<String> =
        types.mapIndexed { i, t ->
            val headPos = i * 32
            if (!t.isDynamic) decodeStatic(t, data, headPos) else decodeDynamic(
                t,
                data,
                intAt(data, headPos)
            )
        }

    // ---------- display and safety ----------
    fun display(type: AbiType, v: Any): String = when (type) {
        is AbiType.Uint, is AbiType.IntN -> (v as BigInteger).toString()
        AbiType.Address -> Keys.toChecksumAddress(v as String)
        AbiType.Bool -> v.toString()
        is AbiType.FixedBytes, AbiType.Bytes -> "0x" + Numeric.toHexStringNoPrefix(v as ByteArray)
        AbiType.Str -> (v as String).sanitize()
        is AbiType.Arr -> (v as List<*>).joinToString(", ", "[", "]") { display(type.elem, it!!) }
    }

    fun riskNotes(sig: FunctionSig, values: List<Any>): List<String> {
        val notes = mutableListOf<String>()
        val unlimited = Constants.TWO_255
        when (sig.signature) {
            "approve(address,uint256)", "increaseAllowance(address,uint256)" -> {
                val spender = Keys.toChecksumAddress(values[0] as String)
                val amount = values[1] as BigInteger
                if (amount.signum() > 0) notes += "$spender can spend your tokens of this contract."
                if (amount >= unlimited) {
                    notes += "UNLIMITED allowance. The spender can take ALL your tokens at any time until you revoke it."
                }
            }

            "setApprovalForAll(address,bool)" ->
                if (values[1] == true) {
                    notes += "${Keys.toChecksumAddress(values[0] as String)} gets control of ALL your NFTs in this collection."
                }
        }
        return notes
    }

    fun decodeRevert(dataHex: String?): String? {
        if (dataHex.isNullOrBlank() || dataHex == "0x") return null
        val b = try {
            parseHex(dataHex)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (b.size < 4) return null
        val sel = Numeric.toHexStringNoPrefix(b.copyOfRange(0, 4))
        val payload = b.copyOfRange(4, b.size)
        return try {
            when (sel) {
                "08c379a0" -> "Reverted: " + decodeOutputs(listOf(AbiType.Str), payload)[0]
                "4e487b71" -> "Panic code 0x" + BigInteger(1, payload.copyOfRange(0, 32)).toString(
                    16
                )

                else -> "Custom error 0x$sel"
            }
        } catch (_: Exception) {
            "Reverted (data 0x$sel...)"
        }
    }
}

/** A callable function taken from an ABI. [sigText] is what AbiCodec.parseSignature reads. */
class AbiFn(
    val name: String,
    val sigText: String,
    val readOnly: Boolean,
    val payable: Boolean,
    val inputNames: List<String>,
    val inputTypes: List<String>
)

class AbiFns(val list: List<AbiFn>, val skipped: Int)

private fun typeOf(o: JSONObject): String {
    val t = o.getString("type")
    if (!t.startsWith("tuple")) return t
    val comps = o.optJSONArray("components") ?: JSONArray()
    val inner = (0 until comps.length()).joinToString(",") { typeOf(comps.getJSONObject(it)) }
    return "($inner)" + t.removePrefix("tuple")
}

/** Functions the wallet can encode. Ones with types AbiCodec cannot parse are counted in [AbiFns.skipped]. */
fun abiFunctions(abiJson: String): AbiFns {
    val arr = try {
        JSONArray(abiJson)
    } catch (_: Exception) {
        return AbiFns(emptyList(), 0)
    }
    val out = LinkedHashMap<String, AbiFn>()
    var skipped = 0
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("type") != "function") continue
        try {
            val name = o.getString("name")
            val ins = o.optJSONArray("inputs") ?: JSONArray()
            val outs = o.optJSONArray("outputs") ?: JSONArray()
            val inTypes = (0 until ins.length()).map { typeOf(ins.getJSONObject(it)) }
            val inNames = (0 until ins.length()).map { ins.getJSONObject(it).optString("name", "") }
            val outTypes = (0 until outs.length()).map { typeOf(outs.getJSONObject(it)) }
            val sig = "$name(${inTypes.joinToString(",")})" +
                    if (outTypes.isEmpty()) "" else " returns (${outTypes.joinToString(",")})"
            val mut = o.optString(
                "stateMutability",
                if (o.optBoolean("constant")) "view" else "nonpayable"
            )
            if (runCatching { AbiCodec.parseSignature(sig) }.isFailure) {
                skipped++; continue
            }
            out.putIfAbsent(
                sig,
                AbiFn(name, sig, mut == "view" || mut == "pure", mut == "payable", inNames, inTypes)
            )
        } catch (_: Exception) {
            skipped++
        }
    }
    return AbiFns(out.values.sortedBy { it.name.lowercase() }, skipped)
}