import org.slf4j.LoggerFactory

class LogTag(category: String) {
    private val logger = LoggerFactory.getLogger(category)

    fun e(ex: Throwable?, msg: String = "exception.") = logger.error(msg, ex)
    fun w(ex: Throwable?, msg: String = "exception.") = logger.warn(msg, ex)
    fun i(ex: Throwable?, msg: String) = logger.info(msg, ex)
    fun d(ex: Throwable?, msg: String) = logger.debug(msg, ex)

    fun e(msg: String) = logger.error(msg)
    fun w(msg: String) = logger.warn(msg)
    fun i(msg: String) = logger.info(msg)
    fun d(msg: String) = logger.debug(msg)
}
