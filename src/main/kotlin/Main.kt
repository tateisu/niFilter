import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.lang.management.ManagementFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import kotlin.concurrent.thread

private val log = LogTag("Main")

const val intervalAfterSuccess = 3600000L
const val intervalAfterError = 300000L

val myProcessId = ManagementFactory.getRuntimeMXBean().name.split("@")[0]

val reNitterUrl = """nitter\.juggler\.jp(?!/pic/)""".toRegex()
val reRtItem = """<item>\s*<title>RT .+?</item>""".toRegex(RegexOption.DOT_MATCHES_ALL)
val rssContentType = ContentType.Application.Rss.withParameter("charset", "utf-8")

val shutdowns = LinkedList<() -> Unit>()
val shutdownThread = thread(start = false) {
    shutdowns.forEach {
        try {
            it.invoke()
        } catch (ex: Throwable) {
            log.e(ex)
        }
    }
}

suspend inline fun <reified T> getOriginalRss(
    client: HttpClient,
    name: String,
): T = client.get("${config.nitterUrl}/$name/rss") {
    headers {
        config.nitterVirtualHost
            ?.let { set("Host", it) }
        config.nitterBasicAuth?.toByteArray()?.encodeBase64()
            ?.let { set("Authorization", "Basic $it") }
    }
}

suspend fun ApplicationCall.handleRequest(db: Connection, client: HttpClient) {
    val name = parameters["name"]
    if (name.isNullOrEmpty()) {
        respond(HttpStatusCode.BadRequest, "missing name in url path.")
        return
    }

    log.i("requested. [$name]")

    try {
        val rssString = getOriginalRss<String>(client, name)
            .replace(reNitterUrl, "twitter.com")
            .replace(reRtItem, "")

        respondText(
            text = rssString,
            contentType = rssContentType
        )
    } catch (ex: ClientRequestException) {
        respond(ex.response.status, ex.message)
        return
    }

    // ???????????????????????????????????????????????????????????????
    val now = System.currentTimeMillis()
    """insert into queue(name_lower,name,requested_at,next_load)
        values(?,?,?,?) 
        on conflict(name_lower) do update set requested_at=?,next_load=?
    """.trimIndent().execute(
        db,
        name.lowercase(),
        name,
        now,
        now + intervalAfterSuccess,
        now,
        now + intervalAfterSuccess,
    )
}


fun launchTimer(db: Connection, client: HttpClient) = EmptyScope.launch(Dispatchers.IO) {

    suspend fun warmUp(user: String) =
        try {
            val response = getOriginalRss<HttpResponse>(client, user)
            log.i("${response.status} [$user]")
            true
        } catch (ex: Throwable) {
            if (ex is ClientRequestException) {
                log.e("[$user] ${ex.response.status}")
            } else {
                log.e(ex, "[$user] warmUp failed.")
            }
            false
        }


    // ???????????????????????????????????????????????????????????????name?????????
    fun checkQueue(now: Long): String? {
        return """select name from queue
         where requested_at >=? and next_load <=? 
         order by next_load asc limit 1
         """.trimIndent()
            .query1(
                db,
                now - 86400000L,
                now,
            )
            ?.let { it["name"] as? String }
    }

    while (true) {
        try {
            delay(10000L)
            val now = System.currentTimeMillis()
            checkQueue(now)?.let { user ->
                val nextLoad = now + when (warmUp(user)) {
                    true -> intervalAfterSuccess
                    else -> intervalAfterError
                }
                """update queue set next_load=? where name_lower=?"""
                    .update(db, nextLoad, user.lowercase())
            }
        } catch (ex: Throwable) {
            if (ex is CancellationException) {
                log.w(ex, "timer cancelled.")
                break
            } else {
                log.e(ex)
            }
        }
    }
}

fun loadSubscriptions(db: Connection) {
    config.subscriptions?.let { fileName ->
        log.i("read $fileName")
        val xmlString = File(fileName).readBytes().toString(Charsets.UTF_8)
        val reAttrXmlUrl = """\bxmlUrl="([^"]+)""".toRegex()
        val reRssUrl = """\Qhttps://nitter.juggler.jp/x/\E([^/]+)/rss""".toRegex()
        val now = System.currentTimeMillis()
        reAttrXmlUrl.findAll(xmlString).forEach {
            val url = it.groupValues[1]
            reRssUrl.find(url)?.groupValues?.elementAtOrNull(1)
                ?.let { name ->
                    log.i("record user $name")
                    """insert into queue(name_lower,name,requested_at)
                        values(?,?,?) 
                        on conflict(name_lower) do update set requested_at=?
                    """.trimIndent().execute(
                        db,
                        name.lowercase(),
                        name,
                        now,
                        now,
                    )
                }
        }
    }
}

fun main(args: Array<String>) {

    (args.elementAtOrNull(0)
        ?: error("usage: java -jar NitterFilter2.jar <config.kts>"))
        .let { loadConfig(File(it)) }

    Runtime.getRuntime().addShutdownHook(shutdownThread)

    log.i("myProcessId=$myProcessId")

    config.pidFile
        ?.let { File(it) }
        ?.writeBytes(myProcessId.toByteArray(Charsets.UTF_8))

    val newDb = !File(config.sqliteFile).exists()

    val db = DriverManager.getConnection("jdbc:sqlite:${config.sqliteFile}")
    shutdowns.addFirst {
        log.i("closing db")
        db.close()
    }

    """create table if not exists queue
            (name_lower text not null primary key
            ,name text not null
            ,requested_at integer not null
            ,next_load integer not null default 0
            )
        """.trimIndent().execute(db)

    """create index if not exists queue_next_load on queue
            (next_load
            ,requested_at
            )
        """.trimIndent().execute(db)

    if (newDb) loadSubscriptions(db)

    val client = HttpClient(CIO) {
        engine {
            // ???????????????????????????
            maxConnectionsCount = 1000

            // ????????????????????????????????????
            endpoint {
                // ?????????????????????????????????????????????????????????????????????
                maxConnectionsPerRoute = 500

                // ??????????????????????????????????????????????????????????????????????????? (??????????????????????????????????????????)
                pipelineMaxSize = 500

                // ??????????????????????????? (keep-alive) ??????????????????????????? (?????????)
                keepAliveTime = 5000

                // ????????????????????????????????????????????? (?????????)
                connectTimeout = 30000
                socketTimeout = 30000

                // ???????????????????????????????????????
                connectAttempts = 5
            }
        }
    }
    shutdowns.addFirst {
        log.i("closing client")
        client.close()
    }

    val timerJob = launchTimer(db, client)
    shutdowns.addFirst {
        log.i("closing timerJob")
        timerJob.cancel()
    }

    embeddedServer(
        Netty,
        host = config.listenAddr,
        port = config.listenPort,
    ) {
        routing {
            get("/x/{name}/rss") {
                context.handleRequest(db, client)
            }
        }
    }.start(wait = true)
}
