package com.adong.adchat.data.rp.template

import com.adong.adchat.data.rp.template.RpTplValues.normalize
import com.adong.adchat.data.rp.template.RpTplValues.toNumber
import com.adong.adchat.data.rp.template.RpTplValues.toStr
import com.adong.adchat.data.rp.template.RpTplValues.truthy
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** JS built-ins commonly used in card templates (strings, arrays, objects, Math, JSON, Date). */
internal object RpTplMethods {
    private fun fn(block: (List<Any?>) -> Any?) = RpTplFunction(block)
    private fun arg(a: List<Any?>, i: Int) = a.getOrElse(i) { RpTplUndefined }
    private fun list(arr: JSONArray) = (0 until arr.length()).map { normalize(arr.opt(it)) }
    private fun arr(items: List<Any?>) = JSONArray().apply { items.forEach { put(RpTplInterpreter.wrapJson(it)) } }

    val math = RpTplObject { name ->
        when (name) {
            "PI" -> Math.PI
            "E" -> Math.E
            "floor" -> fn { Math.floor(toNumber(arg(it, 0))) }
            "ceil" -> fn { Math.ceil(toNumber(arg(it, 0))) }
            "round" -> fn { Math.floor(toNumber(arg(it, 0)) + 0.5) }
            "abs" -> fn { Math.abs(toNumber(arg(it, 0))) }
            "sqrt" -> fn { Math.sqrt(toNumber(arg(it, 0))) }
            "pow" -> fn { Math.pow(toNumber(arg(it, 0)), toNumber(arg(it, 1))) }
            "trunc" -> fn { toNumber(arg(it, 0)).let { v -> if (v < 0) Math.ceil(v) else Math.floor(v) } }
            "sign" -> fn { Math.signum(toNumber(arg(it, 0))) }
            "max" -> fn { a -> a.maxOfOrNull { toNumber(it) } ?: Double.NEGATIVE_INFINITY }
            "min" -> fn { a -> a.minOfOrNull { toNumber(it) } ?: Double.POSITIVE_INFINITY }
            "random" -> fn { Math.random() }
            else -> null
        }
    }

    val json = RpTplObject { name ->
        when (name) {
            "stringify" -> fn { a ->
                val v = arg(a, 0)
                val indent = toNumber(arg(a, 2)).takeIf { !it.isNaN() }?.toInt() ?: 0
                when (v) {
                    is JSONObject -> if (indent > 0) v.toString(indent) else v.toString()
                    is JSONArray -> if (indent > 0) v.toString(indent) else v.toString()
                    is String -> JSONObject.quote(v)
                    null -> "null"
                    RpTplUndefined -> RpTplUndefined
                    else -> toStr(v)
                }
            }
            "parse" -> fn { a -> parseJson(toStr(arg(a, 0))) }
            else -> null
        }
    }

    fun parseJson(text: String): Any? {
        val t = text.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            t == "null" -> null
            t == "true" -> true
            t == "false" -> false
            t.startsWith("\"") -> JSONArray("[$t]").getString(0)
            else -> t.toDoubleOrNull() ?: error("JSON 解析失败")
        }
    }

    val objectStatics = RpTplObject { name ->
        when (name) {
            "keys" -> fn { a -> (arg(a, 0) as? JSONObject)?.let { o -> arr(o.keys().asSequence().toList()) } ?: JSONArray() }
            "values" -> fn { a -> (arg(a, 0) as? JSONObject)?.let { o -> arr(o.keys().asSequence().map { normalize(o.opt(it)) }.toList()) } ?: JSONArray() }
            "entries" -> fn { a ->
                (arg(a, 0) as? JSONObject)?.let { o -> arr(o.keys().asSequence().map { arr(listOf(it, normalize(o.opt(it)))) }.toList()) } ?: JSONArray()
            }
            "assign" -> fn { a ->
                val target = arg(a, 0) as? JSONObject ?: JSONObject()
                a.drop(1).filterIsInstance<JSONObject>().forEach { src -> src.keys().forEach { target.put(it, src.opt(it)) } }
                target
            }
            else -> null
        }
    }

    val arrayStatics = RpTplObject { name ->
        when (name) {
            "isArray" -> fn { it.firstOrNull() is JSONArray }
            "from" -> fn { a ->
                when (val v = arg(a, 0)) {
                    is JSONArray -> JSONArray(v.toString())
                    is String -> arr(v.map { it.toString() })
                    is JSONObject -> arr(List(toNumber(v.opt("length")).toInt().coerceIn(0, 10_000)) { RpTplUndefined })
                    else -> JSONArray()
                }
            }
            else -> null
        }
    }

    interface Callable : RpTplFunction, RpTplObject

    val numberFn: Callable = object : Callable {
        override fun call(args: List<Any?>): Any? = toNumber(args.firstOrNull() ?: 0.0)
        override fun get(name: String): Any? = when (name) {
            "isInteger" -> fn { a -> toNumber(arg(a, 0)).let { it == Math.floor(it) && !it.isInfinite() } }
            "isFinite" -> fn { a -> toNumber(arg(a, 0)).isFinite() }
            "parseFloat" -> fn { a -> toStr(arg(a, 0)).trim().toDoubleOrNull() ?: Double.NaN }
            "parseInt" -> fn { a -> toStr(arg(a, 0)).trim().toDoubleOrNull()?.let { Math.floor(it) } ?: Double.NaN }
            else -> null
        }
    }

    /** `new Date()` / `Date.now()`: a small object with the getters cards use. */
    val dateFn: Callable = object : Callable {
        override fun call(args: List<Any?>): Any? {
            val millis = args.firstOrNull()?.let { toNumber(it).toLong() } ?: System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
            return RpTplObject { name ->
                when (name) {
                    "getFullYear" -> fn { cal.get(java.util.Calendar.YEAR).toDouble() }
                    "getMonth" -> fn { cal.get(java.util.Calendar.MONTH).toDouble() }
                    "getDate" -> fn { cal.get(java.util.Calendar.DAY_OF_MONTH).toDouble() }
                    "getDay" -> fn { (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1).toDouble() }
                    "getHours" -> fn { cal.get(java.util.Calendar.HOUR_OF_DAY).toDouble() }
                    "getMinutes" -> fn { cal.get(java.util.Calendar.MINUTE).toDouble() }
                    "getSeconds" -> fn { cal.get(java.util.Calendar.SECOND).toDouble() }
                    "getTime" -> fn { millis.toDouble() }
                    "toLocaleDateString" -> fn { java.text.DateFormat.getDateInstance().format(cal.time) }
                    "toLocaleTimeString" -> fn { java.text.DateFormat.getTimeInstance().format(cal.time) }
                    "toISOString" -> fn { java.time.Instant.ofEpochMilli(millis).toString() }
                    else -> null
                }
            }
        }
        override fun get(name: String): Any? = if (name == "now") fn { System.currentTimeMillis().toDouble() } else null
    }

    fun invoke(receiver: Any?, name: String, args: List<Any?>, vm: RpTplInterpreter): Any? = when (receiver) {
        is String -> string(receiver, name, args)
        is JSONArray -> array(receiver, name, args, vm)
        is Double -> when (name) {
            "toFixed" -> String.format(java.util.Locale.ROOT, "%.${toNumber(arg(args, 0)).toInt().coerceIn(0, 20)}f", receiver)
            "toString" -> toStr(receiver)
            else -> error("数字没有方法 $name")
        }
        is JSONObject -> when (name) {
            "hasOwnProperty" -> receiver.has(toStr(arg(args, 0)))
            "toString" -> "[object Object]"
            else -> error("对象没有方法 $name")
        }
        is Boolean -> if (name == "toString") receiver.toString() else error("布尔值没有方法 $name")
        else -> error("无法在 ${RpTplValues.typeOf(receiver)} 上调用 $name")
    }

    private fun string(s: String, name: String, a: List<Any?>): Any? = when (name) {
        "includes" -> s.contains(toStr(arg(a, 0)))
        "startsWith" -> s.startsWith(toStr(arg(a, 0)))
        "endsWith" -> s.endsWith(toStr(arg(a, 0)))
        "indexOf" -> s.indexOf(toStr(arg(a, 0))).toDouble()
        "lastIndexOf" -> s.lastIndexOf(toStr(arg(a, 0))).toDouble()
        "slice", "substring" -> {
            val len = s.length
            fun idx(v: Any?, def: Int) = if (v == RpTplUndefined) def else toNumber(v).toInt().let { if (it < 0 && name == "slice") (len + it).coerceAtLeast(0) else it.coerceIn(0, len) }
            val from = idx(arg(a, 0), 0); val to = idx(arg(a, 1), len)
            if (from >= to) "" else s.substring(from, to.coerceAtMost(len))
        }
        "substr" -> {
            val from = toNumber(arg(a, 0)).toInt().let { if (it < 0) (s.length + it).coerceAtLeast(0) else it.coerceAtMost(s.length) }
            val n = if (arg(a, 1) == RpTplUndefined) s.length else toNumber(arg(a, 1)).toInt()
            s.substring(from, (from + n).coerceIn(from, s.length))
        }
        "trim" -> s.trim()
        "trimStart" -> s.trimStart()
        "trimEnd" -> s.trimEnd()
        "toUpperCase" -> s.uppercase()
        "toLowerCase" -> s.lowercase()
        "split" -> when (val sep = arg(a, 0)) {
            RpTplUndefined -> arr(listOf(s))
            else -> toStr(sep).let { if (it.isEmpty()) arr(s.map { c -> c.toString() }) else arr(s.split(it)) }
        }
        "replace" -> s.replaceFirst(toStr(arg(a, 0)), toStr(arg(a, 1)))
        "replaceAll" -> s.replace(toStr(arg(a, 0)), toStr(arg(a, 1)))
        "repeat" -> s.repeat(toNumber(arg(a, 0)).toInt().coerceIn(0, 10_000))
        "padStart" -> s.padStart(toNumber(arg(a, 0)).toInt(), toStr(arg(a, 1)).firstOrNull() ?: ' ')
        "padEnd" -> s.padEnd(toNumber(arg(a, 0)).toInt(), toStr(arg(a, 1)).firstOrNull() ?: ' ')
        "charAt" -> s.getOrNull(toNumber(arg(a, 0)).toInt())?.toString() ?: ""
        "concat" -> s + a.joinToString("") { toStr(it) }
        "toString" -> s
        else -> error("字符串没有方法 $name")
    }

    private fun array(target: JSONArray, name: String, a: List<Any?>, vm: RpTplInterpreter): Any? {
        val items = list(target)
        fun cb(i: Int, v: Any?) = vm.callFunction(arg(a, 0), listOf(v, i.toDouble(), target))
        return when (name) {
            "push" -> { a.forEach { target.put(RpTplInterpreter.wrapJson(it)) }; target.length().toDouble() }
            "pop" -> if (target.length() == 0) RpTplUndefined else normalize(target.remove(target.length() - 1))
            "shift" -> if (target.length() == 0) RpTplUndefined else normalize(target.remove(0))
            "includes" -> items.any { RpTplValues.strictEquals(it, arg(a, 0)) }
            "indexOf" -> items.indexOfFirst { RpTplValues.strictEquals(it, arg(a, 0)) }.toDouble()
            "join" -> items.joinToString(if (arg(a, 0) == RpTplUndefined) "," else toStr(arg(a, 0))) { if (it == null || it == RpTplUndefined) "" else toStr(it) }
            "slice" -> {
                val len = items.size
                fun idx(v: Any?, def: Int) = if (v == RpTplUndefined) def else toNumber(v).toInt().let { if (it < 0) (len + it).coerceAtLeast(0) else it.coerceAtMost(len) }
                val from = idx(arg(a, 0), 0); val to = idx(arg(a, 1), len)
                arr(if (from >= to) emptyList() else items.subList(from, to))
            }
            "concat" -> arr(items + a.flatMap { if (it is JSONArray) list(it) else listOf(it) })
            "reverse" -> arr(items.reversed())
            "map" -> arr(items.mapIndexed { i, v -> cb(i, v) })
            "filter" -> arr(items.filterIndexed { i, v -> truthy(cb(i, v)) })
            "find" -> items.withIndex().firstOrNull { (i, v) -> truthy(cb(i, v)) }?.value ?: RpTplUndefined
            "findIndex" -> items.withIndex().indexOfFirst { (i, v) -> truthy(cb(i, v)) }.toDouble()
            "some" -> items.withIndex().any { (i, v) -> truthy(cb(i, v)) }
            "every" -> items.withIndex().all { (i, v) -> truthy(cb(i, v)) }
            "forEach" -> { items.forEachIndexed { i, v -> cb(i, v) }; RpTplUndefined }
            "reduce" -> {
                var acc = if (a.size > 1) arg(a, 1) else items.firstOrNull() ?: RpTplUndefined
                val start = if (a.size > 1) 0 else 1
                for (i in start until items.size) acc = vm.callFunction(arg(a, 0), listOf(acc, items[i], i.toDouble(), target))
                acc
            }
            "sort" -> {
                val sorted = if (arg(a, 0) == RpTplUndefined) items.sortedBy { toStr(it) }
                    else items.sortedWith { x, y -> toNumber(vm.callFunction(arg(a, 0), listOf(x, y))).let { if (it < 0) -1 else if (it > 0) 1 else 0 } }
                while (target.length() > 0) target.remove(target.length() - 1)
                sorted.forEach { target.put(RpTplInterpreter.wrapJson(it)) }
                target
            }
            "flat" -> arr(items.flatMap { if (it is JSONArray) list(it) else listOf(it) })
            "at" -> toNumber(arg(a, 0)).toInt().let { i -> items.getOrNull(if (i < 0) items.size + i else i) ?: RpTplUndefined }
            "toString" -> toStr(target)
            else -> error("数组没有方法 $name")
        }
    }

    @Suppress("unused")
    private fun Double.round(): Long = roundToLong()
}
