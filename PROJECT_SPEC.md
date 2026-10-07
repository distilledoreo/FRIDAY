PROJECT SPEC — LOCAL-FIRST ANDROID AI ASSISTANT
Version: MVP / Phase 1

## 1. Goal

Build an Android application that acts as a conversational AI assistant backed by an LLM running on the user's personal computer.

The Android app is responsible for:
- Conversation state
- Prompt/orchestration logic
- Tool definitions and execution
- User interface
- Eventually speech input/output

The PC should initially remain simple:
- Host an LLM
- Expose it through an API
- Accept chat requests
- Return normal responses or structured tool calls

The architecture should be extensible, but DO NOT implement future features merely because they are mentioned in this specification.


## 2. Core Design Principle

Treat the system as:

ANDROID APP = assistant/orchestrator
PC = model provider

The Android client should not depend on one specific model, inference engine, or API implementation.

Create a clean ModelProvider abstraction so the backend could later be:
- A local PC
- A different local model
- A cloud model
- A routing system

Do not build model routing yet.


## 3. Phase 1 MVP

The first working version should be deliberately small.

Required features:

A. Text conversation
- Simple chat interface
- Persistent multi-turn conversation
- User messages and assistant responses
- Conversation context preserved across turns
- No need to create a new thread when a tool is used

B. Remote LLM connection
- Connect Android app to a configurable HTTP API
- Send conversation context
- Receive assistant responses
- Support structured tool-call responses
- Configurable server address
- Sensible timeout/error handling

C. Tool system
Implement a generic Tool Registry.

The model should receive schemas describing the tools available to it.

Flow:

User request
→ model
→ model optionally requests tool
→ Android executes tool
→ tool result is added to the conversation
→ model continues its response
→ user sees one seamless conversational interaction

Tool calls and tool results must be represented explicitly in conversation state rather than pretending to be user messages.

D. One initial Android tool

Implement ONLY ONE simple tool initially:

set_media_volume(level)

The purpose is to prove that:
- The model understands tools
- Android can execute a tool
- The tool result returns to the model
- Conversation continues normally afterward

Do not add a large tool library yet.

E. Web search grounding

`web_search` is a second registered tool on the same registry. The phone owns the tool call. A separate PC HTTP service runs the search. That service is not the LLM endpoint. See section 6b.


## 4. Conversation Architecture

Create a conversation/session layer independent from the UI.

Conceptually:

ConversationSession
    messages
    modelProvider
    toolRegistry
    systemPrompt

Messages should support roles/types such as:

USER
ASSISTANT
TOOL_CALL
TOOL_RESULT
SYSTEM

The conversation engine should handle repeated cycles such as:

User:
"Turn the volume down a little."

Assistant requests:
set_media_volume(40)

Android executes it.

Tool result:
success

Assistant:
"Done."

The next user message should continue naturally within the same conversation.


## 5. Tool Architecture

Tools should share a common interface.

Conceptually:

Tool
    name
    description
    inputSchema
    execute(arguments)

ToolRegistry
    register(tool)
    getAvailableTools()
    execute(toolCall)

The LLM must never directly control arbitrary Android functionality.

The Android application decides which capabilities are exposed as tools.

This boundary is important for security and future permission management.


## 6. Model Provider

Create a generic interface such as:

ModelProvider
    sendConversation(messages, tools)
    → ModelResponse

ModelResponse should support:

TextResponse
ToolCallResponse

Do not couple the rest of the application to llama.cpp, Ollama, OpenAI-compatible APIs, or any particular model.

An OpenAI-compatible local endpoint is acceptable for the first implementation if convenient.


## 6b. Web grounding

Web search is provider-agnostic and self-hosted. It is not part of ModelProvider.

Flow:

User asks for something on the web
→ conversation engine sends tools, including web_search, to the model
→ model returns a tool call
→ Android executes web_search
→ Android POSTs to the PC search service (its own URL, not the LLM URL)
→ that service calls SearchProvider.search
→ optional PageFetcher, only when the tool call sets fetch_pages
→ tool result (titles, URLs, snippets, and page text when fetched) goes back into the conversation
→ the model answers in the same turn

Desktop contracts:

SearchProvider
    search(query, options) → SearchResults
    each hit: title, url, snippet, optional score

PageFetcher
    fetch(url) → page text for citations

The default SearchProvider is SearXNG. The default PageFetcher is Crawl4AI, and it runs only when a call opts in. Search snippets do not require a crawl.

The LLM client must not import SearXNG or Crawl4AI. The search service must not call the model. Local models, cloud model APIs, and search backends swap independently.

Do not implement paid hosted search (Bing, Google, SerpAPI, Tavily, or similar) in this service. A later SearchProvider can be added beside SearXNG without changing the phone's tool or the model client.

Voice, memory, model routing, and other PC tools stay out of scope.


## 7. PC-Side Requirements

Keep the PC-side implementation minimal.

The model server only needs to provide an LLM endpoint. Web search, when used, is a second process with no conversation logic (section 6b).

Do NOT build:
- Assistant or conversation logic on the PC
- Tool registration on the PC (the phone owns the tool list)
- Speech processing
- Image generation
- Video generation
- Automatic model swapping
- Agent infrastructure
- Long-term memory

Those belong to later phases.

The PC should initially be replaceable with any compatible model server.


## 8. Android UI

Keep Phase 1 UI extremely simple.

Main screen:

--------------------------------
Assistant
--------------------------------

conversation transcript

User: ...
Assistant: ...

--------------------------------
[text input]        [Send]
--------------------------------

Optional:
- Connection status indicator
- Clear/new conversation button
- Settings page for server address

Do not spend significant development time on visual polish.


## 9. Reliability

The application should handle:

- PC unreachable
- Connection timeout
- Model server errors
- Invalid tool calls
- Unknown tool names
- Invalid tool arguments
- Tool execution failure
- Android permission failure

These should fail gracefully without destroying the conversation.


## 10. Architecture to Preserve for Future Development

The following capabilities are PLANNED but MUST NOT be implemented during Phase 1.

The architecture should simply avoid making them difficult to add later.


### Voice Assistant

Future flow:

Push button
→ Android speech-to-text
→ conversation
→ model/tool execution
→ Android text-to-speech

Speech recognition and TTS should normally run on the phone.

No wake word is currently required.


### Android Assistant Integration

Potential future capabilities include:

- Launch apps
- Open Maps/navigation
- Change media volume
- Control media playback
- Place calls
- Send messages
- Read or respond to notifications
- Set alarms/timers
- Open URLs
- Share content
- Use Android intents
- Interact with supported apps

These should eventually use the same Tool Registry architecture.


### Bluetooth / Smart Glasses

Future smart glasses should be treated primarily as a Bluetooth audio/input accessory.

Possible interaction:

Glasses button
→ Android assistant activates
→ glasses microphone
→ Android STT
→ assistant
→ TTS response through glasses

The assistant must NOT depend on a particular glasses manufacturer.


### PC Tools

The same tool architecture may later expose remote PC capabilities such as:

- Image generation
- Video generation
- Image editing
- File processing
- Specialized AI models

Web search grounding is specified in section 6b. It is the only PC capability implemented here.

Example future call:

generate_image(prompt)

Android
→ PC service
→ load appropriate model if necessary
→ generate
→ return result

Do not implement image, video, file, or specialized-model tools now.


### Dynamic Model Management

Eventually the PC may dynamically:

- Load/unload models
- Swap models in VRAM
- Route tasks to different models
- Manage the P100's available VRAM
- Start image/video pipelines when requested

The Android application's conversation architecture must not depend on which model is currently loaded.

Do not implement model management now.


### Long-Term Memory

Future versions may have persistent assistant memory.

Potential architecture:

Conversation context
+
Long-term memory store
+
retrieval

Do not implement memory or vector databases in Phase 1.


### Cloud Fallback

A future ModelProvider could use services such as ChatGPT, Claude, Gemini, or another API when desired.

The application architecture should permit this without rewriting the conversation system.

Do not implement cloud providers now.


## 11. Security Philosophy

The AI should never receive unrestricted phone control.

All actions must occur through explicitly registered tools.

Future sensitive operations such as:

send_message
place_call
delete_file

may require confirmation before execution.

Design the tool system so confirmation requirements can eventually be attached to individual tools.

Do not build the full permission system yet.


## 12. Development Milestones

MILESTONE 1
Android text chat → PC model → response.

No tools.
No voice.

MILESTONE 2
Add Tool Registry and set_media_volume.

Verify this complete interaction:

User:
"Set the volume to 30%."

Model:
tool call → set_media_volume(30)

Android:
executes action

Model:
receives result and responds

Conversation remains active.

MILESTONE 3
Only after Milestones 1–2 are stable:

Add push-to-talk voice:
- Android STT
- Android TTS

MILESTONE 4
Add a very small set of genuinely useful Android tools.

Possible first additions:
open_app
open_map
media_play_pause

Do not proceed into later capabilities merely because they are documented above.


## 13. Definition of Success for Phase 1

Phase 1 is successful when I can:

1. Open the Android app.
2. Have a multi-turn text conversation with my local PC model.
3. Say something equivalent to:
   "Turn the volume down."
4. Have the model select the Android volume tool.
5. Have Android perform the action.
6. Continue the same conversation immediately afterward.

Nothing beyond this is required for the initial implementation.


## 14. Important Instruction to Coding Agent

Favor clean interfaces and separation of concerns over feature quantity.

Do not prematurely implement the future features described in this document.

They exist only so that architectural decisions made today do not unnecessarily prevent them later.

Build Milestones 1 and 2 first.
Stop once they work reliably and report the implementation status before expanding scope.
