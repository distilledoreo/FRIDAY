package com.localfirst.assistant.genui

/** The system-prompt section that teaches the model the interface language, generated from the catalog. */
object UiPrompt {
    private val layoutProps = setOf("gap", "padding", "paddingX", "paddingY", "margin", "border", "radius", "background", "width", "height",
        "minWidth", "maxWidth", "minHeight", "maxHeight", "grow", "align", "justify", "wrap", "onClick")
    private val fieldProps = setOf("label", "name", "value", "defaultValue", "onChange", "disabled", "placeholder", "helper", "required")

    fun section(catalog: Catalog = Catalog.default): String = buildString {
        appendLine(GUIDE.trimIndent())
        appendLine("Components. Layout props (marked …layout): gap padding border radius background width maxWidth align justify wrap grow onClick. Field props (…field): label name value defaultValue onChange placeholder disabled.")
        for (spec in catalog.components.filter { it.listed }) {
            val names = spec.props.keys
            val hasLayout = names.containsAll(listOf("gap", "padding", "align"))
            val hasField = names.containsAll(listOf("label", "onChange", "value"))
            val own = names.filter { !(hasLayout && it in layoutProps) && !(hasField && it in fieldProps) }.map { name ->
                val prop = spec.props.getValue(name)
                if (prop.type == PropType.ENUM && prop.values.size <= 6) "$name=${prop.values.joinToString("|")}" else name
            } + listOfNotNull("…layout".takeIf { hasLayout }, "…field".takeIf { hasField })
            append("- <").append(spec.name)
            if (own.isNotEmpty()) append(' ').append(own.joinToString(" "))
            append(if (spec.kind == ComponentKind.VOID) "/>" else ">")
            append(": ").appendLine(spec.summary)
        }
        append("Children: <tabs> holds <tab label value>, <list> holds <item>, <timeline> holds <event time title>, <steps> holds <step title status>, <checklist> holds <check label>, <table> may hold <tr><th/><td/></tr>. HTML names also work: h1–h6, p, b, i, a, img, ul/ol/li, hr, div.")
    }

    private val GUIDE = """
        Interactive interfaces: when an answer is clearer as an interface (comparisons, plans, calculators, dashboards, trackers, forms, choices to pick from), write it in FRIDAY's interface language. Markdown is the prose, component tags are the interface, and JavaScript is the state and logic. Everything else stays plain Markdown.
        - {@body const [seats, setSeats] = useState(8)} declares state; {@body const price = seats * 29} derives values. One statement per {@body} line, before the markup that uses it.
        - {expression} inserts a value. Attributes take "strings" or {expressions}; handlers are functions: onChange={setSeats}, onClick={() => setOpen(!open)}.
        - {#if test}…{:else if test}…{:else}…{/if} and {#each items as item, i}…{/each} branch and repeat. JSX inside expressions and .map() work too. A function in {@body} that returns markup can be used as a <Capitalized/> component.
        - Interactions run on the phone without asking you again. GenUI.issueNewTurn(text) sends text as the user's next message, for buttons such as "Plan this" and for form results; also GenUI.copy(text), GenUI.openUrl(url), GenUI.share(text), GenUI.toast(text). A <form> gathers fields that have a name and calls onSubmit(values); without onSubmit it sends the values as the user's reply.
        - There is no network, timers, effects or eval: compute everything from state during render. Never invent data to fill a component; use facts you know or found. Show pictures with <AsyncImage query="a vivid description"/>; FRIDAY searches for the image. For web results, use their ref ids: {web1_0.title}, <Cite ref="web1_0"/>, <Entity ref="web1_0"/>.
        - Style with tokens: spacing 0–12 (4 px steps), radius none|sm|md|lg|xl|full, sizes xs–3xl, colors text|muted|accent|success|warning|danger|info|surface|card|border or a palette name (blue, green…). It's a phone: stack vertically, use grid columns={{base: 1, md: 2}}, avoid fixed pixel widths.
        - For what components can't do (a game, sound, a drawing canvas), write <AppBlock title="…">a self-contained HTML/CSS/JS app</AppBlock>. It runs offline in a sandbox, should use the colors var(--viz-text), var(--viz-card), var(--viz-panel), var(--viz-border), var(--viz-accent), and can call friday.sendMessage(text).
        - Use an interface only when it helps, at most one or two per reply, with a sentence of context first. Don't repeat the same content as a Markdown list.
        Example:
        ## Team plan estimate
        Drag the slider to see the **monthly price**.
        {@body const [seats, setSeats] = useState(8)}
        {@body const price = seats * 29}
        <card gap={2}>
          <slider label="Seats" min={1} max={50} value={seats} onChange={setSeats}/>
          <title size="xl">${'$'}{price}/mo</title>
          <button onClick={() => GenUI.issueNewTurn(`Set up a team plan for ${'$'}{seats} seats`)}>Set it up</button>
        </card>
    """
}
