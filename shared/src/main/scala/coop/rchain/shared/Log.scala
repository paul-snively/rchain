package coop.rchain.shared

import cats._
import cats.effect.Sync
import cats.syntax.all._
import cats.tagless._

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

trait LogSource {
  val clazz: Class[_]
}

object LogSource {
  def apply(c: Class[_]): LogSource = new LogSource {
    val clazz: Class[_] = c
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null")) // false-positive
  implicit def matLogSource: LogSource = macro LogSourceMacros.mkLogSource
}

class LogSourceMacros(val c: blackbox.Context) {
  import c.universe._

  def mkLogSource: c.Expr[LogSource] = {
    val tree =
      q"""
          coop.rchain.shared.LogSource(${c.reifyEnclosingRuntimeClass}.asInstanceOf[Class[_]])
       """

    c.Expr[LogSource](tree)
  }
}

trait Log[F[_]] {
  def trace(msg: => String)(implicit ev: LogSource): F[Unit]
  def debug(msg: => String)(implicit ev: LogSource): F[Unit]
  def info(msg: => String)(implicit ev: LogSource): F[Unit]
  def warn(msg: => String)(implicit ev: LogSource): F[Unit]
  def warn(msg: => String, cause: Throwable)(implicit ev: LogSource): F[Unit]
  def error(msg: => String)(implicit ev: LogSource): F[Unit]
  def error(msg: => String, cause: Throwable)(implicit ev: LogSource): F[Unit]
}

object Log extends LogInstances {
  def apply[F[_]](implicit L: Log[F]): Log[F] = L

  class NOPLog[F[_]: Applicative] extends Log[F] {
    def trace(msg: => String)(implicit ev: LogSource): F[Unit]                   = ().pure[F]
    def debug(msg: => String)(implicit ev: LogSource): F[Unit]                   = ().pure[F]
    def info(msg: => String)(implicit ev: LogSource): F[Unit]                    = ().pure[F]
    def warn(msg: => String)(implicit ev: LogSource): F[Unit]                    = ().pure[F]
    def warn(msg: => String, cause: Throwable)(implicit ev: LogSource): F[Unit]  = ().pure[F]
    def error(msg: => String)(implicit ev: LogSource): F[Unit]                   = ().pure[F]
    def error(msg: => String, cause: Throwable)(implicit ev: LogSource): F[Unit] = ().pure[F]
  }

  // FunctorK
  implicit class LogMapKOps[F[_]](val log: Log[F]) extends AnyVal {
    def mapK[G[_]](nt: F ~> G): Log[G] = new Log[G] {
      override def trace(msg: => String)(implicit ev: LogSource): G[Unit] = nt(log.trace(msg))
      override def debug(msg: => String)(implicit ev: LogSource): G[Unit] = nt(log.debug(msg))
      override def info(msg: => String)(implicit ev: LogSource): G[Unit]  = nt(log.info(msg))
      override def warn(msg: => String)(implicit ev: LogSource): G[Unit]  = nt(log.warn(msg))
      override def warn(msg: => String, cause: Throwable)(implicit ev: LogSource): G[Unit] =
        nt(log.warn(msg, cause))
      override def error(msg: => String)(implicit ev: LogSource): G[Unit] = nt(log.error(msg))
      override def error(msg: => String, cause: Throwable)(implicit ev: LogSource): G[Unit] =
        nt(log.error(msg, cause))
    }
  }
}

sealed abstract class LogInstances {

  def log[F[_]: Sync](implicit ev: LogSource): Log[F] = new Log[F] {
    val logger: Logger[F] = Slf4jLogger.getLoggerFromClass(ev.clazz)

    def trace(msg: => String)(implicit ev: LogSource): F[Unit] =
      logger.trace(msg)
    def debug(msg: => String)(implicit ev: LogSource): F[Unit] =
      logger.debug(msg)
    def info(msg: => String)(implicit ev: LogSource): F[Unit] =
      logger.info(msg)
    def warn(msg: => String)(implicit ev: LogSource): F[Unit] =
      logger.warn(msg)
    def warn(msg: => String, cause: Throwable)(implicit ev: LogSource): F[Unit] =
      logger.warn(cause)(msg)
    def error(msg: => String)(implicit ev: LogSource): F[Unit] =
      logger.error(msg)
    def error(msg: => String, cause: Throwable)(implicit ev: LogSource): F[Unit] =
      logger.error(cause)(msg)
  }
}
