import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.util.isError
import kotlin.script.experimental.jvm.util.isIncomplete
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

var config = AppConfig()

private val log = LogTag("AppConfig")

@KotlinScript(displayName = "config-script")
abstract class KtsScript

fun loadConfig(file: File) {
    val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<KtsScript> {
        jvm {
            // configure dependencies for compilation, they should contain at least the script base class and
            // its dependencies

            // variant 1: try to extract current classpath and take only a path to the specified "script.jar"
            // script library jar name (exact or without a version)
            // dependenciesFromCurrentContext("script" )

            // variant 2: try to extract current classpath and use it for the compilation without filtering
            dependenciesFromCurrentContext(wholeClasspath = true)

            // variant 3: try to extract a classpath from a particular classloader (or Thread.contextClassLoader by default)
            // filtering as in the variat 1 is supported too
            // dependenciesFromClassloader(classLoader = SimpleScript::class.java.classLoader, wholeClasspath = true)

            // variant 4: explicit classpath
            // updateClasspath(listOf(File("/path/to/jar")))
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
    if( result !is ResultWithDiagnostics.Success){
        error("script evaluation failed.")
    }

    when(val resultValue = result.value.returnValue){
        is ResultValue.Value ->  when ( val newConfig =resultValue.value ) {
            is AppConfig -> config = newConfig
            null -> error("config script returns null.")
            else -> error("config script return value is not AppConfig. ${newConfig.javaClass.simpleName}")
        }
        else-> error("resultValue is $resultValue")
    }
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

