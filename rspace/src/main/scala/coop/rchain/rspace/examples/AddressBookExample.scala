package coop.rchain.rspace.examples

import cats.effect.{Concurrent, ContextShift}
import cats.{Applicative, Id}
import coop.rchain.metrics.{Metrics, NoopSpan, Span}
import coop.rchain.rspace.syntax.rspaceSyntaxKeyValueStoreManager
import coop.rchain.rspace.util.{runKs, unpackOption, unpackSeq}
import coop.rchain.rspace._
import coop.rchain.shared.Language.ignore
import coop.rchain.shared.{Log, Serialize}
import coop.rchain.store.InMemoryStoreManager
import scodec.bits.ByteVector

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

@SuppressWarnings(Array("org.wartremover.warts.EitherProjectionPartial"))
object AddressBookExample {

  /* Here we define a type for channels */

  final case class Channel(name: String)

  /* Ordering for Channel */

  implicit val channelOrdering: Ordering[Channel] =
    (x: Channel, y: Channel) => x.name.compare(y.name)

  /* Here we define a type for data */

  final case class Name(first: String, last: String)
  final case class Address(street: String, city: String, state: String, zip: String)
  final case class Entry(name: Name, address: Address, email: String, phone: String)

  /* Here we define a type for patterns */

  sealed trait Pattern                       extends Product with Serializable
  final case class NameMatch(last: String)   extends Pattern
  final case class CityMatch(city: String)   extends Pattern
  final case class StateMatch(state: String) extends Pattern

  /* Here we define a type for continuations */

  class Printer extends ((Seq[Entry]) => Unit) with Serializable {

    def apply(entries: Seq[Entry]): Unit =
      entries.foreach {
        case Entry(name, address, email, phone) =>
          val nameStr = s"${name.last}, ${name.first}"
          val addrStr = s"${address.street}, ${address.city}, ${address.state} ${address.zip}"
          Console.printf(s"""|
                             |=== ENTRY ===
                             |name:    $nameStr
                             |address: $addrStr
                             |email:   $email
                             |phone:   $phone
                             |""".stripMargin)
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  class EntriesCaptor extends ((Seq[Entry]) => Unit) with Serializable {

    @transient
    private final lazy val res: ListBuffer[Seq[Entry]] = ListBuffer.empty[Seq[Entry]]

    final def results: Seq[Seq[Entry]] = res.toList

    final def apply(v1: Seq[Entry]): Unit = ignore(res += v1)

    override def hashCode(): Int =
      res.hashCode() * 37

    override def equals(obj: scala.Any): Boolean = obj match {
      case ec: EntriesCaptor => ec.res == res
      case _                 => false
    }
  }

  object implicits {

    /* Now I will troll Greg... */

    /* Serialize instances */

    /**
      * An instance of [[Serialize]] for [[Channel]]
      */
    implicit val serializeChannel: Serialize[Channel] = new Serialize[Channel] {

      def encode(channel: Channel): ByteVector = {
        val baos = new ByteArrayOutputStream()
        try {
          val oos = new ObjectOutputStream(baos)
          try {
            oos.writeObject(channel)
          } finally {
            oos.close()
          }
          ByteVector.view(baos.toByteArray)
        } finally {
          baos.close()
        }
      }

      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
      def decode(bytes: ByteVector): Either[Throwable, Channel] =
        try {
          val bais = new ByteArrayInputStream(bytes.toArray)
          try {
            val ois = new ObjectInputStream(bais)
            try {
              Right(ois.readObject.asInstanceOf[Channel])
            } finally {
              ois.close()
            }
          } finally {
            bais.close()
          }
        } catch {
          case ex: Throwable => Left(ex)
        }
    }

    /**
      * An instance of [[Serialize]] for [[Pattern]]
      */
    implicit val serializePattern: Serialize[Pattern] = makeSerializeFromSerializable[Pattern]

    /**
      * An instance of [[Serialize]] for [[Entry]]
      */
    implicit val serializeInfo: Serialize[Entry] = makeSerializeFromSerializable[Entry]

    /**
      * An instance of [[Serialize]] for [[Printer]]
      */
    implicit val serializePrinter: Serialize[Printer] = makeSerializeFromSerializable[Printer]

    /**
      * An instance of [[Serialize]] for [[EntriesCaptor]]
      */
    implicit val serializeEntriesCaptor: Serialize[EntriesCaptor] =
      makeSerializeFromSerializable[EntriesCaptor]

    /* Match instance */

    /**
      * An instance of [[Match]] for [[Pattern]] and [[Entry]]
      */
    implicit def matchPatternEntry[F[_]](
        implicit apF: Applicative[F]
    ): Match[F, Pattern, Entry] =
      (p: Pattern, a: Entry) =>
        p match {
          case NameMatch(last) if a.name.last == last        => apF.pure(Some(a))
          case CityMatch(city) if a.address.city == city     => apF.pure(Some(a))
          case StateMatch(state) if a.address.state == state => apF.pure(Some(a))
          case _                                             => apF.pure(None)
        }
  }

  import implicits._

  // Let's define some Entries
  val alice = Entry(
    name = Name("Alice", "Lincoln"),
    address = Address("777 Ford St.", "Crystal Lake", "Idaho", "223322"),
    email = "alicel@ringworld.net",
    phone = "787-555-1212"
  )

  val bob = Entry(
    name = Name("Bob", "Lahblah"),
    address = Address("1000 Main St", "Crystal Lake", "Idaho", "223322"),
    email = "blablah@tenex.net",
    phone = "698-555-1212"
  )

  val carol = Entry(
    name = Name("Carol", "Lahblah"),
    address = Address("22 Goldwater Way", "Herbert", "Nevada", "334433"),
    email = "carol@blablah.org",
    phone = "232-555-1212"
  )
}
