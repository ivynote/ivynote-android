package app.ivynote.core

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Answers from a table of `"METHOD path" -> (status, json)` and records what was sent. */
class StubTransport(private val routes: MutableMap<String, Pair<Int, String>> = mutableMapOf()) : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    var offline = false

    override suspend fun send(request: HttpRequest): HttpResponse {
        sent += request
        if (offline) throw IOException("offline")
        val path = request.url.substringAfter("://").substringAfter('/').substringBefore('?')
        val (status, body) = routes["${request.method} /$path"] ?: (404 to """{"error":"not_found","status":404}""")
        return HttpResponse(status, body.toByteArray())
    }
}

const val USER_JSON = """{"id":"u1234567","username":"ivy","email":"ivy@example.com","displayName":"Ivy","role":"admin","status":"active","createdAt":1,"updatedAt":1,"lastLoginAt":1,"lastSeenAt":1,"aiEnabled":true,"aiAvailable":true,"features":{}}"""

class ApiClientTest {
    private val server = ServerAddress.parse("https://notes.example.com")

    @Test fun probeChecksHealthThenInfo() = runTest {
        val stub = StubTransport(mutableMapOf(
            "GET /api/v1/health" to (200 to """{"ok":true,"version":"1.0"}"""),
            "GET /api/v1/info" to (200 to """{"siteName":"Ivy","allowRegistration":false,"hasUsers":false,"version":"1.0","maxUploadMB":100,"storageMode":"local","cdn":false}"""),
        ))
        val info = ApiClient(server, transport = stub).probe()
        assertTrue(info.canRegister)
        assertEquals(100, info.maxUploadMB)
    }

    @Test fun loginSendsJsonAndReadsTheToken() = runTest {
        val stub = StubTransport(mutableMapOf("POST /api/v1/auth/login" to (200 to """{"token":"tok","user":$USER_JSON}""")))
        val (token, user) = ApiClient(server, transport = stub).login("ivy", "secret123")
        assertEquals("tok", token)
        assertTrue(user.isAdmin)
        val req = stub.sent.single()
        assertEquals("https://notes.example.com/api/v1/auth/login", req.url)
        assertEquals("""{"identity":"ivy","password":"secret123"}""", req.body!!.decodeToString())
        assertEquals("application/json", req.headers["Content-Type"])
    }

    @Test fun errorsCarryTheServerMessage() = runTest {
        val stub = StubTransport(mutableMapOf("GET /api/v1/me" to (401 to """{"error":"unauthorized","status":401}""")))
        val e = assertFailsWith<ApiError.Server> { ApiClient(server, "bad", stub).me() }
        assertEquals(401, e.status)
        assertTrue(e.endsSession)
        assertEquals("unauthorized", e.message)
        assertEquals("Bearer bad", stub.sent.single().headers["Authorization"])
    }

    @Test fun networkFailuresAreTransient() = runTest {
        val stub = StubTransport().apply { offline = true }
        val e = assertFailsWith<ApiError.Unreachable> { ApiClient(server, transport = stub).info() }
        assertTrue(e.isTransient)
        assertFalse(e.endsSession)
    }

    @Test fun notAnIvynoteServer() = runTest {
        val stub = StubTransport(mutableMapOf("GET /api/v1/info" to (200 to "<html>")))
        assertIs<ApiError.InvalidResponse>(runCatching { ApiClient(server, transport = stub).info() }.exceptionOrNull())
    }
}

class SessionStoreTest {
    private val server = ServerAddress.parse("https://notes.example.com")
    private val account = StoredAccount(server, "tok", User("u1234567", "old"))

    @Test fun restoreRefreshesTheUser() = runTest {
        val stub = StubTransport(mutableMapOf("GET /api/v1/me" to (200 to USER_JSON)))
        val creds = InMemoryCredentialStore(account)
        val s = SessionStore(creds, MemoryKeyValueStore(), stub)
        s.restore()
        assertEquals("ivy", s.account?.user?.username)
        assertEquals("ivy", creds.load()?.user?.username)
    }

    @Test fun offlineKeepsTheSession() = runTest {
        val stub = StubTransport().apply { offline = true }
        val s = SessionStore(InMemoryCredentialStore(account), MemoryKeyValueStore(), stub)
        s.restore()
        assertIs<SessionStore.State.SignedIn>(s.state.value)
        assertTrue(s.isOffline.value)
    }

    @Test fun unauthorizedEndsTheSession() = runTest {
        val stub = StubTransport(mutableMapOf("GET /api/v1/me" to (401 to """{"error":"unauthorized"}""")))
        val creds = InMemoryCredentialStore(account)
        val s = SessionStore(creds, MemoryKeyValueStore(), stub)
        s.restore()
        assertIs<SessionStore.State.SignedOut>(s.state.value)
        assertEquals(null, creds.load())
        assertEquals("登录已过期，请重新登录", s.notice.value)
    }

    @Test fun signOutRevokesAndForgets() = runTest {
        val stub = StubTransport(mutableMapOf("POST /api/v1/auth/logout" to (200 to """{"ok":true}""")))
        val creds = InMemoryCredentialStore(account)
        val s = SessionStore(creds, MemoryKeyValueStore(), stub)
        s.signIn(server, "tok", User("u1", "ivy"))
        s.signOut()
        assertEquals("POST", stub.sent.single().method)
        assertEquals(null, creds.load())
        assertIs<SessionStore.State.SignedOut>(s.state.value)
    }
}

class NoteApiTest {
    private val server = ServerAddress.parse("https://notes.example.com")

    @Test fun shareIsNullUntilMadeAndPutSendsBothFields() = runTest {
        val stub = StubTransport(mutableMapOf(
            "GET /api/v1/notes/n1234567/share" to (200 to "null"),
            "PUT /api/v1/notes/n1234567/share" to (200 to """{"id":"s1234567","noteId":"n1234567","enabled":true,"hasPassword":true,"password":"abcdef","expiresAt":0,"views":2}"""),
        ))
        val api = ApiClient(server, "tok", stub)
        assertEquals(null, api.share("n1234567"))
        val s = api.setShare("n1234567", enabled = true, expiresAt = 0, password = "abcdef")
        assertTrue(s.live)
        assertEquals("""{"enabled":true,"expiresAt":0,"password":"abcdef"}""", stub.sent.last().body!!.decodeToString())
        assertEquals("https://notes.example.com/s/s1234567", api.shareLink(s.id))
        api.setShare("n1234567", enabled = false, expiresAt = 5)
        assertEquals("""{"enabled":false,"expiresAt":5}""", stub.sent.last().body!!.decodeToString())
    }

    @Test fun historyAndExport() = runTest {
        val stub = StubTransport(mutableMapOf(
            "GET /api/v1/notes/n1234567/history" to (200 to """[{"rev":3,"title":"T","device":"web","size":10,"createdAt":5}]"""),
            "GET /api/v1/notes/n1234567/history/3" to (200 to """{"rev":3,"title":"T","body":"old"}"""),
            "GET /api/v1/notes/n1234567/export" to (200 to "# T\n\nbody"),
        ))
        val api = ApiClient(server, "tok", stub)
        assertEquals(3, api.history("n1234567").single().rev)
        assertEquals("old", api.revision("n1234567", 3).body)
        assertEquals("# T\n\nbody", api.exportNote("n1234567", public = true))
        assertTrue(stub.sent.last().url.endsWith("/export?public=1"))
    }

    @Test fun uploadsAsMultipartAndListsMedia() = runTest {
        val stub = StubTransport(mutableMapOf(
            "POST /api/v1/attachments" to (200 to """{"attachment":{"id":"a1234567","name":"x.png","kind":"image","sha256":"ab","refs":0},"duplicate":true}"""),
            "GET /api/v1/attachments" to (200 to """{"items":[{"id":"a1234567","name":"x.png","kind":"image","public":true}],"counts":{"all":1,"image":1,"orphaned":1},"policy":{"mode":"local","orphanDays":30,"trashDays":30}}"""),
        ))
        val api = ApiClient(server, "tok", stub)
        val f = java.nio.file.Files.createTempFile("ivy", ".png").toFile()
        try {
            val r = api.upload(f, "x.png", "image/png")
            assertTrue(r.duplicate)
            val req = stub.sent.last()
            assertEquals("x.png", req.upload?.name)
            assertEquals("file", req.upload?.field)
            assertEquals(null, req.body)
        } finally {
            f.delete()
        }
        val list = api.media(kind = "image", query = "a b")
        assertEquals(30, list.policy.orphanDays)
        assertTrue(list.items.single().public)
        assertTrue(stub.sent.last().url.endsWith("/attachments?kind=image&q=a%20b"))
    }
}
