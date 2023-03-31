import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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

private suspend fun HttpClient.getOriginalRss(user: String): HttpResponse =
    request(url = Url("${config.nitterUrl}/$user/rss")) {
        config.nitterVirtualHost
            ?.let { header("Host", it) }
        config.nitterBasicAuth?.toByteArray()?.encodeBase64()
            ?.let { header("Authorization", "Basic $it") }
    }

suspend fun ApplicationCall.handleRequest(
    db: Connection,
    client: HttpClient,
    removeRt: Boolean,
) {
    val name = parameters["name"]
    if (name.isNullOrEmpty()) {
        respond(HttpStatusCode.BadRequest, "missing name in url path.")
        return
    }

    log.i("requested. [$name]")

    try {
        var rssString = client.getOriginalRss(name)
            .bodyAsText(Charsets.UTF_8)
            .replace(reNitterUrl, "twitter.com")

        if (removeRt) {
            rssString = rssString.replace(reRtItem, "")
        }

        respondText(
            text = rssString,
            contentType = rssContentType
        )
    } catch (ex: ClientRequestException) {
        respond(ex.response.status, ex.message)
        return
    }

    // 成功時はリクエストを受けたことを覚えておく
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
            val response: HttpResponse = client.getOriginalRss(user)
            log.i("warmUp: ${response.status} [$user]")
            true
        } catch (ex: Throwable) {
            if (ex is ClientRequestException) {
                log.e("warmUp: ${ex.response.status} ${ex.response.request.url}")
            } else {
                log.e(ex, "warmUp: error. [$user]")
            }
            false
        }

    // キューの項目で処理するべきものがあればそのnameを返す
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
            // 最大コネクション数
            maxConnectionsCount = 1000

            // エンドポイント固有の設定
            endpoint {
                // 特定のエンドポイントに対するリクエストの最大数
                maxConnectionsPerRoute = 500

                // コネクションごとのスケジュール済リクエストの最大数 (パイプラインのキューのサイズ)
                pipelineMaxSize = 500

                // 接続のアイドル状態 (keep-alive) を維持する最大時間 (ミリ秒)
                keepAliveTime = 5000

                // サーバへの接続のタイムアウト値 (ミリ秒)
                connectTimeout = 30000
                socketTimeout = 30000

                // 接続ごとのリトライの最大値
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
                val includeRetweet = when (context.request.queryParameters["rt"]) {
                    null, "0", "false" -> false
                    else -> true
                }
                context.handleRequest(db, client, removeRt = !includeRetweet)
            }
        }
    }.start(wait = true)
}
