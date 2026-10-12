# FRIDAY Intelligent UI

FRIDAY can answer with live interfaces (sliders, forms, charts, tables, cards, small apps) drawn with
its own native components, the way ChatGPT's Intelligent UI does. The design follows the published
teardown of ChatGPT's implementation (Thesys, "How ChatGPT Intelligent UI works", October 2026) and
fixes the rough edges it lists.

## Pipeline

| Layer | ChatGPT | FRIDAY |
| --- | --- | --- |
| Inference format | DIL: Markdown + JSX-like tags + JavaScript (`{@body}`, `{expr}`, `{#if}`, `{#each}`, handlers, `GenUI.*`) | The same language, so the model writes notation it knows. Also accepts JSX inside expressions (`.map(i => <chip/>)`), `{@const}`, HTML names (`h2`, `ul`, `hr`, `div`…) and function components defined in `{@body}`. |
| Compilation | Server recompiles the whole partial response every few hundred ms | `core/genui/UiCompiler` recompiles each streamed update on the phone (milliseconds), coalesced to every 120 ms. |
| Streaming repair | Cut at the last complete construct; close open elements with content, drop empty ones; `recoveryDiagnostics` | Same rules (`unterminated_tag`, `unclosed_element`, `unclosed_block`, `unterminated_braced_value`). Finished replies never truncate on a stray `{` or `<`: prose like "a < b", "List<String>" and "{x}" stays text. |
| Stable state keys | `useState(8, {key: "seats"})` | The compiler adds the variable name as the key, so state survives every recompile. |
| Validation | Unknown props and wrong literals removed, recorded | Every prop (literal or computed) is checked against the catalog at render; numeric strings for numbers/tokens are coerced instead of dropped; the rest is removed and recorded. |
| Runtime | Sandboxed iframe + Web Worker; lockdown, `new Function`, watchdog, keyed hooks, React-style reconciler, operation diffs | `core/genui/script`: a JavaScript-subset interpreter written in Kotlin. There is nothing to lock down: no network, timers, messaging, eval or host objects exist unless the runtime installs them. Step, wall-time, call-depth and allocation budgets act as the watchdog; three budget failures quarantine the reply and keep the last good tree. It runs on its own engine thread with a 16 MB stack. Compose does the reconciling. |
| Error isolation | `__dilSafe`: a throwing expression removes one element | Same, per expression and per `{@body}` statement. Failures are also listed under the reply ("Part of this answer couldn't be shown · Details") rather than dropped silently. |
| Rendering | Operations applied to ChatGPT's own components; animated | `app/ui/genui`: a registry of native Compose renderers, one per catalog component. Text fades in over 0.7 s, new components slide in over 0.42 s, charts draw over 1.8 s, containers animate their size. |
| Catalog | ~70 native components, design tokens, prebuilt composites | ~75 components (layout, text, inputs, data, media, entities), spacing/radius/size/color tokens, aliases. The model's prompt section is generated from the catalog, so docs and renderer never drift. |
| Server data | `AsyncImage` searched by the server; `Entity`, `Cite`; tool results bound by id | `AsyncImage`/`AsyncImageGroup` search through the PC (`GET /images`) and load through the PC (`GET /image`), so the phone never contacts image hosts. Web results carry a `ref` (`web_3f9a2c`); `{web_3f9a2c.title}`, `<Cite ref>` and `<Entity ref>` show values from the result itself. |
| Actions | `GenUI.issueNewTurn`, `copy`, `openUrl`, `openEntityDetail` | The same, plus `share` and `toast`. Actions run only from user interactions; a reply can't send messages while it renders. Forms without `onSubmit` send their values as the reply. |
| AppBlock | HTML/CSS/JS in a cross-origin iframe, theme variables, auto-height, no callbacks | A WebView with `default-src 'none'` CSP, every network request intercepted and blocked, no file/content access, theme variables (`--viz-*`), auto-height and a full-screen view. It may call `friday.sendMessage()` (the user confirms each one), `friday.copy()` and `friday.state.get/set()` (saved with the chat). |
| Fallback | `fallbackMarkdown` without computed values or `{#each}` content | Made from the rendered tree, so it includes computed values and loop content. Used for copy, voice, Android Auto, notifications and search. |

## Fixes to the rough edges the teardown found

- **State resets on reload** → each reply's state is saved per conversation (`files/genui-state/`).
- **The model doesn't see interface state** → the next turn carries a note: "The user changed your last
  interface: seats = 9".
- **No feedback on invalid properties** → the same note lists the reply's problems so the model avoids them.
- **Fallback omits computed content** → fixed (see above).
- **Fixed layouts** → `grid columns={{base: 1, md: 2}}` breakpoints, `minItemWidth` auto-fit, and rows that
  give text and cards flexible width.
- **AppBlock apps are isolated and lose state** → `friday.state` persists; `friday.sendMessage` is allowed
  with confirmation.
- **Logic errors are silent** → reported under the reply and in the model note.

## Extending

A new component is one `component(...)` line in `core/genui/Catalog.kt` (props, summary) and one
`GenUiRegistry.register("name") { e -> … }` renderer in `app/ui/genui`. Composite components can also be
written in the language itself: any `{@body function Name(props) { return <…/> }}` becomes `<Name/>`.

## Settings

Appearance > Answers > Interactive answers (on by default). Off restores plain Markdown answers and the
earlier ```` ```friday ```` JSON components, which still render in old chats either way.
