import kotlinx.coroutines.CoroutineScope
import java.util.*
import kotlin.coroutines.EmptyCoroutineContext

inline fun <reified T:Any> Any.cast() :T? = this as? T

inline fun <reified T:Any> Any?.castOrError(noinline messageCreator:(Any?)->String) =
    (this as? T) ?: error(messageCreator(this))

fun ByteArray.encodeBase64():String =
    Base64.getEncoder().encodeToString(this)


object EmptyScope : CoroutineScope {
    override val coroutineContext = EmptyCoroutineContext
}
