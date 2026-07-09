package cn.sanrolnet.chunkup.log

import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * SL Studio Log Standard — English structured logs for Chunkup JVM layer.
 */
object ChunkupSlLog {
	private const val PROJECT_ID = "[Multi-Lang-Chunkup]"
	private val LOGGER = LoggerFactory.getLogger("chunkup.sl")
	private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
	private val EVENT_SEQ = AtomicInteger(1)

	private fun eventId(): String {
		val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
		val seq = EVENT_SEQ.getAndIncrement() % 1000
		return "CHUP-$date-${seq.toString().padStart(3, '0')}"
	}

	private fun emit(
		levelId: Int,
		levelName: String,
		module: String,
		actor: String,
		content: String,
		params: String,
		optional: String? = null,
	) {
		val ts = LocalDateTime.now().format(TS_FMT)
		val event = eventId()
		val base =
			"[$levelId-$levelName] [$ts] $PROJECT_ID [$module] [$actor] [$event] | Content:[$content] | Params:[$params]"
		val line = if (optional.isNullOrBlank()) base else "$base | $optional"
		LOGGER.info(line)
	}

	@JvmStatic
	fun infoInit(module: String, content: String, params: String) =
		emit(4, "INFO_INIT", module, "Service:chunkup_jvm", content, params)

	@JvmStatic
	fun infoStart(module: String, content: String, params: String) =
		emit(5, "INFO_START", module, "Service:chunkup_jvm", content, params)

	@JvmStatic
	fun infoProgress(module: String, content: String, params: String) =
		emit(6, "INFO_PROGRESS", module, "Service:chunkup_jvm", content, params)

	@JvmStatic
	fun infoComplete(module: String, content: String, params: String) =
		emit(7, "INFO_COMPLETE", module, "Service:chunkup_jvm", content, params)

	@JvmStatic
	fun infoStatus(module: String, content: String, params: String) =
		emit(8, "INFO_STATUS", module, "Service:chunkup_jvm", content, params)

	@JvmStatic
	fun debugFunc(module: String, content: String, params: String) =
		emit(3, "DEBUG_FUNC", module, "Service:chunkup_jvm", content, params)

	@JvmStatic
	fun warnPerf(module: String, content: String, params: String) =
		emit(10, "WARN_PARAM", module, "Service:chunkup_jvm", content, params)
}
