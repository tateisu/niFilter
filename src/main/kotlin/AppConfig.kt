import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

var config = AppConfig()

private val log = LogTag("AppConfig")

@KotlinScript(displayName = "config-script")
abstract class KtsScript

fun loadConfig(file: File) {
    val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<KtsScript> {
        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
        }
    }

    val result = BasicJvmScriptingHost().eval(
        file.toScriptSource(),
        compilationConfiguration,
        null
    )

    result.reports.forEach {
        if (it.severity > ScriptDiagnostic.Severity.DEBUG) {
            log.w(it.toString())
        }
    }

    result.castOrError<ResultWithDiagnostics.Success<EvaluationResult>> {
        "script evaluation failed. $it"
    }.value.returnValue.castOrError<ResultValue.Value> {
        "script evaluation result is $it"
    }.value.castOrError<AppConfig> {
        "script returns value, but it's not AppConfig. ${it?.javaClass?.simpleName}"
    }.let { config = it }
}

data class AppConfig(

    // ---------------------------------------------

    // HTTP listen addr
    val listenAddr: String = "localhost",

    // HTTP listen port
    val listenPort: Int = 8000,

    // ---------------------------------------------

    // nitter server url prefix
    val nitterUrl: String = "http://localhost",

    // nitter server にアクセスする際にHostヘッダを補う
    val nitterVirtualHost: String? = null,

    // Basic認証をのユーザとパスワードをコロンで連結したもの
    val nitterBasicAuth: String? = null,

    // ---------------------------------------------

    // 起動時にプロセスIDをファイルに出力する
    val pidFile: String? = "nitterFilter2.pid",

    // SQLITE3 データベースのファイルパス
    val sqliteFile: String = "nitterFilter2.db",

    // DB新規作成時にopmlファイルを読んでDBエントリを温める
    val subscriptions: String? = null,
)
