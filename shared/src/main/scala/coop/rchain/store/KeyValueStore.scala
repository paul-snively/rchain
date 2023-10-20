package coop.rchain.store

import java.nio.ByteBuffer

trait KeyValueStore[F[_]] {
  def get[T](keys: Vector[ByteBuffer], fromBuffer: ByteBuffer => T): F[Vector[Option[T]]]

  def put[T](kvPairs: Vector[(ByteBuffer, T)], toBuffer: T => ByteBuffer): F[Unit]

  def delete(keys: Vector[ByteBuffer]): F[Int]

  def iterate[T](f: Iterator[(ByteBuffer, ByteBuffer)] => T): F[T]
}
