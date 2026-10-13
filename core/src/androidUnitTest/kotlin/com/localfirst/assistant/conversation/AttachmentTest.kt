package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.model.messagesToOpenAi
import com.localfirst.assistant.tools.ToolDefinition
import com.localfirst.assistant.tools.ToolRegistry
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTest {
    private val image = Attachment("i1", AttachmentKind.IMAGE, "photo.jpg", "image/jpeg", path = "/photos/i1.jpg")
    private val doc = Attachment(
        "d1", AttachmentKind.DOCUMENT, "report.pdf", "application/pdf",
        text = "Revenue grew 12%.", note = "Text cut at 60,000 characters.",
    )
    private val scan = Attachment(
        "s1", AttachmentKind.DOCUMENT, "scan.pdf", "application/pdf",
        pageImages = listOf("/pages/p1.jpg", "/gone/p2.jpg"), note = "No text layer; sent 2 page image(s).",
    )

    @Test
    fun attachmentsBecomeMultiPartContent() {
        val reads = mapOf("/photos/i1.jpg" to byteArrayOf(1, 2, 3), "/pages/p1.jpg" to byteArrayOf(9))
        val json = messagesToOpenAi(listOf(Message.User("What's in these?", listOf(image, doc, scan)))) { reads[it] }
        val content = json.single().jsonObject["content"]!!.jsonArray
        val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("What's in these?"))
        assertTrue(text.contains("<file name=\"report.pdf\">\nRevenue grew 12%.\n[Text cut at 60,000 characters.]\n</file>"))
        assertTrue(text.contains("<file name=\"scan.pdf\">\n[No text layer; sent 2 page image(s).]\n</file>"))
        val types = content.map { (it as JsonObject)["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("text", "image_url", "text", "image_url", "text"), types)
        val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), url)
        assertEquals("[Pages of scan.pdf]", content[2].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("[Image no longer available: scan.pdf]", content[4].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun plainMessagesStayPlainStrings() {
        val json = messagesToOpenAi(listOf(Message.User("Hi")))
        assertTrue(json.single().jsonObject["content"] is JsonPrimitive)
    }

    @Test
    fun sessionsAcceptAttachmentOnlyMessagesAndEditsKeepAttachments() = runBlocking {
        val sent = mutableListOf<List<Message>>()
        val provider = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse {
                sent += messages
                return ModelResponse.TextResponse("It's a photo.")
            }
        }
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "")
        session.latestUserNote = "[Sent at 9:00 AM]"
        assertTrue(session.submitUserMessage("", attachments = listOf(image)) is TurnOutcome.Completed)
        // The time note is added to the text but the attachments still go along.
        assertEquals(Message.User("[Sent at 9:00 AM]", listOf(image)), sent[0][0])
        assertEquals(Message.User("", listOf(image)), session.snapshot()[0])

        session.editUserMessage(0, "Describe it")
        assertEquals(Message.User("Describe it", listOf(image)), session.snapshot()[0])
        assertTrue(session.submitUserMessage("  ") is TurnOutcome.EmptyInput)
    }

    @Test
    fun followUpsAndRegenerationRetainTheOriginalImage() = runBlocking {
        val sent = mutableListOf<List<Message>>()
        val provider = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse {
                sent += messages
                return ModelResponse.TextResponse("Answer ${sent.size}")
            }
        }
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "")
        session.submitUserMessage("Describe it", attachments = listOf(image))
        session.submitUserMessage("What color is it?")
        session.regenerate()
        assertEquals(3, sent.size)
        sent.forEach { assertEquals(listOf(image), (it.first() as Message.User).attachments) }
        assertEquals(2, session.snapshot().filterIsInstance<Message.User>().size)
        assertEquals("Answer 3", (session.snapshot().last() as Message.Assistant).content)
    }

    @Test
    fun attachmentsAreSavedWithTheChat() {
        val dir: File = Files.createTempDirectory("chats").toFile()
        val store = FileConversationStore(dir)
        val messages = listOf(Message.User("Look", listOf(image, doc, scan)), Message.Assistant("Done."))
        store.save(StoredConversation(ConversationSummary("a", "t", 0, 1), messages))
        assertEquals(messages, store.load("a")!!.messages)
    }

    @Test
    fun titlesFallBackToTheAttachment() {
        assertEquals("Image", ConversationTitles.fromFirstMessage(Message.User("", listOf(image))))
        assertEquals("report.pdf", ConversationTitles.fromFirstMessage(Message.User(" ", listOf(doc))))
        assertEquals("Summarize this", ConversationTitles.fromFirstMessage(Message.User("Summarize this", listOf(doc))))
    }
}
