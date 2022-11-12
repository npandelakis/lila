package lila.irwin

import org.joda.time.DateTime
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

object JSONHandlers:

  import IrwinReport.*

  implicit val moveReader: Reads[MoveReport] = (
    (__ \ "a").read[Int] and           // activation
      (__ \ "r").readNullable[Int] and // rank
      (__ \ "m").read[Int] and         // ambiguity
      (__ \ "o").read[Int] and         // odds
      (__ \ "l").read[Int]             // loss
  )(MoveReport.apply)

  private given Reads[GameReport] = Json.reads

  implicit val reportReader: Reads[IrwinReport] = (
    (__ \ "userId").read[String] and
      (__ \ "activation").read[Int] and
      (__ \ "games").read[List[GameReport]] and
      (__ \ "owner").read[String] and
      Reads(_ => JsSuccess(DateTime.now))
  )(IrwinReport.apply)
