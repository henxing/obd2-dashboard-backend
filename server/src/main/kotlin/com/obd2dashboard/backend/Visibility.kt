package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.AccessStore
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.pluginOrNull
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.util.AttributeKey
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * What's hidden from one request (M25): every hidden car, course and event
 * for someone signed out, nothing for anyone signed in (Sam, 2026-10-15: any
 * signed-in user sees everything). Hidden from them, a thing doesn't exist:
 * `404` as for one that never did, and left out of every list.
 */
class Hidden(private val things: Set<Thing>) {
    fun car(slug: String) = Thing(Kind.CAR, slug) in things
    fun course(id: String) = Thing(Kind.COURSE, id) in things
    fun event(id: String) = Thing(Kind.EVENT, id) in things

    companion object {
        val NOTHING = Hidden(emptySet())
    }
}

/**
 * Every hidden thing, held a few seconds: public pages are read far more often
 * than anything is hidden, and `admin.sh hide` writes the store directly, so a
 * change from there shows within [HELD]. A change through the server is seen
 * at once ([forget]).
 */
class HiddenThings(private val access: AccessStore, private val clock: Clock = Clock.systemUTC()) {
    @Volatile private var held: Pair<Instant, Set<Thing>>? = null

    suspend fun all(): Set<Thing> {
        val now = clock.instant()
        held?.let { (at, things) -> if (Duration.between(at, now) < HELD) return things }
        val things = access.all().filterValues { it.hidden }.keys
        held = now to things
        return things
    }

    fun forget() {
        held = null
    }

    private companion object {
        val HELD: Duration = Duration.ofSeconds(5)
    }
}

private val HIDDEN_KEY = AttributeKey<Hidden>("Hidden")

/** What's hidden from this request, worked out once per request. */
suspend fun ApplicationCall.hidden(): Hidden {
    attributes.getOrNull(HIDDEN_KEY)?.let { return it }
    val gate = application.gate
    val hidden = if (gate.who(request.cookies[AdminAuth.COOKIE]) != null) Hidden.NOTHING else Hidden(gate.hidden.all())
    attributes.put(HIDDEN_KEY, hidden)
    return hidden
}

/**
 * What's hidden, for marking it "Hidden" (M25) to someone signed in, who sees it; nothing to anyone else,
 * from whom it's left out anyway.
 */
suspend fun ApplicationCall.hiddenMarks(): Hidden {
    val gate = application.gate
    return if (gate.who(request.cookies[AdminAuth.COOKIE]) != null) Hidden(gate.hidden.all()) else Hidden.NOTHING
}

/** `/api/cars/{slug}…`, `/api/courses/{id}…`, `/api/events/{id}…`: the thing a public path is about. */
private val THING_PATH = Regex("^/api/(cars|courses|events)/([^/]+)(/.*)?$")

/**
 * Every public read and change about one car, course or event (M25), the
 * crew's included, answers `404` for a hidden one to someone signed out,
 * before any route runs; a route added under these paths later is covered
 * too. The lists, sessions, results and drivers' pages leave hidden things
 * out themselves. Public answers can differ by sign-in now: none is kept by
 * anything shared, and a browser asks again each time (`private, no-cache`,
 * unless the route says otherwise; the series' ETags still save the bytes).
 */
val HideFromSignedOut = createApplicationPlugin("HideFromSignedOut") {
    fun isPublicApi(path: String) = path.startsWith("/api/") && !path.startsWith("/api/admin/")
    // Every route as (method, path pattern), once routing is installed: a path no route serves is left to
    // routing's own empty 404, which is what one that never existed gets there too.
    val routes by lazy {
        application.pluginOrNull(RoutingRoot)?.getAllRoutes().orEmpty().map { node ->
            val s = node.toString()
            val method = Regex("\\(method:([A-Z]+)\\)").find(s)?.groupValues?.get(1)
            val path = s.replace(Regex("/\\([^)]*\\)"), "")
            val pattern = path.split("/").joinToString("/") { if (it.startsWith("{")) "[^/]+" else Regex.escape(it) }
            method to Regex("^$pattern$")
        }
    }
    fun routed(method: String, path: String) = routes.any { (m, p) -> (m == null || m == method) && p.matches(path) }
    onCallRespond { call, _ ->
        if (!isPublicApi(call.request.path())) return@onCallRespond
        if (call.response.headers[HttpHeaders.CacheControl] == null) call.response.header(HttpHeaders.CacheControl, "private, no-cache")
        if (call.response.headers[HttpHeaders.Vary] == null) call.response.header(HttpHeaders.Vary, HttpHeaders.Cookie)
    }
    onCall { call ->
        val path = call.request.path()
        if (!isPublicApi(path)) return@onCall
        val (plural, id) = THING_PATH.find(path)?.destructured ?: return@onCall
        val hidden = call.hidden()
        val gone = when (plural) {
            "cars" -> hidden.car(id)
            "courses" -> hidden.course(id)
            else -> hidden.event(id)
        }
        // Word for word what each route says of one that never was, so a hidden one can't be told apart.
        if (gone && routed(call.request.httpMethod.value, path)) {
            val message = when {
                plural != "cars" -> "No such ${plural.removeSuffix("s")}."
                path.endsWith("/live") -> "No car \"$id\"."
                else -> "No such car."
            }
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", message))
        }
    }
}
