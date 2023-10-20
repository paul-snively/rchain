package coop.rchain.store

import java.nio.ByteBuffer

trait KeyValueStore[F[_]] {
  def get[T](keys: List[ByteBuffer], fromBuffer: ByteBuffer => T): F[List[Option[T]]]

  def put[T](kvPairs: List[(ByteBuffer, T)], toBuffer: T => ByteBuffer): F[Unit]

  def delete(keys: List[ByteBuffer]): F[Int]

  def iterate[T](f: Iterator[(ByteBuffer, ByteBuffer)] => T): F[T]
}
