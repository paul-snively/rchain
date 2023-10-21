package coop.rchain.rspace.history

import coop.rchain.rspace.hashing.Blake2b256Hash

sealed trait HistoryAction extends Product with Serializable {
  def key: KeySegment
}

final case class InsertAction(key: KeySegment, hash: Blake2b256Hash) extends HistoryAction
final case class DeleteAction(key: KeySegment)                       extends HistoryAction
