package com.localfirst.assistant.genui.script

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InterpreterTest {
    private fun run(src: String, limits: Limits = Limits()): Any? {
        val interp = Interpreter(limits)
        return interp.execProgram(Parser(src).parseProgram())
    }

    /** The program's last expression, as JS would print it with String(). */
    private fun js(src: String): String = Values.toStr(run(src))

    private fun throws(src: String): String = try {
        run(src); fail("expected an error"); ""
    } catch (e: JsThrow) {
        Values.toStr(e.value)
    }

    @Test
    fun arithmeticAndCoercionFollowJs() {
        assertEquals("3", js("1 + 2"))
        assertEquals("12", js("'1' + 2"))
        assertEquals("-1", js("'1' - 2"))
        assertEquals("0.30000000000000004", js("0.1 + 0.2"))
        assertEquals("NaN", js("0 / 0"))
        assertEquals("Infinity", js("1 / 0"))
        assertEquals("8", js("2 ** 3"))
        assertEquals("512", js("2 ** 3 ** 2"))
        assertEquals("1", js("7 % 3"))
        assertEquals("-1", js("-7 % 3"))
        assertEquals("true", js("'2' == 2"))
        assertEquals("false", js("'2' === 2"))
        assertEquals("true", js("null == undefined"))
        assertEquals("false", js("null === undefined"))
        assertEquals("true", js("NaN !== NaN"))
        assertEquals("true", js("'b' > 'a'"))
        assertEquals("true", js("'10' < 9 === false"))
        assertEquals("1e+21", js("1e21"))
        assertEquals("1e-7", js("0.0000001"))
        assertEquals("0.000001", js("0.000001"))
        assertEquals("7", js("5 | 2"))
        assertEquals("-6", js("~5"))
        assertEquals("4294967295", js("-1 >>> 0"))
        assertEquals("[object Object]", js("'' + {}"))
        assertEquals("1,2,3", js("'' + [1, 2, 3]"))
    }

    @Test
    fun variablesScopesAndClosures() {
        assertEquals("3", js("let a = 1; { let a = 2; } a + 2"))
        assertEquals("[0,1,2]", js("const fs = []; for (let i = 0; i < 3; i++) fs.push(() => i); JSON.stringify(fs.map(f => f()))"))
        assertEquals("[3,3,3]", js("const fs = []; for (var i = 0; i < 3; i++) fs.push(() => i); JSON.stringify(fs.map(f => f()))"))
        assertEquals("5", js("function add(a, b = 2) { return a + b } add(3)"))
        assertEquals("6", js("const sum = (...xs) => xs.reduce((a, b) => a + b, 0); sum(1, 2, 3)"))
        assertEquals("hoisted", js("f(); function f() { return 'hoisted' } f()"))
        assertEquals("2", js("let counter = (() => { let n = 0; return () => ++n })(); counter(); counter()"))
        assertTrue(throws("const x = 1; x = 2").contains("constant"))
        assertTrue(throws("missing + 1").contains("missing is not defined"))
        assertEquals("undefined", js("typeof missing"))
    }

    @Test
    fun destructuringSpreadAndOptionalChaining() {
        assertEquals("1 2 3", js("const [a, , b = 2, ...rest] = [1, 9, undefined, 3]; `\${a} \${b} \${rest}`"))
        assertEquals("Ada 36 x", js("const { name, age = 36, more: { tag } } = { name: 'Ada', more: { tag: 'x' } }; `\${name} \${age} \${tag}`"))
        assertEquals("{\"b\":2,\"c\":3}", js("const { a, ...others } = { a: 1, b: 2, c: 3 }; JSON.stringify(others)"))
        assertEquals("{\"a\":1,\"b\":3}", js("JSON.stringify({ ...{ a: 1, b: 2 }, b: 3 })"))
        assertEquals("1,2,3,4", js("[...[1, 2], ...new Set([3, 4])].join()"))
        assertEquals("undefined", js("const o = null; o?.a.b.c"))
        assertEquals("undefined", js("const o = {}; o.fn?.()"))
        assertEquals("2", js("const o = { a: { b: 2 } }; o?.a?.['b']"))
        assertEquals("fallback", js("null ?? 'fallback'"))
        assertEquals("0", js("0 ?? 'fallback'"))
        assertEquals("1,2", js("let x = 2, y = 1; [x, y] = [y, x]; [x, y].join()"))
        assertEquals("5", js("let t = { n: 2 }; t.n += 3; t.n"))
        assertEquals("7", js("let v = null; v ??= 7; v"))
    }

    @Test
    fun controlFlow() {
        assertEquals("10", js("let s = 0; for (const n of [1, 2, 3, 4]) s += n; s"))
        assertEquals("a,b", js("const ks = []; for (const k in { a: 1, b: 2 }) ks.push(k); ks.join()"))
        assertEquals("3", js("let i = 0; while (true) { if (++i === 3) break } i"))
        assertEquals("1,3,5", js("const out = []; for (let i = 0; i < 6; i++) { if (i % 2 === 0) continue; out.push(i) } out.join()"))
        assertEquals("two", js("function f(x) { switch (x) { case 1: return 'one'; case 2: return 'two'; default: return 'many' } } f(2)"))
        assertEquals("ab", js("let s = ''; switch (1) { case 1: s += 'a'; case 2: s += 'b'; break; case 3: s += 'c' } s"))
        assertEquals("caught boom", js("let r; try { throw new Error('boom') } catch (e) { r = 'caught ' + e.message } r"))
        assertEquals("finally", js("let r = ''; try { r = 'try' } finally { r = 'finally' } r"))
        assertEquals("2", js("outer: for (let i = 0; i < 3; i++) { for (let j = 0; j < 3; j++) { if (j === 1) continue outer; if (i === 2) break outer } } 2"))
        assertEquals("3", js("let n = 0; do { n++ } while (n < 3); n"))
        assertEquals("TypeError", js("let n; try { null.x } catch (e) { n = e.name } n"))
        assertEquals("true", js("let t; try { undefined() } catch (e) { t = e instanceof TypeError } t"))
    }

    @Test
    fun stringsAndRegex() {
        assertEquals("HELLO", js("'hello'.toUpperCase()"))
        assertEquals("05", js("'5'.padStart(2, '0')"))
        assertEquals("a-b-c", js("'a b c'.split(' ').join('-')"))
        assertEquals("b,c", js("'a,b,c'.split(',').slice(1).join()"))
        assertEquals("1,234", js("'1234'.replace(/(\\d)(?=(\\d{3})+$)/g, '$1,')"))
        assertEquals("x-x", js("'a-b'.replace(/[a-z]/g, 'x')"))
        assertEquals("A-B", js("'a-b'.replace(/[a-z]/g, c => c.toUpperCase())"))
        assertEquals("true", js("/^\\d+$/.test('123')"))
        assertEquals("2026", js("'Due 2026-10-11'.match(/(\\d{4})-(\\d{2})/)[1]"))
        assertEquals("ell", js("'hello'.slice(1, -1)"))
        assertEquals("lo", js("'hello'.slice(-2)"))
        assertEquals("abcabc", js("'abc'.repeat(2)"))
        assertEquals("true", js("'Hello'.startsWith('He') && 'Hello'.includes('ll')"))
        assertEquals("line1\nline2", js("`line1\nline2`"))
        assertEquals("3 items", js("const n = 3; `\${n} \${n === 1 ? 'item' : 'items'}`"))
        assertEquals("o", js("'hello'.at(-1)"))
        assertEquals("a b", js("'  a b  '.trim()"))
        assertEquals("-1", js("'a'.localeCompare('b')"))
    }

    @Test
    fun arrays() {
        assertEquals("2,4,6", js("[1, 2, 3].map(x => x * 2).join()"))
        assertEquals("2", js("[1, 2, 3].filter(x => x % 2).length"))
        assertEquals("6", js("[1, 2, 3].reduce((a, b) => a + b)"))
        assertEquals("1,10,2", js("[10, 2, 1].sort().join()"))
        assertEquals("1,2,10", js("[10, 2, 1].sort((a, b) => a - b).join()"))
        assertEquals("3", js("[{ id: 1 }, { id: 3 }].find(x => x.id > 1).id"))
        assertEquals("1", js("['a', 'b'].indexOf('b')"))
        assertEquals("true", js("[NaN].includes(NaN)"))
        assertEquals("1,2,3,4", js("[[1, 2], [3, [4]]].flat(2).join()"))
        assertEquals("0,1,2", js("Array.from({ length: 3 }, (_, i) => i).join()"))
        assertEquals("a:1,b:2", js("Object.entries({ a: 1, b: 2 }).map(([k, v]) => k + ':' + v).join()"))
        assertEquals("3", js("const a = [1, 2]; a.push(3); a.length"))
        assertEquals("2,3", js("const a = [1, 2, 3, 4]; a.splice(1, 2).join()"))
        assertEquals("true", js("[1, 2].every(x => x > 0) && [1, 2].some(x => x > 1)"))
        assertEquals("3,2,1", js("[1, 2, 3].reverse().join()"))
        assertEquals("[1,2]", js("JSON.stringify([...new Set([1, 1, 2])])"))
        assertEquals("1", js("[3, 1, 2].toSorted()[0]"))
        assertEquals("2", js("Math.max(...[1, 2])"))
    }

    @Test
    fun objectsMapsJsonAndMath() {
        assertEquals("a,b", js("Object.keys({ a: 1, b: 2 }).join()"))
        assertEquals("{\"x\":[1,{\"y\":null}]}", js("JSON.stringify({ x: [1, { y: null }], f() {}, u: undefined })"))
        assertEquals("{\n  \"a\": 1\n}", js("JSON.stringify({ a: 1 }, null, 2)"))
        assertEquals("2", js("JSON.parse('{\"a\":[1,2]}').a[1]"))
        assertEquals("2", js("const m = new Map(); m.set('a', 1).set('b', 2); m.get('b')"))
        assertEquals("2", js("new Map([['a', 1], ['b', 2]]).size"))
        assertEquals("a=1;b=2", js("const m = new Map([['a', 1], ['b', 2]]); [...m].map(([k, v]) => `\${k}=\${v}`).join(';')"))
        assertEquals("3", js("Math.round(2.5)"))
        assertEquals("-2", js("Math.round(-2.5)"))
        assertEquals("4", js("Math.ceil(3.2)"))
        assertEquals("3", js("Math.trunc(3.9)"))
        assertEquals("true", js("const r = Math.random(); r >= 0 && r < 1"))
        assertEquals("7", js("parseInt('7px')"))
        assertEquals("3.5", js("parseFloat('3.5kg')"))
        assertEquals("255", js("parseInt('ff', 16)"))
        assertEquals("true", js("Number.isInteger(5) && !Number.isInteger(5.5)"))
        assertEquals("true", js("Array.isArray([]) && typeof {} === 'object' && typeof (() => 1) === 'function'"))
        assertEquals("hi Ada", js("const p = { name: 'Ada', greet() { return 'hi ' + this.name } }; p.greet()"))
        assertEquals("Rex", js("function Dog(n) { this.name = n } Dog.prototype.call = function () { return this.name }; new Dog('Rex').call()"))
        assertEquals("true", js("const o = Object.freeze({ a: 1 }); o.a = 2; o.a === 1"))
    }

    @Test
    fun numberFormatting() {
        assertEquals("3.14", js("(3.14159).toFixed(2)"))
        assertEquals("1.01", js("(1.005 + 0.005).toFixed(2)"))
        assertEquals("10", js("(9.995).toFixed(0)"))
        assertEquals("-1.50", js("(-1.5).toFixed(2)"))
        assertEquals("1,234,567.891", js("(1234567.891).toLocaleString()"))
        assertEquals("1,234.5", js("(1234.5).toLocaleString('en-US')"))
        assertEquals("$1,234.50", js("(1234.5).toLocaleString('en-US', { style: 'currency', currency: 'USD' })"))
        assertEquals("€9.00", js("new Intl.NumberFormat('en-US', { style: 'currency', currency: 'EUR' }).format(9)"))
        assertEquals("45%", js("new Intl.NumberFormat('en-US', { style: 'percent' }).format(0.45)"))
        assertEquals("1.2M", js("new Intl.NumberFormat('en-US', { notation: 'compact' }).format(1234567)"))
        assertEquals("12.346", js("(12.3456).toPrecision(5)"))
        assertEquals("ff", js("(255).toString(16)"))
    }

    @Test
    fun dates() {
        Dates.zone = "America/New_York"
        try {
            assertEquals("2026", js("new Date('2026-10-11T09:30:00').getFullYear()"))
            assertEquals("9", js("new Date('2026-10-11T09:30:00').getHours()"))
            assertEquals("2026-10-11T13:30:00.000Z", js("new Date('2026-10-11T09:30:00').toISOString()"))
            assertEquals("Sun, Oct 11", js("new Date(2026, 9, 11).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })"))
            assertEquals("October 11, 2026", js("new Date(2026, 9, 11).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })"))
            assertEquals("10/11/2026", js("new Date(2026, 9, 11).toLocaleDateString()"))
            assertEquals("9:30 AM", js("new Date(2026, 9, 11, 9, 30).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })"))
            assertEquals("1", js("const d = new Date(2026, 0, 31); d.setDate(d.getDate() + 1); d.getMonth()"))
            assertEquals("86400000", js("new Date('2026-01-02') - new Date('2026-01-01')"))
            assertEquals("true", js("typeof Date.now() === 'number'"))
        } finally {
            Dates.zone = java.time.ZoneId.systemDefault().id
        }
    }

    @Test
    fun budgetsStopRunawayPrograms() {
        try {
            run("while (true) {}", Limits(maxSteps = 100_000))
            fail("loop should stop")
        } catch (e: ScriptLimit) {
            assertTrue(e.message!!.contains("ran too long"))
        }
        try {
            run("function f() { return f() } f()")
            fail("recursion should stop")
        } catch (e: ScriptLimit) {
            assertTrue(e.message!!.contains("recursion"))
        }
        try {
            run("let s = 'x'; while (true) s += s")
            fail("string growth should stop")
        } catch (e: ScriptLimit) {
            assertTrue(e.message!!.contains("too long"))
        }
        try {
            run("const a = []; while (true) a.push({})", Limits(maxAllocations = 10_000))
            fail("allocation should stop")
        } catch (e: ScriptLimit) {
            assertTrue(e.message!!.contains("memory"))
        }
        // try/catch can't swallow a budget stop.
        try {
            run("try { while (true) {} } catch (e) {}", Limits(maxSteps = 10_000))
            fail("budget should not be catchable")
        } catch (e: ScriptLimit) { }
    }

    @Test
    fun theSandboxHasNoWayOut() {
        for (name in listOf("fetch", "XMLHttpRequest", "setTimeout", "setInterval", "eval", "Function", "require", "process", "globalThis", "window", "importScripts", "WebSocket")) {
            assertEquals(name, "undefined", js("typeof $name"))
        }
        assertTrue(throws("new Function('return 1')").contains("not defined"))
        assertEquals("undefined", js("({}).constructor"))
    }

    @Test
    fun semicolonsAreOptional() {
        assertEquals("3", js("const a = 1\nconst b = 2\na + b"))
        assertEquals("undefined", js("function f() {\n  return\n  42\n}\nf()"))
        assertEquals("2", js("let x = 1\nx++\nx"))
    }
}
