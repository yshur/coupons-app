package models

import org.joda.time.DateTime

import play.api.data._
import play.api.data.Forms.{ text, longNumber, mapping, nonEmptyText, optional }
import play.api.data.validation.Constraints.pattern

import reactivemongo.bson.{
  BSONDateTime, BSONDocument, BSONObjectID
}

case class Coupon (
                    id: Option[String],
                    title: String,
                    description: String,
                    location: String,
                    price: String,
                    discount: String,
                    image: String,
                    creationDate: Option[DateTime],
                    updateDate: Option[DateTime])

// Turn off your mind, relax, and float downstream
// It is not dying...
object Coupon {
  import play.api.libs.json._

  implicit object CouponWrites extends OWrites[Coupon] {
    def writes(coupon: Coupon): JsObject = Json.obj(
      "_id" -> coupon.id,
      "title" -> coupon.title,
      "description" -> coupon.description,
      "location" -> coupon.location,
      "price" -> coupon.price,
      "discount" -> coupon.discount,
      "image" -> coupon.image,
      "creationDate" -> coupon.creationDate.fold(-1L)(_.getMillis),
      "updateDate" -> coupon.updateDate.fold(-1L)(_.getMillis))
  }
  implicit object CouponReads extends Reads[Coupon] {
    def reads(json: JsValue): JsResult[Coupon] = json match {
      case obj: JsObject => try {
        val id = (obj \ "_id").asOpt[String]
        val title = (obj \ "title").as[String]
        val description = (obj \ "description").as[String]
        val location = (obj \ "location").as[String]
        val price = (obj \ "price").as[String]
        val discount = (obj \ "discount").as[String]
        val image = (obj \ "image").as[String]
        val creationDate = (obj \ "creationDate").asOpt[Long]
        val updateDate = (obj \ "updateDate").asOpt[Long]
        JsSuccess(Coupon(id, title, description, location, price, discount,image,
          creationDate.map(new DateTime(_)),
          updateDate.map(new DateTime(_))))

      } catch {
        case cause: Throwable => JsError(cause.getMessage)
      }

      case _ => JsError("expected.jsobject")
    }
  }

  val form = Form(
    mapping(
      "id" -> optional(text verifying pattern(
        """[a-fA-F0-9]{24}""".r, error = "error.objectId")),
      "title" -> nonEmptyText,
      "description" -> text,
      "location" -> text,
      "price" -> nonEmptyText,
      "discount" -> text,
      "image" -> text,
      "creationDate" -> optional(longNumber),
      "updateDate" -> optional(longNumber)) {

      (id, title, description, location, price, discount,image, creationDate, updateDate) =>
        Coupon(
          id,
          title,
          description,
          location,
          price,
          discount,
          image,
          creationDate.map(new DateTime(_)),
          updateDate.map(new DateTime(_)))
    } { coupon =>
      Some(
        (coupon.id,
          coupon.title,
          coupon.description,
          coupon.location,
          coupon.price,
          coupon.discount,
          coupon.image,
          coupon.creationDate.map(_.getMillis),
          coupon.updateDate.map(_.getMillis)))
    })
}
