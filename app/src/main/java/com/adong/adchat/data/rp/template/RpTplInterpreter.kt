package com.adong.adchat.data.rp.template

import com.adong.adchat.data.rp.template.RpTplValues.normalize
import com.adong.adchat.data.rp.template.RpTplValues.toNumber
import com.adong.adchat.data.rp.template.RpTplValues.toStr
import com.adong.adchat.data.rp.template.RpTplValues.truthy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tree-walking interpreter for [RpTplProgram]. Values are Kotlin/org.json: Double, String, Boolean,
 * null, [RpTplUndefined], JSONObject, JSONArray, [RpTplFunction]. Runtime limits keep a runaway card
 * template from freezing the app.
 */
class RpTplInterpreter(private val scope: RpTplScope, private val escapeHtml: Boolean) {
    private val out = StringBuilder()
    private var steps = 0
    private val frames = ArrayDeque<MutableMap<String, Any?>>().apply { addLast(mutableMapOf()) }

    private object BreakSignal : RuntimeException(null, null, false, false)
    private object ContinueSignal : RuntimeException(null, null, false, false)

    fun run(program: RpTplProgram): String {
        frames.last()["print"] = RpTplFunction { args -> args.forEach { out.append(RpTplValues.output(it)) }; RpTplUndefined }
        program.body.forEach(::exec)
        return out.toString()
    }

    private fun tick() {
        if (++steps > MAX_STEPS) error("模板执行步数过多")
        if (out.length > MAX_OUTPUT) error("模板输出过长")
    }

    private fun exec(s: Stmt) {
        tick()
        when (s) {
            is Stmt.Text -> out.append(s.text)
            is Stmt.Output -> {
                val text = RpTplValues.output(eval(s.expr))
                out.append(if (s.escape && escapeHtml) escape(text) else text)
            }
            is Stmt.ExprStmt -> eval(s.expr)
            is Stmt.Decl -> s.names.forEach { (name, init) -> frames.last()[name] = init?.let(::eval) ?: RpTplUndefined }
            is Stmt.Block -> scoped { s.body.forEach(::exec) }
            is Stmt.If -> if (truthy(eval(s.cond))) exec(s.then) else s.otherwise?.let(::exec)
            is Stmt.While -> loop { while (truthy(eval(s.cond))) { if (!body(s.body)) break } }
            is Stmt.ForC -> scoped {
                s.init?.let(::exec)
                loop { while (s.cond == null || truthy(eval(s.cond))) { if (!body(s.body)) break; s.step?.let(::eval) } }
            }
            is Stmt.ForOf -> {
                val items: List<Any?> = when (val v = eval(s.iterable)) {
                    is JSONArray -> if (s.keys) (0 until v.length()).map { it.toString() } else (0 until v.length()).map { normalize(v.opt(it)) }
                    is JSONObject -> if (s.keys) v.keys().asSequence().toList() else error("对象不可迭代，请用 Object.entries")
                    is String -> if (s.keys) v.indices.map { it.toString() } else v.map { it.toString() }
                    else -> emptyList()
                }
                loop {
                    for (item in items) {
                        val stop = scoped { frames.last()[s.name] = item; !body(s.body) }
                        if (stop) break
                    }
                }
            }
            Stmt.Break -> throw BreakSignal
            Stmt.Continue -> throw ContinueSignal
            Stmt.Empty -> Unit
        }
    }

    /** Runs a loop body; false = break. */
    private fun body(stmt: Stmt): Boolean = try { exec(stmt); true } catch (_: ContinueSignal) { true } catch (_: BreakSignal) { false }

    private inline fun loop(block: () -> Unit) = try { block() } catch (_: BreakSignal) {}

    private inline fun <T> scoped(block: () -> T): T {
        frames.addLast(mutableMapOf())
        try { return block() } finally { frames.removeLast() }
    }

    private fun lookup(name: String): Any? {
        for (frame in frames.asReversed()) if (frame.containsKey(name)) return frame[name]
        return builtin(name) ?: scope.resolve(name)?.let(::normalize) ?: RpTplUndefined
    }

    private fun assignName(name: String, value: Any?) {
        for (frame in frames.asReversed()) if (frame.containsKey(name)) { frame[name] = value; return }
        frames.first()[name] = value
    }

    fun eval(e: Expr): Any? {
        tick()
        return when (e) {
            is Expr.Lit -> normalize(e.value)
            is Expr.Name -> lookup(e.name)
            is Expr.Template -> e.parts.joinToString("") { if (it is String) it else toStr(eval(it as Expr)) }
            is Expr.Arr -> JSONArray().apply { e.items.forEach { put(wrapJson(eval(it))) } }
            is Expr.Obj -> JSONObject().apply { e.entries.forEach { (k, v) -> put(k, wrapJson(eval(v))) } }
            is Expr.Member -> {
                val target = eval(e.target)
                if (e.optional && isNullish(target)) RpTplUndefined else member(target, eval(e.key))
            }
            is Expr.Call -> call(e)
            is Expr.Unary -> {
                val v = eval(e.operand)
                when (e.op) {
                    "!" -> !truthy(v)
                    "-" -> -toNumber(v)
                    "+" -> toNumber(v)
                    "typeof" -> RpTplValues.typeOf(v)
                    "void" -> RpTplUndefined
                    else -> v // await: host calls are synchronous
                }
            }
            is Expr.Logical -> {
                val l = eval(e.left)
                when (e.op) {
                    "&&" -> if (truthy(l)) eval(e.right) else l
                    "||" -> if (truthy(l)) l else eval(e.right)
                    else -> if (isNullish(l)) eval(e.right) else l
                }
            }
            is Expr.Binary -> binary(e.op, eval(e.left), eval(e.right))
            is Expr.Cond -> if (truthy(eval(e.test))) eval(e.yes) else eval(e.no)
            is Expr.Assign -> {
                val value = when (e.op) {
                    "=" -> eval(e.value)
                    "??=" -> eval(e.target).let { if (isNullish(it)) eval(e.value) else return it }
                    "||=" -> eval(e.target).let { if (!truthy(it)) eval(e.value) else return it }
                    "&&=" -> eval(e.target).let { if (truthy(it)) eval(e.value) else return it }
                    else -> binary(e.op.dropLast(1), eval(e.target), eval(e.value))
                }
                store(e.target, value)
                value
            }
            is Expr.Update -> {
                val old = toNumber(eval(e.target))
                val new = if (e.op == "++") old + 1 else old - 1
                store(e.target, new)
                if (e.prefix) new else old
            }
            is Expr.Arrow -> RpTplFunction { args ->
                scoped {
                    e.params.forEachIndexed { i, name -> frames.last()[name] = normalize(args.getOrElse(i) { RpTplUndefined }) }
                    when (val b = e.body) {
                        is Expr -> eval(b)
                        is Stmt -> { exec(b); RpTplUndefined }
                        else -> RpTplUndefined
                    }
                }
            }
        }
    }

    private fun store(target: Expr, value: Any?) {
        when (target) {
            is Expr.Name -> assignName(target.name, value)
            is Expr.Member -> {
                val obj = eval(target.target)
                val key = eval(target.key)
                when (obj) {
                    is JSONObject -> obj.put(toStr(key), wrapJson(value))
                    is JSONArray -> obj.put(toNumber(key).toInt(), wrapJson(value))
                    else -> error("无法给 ${RpTplValues.typeOf(obj)} 设置属性")
                }
            }
            else -> error("赋值目标无效")
        }
    }

    private fun binary(op: String, l: Any?, r: Any?): Any? = when (op) {
        "+" -> if (l is String || r is String || l is JSONArray || l is JSONObject || r is JSONArray || r is JSONObject) toStr(l) + toStr(r) else toNumber(l) + toNumber(r)
        "-" -> toNumber(l) - toNumber(r)
        "*" -> toNumber(l) * toNumber(r)
        "/" -> toNumber(l) / toNumber(r)
        "%" -> toNumber(l) % toNumber(r)
        "**" -> Math.pow(toNumber(l), toNumber(r))
        "==" -> RpTplValues.looseEquals(l, r)
        "!=" -> !RpTplValues.looseEquals(l, r)
        "===" -> RpTplValues.strictEquals(l, r)
        "!==" -> !RpTplValues.strictEquals(l, r)
        "<", ">", "<=", ">=" -> {
            val c = if (l is String && r is String) l.compareTo(r).toDouble() else toNumber(l) - toNumber(r)
            when (op) { "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
        }
        "in" -> when (r) {
            is JSONObject -> r.has(toStr(l))
            is JSONArray -> toNumber(l).toInt() in 0 until r.length()
            else -> false
        }
        "instanceof" -> false
        else -> error("不支持的运算符 $op")
    }

    private fun isNullish(v: Any?) = v == null || v == RpTplUndefined || v == JSONObject.NULL

    private fun call(e: Expr.Call): Any? {
        val callee = e.callee
        // Method call: resolve receiver once so built-in methods see `this`.
        if (callee is Expr.Member) {
            val receiver = eval(callee.target)
            if ((callee.optional || e.optional) && isNullish(receiver)) return RpTplUndefined
            val name = toStr(eval(callee.key))
            val args = e.args.map(::eval)
            (member(receiver, name) as? RpTplFunction)?.let { return normalize(it.call(args)) }
            if (e.optional) return RpTplUndefined
            return RpTplMethods.invoke(receiver, name, args, this)
        }
        val fn = eval(callee)
        if (e.optional && isNullish(fn)) return RpTplUndefined
        val args = e.args.map(::eval)
        return when (fn) {
            is RpTplFunction -> normalize(fn.call(args))
            else -> error("${(callee as? Expr.Name)?.name ?: "表达式"} 不是函数")
        }
    }

    fun callFunction(fn: Any?, args: List<Any?>): Any? = (fn as? RpTplFunction)?.call(args)?.let(::normalize) ?: error("回调不是函数")

    private fun member(target: Any?, key: Any?): Any? = when (target) {
        null, RpTplUndefined, JSONObject.NULL -> error("无法读取 ${toStr(target)} 的属性 '${toStr(key)}'")
        is JSONObject -> if (target.has(toStr(key))) normalize(target.opt(toStr(key))) else RpTplUndefined
        is JSONArray -> when (val k = toStr(key)) {
            "length" -> target.length().toDouble()
            else -> k.toIntOrNull()?.let { if (it in 0 until target.length()) normalize(target.opt(it)) else RpTplUndefined } ?: RpTplUndefined
        }
        is String -> when (val k = toStr(key)) {
            "length" -> target.length.toDouble()
            else -> k.toIntOrNull()?.let { target.getOrNull(it)?.toString() ?: RpTplUndefined } ?: RpTplUndefined
        }
        is RpTplObject -> target.get(toStr(key))?.let(::normalize) ?: RpTplUndefined
        else -> RpTplUndefined
    }

    private fun builtin(name: String): Any? = when (name) {
        "Math" -> RpTplMethods.math
        "JSON" -> RpTplMethods.json
        "Object" -> RpTplMethods.objectStatics
        "Array" -> RpTplMethods.arrayStatics
        "Number" -> RpTplMethods.numberFn
        "String" -> RpTplFunction { toStr(it.firstOrNull() ?: "") }
        "Boolean" -> RpTplFunction { truthy(it.firstOrNull()) }
        "parseInt" -> RpTplFunction { a -> toStr(a.firstOrNull()).trim().takeWhile { it.isDigit() || it == '-' }.toDoubleOrNull() ?: Double.NaN }
        "parseFloat" -> RpTplFunction { a -> Regex("^-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").find(toStr(a.firstOrNull()).trim())?.value?.toDoubleOrNull() ?: Double.NaN }
        "isNaN" -> RpTplFunction { toNumber(it.firstOrNull()).isNaN() }
        "Date" -> RpTplMethods.dateFn
        "NaN" -> Double.NaN
        "Infinity" -> Double.POSITIVE_INFINITY
        else -> null
    }

    companion object {
        const val MAX_STEPS = 200_000
        const val MAX_OUTPUT = 400_000

        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&#34;").replace("'", "&#39;")

        /** Converts interpreter values into org.json-storable ones. */
        fun wrapJson(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            RpTplUndefined -> JSONObject.NULL
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 9e15) v.toLong() else v
            is RpTplFunction, is RpTplObject -> JSONObject.NULL
            else -> v
        }
    }
}

/** Read-only host object with named members (e.g. a helper namespace). */
fun interface RpTplObject {
    fun get(name: String): Any?
}
