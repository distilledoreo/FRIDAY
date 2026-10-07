# PC memory and continuity

Authenticated `/workspace/memory` API stores SQLite, original imported conversation text, approved facts, candidate facts, recent situations and a CPU semantic index on the PC. Android streams an export from the file picker without saving an extra phone copy. It requests small pages and a bounded context before typed or spoken turns. Conversation threads remain independent.

Deploy this package beside `workspace` and `imagegen` in the existing API directory. Install `numpy`, `onnxruntime`, and `tokenizers` in its Python environment. Run `python -m memory.install_encoder /path/to/workspace-data/memory` to download the pinned MiniLM revision and verify the model against its upstream LFS checksum. Startup verifies the recorded file hashes. The encoder uses CPU execution only (two threads), never GPU memory. Add after the image/workspace hooks:

```python
from memory.integration import enable as enable_memory
memory_store = enable_memory(app, auth,
    WorkspacePath(__file__).resolve().parent.parent / 'workspace-data' / 'memory',
    llm, gpu_gate)
```

The existing authenticated API and GPU gate are reused. Background fact/situation extraction pauses while chat or image generation holds the GPU gate. CPU indexing continues independently. No export content is sent to a cloud service.

Import ZIP or conversations.json: upload cap 256 MB, expanded JSON cap 512 MB, 20,000 conversations, 8 MB per conversation. Only visible user/assistant text from the selected branch is accepted; media, tools, system text and analysis messages are omitted. Preview precedes commit. Duplicate IDs are skipped, never overwritten. Review at most eight fact candidates per chat, each with an exact user quote. Extraction examines at most 12,000 user characters per chat. Approved facts are limited to 5,000 and remain editable.

Archive text is retained fully; semantic indexing selects at most fifty excerpts per chat and scans at most 100,000 indexed excerpts per query. Full-text search supplements semantic recall. Each turn supplies at most four historical chats plus relevant approved facts and at most three recent situations, within 14,000 characters. Source previews show only the last twenty messages with 2,000 characters each. Continuing an imported source creates a phone thread from that bounded preview.

Recent native chats can propose up to four machine-summarized situations automatically. These are explicitly labeled tentative, separately displayed, source-linked, scoped and expiring. Old imported chats do not become current events. A newer report can resolve/update an existing situation with evidence; repeated quotes do not refresh it. Optional check-in guidance uses a three-day offer cooldown and only recent unresolved relevant subjects or greeting-only turns. An offer counts when supplied even if no question is actually asked. There are no unsolicited push messages. See PLAN.md for policy and validation.

Controls independently disable approved-memory use, historical recall, new chat archiving, new fact suggestions, situation tracking and check-ins. Forgetting a source-linked fact or situation excludes its source from recall to avoid immediate recreation. Deleting an archive source removes its text/index/suggestions/situations; already approved facts remain separately manageable. Excluding a source suppresses retrieval and extraction. SQLite deletion is logical deletion, not forensic erasure.

Phone backup covers local chats/projects, not this database. Back up the PC memory directory with the service stopped (or use SQLite's backup API); retain the model manifest if copying the encoder. No downloaded models or user data belong in Git.

Tests: `python -m unittest desktop.memory.tests.test_memory desktop.memory.tests.test_api desktop.memory.tests.test_continuity` from the repository root. Synthetic exports and continuity fixtures are tested; a real user export and phone layout still require device validation. Biological resemblance and parity with ChatGPT are not asserted.
