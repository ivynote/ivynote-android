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
