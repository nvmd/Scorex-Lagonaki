package scorex.props

import java.io.File

import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scorex.crypto.ads.merkle.AuthDataBlock
import scorex.perma.Storage.AuthDataStorage
import scorex.perma.consensus._
import scorex.perma.settings.Constants.DataSegment
import scorex.perma.settings.{Constants, PermaSettings}
import scorex.settings.Settings
import scorex.storage.Storage
import scorex.utils._

class PermaConsensusBlockFiendSpecification extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers {

  implicit val settings = new Settings with PermaSettings {
    val filename = "settings-test.json"
  }
  new File(settings.treeDir).mkdirs()
  implicit lazy val authDataStorage: Storage[Long, AuthDataBlock[DataSegment]] = new AuthDataStorage(settings.authDataStorage)
  val consensus = new PermaConsensusModule(randomBytes(32))

  property("Encode to bytes round-trip") {
    forAll { (diff: Long, segmentIndex: Long) =>

      val puz = randomBytes(PermaConsensusBlockField.PuzLength)
      val pubkey = randomBytes(PermaConsensusBlockField.PublicKeyLength)
      val s = randomBytes(PermaConsensusBlockField.SLength)
      val signature = randomBytes(PermaConsensusBlockField.SignatureLength)
      val signature2 = randomBytes(PermaConsensusBlockField.SignatureLength)
      val blockdata = randomBytes(Constants.segmentSize)
      val hash1 = randomBytes(PermaConsensusBlockField.HashLength)
      val hash2 = randomBytes(PermaConsensusBlockField.HashLength)

      val authDataBlock: AuthDataBlock[DataSegment] = AuthDataBlock(blockdata, Seq(hash1, hash2))
      val initialBlock = PermaConsensusBlockField(PermaLikeConsensusBlockData(
        math.abs(diff),
        puz,
        Ticket(pubkey, s, IndexedSeq(
          PartialProof(signature, segmentIndex, authDataBlock),
          PartialProof(signature2, segmentIndex, authDataBlock)
        ))
      ))
      val parsedBlock = PermaConsensusBlockField.parse(initialBlock.bytes)

      checkAll(initialBlock, parsedBlock)

    }
  }

  property("Encode to bytes round-trip for genesis") {
    val initialBlock = consensus.genesisData
    val parsedBlock = PermaConsensusBlockField.parse(initialBlock.bytes)
    checkAll(initialBlock, parsedBlock)
  }

  def checkAll(initialBlock: PermaConsensusBlockField, parsedBlock: PermaConsensusBlockField): Unit = {
    parsedBlock.value.target shouldBe initialBlock.value.target
    assert(parsedBlock.value.puz sameElements initialBlock.value.puz)
    assert(parsedBlock.value.ticket.publicKey sameElements initialBlock.value.ticket.publicKey)
    assert(parsedBlock.value.ticket.s sameElements initialBlock.value.ticket.s)
    parsedBlock.value.ticket.proofs.size shouldBe initialBlock.value.ticket.proofs.size
    parsedBlock.value.ticket.proofs.indices.foreach { i =>
      val parsedProof = parsedBlock.value.ticket.proofs(i)
      val initialProof = initialBlock.value.ticket.proofs(i)
      assert(parsedProof.signature sameElements initialProof.signature)
      parsedProof.segmentIndex shouldBe initialProof.segmentIndex
      assert(parsedProof.segment.data sameElements initialProof.segment.data)
      parsedProof.segment.merklePath.size shouldBe initialProof.segment.merklePath.size

      if (initialProof.segment.merklePath.nonEmpty) {
        assert(parsedProof.segment.merklePath.head sameElements initialProof.segment.merklePath.head)
        assert(parsedProof.segment.merklePath.last sameElements initialProof.segment.merklePath.last)
      }
    }
  }
}