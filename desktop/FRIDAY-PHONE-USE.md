# FRIDAY phone use

FRIDAY operates apps on the phone the way Codex's Computer Use plugin operates apps on a Mac: it works
inside one app at a time, reads the app as an indexed accessibility tree plus a screenshot, acts on
elements by index, and gets the app's fresh state back after every action.

This replaces the earlier `read_phone_screen` / `control_phone_screen` / `capture_phone_screen` tools,
which made the model read the screen, act on raw coordinates, and re-read separately.

## Tools

The tool surface matches Codex Computer Use. `app` is an app name from `list_apps` or a package id.

| Tool | What it does on Android |
| --- | --- |
| `list_apps` | Launchable apps, recently used first (from the accessibility service's window events). |
| `get_app_state(app)` | Opens or brings the app to the front, waits for the UI to settle, and returns its tree and a screenshot. Required once per turn before acting on the app. |
| `click(app, element_index \| x,y, click_count, mouse_button)` | `ACTION_CLICK` on the element (or its nearest clickable ancestor), else a tap gesture. `click_count` 2 double-taps; `mouse_button` `right` long-presses. |
| `perform_secondary_action(app, element_index, action)` | Any non-primary accessibility action the tree lists for the element: Long press, Expand, Collapse, Dismiss, Copy, Paste, or the app's own custom actions such as "Archive". |
| `scroll(app, element_index?, direction, pages)` | Directional scroll actions (`ACTION_SCROLL_DOWN`…, else forward/backward) for whole pages; a swipe gesture for fractions or views without scroll actions. |
| `drag(app, from_x, from_y, to_x, to_y)` | Press, hold, then move: a continued gesture stroke, so lists start a drag rather than a fling. |
| `type_text(app, text)` | On Android 13+, the accessibility input method commits the text like a keyboard. Otherwise the text is inserted at the cursor of the focused field. Password fields are refused: the user types them. |
| `press_key(app, key)` | xdotool names (`Return`, `BackSpace`, `Tab`, `Escape`, `ctrl+a/c/x/v`, arrows, `Page_Down`…) plus phone keys (`Back`, `Home`, `Recents`, `Notifications`, `Quick_Settings`). |
| `set_value(app, element_index, value)` | Replace a field's text, set a slider's progress, or switch a checkbox/switch on or off. |
| `phone_script(title, code)` | Code mode: JavaScript that calls the tools above (see below). |

Every action returns a one-line outcome ("Clicked button “Compose”.") followed by the app's new state.

### Code mode

Codex's newer computer use runs model-written JavaScript in a REPL (`cua_repl` → `js {title, code}`).
FRIDAY has the same mode as `phone_script {title, code}`, running in the sandboxed interpreter built for
Intelligent UI (see FRIDAY-INTELLIGENT-UI.md): no network, files or timers. The script drives the phone
through `phone` (alias `cua`):

```js
const s = await phone.getState('Gmail')          // {app, window, text, elements, find(label)}
await phone.click('Gmail', s.find('Compose'))      // index, {x, y}, or a label
await phone.type('Gmail', 'invoices'); await phone.key('Gmail', 'Return')
for (const i of [1, 2]) await phone.scroll('Gmail', 'down', {pages: 0.5})
```

Each call goes through the same tool, so per-app access, the once-per-turn rule and the review of
consequential taps all apply. A failed action throws an Error the script can catch. The result carries
console output, the last value and the final screen with its screenshot. The title is what the chat shows.

## App state

```
App: Gmail (com.google.android.gm)
Window: Inbox
Coordinates: 576x1280 screenshot pixels, origin top-left (Android display 1080x2400)
Screenshot: attached after this result, in the same coordinates
Keyboard: showing (Gboard)

0 window Inbox [0,0,576,1280]
	1 text field Search in mail (settable) [40,60,536,110]
	2 button Compose [400,1150,560,1230]
	3 list (scrollable) [0,120,576,1140]
		4 button Alice, Lunch tomorrow?, 10:42 AM, Secondary Actions: Archive, Long press [0,120,576,220]
```

- Each line: index, role, label, `Value:`, states, `Secondary Actions:`, bounds. Tab indentation shows nesting.
  Empty wrapper layouts are skipped, and a tappable row whose children are only text reads as one element.
- Coordinates are always screenshot pixels: the display scaled onto the vision encoder's 32 px grid
  (`ScreenContextService.visionFrameSize`), whether or not an image is attached, so the tree and the
  image agree and taps map back exactly.
- Screenshots come from `AccessibilityService.takeScreenshot` (Android 11+), or from an active screen share
  on older phones. The banner and touch marks are hidden for the capture. Apps that block screenshots are
  reported as such.
- Indices are valid for one state only. FRIDAY keeps the nodes of the latest state and refreshes them before
  acting; a vanished node asks for `get_app_state` again.

## Access and safety

- **Per-app access**, as Codex asks before using an app: *Deny*, *Allow in this chat*, or *Always allow*
  (saved). Settings > Phone use can switch the default to allow all apps, and lists saved rules
  (Allow / Block / Forget).
- **Never operated:** FRIDAY itself, package installers and permission controllers. These are hand-offs.
- **Leaving the allowed app:** if an action opens an app FRIDAY may not use, its screen is withheld (the
  screenshot is deleted) and the model is told to ask with `get_app_state`. The keyboard, notification shade
  and system dialogs stay within the current session.
- **Action review:** taps whose element label starts with a consequential verb (Send, Pay, Buy, Order,
  Delete, Submit, Confirm, Install, Sign out…) get an approval card first, like Codex's review of risky
  computer actions. The element's own words decide, never the model's description. Can be turned off.
- **Confirmation policy:** the system prompt carries a condensed version of Codex's Computer Use
  confirmation policy: hand-offs (credentials, financial transactions, security warnings), confirm at action
  time (permanent deletion, binding agreements, new permissions, CAPTCHAs), pre-approval rules, and what
  needs no confirmation. Screen content is untrusted data and never grants permission.
- **Banner:** "FRIDAY is using Gmail · Stop" shows over other apps while FRIDAY works (an accessibility
  overlay, no extra permission). Stop cancels the turn. A short ring marks each touch.
- **Turn end:** like Codex's `turn_ended` hook, the session's screen references and the banner are released
  when a turn finishes, fails or is stopped, and the next turn must look at the app again.

## Conversation engine

- One screen action per model response; extra actions in the same response are refused with a note to read
  the returned state.
- Rounds made only of phone-use tools draw on their own budget of 60 (on top of the usual 20), since phone
  tasks take many small steps.
- Chat Completions tool messages are text-only, so the latest screenshot travels as a short user message
  right after the round's results. It is sent once; the stored result keeps its path for the transcript.
- Older states are shortened to their outcome line, app and window in batches of four, so at most four full
  trees are in context and the server's prompt cache stays valid between batches.
- The transcript groups a run of phone-use steps into one card: "Using Gmail · 3 actions" while running, then
  "Used Gmail · 6 actions · 1 failed" with failures and screenshots previewed first, every step a tap away,
  and the latest screenshot as a thumbnail.

## Requirements

FRIDAY's accessibility service must be on (Android Settings > Accessibility > FRIDAY phone control). Its
configuration now asks for screenshots (`canTakeScreenshot`) and the accessibility input method
(`flagInputMethodEditor`); Android may ask the user to turn the service off and on again after updating.
