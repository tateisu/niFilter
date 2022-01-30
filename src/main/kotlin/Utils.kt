import kotlinx.coroutines.CoroutineScope
import java.util.*
import kotlin.coroutines.EmptyCoroutineContext

fun ByteArray.encodeBase64():String =
    Base64.getEncoder().encodeToString(this)

object EmptyScope : CoroutineScope {
    override val coroutineContext = EmptyCoroutineContext
}
