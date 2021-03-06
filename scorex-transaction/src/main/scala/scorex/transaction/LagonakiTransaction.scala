package scorex.transaction

import play.api.libs.json.Json
import scorex.account.Account
import scorex.crypto.Base58
import scorex.transaction.LagonakiTransaction.{ValidationResult, _}


abstract class LagonakiTransaction(val transactionType: TransactionType.Value,
                                   val recipient: Account,
                                   val amount: Long,
                                   override val fee: Long,
                                   override val timestamp: Long,
                                   override val signature: Array[Byte]) extends Transaction {

  //24HOUR DEADLINE TO INCLUDE TRANSACTION IN BLOCK
  lazy val deadline = timestamp + (1000 * 60 * 60 * 24)

  lazy val feePerByte = fee / dataLength.toDouble
  lazy val hasMinimumFee = fee >= MinimumFee
  lazy val hasMinimumFeePerByte = {
    val minFeePerByte = 1.0 / MaxBytesPerToken
    feePerByte >= minFeePerByte
  }

  val TypeId = transactionType.id

  //PARSE/CONVERT
  val dataLength: Int

  val creator: Option[Account]


  def isSignatureValid(): Boolean

  //VALIDATE

  def validate()(implicit transactionModule: SimpleTransactionModule): ValidationResult.Value

  def involvedAmount(account: Account): Long

  def balanceChanges(): Map[Account, Long]

  override def equals(other: Any) = other match {
    case tx: LagonakiTransaction => signature.sameElements(tx.signature)
    case _ => false
  }

  protected def jsonBase() = {
    Json.obj("type" -> transactionType.id,
      "fee" -> fee,
      "timestamp" -> timestamp,
      "signature" -> Base58.encode(this.signature)
    )
  }
}

object LagonakiTransaction {

  val MaxBytesPerToken = 512

  //MINIMUM FEE
  val MinimumFee = 1
  val RecipientLength = Account.AddressLength
  val TypeLength = 1
  val TimestampLength = 8
  val AmountLength = 8

  object ValidationResult extends Enumeration {
    type ValidationResult = Value

    val ValidateOke = Value(1)
    val InvalidAddress = Value(2)
    val NegativeAmount = Value(3)
    val NegativeFee = Value(4)
    val NoBalance = Value(5)
  }

  //TYPES
  object TransactionType extends Enumeration {
    val GenesisTransaction = Value(1)
    val PaymentTransaction = Value(2)
  }

  def parse(data: Array[Byte]): LagonakiTransaction = data.head match {
    case txType: Byte if txType == TransactionType.GenesisTransaction.id =>
      GenesisTransaction.parse(data.tail)

    case txType: Byte if txType == TransactionType.PaymentTransaction.id =>
      PaymentTransaction.parse(data.tail)

    case txType => throw new Exception(s"Invalid transaction type: $txType")
  }
}