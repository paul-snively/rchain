package coop.rchain.shared

import monix.execution.UncaughtExceptionReporter

import org.slf4j.LoggerFactory

object UncaughtExceptionLogger extends UncaughtExceptionReporter {
  implicit private val logSource: LogSource = LogSource(this.getClass)
  private val log                           = LoggerFactory.getLogger(logSource.clazz)

  def reportFailure(ex: scala.Throwable): Unit =
    log.error("Uncaught Exception", ex)
}
