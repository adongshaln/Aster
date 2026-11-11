package com.adong.adchat.data.rp.template

/* ---------------- AST ---------------- */

sealed interface RpTplNode

sealed interface Stmt : RpTplNode {
    data class Text(val text: String) : Stmt
    data class Output(val expr: Expr, val escape: Boolean) : Stmt
    data class ExprStmt(val expr: Expr) : Stmt
    data class Decl(val names: List<Pair<String, Expr?>>) : Stmt
    data class Block(val body: List<Stmt>) : Stmt
    data class If(val cond: Expr, val then: Stmt, val otherwise: Stmt?) : Stmt
    data class ForOf(val name: String, val iterable: Expr, val body: Stmt, val keys: Boolean) : Stmt
    data class ForC(val init: Stmt?, val cond: Expr?, val step: Expr?, val body: Stmt) : Stmt
    data class While(val cond: Expr, val body: Stmt) : Stmt
    data object Break : Stmt
    data object Continue : Stmt
    data object Empty : Stmt
}

sealed interface Expr : RpTplNode {
    data class Lit(val value: Any?) : Expr
    data class Name(val name: String) : Expr
    data class Template(val parts: List<Any>) : Expr // String | Expr
    data class Arr(val items: List<Expr>) : Expr
    data class Obj(val entries: List<Pair<String, Expr>>) : Expr
    data class Member(val target: Expr, val key: Expr, val optional: Boolean) : Expr
    data class Call(val callee: Expr, val args: List<Expr>, val optional: Boolean) : Expr
    data class Unary(val op: String, val operand: Expr) : Expr
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
    data class Logical(val op: String, val left: Expr, val right: Expr) : Expr
    data class Cond(val test: Expr, val yes: Expr, val no: Expr) : Expr
    data class Assign(val op: String, val target: Expr, val value: Expr) : Expr
    data class Update(val op: String, val target: Expr, val prefix: Boolean) : Expr
    data class Arrow(val params: List<String>, val body: RpTplNode) : Expr
}

data class RpTplProgram(val body: List<Stmt>)

/* ---------------- Lexer ---------------- */

internal data class Tok(val type: Type, val text: String, val value: Any? = null) {
    enum class Type { Ident, Num, Str, Tpl, Punct, Text, OutEsc, OutRaw, OutEnd, Eof }
}

internal object RpTplLexer {
    private val PUNCT = listOf(
        "===", "!==", "**=", "...", "??=", "&&=", "||=", "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.",
        "++", "--", "+=", "-=", "*=", "/=", "%=", "**",
        "{", "}", "(", ")", "[", "]", ";", ",", "<", ">", "+", "-", "*", "/", "%", "!", "=", "?", ":", "."
    )

    fun tokenize(segments: List<RpTemplate.Segment>): List<Tok> {
        val out = mutableListOf<Tok>()
        for (segment in segments) when (segment) {
            is RpTemplate.Segment.Text -> out += Tok(Tok.Type.Text, segment.text)
            is RpTemplate.Segment.Out -> {
                out += Tok(if (segment.escape) Tok.Type.OutEsc else Tok.Type.OutRaw, "")
                lex(segment.expr, out)
                out += Tok(Tok.Type.OutEnd, "")
            }
            is RpTemplate.Segment.Code -> { lex(segment.code, out); out += Tok(Tok.Type.Punct, ";") }
        }
        out += Tok(Tok.Type.Eof, "")
        return out
    }

    fun lex(src: String, out: MutableList<Tok>) {
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2); i = if (e < 0) src.length else e + 2 }
                c.isDigit() || (c == '.' && src.getOrNull(i + 1)?.isDigit() == true) -> {
                    val start = i
                    if (src.startsWith("0x", i) || src.startsWith("0X", i)) {
                        i += 2
                        while (i < src.length && src[i].isLetterOrDigit()) i++
                        out += Tok(Tok.Type.Num, src.substring(start, i), src.substring(start + 2, i).toLong(16).toDouble())
                    } else {
                        while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == '_')) i++
                        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                            i++
                            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                            while (i < src.length && src[i].isDigit()) i++
                        }
                        out += Tok(Tok.Type.Num, src.substring(start, i), src.substring(start, i).replace("_", "").toDouble())
                    }
                }
                c == '"' || c == '\'' -> {
                    val (value, end) = readString(src, i + 1, c)
                    out += Tok(Tok.Type.Str, value, value)
                    i = end
                }
                c == '`' -> {
                    val (parts, end) = readTemplate(src, i + 1)
                    out += Tok(Tok.Type.Tpl, "`", parts)
                    i = end
                }
                c.isLetter() || c == '_' || c == '$' || c.code > 127 -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$' || src[i].code > 127)) i++
                    out += Tok(Tok.Type.Ident, src.substring(start, i))
                }
                else -> {
                    val p = PUNCT.firstOrNull { src.startsWith(it, i) } ?: error("无法识别的字符 '$c'")
                    out += Tok(Tok.Type.Punct, p)
                    i += p.length
                }
            }
        }
    }

    private fun readString(src: String, from: Int, quote: Char): Pair<String, Int> {
        val sb = StringBuilder()
        var i = from
        while (i < src.length && src[i] != quote) {
            if (src[i] == '\\' && i + 1 < src.length) {
                i++
                when (val e = src[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'u' -> { sb.append(src.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    '\n' -> Unit
                    else -> sb.append(e)
                }
            } else sb.append(src[i])
            i++
        }
        require(i < src.length) { "字符串未闭合" }
        return sb.toString() to i + 1
    }

    /** Template literal → list of raw strings and nested expression sources (as token lists). */
    private fun readTemplate(src: String, from: Int): Pair<List<Any>, Int> {
        val parts = ArrayList<Any>()
        val sb = StringBuilder()
        var i = from
        while (i < src.length && src[i] != '`') {
            when {
                src[i] == '\\' && i + 1 < src.length -> {
                    i++
                    sb.append(when (src[i]) { 'n' -> '\n'; 't' -> '\t'; else -> src[i] })
                    i++
                }
                src.startsWith("\${", i) -> {
                    parts += sb.toString(); sb.clear()
                    var depth = 1
                    var j = i + 2
                    while (j < src.length && depth > 0) {
                        when (src[j]) { '{' -> depth++; '}' -> depth--; '"', '\'' -> j = readString(src, j + 1, src[j]).second - 1 }
                        if (depth > 0) j++
                    }
                    val tokens = mutableListOf<Tok>()
                    lex(src.substring(i + 2, j), tokens)
                    tokens += Tok(Tok.Type.Eof, "")
                    parts.add(tokens)
                    i = j + 1
                }
                else -> { sb.append(src[i]); i++ }
            }
        }
        require(i < src.length) { "模板字符串未闭合" }
        parts += sb.toString()
        return parts to i + 1
    }
}

/* ---------------- Parser ---------------- */

internal class RpTplParser(private val toks: List<Tok>) {
    private var p = 0
    private fun peek(o: Int = 0) = toks.getOrElse(p + o) { toks.last() }
    private fun next() = toks[p++]
    private fun isP(text: String, o: Int = 0) = peek(o).type == Tok.Type.Punct && peek(o).text == text
    private fun isKw(text: String) = peek().type == Tok.Type.Ident && peek().text == text
    private fun eatP(text: String): Boolean = if (isP(text)) { p++; true } else false
    private fun expectP(text: String) { require(eatP(text)) { "缺少 '$text'，遇到 '${peek().text}'" } }

    fun program(): RpTplProgram {
        val body = mutableListOf<Stmt>()
        while (peek().type != Tok.Type.Eof) body += statement()
        return RpTplProgram(body)
    }

    private fun statement(): Stmt {
        val t = peek()
        return when {
            t.type == Tok.Type.Text -> { p++; Stmt.Text(t.text) }
            t.type == Tok.Type.OutEsc || t.type == Tok.Type.OutRaw -> {
                p++
                val expr = if (peek().type == Tok.Type.OutEnd) Expr.Lit(RpTplUndefined) else expression()
                eatP(";")
                require(next().type == Tok.Type.OutEnd) { "输出标签内只能写一个表达式" }
                Stmt.Output(expr, t.type == Tok.Type.OutEsc)
            }
            isP(";") -> { p++; Stmt.Empty }
            isP("{") -> block()
            isKw("let") || isKw("const") || isKw("var") -> declaration().also { eatP(";") }
            isKw("if") -> {
                p++; expectP("(")
                val cond = expression(); expectP(")")
                val then = statement()
                skipSemis()
                val otherwise = if (isKw("else")) { p++; statement() } else null
                Stmt.If(cond, then, otherwise)
            }
            isKw("for") -> forStatement()
            isKw("while") -> { p++; expectP("("); val c = expression(); expectP(")"); Stmt.While(c, statement()) }
            isKw("break") -> { p++; eatP(";"); Stmt.Break }
            isKw("continue") -> { p++; eatP(";"); Stmt.Continue }
            else -> Stmt.ExprStmt(expression()).also { eatP(";") }
        }
    }

    /** Code tags end in an implicit ';'; an `else` may follow after the next tag. */
    private fun skipSemis() {
        var o = 0
        while (isP(";", o)) o++
        if (peek(o).type == Tok.Type.Ident && peek(o).text == "else") p += o
    }

    private fun block(): Stmt {
        expectP("{")
        val body = mutableListOf<Stmt>()
        while (!isP("}")) {
            require(peek().type != Tok.Type.Eof) { "代码块未闭合，缺少 '}'" }
            body += statement()
        }
        p++
        return Stmt.Block(body)
    }

    private fun declaration(): Stmt {
        p++
        val names = mutableListOf<Pair<String, Expr?>>()
        do {
            val name = next().also { require(it.type == Tok.Type.Ident) { "变量名无效" } }.text
            names += name to (if (eatP("=")) assignment() else null)
        } while (eatP(","))
        return Stmt.Decl(names)
    }

    private fun forStatement(): Stmt {
        p++; expectP("(")
        val declared = isKw("let") || isKw("const") || isKw("var")
        if (declared && peek(1).type == Tok.Type.Ident && peek(2).type == Tok.Type.Ident && (peek(2).text == "of" || peek(2).text == "in")) {
            p++
            val name = next().text
            val keys = next().text == "in"
            val iterable = expression(); expectP(")")
            return Stmt.ForOf(name, iterable, statement(), keys)
        }
        val init = when {
            isP(";") -> null
            declared -> declaration()
            else -> Stmt.ExprStmt(expression())
        }
        expectP(";")
        val cond = if (isP(";")) null else expression()
        expectP(";")
        val step = if (isP(")")) null else expression()
        expectP(")")
        return Stmt.ForC(init, cond, step, statement())
    }

    fun expression(): Expr {
        var e = assignment()
        while (isP(",") ) { p++; e = assignment() } // comma operator: keep last
        return e
    }

    private fun assignment(): Expr {
        arrowOrNull()?.let { return it }
        val left = conditional()
        val op = peek().text
        if (peek().type == Tok.Type.Punct && op in setOf("=", "+=", "-=", "*=", "/=", "%=", "??=", "||=", "&&=")) {
            p++
            require(left is Expr.Name || left is Expr.Member) { "赋值目标无效" }
            return Expr.Assign(op, left, assignment())
        }
        return left
    }

    private fun arrowOrNull(): Expr? {
        if (peek().type == Tok.Type.Ident && isP("=>", 1)) {
            val name = next().text; p++
            return Expr.Arrow(listOf(name), arrowBody())
        }
        if (!isP("(")) return null
        var o = 1
        val params = mutableListOf<String>()
        while (!isP(")", o)) {
            if (peek(o).type != Tok.Type.Ident) return null
            params += peek(o).text; o++
            if (isP(",", o)) o++ else if (!isP(")", o)) return null
        }
        if (!isP("=>", o + 1)) return null
        p += o + 2
        return Expr.Arrow(params, arrowBody())
    }

    private fun arrowBody(): RpTplNode = if (isP("{")) block() else assignment()

    private fun conditional(): Expr {
        val test = binary(0)
        if (!eatP("?")) return test
        val yes = assignment(); expectP(":")
        return Expr.Cond(test, yes, assignment())
    }

    private val levels = listOf(
        setOf("??"), setOf("||"), setOf("&&"), setOf("==", "!=", "===", "!=="),
        setOf("<", ">", "<=", ">=", "in", "instanceof"), setOf("+", "-"), setOf("*", "/", "%"), setOf("**")
    )

    private fun binary(level: Int): Expr {
        if (level == levels.size) return unary()
        var left = binary(level + 1)
        while (true) {
            val t = peek()
            val op = t.text
            val matches = (t.type == Tok.Type.Punct || (t.type == Tok.Type.Ident && op in setOf("in", "instanceof"))) && op in levels[level]
            if (!matches) return left
            p++
            val right = if (op == "**") binary(level) else binary(level + 1)
            left = if (op in setOf("&&", "||", "??")) Expr.Logical(op, left, right) else Expr.Binary(op, left, right)
        }
    }

    private fun unary(): Expr {
        val t = peek()
        if (t.type == Tok.Type.Punct && t.text in setOf("!", "-", "+")) { p++; return Expr.Unary(t.text, unary()) }
        if (t.type == Tok.Type.Punct && t.text in setOf("++", "--")) { p++; return Expr.Update(t.text, unary(), prefix = true) }
        if (t.type == Tok.Type.Ident && t.text in setOf("typeof", "void", "await")) { p++; return Expr.Unary(t.text, unary()) }
        val e = postfix()
        if (peek().type == Tok.Type.Punct && peek().text in setOf("++", "--")) return Expr.Update(next().text, e, prefix = false)
        return e
    }

    private fun postfix(): Expr {
        var e = primary()
        while (true) {
            e = when {
                isP(".") -> { p++; Expr.Member(e, Expr.Lit(next().text), false) }
                isP("?.") -> {
                    p++
                    when {
                        isP("(") -> Expr.Call(e, args(), true)
                        isP("[") -> { p++; val k = expression(); expectP("]"); Expr.Member(e, k, true) }
                        else -> Expr.Member(e, Expr.Lit(next().text), true)
                    }
                }
                isP("[") -> { p++; val k = expression(); expectP("]"); Expr.Member(e, k, false) }
                isP("(") -> Expr.Call(e, args(), false)
                peek().type == Tok.Type.Tpl -> Expr.Call(e, listOf(template(next())), false) // tagged: pass string
                else -> return e
            }
        }
    }

    private fun args(): List<Expr> {
        expectP("(")
        val list = mutableListOf<Expr>()
        while (!isP(")")) {
            list += assignment()
            if (!eatP(",")) break
        }
        expectP(")")
        return list
    }

    private fun primary(): Expr {
        val t = next()
        return when (t.type) {
            Tok.Type.Num -> Expr.Lit(t.value)
            Tok.Type.Str -> Expr.Lit(t.value)
            Tok.Type.Tpl -> template(t)
            Tok.Type.Ident -> when (t.text) {
                "true" -> Expr.Lit(true)
                "false" -> Expr.Lit(false)
                "null" -> Expr.Lit(null)
                "undefined" -> Expr.Lit(RpTplUndefined)
                "new" -> postfix() // `new Date()` etc. resolve to host constructors called as functions
                else -> Expr.Name(t.text)
            }
            Tok.Type.Punct -> when (t.text) {
                "(" -> expression().also { expectP(")") }
                "[" -> {
                    val items = mutableListOf<Expr>()
                    while (!isP("]")) { items += assignment(); if (!eatP(",")) break }
                    expectP("]")
                    Expr.Arr(items)
                }
                "{" -> {
                    val entries = mutableListOf<Pair<String, Expr>>()
                    while (!isP("}")) {
                        val k = next()
                        val key = when (k.type) {
                            Tok.Type.Ident, Tok.Type.Str -> k.value as? String ?: k.text
                            Tok.Type.Num -> RpTplValues.toStr(k.value)
                            else -> error("对象键无效")
                        }
                        entries += key to (if (eatP(":")) assignment() else Expr.Name(key))
                        if (!eatP(",")) break
                    }
                    expectP("}")
                    Expr.Obj(entries)
                }
                else -> error("意外的符号 '${t.text}'")
            }
            else -> error("意外的内容")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun template(t: Tok): Expr = Expr.Template((t.value as List<Any>).map { part ->
        if (part is String) part else RpTplParser(part as List<Tok>).expression()
    })
}
