package com.localfirst.assistant.genui

/**
 * The components the model may use, their properties and design tokens. The compiler validates
 * against it, the prompt describes it, and the app registers a native renderer per entry. Adding a
 * component is one [component] line here plus one renderer in the app.
 */
enum class PropType { STRING, NUMBER, BOOLEAN, ENUM, FUNCTION, ARRAY, OBJECT, ANY, SPACING, RADIUS, COLOR, SIZE, ICON, URL }

data class PropSpec(val name: String, val type: PropType, val values: List<String> = emptyList())

enum class ComponentKind {
    /** Lays out other components and Markdown blocks. */
    BLOCK,
    /** Holds text and inline spans: title, text, button. */
    TEXT,
    /** An inline span inside text: bold, link. */
    INLINE,
    /** No children: divider, slider, image. */
    VOID,
    /** Raw source rendered in a web sandbox. */
    RAW,
}

data class ComponentSpec(
    val name: String,
    val kind: ComponentKind,
    val props: Map<String, PropSpec>,
    val summary: String,
    /** Shown in the model's catalog. Aliases and internal parts stay out of the prompt. */
    val listed: Boolean = true,
    /** Its data comes from FRIDAY (image search, tool results), not from the model's text. */
    val serverResolved: Boolean = false,
)

/** An alternative tag name, often HTML's, mapped onto a catalog component with fixed props. */
data class Alias(val name: String, val target: String, val fixed: Map<String, Any?> = emptyMap())

object DesignTokens {
    /** 4 px spacing scale: `padding={3}` is 12 dp. */
    const val SPACING_UNIT_DP = 4
    val radius = listOf("none", "sm", "md", "lg", "xl", "full")
    val sizes = listOf("xs", "sm", "md", "lg", "xl", "2xl", "3xl")
    val colors = listOf(
        "text", "muted", "subtle", "accent", "primary", "success", "warning", "danger", "info",
        "surface", "card", "border", "inverse",
        "red", "orange", "amber", "yellow", "lime", "green", "teal", "cyan", "blue", "indigo", "violet", "purple", "pink", "gray",
    )
    val tones = listOf("neutral", "accent", "success", "warning", "danger", "info")
}

class Catalog(private val specs: Map<String, ComponentSpec>, private val aliases: Map<String, Alias>) {
    val components: Collection<ComponentSpec> get() = specs.values

    /** The canonical spec for a tag, following aliases. */
    fun resolve(tag: String): Pair<ComponentSpec, Map<String, Any?>>? {
        specs[tag]?.let { return it to emptyMap() }
        val alias = aliases[tag] ?: aliases[tag.lowercase()]?.takeIf { tag.first().isLowerCase() } ?: return null
        val spec = specs[alias.target] ?: return null
        return spec to alias.fixed
    }

    fun has(tag: String) = resolve(tag) != null

    /** Tags that switch a reply into interface mode when they appear in prose. HTML aliases don't. */
    fun isSignature(tag: String) = specs[tag]?.listed == true

    fun isPrefix(text: String) = specs.keys.any { it.startsWith(text) } || aliases.keys.any { it.startsWith(text) }

    fun isVoid(tag: String) = resolve(tag)?.first?.kind == ComponentKind.VOID
    fun isRaw(tag: String) = resolve(tag)?.first?.kind == ComponentKind.RAW

    /** Props every component accepts. */
    val commonProps: Map<String, PropSpec> = listOf(
        PropSpec("key", PropType.ANY), PropSpec("id", PropType.STRING), PropSpec("style", PropType.ANY),
        PropSpec("className", PropType.STRING), PropSpec("class", PropType.STRING), PropSpec("hidden", PropType.BOOLEAN),
        PropSpec("children", PropType.ANY), PropSpec("ariaLabel", PropType.STRING), PropSpec("aria-label", PropType.STRING),
    ).associateBy { it.name }

    companion object {
        private fun parseProps(spec: String): Map<String, PropSpec> = spec.split(' ').filter { it.isNotBlank() }.associate { entry ->
            val name = entry.substringBefore(':')
            val type = entry.substringAfter(':', "any")
            val prop = when {
                type.startsWith("enum(") -> PropSpec(name, PropType.ENUM, type.removePrefix("enum(").removeSuffix(")").split(','))
                else -> PropSpec(name, when (type) {
                    "str" -> PropType.STRING; "num" -> PropType.NUMBER; "bool" -> PropType.BOOLEAN; "fn" -> PropType.FUNCTION
                    "arr" -> PropType.ARRAY; "obj" -> PropType.OBJECT; "space" -> PropType.SPACING; "radius" -> PropType.RADIUS
                    "color" -> PropType.COLOR; "size" -> PropType.SIZE; "icon" -> PropType.ICON; "url" -> PropType.URL
                    else -> PropType.ANY
                })
            }
            name to prop
        }

        private const val LAYOUT = "gap:space padding:space paddingX:space paddingY:space margin:space border:bool radius:radius " +
            "background:color width:size height:size minWidth:size maxWidth:size minHeight:size maxHeight:size grow:num " +
            "align:enum(start,center,end,stretch,baseline) justify:enum(start,center,end,between,around,evenly) wrap:bool onClick:fn"
        private const val TYPE = "size:size weight:enum(regular,medium,semibold,bold) color:color align:enum(start,center,end) italic:bool lines:num mono:bool"
        private const val FIELD = "label:str name:str value:any defaultValue:any onChange:fn disabled:bool placeholder:str helper:str required:bool"

        /** FRIDAY's catalog. */
        val default: Catalog by lazy { build() }

        private fun build(): Catalog {
            val specs = linkedMapOf<String, ComponentSpec>()
            fun component(name: String, kind: ComponentKind, props: String, summary: String, listed: Boolean = true, server: Boolean = false) {
                specs[name] = ComponentSpec(name, kind, parseProps(props), summary, listed, server)
            }
            val B = ComponentKind.BLOCK; val T = ComponentKind.TEXT; val I = ComponentKind.INLINE; val V = ComponentKind.VOID

            // Layout
            component("box", B, "direction:enum(row,column) $LAYOUT", "flex container; direction row or column (default column)")
            component("row", B, LAYOUT, "horizontal box, items centered")
            component("column", B, LAYOUT, "vertical box")
            component("grid", B, "columns:any minItemWidth:size $LAYOUT", "grid; columns number or {base:1, md:2}; or minItemWidth for auto-fit")
            component("card", B, "title:str subtitle:str icon:icon image:url variant:enum(outlined,filled,elevated,ghost) tone:enum(neutral,accent,success,warning,danger,info) $LAYOUT", "surface with optional title/subtitle")
            component("section", B, "title:str subtitle:str collapsible:bool defaultOpen:bool $LAYOUT", "titled group, optionally collapsible")
            component("collapsible", B, "title:str defaultOpen:bool open:bool onToggle:fn", "disclosure: title row that expands its children")
            component("tabs", B, "value:str defaultValue:str onChange:fn", "tab bar; children are <tab label value>")
            component("tab", B, "label:str value:str icon:icon badge:str", "one tab's content", listed = false)
            component("scroll", B, "gap:space padding:space snap:bool", "horizontal scroller (carousel)")
            component("divider", V, "vertical:bool label:str spacing:space", "separator line")
            component("spacer", V, "size:space grow:bool", "empty space")

            // Text
            component("title", T, TYPE, "heading; size xs..3xl (default lg)")
            component("text", T, TYPE, "paragraph text with inline spans")
            component("caption", T, TYPE, "small muted text")
            component("bold", I, "", "bold span")
            component("italic", I, "", "italic span")
            component("underline", I, "", "underlined span", listed = false)
            component("strike", I, "", "struck-through span", listed = false)
            component("code", I, "", "monospace span")
            component("mark", I, "color:color", "highlighted span", listed = false)
            component("link", I, "href:url onClick:fn", "link span: href opens a page, onClick runs code")
            component("color", I, "value:color", "colored span", listed = false)
            component("markdown", V, "content:str", "renders a Markdown string")
            component("codeblock", V, "code:str language:str title:str", "code block with copy button")
            component("quote", B, "cite:str", "block quotation")
            component("list", B, "ordered:bool marker:str gap:space", "bulleted or numbered list of <item>")
            component("item", B, "icon:icon checked:bool", "list item", listed = false)
            component("badge", T, "tone:enum(neutral,accent,success,warning,danger,info) icon:icon variant:enum(soft,solid,outline)", "small pill label")
            component("kbd", T, "", "keyboard key", listed = false)
            component("icon", V, "name:icon size:size color:color", "Material icon by name: check, close, star, heart, add, remove, search, home, settings, info, warning, error, arrow-right, arrow-left, calendar, clock, place, phone, mail, person, cart, bolt, sun, cloud, rain, snow, music, image, link, download, share, edit, delete, play, pause, trending-up, trending-down, …")
            component("avatar", V, "name:str src:url size:size", "round avatar; initials when no image")

            // Inputs
            component("button", T, "variant:enum(primary,secondary,outline,ghost,danger,link) size:enum(sm,md,lg) icon:icon iconRight:icon block:bool disabled:bool submit:bool onClick:fn href:url tone:enum(neutral,accent,success,warning,danger,info)", "button; onClick runs code, href opens a link, submit sends its form")
            component("slider", V, "min:num max:num step:num $FIELD showValue:bool format:enum(number,currency,percent) unit:str", "range slider")
            component("stepper", V, "min:num max:num step:num $FIELD", "number with − and + buttons")
            component("toggle", V, "checked:bool $FIELD", "on/off switch")
            component("checkbox", V, "checked:bool $FIELD", "checkbox with label")
            component("radiogroup", V, "options:arr direction:enum(row,column) $FIELD", "single choice from options")
            component("select", V, "options:arr $FIELD", "dropdown; options are strings or {label, value}")
            component("segmented", V, "options:arr $FIELD", "segmented control for 2–5 options")
            component("chips", V, "options:arr multiple:bool $FIELD", "selectable chips; value is an array when multiple")
            component("chip", T, "selected:bool onClick:fn icon:icon tone:enum(neutral,accent,success,warning,danger,info)", "a single tappable chip")
            component("input", V, "type:enum(text,number,email,tel,url,search) prefix:str suffix:str min:num max:num $FIELD", "text field")
            component("textarea", V, "rows:num $FIELD", "multi-line text field")
            component("date", V, "min:str max:str $FIELD", "date picker; value is YYYY-MM-DD")
            component("time", V, FIELD, "time picker; value is HH:MM")
            component("rating", V, "max:num readOnly:bool $FIELD", "star rating")
            component("form", B, "onSubmit:fn submitLabel:str $LAYOUT", "collects named fields; onSubmit(values)")

            // Data
            component("table", B, "columns:arr rows:arr striped:bool compact:bool caption:str", "table from columns + rows, or <tr>/<th>/<td> children")
            component("tr", B, "", "table row", listed = false)
            component("th", T, "align:enum(start,center,end)", "header cell", listed = false)
            component("td", T, "align:enum(start,center,end)", "cell", listed = false)
            component("chart", V, "type:enum(bar,line,area,pie,donut,scatter) data:arr x:str y:any series:arr labels:arr values:arr colors:arr stacked:bool height:size format:enum(number,currency,percent,compact) currency:str legend:bool title:str", "chart; data rows + x key + y key(s), or labels + values")
            component("sparkline", V, "data:arr color:color height:size", "tiny inline line chart")
            component("progress", V, "value:num max:num label:str tone:enum(neutral,accent,success,warning,danger,info) showValue:bool", "progress bar")
            component("ring", V, "value:num max:num label:str size:size tone:enum(neutral,accent,success,warning,danger,info)", "circular progress / gauge")
            component("stat", B, "label:str value:any delta:any deltaTone:enum(auto,success,danger,neutral) caption:str icon:icon trend:arr", "key metric with optional delta and trend")
            component("facts", V, "items:arr columns:num", "label/value pairs: items [{label, value}]")
            component("timeline", B, "", "vertical timeline of <event>")
            component("event", B, "time:str title:str icon:icon tone:enum(neutral,accent,success,warning,danger,info)", "timeline entry", listed = false)
            component("steps", B, "current:num", "numbered steps of <step title status>")
            component("step", B, "title:str status:enum(done,current,todo)", "one step", listed = false)
            component("checklist", B, "", "tickable list of <check label>")
            component("check", V, "label:str checked:bool onChange:fn", "one checklist entry", listed = false)
            component("weather", V, "place:str temperature:any condition:str high:any low:any icon:icon days:arr", "weather card; days [{day, high, low, condition}]")
            component("map", V, "query:str lat:num lng:num zoom:num markers:arr height:size", "place or markers [{lat, lng, label}]; opens in Maps")
            component("timer", V, "seconds:num label:str autoStart:bool", "countdown timer that runs on the phone")
            component("countdown", V, "to:str label:str", "live countdown to a date/time")
            component("callout", B, "tone:enum(info,success,warning,danger,neutral) title:str icon:icon", "highlighted note")
            component("empty", V, "title:str description:str icon:icon", "empty state", listed = false)
            component("skeleton", V, "lines:num", "loading placeholder", listed = false)

            // Media and entities
            component("image", V, "src:url alt:str aspectRatio:str width:size height:size maxWidth:size radius:radius fit:enum(cover,contain) caption:str", "image from an https URL")
            component("AsyncImage", V, "query:str alt:str aspectRatio:str maxWidth:size width:size height:size radius:radius caption:str", "image FRIDAY finds by searching for query; describe it, don't link it", server = true)
            component("AsyncImageGroup", V, "queries:arr query:str count:num aspectRatio:str height:size", "carousel of searched images", server = true)
            component("Entity", V, "ref:any type:enum(product,place,person,article,app,item) name:str subtitle:str url:url image:url", "chip for a search result (ref) or named thing; tap for details", server = true)
            component("Cite", V, "ref:any url:url title:str index:num", "source chip for a web result ref", server = true)
            component("product", V, "name:str price:any merchant:str rating:num reviews:num image:url url:url badge:str ref:any", "product card")
            component("place", V, "name:str address:str rating:num reviews:num category:str hours:str phone:str url:url lat:num lng:num ref:any", "place card")
            component("video", V, "url:url title:str thumbnail:url", "video card that opens the video")
            component("AppBlock", ComponentKind.RAW, "title:str icon:icon variant:enum(inline,fullscreen) app_block_id:str height:size", "escape hatch: a self-contained HTML/CSS/JS app")

            val aliases = listOf(
                Alias("h1", "title", mapOf("size" to "2xl")), Alias("h2", "title", mapOf("size" to "xl")), Alias("h3", "title", mapOf("size" to "lg")),
                Alias("h4", "title", mapOf("size" to "md")), Alias("h5", "title", mapOf("size" to "sm")), Alias("h6", "title", mapOf("size" to "xs")),
                Alias("heading", "title"), Alias("p", "text"), Alias("span", "text"), Alias("label", "text"), Alias("small", "caption"),
                Alias("b", "bold"), Alias("strong", "bold"), Alias("i", "italic"), Alias("em", "italic"), Alias("u", "underline"),
                Alias("s", "strike"), Alias("del", "strike"), Alias("a", "link"), Alias("img", "image"), Alias("hr", "divider"),
                Alias("ul", "list"), Alias("ol", "list", mapOf("ordered" to true)), Alias("li", "item"), Alias("blockquote", "quote"),
                Alias("pre", "codeblock"), Alias("div", "box"), Alias("hstack", "row"), Alias("vstack", "column"), Alias("stack", "column"),
                Alias("col", "column"), Alias("flex", "box"), Alias("details", "collapsible"), Alias("switch", "toggle"),
                Alias("metric", "stat"), Alias("kv", "facts"), Alias("alert", "callout"), Alias("note", "callout"), Alias("pill", "badge"),
                Alias("tag", "badge"), Alias("carousel", "scroll"), Alias("textfield", "input"), Alias("dropdown", "select"),
                Alias("radio", "radiogroup"), Alias("gauge", "ring"), Alias("graph", "chart"), Alias("thead", "box"), Alias("tbody", "box"),
                Alias("checkitem", "check"), Alias("todo", "check"), Alias("Image", "image"), Alias("Card", "card"), Alias("Button", "button"),
                Alias("Text", "text"), Alias("Title", "title"), Alias("Box", "box"), Alias("Row", "row"), Alias("Column", "column"),
                Alias("Chart", "chart"), Alias("Table", "table"), Alias("Slider", "slider"), Alias("Badge", "badge"), Alias("Icon", "icon"),
                Alias("Stat", "stat"), Alias("Grid", "grid"), Alias("Divider", "divider"), Alias("Callout", "callout"),
            ).associateBy { it.name }
            return Catalog(specs, aliases)
        }
    }
}
