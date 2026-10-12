package com.localfirst.assistant.genui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiRuntimeTest {
    private val seats = """
        ## Team plan estimate
        Drag the slider to see the **monthly price** for your team.
        {@body const [seats,setSeats] = DIL.useState(8)}
        {@body const price = seats*29}
        <box border padding={3} gap={2}>
          <slider min={1} max={50} value={seats} onChange={setSeats}/>
          <title size="xl">${'$'}{price}/mo</title>
        </box>
    """.trimIndent()

    private fun elements(nodes: List<UiNode>): List<UiElement> = nodes.flatMap { n ->
        if (n is UiElement) listOf(n) + elements(n.children) else emptyList()
    }

    private fun text(nodes: List<UiNode>): String = nodes.joinToString("") { n ->
        when (n) { is UiText -> n.text; is UiElement -> text(n.children); is UiRaw -> "" }
    }

    @Test
    fun theArticleExampleRendersAndRespondsToTheSliderWithoutTheModel() {
        val runtime = UiRuntime()
        val first = runtime.load(UiCompiler.compile(seats, complete = true))
        val top = first.nodes
        assertTrue((top[0] as UiText).text.startsWith("## Team plan estimate"))
        val box = top[1] as UiElement
        assertEquals("box", box.type)
        assertEquals(3.0, box.props["padding"])
        assertEquals(true, box.props["border"])
        val slider = box.children[0] as UiElement
        assertEquals(8.0, slider.props["value"])
        assertEquals("\$232/mo", text(listOf(box.children[1])))

        val moved = runtime.dispatch(slider.callback("onChange")!!, listOf(9.0))
        val price = (moved.nodes[1] as UiElement).children[1]
        assertEquals("\$261/mo", text(listOf(price)))
        assertEquals(mapOf("seats" to 9L), runtime.changedState())
    }

    @Test
    fun streamingCutsAtTheLastCompleteConstructAndKeepsState() {
        val runtime = UiRuntime()
        // Mid-tag: the slider isn't shown yet, the text is.
        val partial = seats.substring(0, seats.indexOf("onChange"))
        val early = runtime.load(UiCompiler.compile(partial, complete = false))
        assertTrue(text(early.nodes).contains("monthly price"))
        assertTrue(elements(early.nodes).none { it.type == "slider" })

        val done = runtime.load(UiCompiler.compile(seats, complete = false))
        val slider = elements(done.nodes).single { it.type == "slider" }
        runtime.dispatch(slider.callback("onChange")!!, listOf(20.0))
        // A later recompile (more text streamed in) keeps the user's value.
        val more = runtime.load(UiCompiler.compile("$seats\nAsk me to adjust it.", complete = true))
        assertEquals(20.0, elements(more.nodes).single { it.type == "slider" }.props["value"])
        assertEquals("\$580/mo", text(listOf(elements(more.nodes).single { it.type == "title" })))
    }

    @Test
    fun openElementsWithContentCloseAndEmptyOnesWait() {
        val compiled = UiCompiler.compile("Intro\n<card title=\"Plan\">\n  <text>Day one", complete = false)
        val card = UiRuntime().load(compiled).nodes.filterIsInstance<UiElement>().single()
        assertEquals("card", card.type)
        assertEquals("Day one", text(card.children).trim())
        assertTrue(compiled.diagnostics.any { it.code == "unclosed_element" })

        val waiting = UiRuntime().load(UiCompiler.compile("Intro\n<card title=\"Plan\">\n", complete = false)).nodes
        assertTrue(waiting.none { it is UiElement })
        val half = UiRuntime().load(UiCompiler.compile("Intro <sli", complete = false)).nodes
        assertEquals("Intro ", text(half))
    }

    @Test
    fun eachIfFunctionComponentsAndMapRender() {
        val src = """
            {@body const items = [{name: 'Rice', qty: 2}, {name: 'Lamb', qty: 1}]}
            {@body const [showAll, setShowAll] = useState(false)}
            {@body function Line({name, qty}) { return <row><text>{name}</text><badge>{qty}</badge></row> }}
            {#each items as item, i (item.name)}
            <Line name={item.name} qty={item.qty}/>
            {/each}
            {#if showAll}
            <text>All shown</text>
            {:else}
            <button onClick={() => setShowAll(true)}>Show all</button>
            {/if}
            <list>{items.map(x => <item>{x.name.toUpperCase()}</item>)}</list>
        """.trimIndent()
        val runtime = UiRuntime()
        val result = runtime.load(UiCompiler.compile(src, complete = true))
        val all = elements(result.nodes)
        assertEquals(listOf("Rice", "Lamb"), all.filter { it.type == "row" }.map { text(it.children).substringBefore(it.children.last().let { b -> text(listOf(b)) }) })
        assertEquals(listOf("RICE", "LAMB"), all.filter { it.type == "item" }.map { text(it.children) })
        val button = all.single { it.type == "button" }
        val after = runtime.dispatch(button.callback("onClick")!!, emptyList())
        assertTrue(text(after.nodes).contains("All shown"))
        assertTrue(elements(after.nodes).none { it.type == "button" })
    }

    @Test
    fun actionsOnlyHappenOnUserInteraction() {
        val src = """
            {@body const [style, setStyle] = useState('cozy')}
            {@body GenUI.issueNewTurn('render spam')}
            <segmented options={['cozy', 'sporty']} value={style} onChange={setStyle}/>
            <button onClick={() => GenUI.issueNewTurn('Develop a ' + style + ' design')}>Develop this</button>
        """.trimIndent()
        val runtime = UiRuntime()
        val result = runtime.load(UiCompiler.compile(src, complete = true))
        assertTrue(result.actions.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "action_during_render" })
        val all = elements(result.nodes)
        runtime.dispatch(all.single { it.type == "segmented" }.callback("onChange")!!, listOf("sporty"))
        val clicked = runtime.dispatch(elements(runtime.render().nodes).single { it.type == "button" }.callback("onClick")!!, emptyList())
        assertEquals(listOf(UiAction.NewTurn("Develop a sporty design")), clicked.actions)
    }

    @Test
    fun invalidPropsAreRemovedAndReported() {
        val result = UiRuntime().load(UiCompiler.compile("<icon name=\"star\" fill=\"red\"/>\n<box gap=\"1\" padding=\"huge\"><text>x</text></box>", complete = true))
        val icon = elements(result.nodes).single { it.type == "icon" }
        assertFalse(icon.props.containsKey("fill"))
        assertTrue(result.diagnostics.any { it.code == "unknown_prop" && it.message.contains("fill") })
        val box = elements(result.nodes).single { it.type == "box" }
        // A numeric string for a spacing token is accepted; nonsense is dropped.
        assertEquals(1.0, box.props["gap"])
        assertFalse(box.props.containsKey("padding"))
        assertTrue(result.diagnostics.any { it.code == "invalid_value" })
    }

    @Test
    fun aFailingExpressionRemovesOnlyItsElement() {
        val result = UiRuntime().load(UiCompiler.compile("<text>before</text>\n<text>{missing.value}</text>\n<text>after</text>", complete = true))
        // As with ChatGPT's __dilSafe, the failing expression renders nothing; its neighbors are untouched.
        assertEquals(listOf("before", "", "after"), elements(result.nodes).map { text(it.children) })
        assertTrue(result.diagnostics.any { it.code == "runtime_error" && it.message.contains("missing is not defined") })
    }

    @Test
    fun aRunawayProgramIsStoppedAndTheLastGoodTreeKept() {
        val runtime = UiRuntime()
        val good = runtime.load(UiCompiler.compile("<text>fine</text>", complete = false))
        val bad = runtime.load(UiCompiler.compile("<text>fine</text>\n{@body while (true) {}}", complete = true))
        assertEquals(good.nodes, bad.nodes)
        assertTrue(bad.diagnostics.any { it.code == "watchdog" })
    }

    @Test
    fun stateIsSavedAndRestored() {
        val first = UiRuntime()
        val slider = elements(first.load(UiCompiler.compile(seats, true)).nodes).single { it.type == "slider" }
        first.dispatch(slider.callback("onChange")!!, listOf(30.0))
        val saved = first.snapshotState()
        val second = UiRuntime(savedState = saved)
        assertEquals(30.0, elements(second.load(UiCompiler.compile(seats, true)).nodes).single { it.type == "slider" }.props["value"])
    }

    @Test
    fun bindingsLetTheModelReferenceToolResults() {
        val result = UiRuntime(bindings = mapOf("web1_0" to mapOf("title" to "Sony WH-1000XM6", "price" to "\$399"))).load(
            UiCompiler.compile("<title size=\"lg\">{web1_0.title}</title><caption>{web1_0.price}</caption>", true),
        )
        assertEquals(listOf("Sony WH-1000XM6", "\$399"), elements(result.nodes).map { text(it.children) })
    }

    @Test
    fun htmlAliasesVoidTagsAndStyle() {
        val result = UiRuntime().load(UiCompiler.compile("<h2>Menu</h2><hr><div style=\"padding: 12px; background: blue\"><p>Soup</p></div>", true))
        val all = elements(result.nodes)
        assertEquals(listOf("title", "divider", "box", "text"), all.map { it.type })
        assertEquals("xl", all[0].props["size"])
        assertEquals("12px", all[2].props["padding"])
        assertEquals("blue", all[2].props["background"])
    }

    @Test
    fun detectionIgnoresCodeAndPlainProse() {
        assertTrue(UiCompiler.isInterface("Here:\n<slider min={1}/>"))
        assertTrue(UiCompiler.isInterface("{@body const x = 1}\nHi"))
        assertFalse(UiCompiler.isInterface("Use `<slider>` like this:\n```html\n<slider/>\n```"))
        assertFalse(UiCompiler.isInterface("I love you <3 and List<String> works; a < b."))
        assertFalse(UiCompiler.isInterface("Plain **markdown** answer."))
    }

    @Test
    fun proseWithBracesAndAngleBracketsSurvives() {
        val src = "Ratio a < b, set {x} literally, and List<String>.\n<badge>ok</badge>"
        val nodes = UiRuntime().load(UiCompiler.compile(src, true)).nodes
        assertTrue(text(nodes).contains("Ratio a < b"))
        assertTrue(text(nodes).contains("List<String>"))
        assertNotNull(elements(nodes).singleOrNull { it.type == "badge" })
    }

    @Test
    fun theFallbackIncludesComputedValues() {
        val markdown = UiFallback.markdown(seats)
        assertTrue(markdown.contains("## Team plan estimate"))
        assertTrue(markdown.contains("\$232/mo"))
        val list = UiFallback.markdown("{@body const xs = ['a', 'b']}\n<list>{#each xs as x}<item>{x}</item>{/each}</list>")
        assertEquals("- a\n- b", list)
        val table = UiFallback.markdown("<table columns={['Item', 'Qty']} rows={[['Rice', 2], ['Lamb', 1]]}/>")
        assertEquals("| Item | Qty |\n| --- | --- |\n| Rice | 2 |\n| Lamb | 1 |", table)
    }

    @Test
    fun appBlocksStayRaw() {
        val nodes = UiRuntime().load(UiCompiler.compile("Play:\n<AppBlock title=\"Drum Lab\"><div id=\"dl\"></div><script>let a = {x: 1};</script></AppBlock>", true)).nodes
        val raw = nodes.filterIsInstance<UiRaw>().single()
        assertEquals("Drum Lab", raw.props["title"])
        assertTrue(raw.source.contains("<script>let a = {x: 1};</script>"))
        assertTrue(raw.complete)
    }

    @Test
    fun thePromptListsTheCatalog() {
        val prompt = UiPrompt.section()
        assertTrue(prompt.contains("- <slider"))
        assertTrue(prompt.contains("- <AsyncImage"))
        assertTrue(prompt.contains("\${price}/mo"))
        assertFalse(prompt.contains("- <tr"))
    }
}
