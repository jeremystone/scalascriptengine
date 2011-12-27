package com.googlecode.scalascriptengine

import org.scala_tools.time.Imports._
import com.googlecode.concurrent.ExecutorServiceManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * periodically scans the source directories and if a file changed, it recompiles
 * and creates a new CodeVersion (changes will be visible as soon as compilation
 * completes)
 *
 * @author kostantinos.kougios
 *
 * 25 Dec 2011
 */
trait TimedRefresh { this: ScalaScriptEngine =>
	def rescheduleAt: DateTime

	private val executor = ExecutorServiceManager.newScheduledThreadPool(1, e => error("error during recompilation of %s".format(this), e))
	executor.runPeriodically(rescheduleAt, Some(rescheduleAt)) {
		refresh
	}

	def shutdown = executor.shutdown
}

/**
 * checks scala files for modification and if yes it recompiles
 * the changed sources.
 */
trait OnChangeRefresh extends ScalaScriptEngine {
	val recheckEveryMillis: Long
	private val lastChecked = new ConcurrentHashMap[String, java.lang.Long]
	private val filesCheched = new AtomicLong
	def numberOfFilesChecked = filesCheched.get
	abstract override def get[T](className: String): Class[T] = {
		val l = lastChecked.get(className)
		if (l == null || recheckEveryMillis <= 0 || System.currentTimeMillis - l > recheckEveryMillis) {
			lastChecked.put(className, System.currentTimeMillis)
			val fileName = className.replace('.', '/') + ".scala"
			val srcFileOption = sourcePaths.find(dir => new File(dir, fileName).exists).map(dir => new File(dir, fileName))
			val isMod = srcFileOption.map(f => currentVersion.isModifiedOrNew(f)).getOrElse(true)
			filesCheched.incrementAndGet
			if (isMod) doRefresh
		}
		super.get(className)
	}

	def doRefresh: Unit
}

trait RefreshSynchronously extends ScalaScriptEngine with OnChangeRefresh {
	private var lastCompiled: Long = 0
	override def doRefresh: Unit = {
		// refresh only if not already refreshing
		val time = System.currentTimeMillis
		synchronized {
			if (time > lastCompiled) try {
				refresh
			} catch {
				case e => error("error during compilation", e)
			} finally {
				// set lastCompile even in case of compilation errors
				lastCompiled = System.currentTimeMillis
			}
		}
	}
}

/**
 * makes sure the refresh is run only once at a time. All other calls
 * to refresh return straight away with the current code version
 */
trait RefreshAsynchronously extends ScalaScriptEngine with OnChangeRefresh {
	private val isCompiling = new AtomicBoolean(false)
	private val executor = ExecutorServiceManager.newSingleThreadExecutor
	override def doRefresh: Unit = {
		// refresh only if not already refreshing 
		val c = isCompiling.getAndSet(true)
		if (!c) executor.submit {
			try {
				refresh
			} catch {
				case e => error("error during refresh", e)
			} finally {
				isCompiling.set(false)
			}
		}
	}

	def shutdown = executor.shutdown
}