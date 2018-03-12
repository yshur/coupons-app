package controllers

import javax.inject.Inject

import scala.util.Failure

import org.joda.time.DateTime

import scala.concurrent.{ Await, Future, duration }, duration.Duration

import play.api.Logger

import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.mvc.{
  Action, AbstractController, ControllerComponents, Request
}
import play.api.libs.json.{ Json, JsObject, JsString }

import reactivemongo.api.Cursor
import reactivemongo.api.gridfs.{ GridFS, ReadFile }

import play.modules.reactivemongo.{
  MongoController, ReactiveMongoApi, ReactiveMongoComponents
}

import reactivemongo.play.json._
import reactivemongo.play.json.collection._

import models.Coupon, Coupon._

class Coupons @Inject() (
                           components: ControllerComponents,
                           val reactiveMongoApi: ReactiveMongoApi,
                           implicit val materializer: akka.stream.Materializer
                         ) extends AbstractController(components)
  with MongoController with ReactiveMongoComponents {

  import java.util.UUID
  import MongoController.readFileReads

  type JSONReadFile = ReadFile[JSONSerializationPack.type, JsString]

  implicit def ec = components.executionContext

  // get the collection 'coupons'
  def collection = reactiveMongoApi.database.
    map(_.collection[JSONCollection]("coupons"))

  // a GridFS store named 'attachments'
  //val gridFS = GridFS(db, "attachments")
  private def gridFS: Future[MongoController.JsGridFS] = for {
    db <- reactiveMongoApi.database
    fs = GridFS[JSONSerializationPack.type](db)
    _ <- fs.ensureIndex().map { index =>
      // let's build an index on our gridfs chunks collection if none
      Logger.info(s"Checked index, result is $index")
    }
  } yield fs

  // list all coupons and sort them
  val index = Action.async { implicit request =>
    // get a sort document (see getSort method for more information)
    val sort: JsObject = getSort(request).getOrElse(Json.obj())

    val activeSort = request.queryString.get("sort").
      flatMap(_.headOption).getOrElse("none")

    // the cursor of documents
    val found = collection.map(_.find(Json.obj()).sort(sort).cursor[Coupon]())

    // build (asynchronously) a list containing all the coupons
    found.flatMap(_.collect[List](-1, Cursor.FailOnError[List[Coupon]]())).
      map { coupons =>
        Ok(views.html.couponsUser(coupons, activeSort))
      }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }

  // list all coupons and sort them
  val indexAdmin = Action.async { implicit request =>
    // get a sort document (see getSort method for more information)
    val sort: JsObject = getSort(request).getOrElse(Json.obj())

    val activeSort = request.queryString.get("sort").
      flatMap(_.headOption).getOrElse("none")

    // the cursor of documents
    val found = collection.map(_.find(Json.obj()).sort(sort).cursor[Coupon]())

    // build (asynchronously) a list containing all the coupons
    found.flatMap(_.collect[List](-1, Cursor.FailOnError[List[Coupon]]())).
      map { coupons =>
        Ok(views.html.coupons(coupons, activeSort))
      }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }

  def showCreationForm = Action { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    Ok(views.html.editCoupon(None, Coupon.form, None))
  }

  def showEditForm(id: String) = Action.async { implicit request =>
    // get the documents having this id (there will be 0 or 1 result)
    def futureCoupon = collection.flatMap(
      _.find(Json.obj("_id" -> id)).one[Coupon])

    // ... so we get optionally the matching coupon, if any
    // let's use for-comprehensions to compose futures
    for {
      // get a future option of coupon
      maybeCoupon <- futureCoupon
      // if there is some coupon, return a future of result
      // with the coupon and its attachments
      fs <- gridFS
      result <- maybeCoupon.map { coupon =>
        // search for the matching attachments
        // find(...).toList returns a future list of documents
        // (here, a future list of ReadFileEntry)
        fs.find[JsObject, JSONReadFile](
          Json.obj("coupon" -> coupon.id.get)).
          collect[List](-1, Cursor.FailOnError[List[JSONReadFile]]()).
          map { files =>

            @inline def filesWithId = files.map { file => file.id -> file }
            implicit val messages = messagesApi.preferred(request)

            Ok(views.html.editCoupon(Some(id),
              Coupon.form.fill(coupon), Some(filesWithId)))
          }
      }.getOrElse(Future.successful(NotFound))
    } yield result
  }

  def create = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    Coupon.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.editCoupon(None, errors, None))),

      // if no error, then insert the coupon into the 'coupons' collection
      coupon => collection.flatMap(_.insert(coupon.copy(
        id = coupon.id.orElse(Some(UUID.randomUUID().toString)),
        creationDate = Some(new DateTime()),
        updateDate = Some(new DateTime()))
      )).map(_ => Redirect(routes.Coupons.indexAdmin()))
    )
  }

  def edit(id: String) = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)
    import reactivemongo.bson.BSONDateTime

    Coupon.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.editCoupon(Some(id), errors, None))),

      coupon => {
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = Json.obj(
          // this modifier will set the fields
          // 'updateDate', 'title', 'description', 'location','price','discount' and 'image'
          "$set" -> Json.obj(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "title" -> coupon.title,
            "description" -> coupon.description,
            "location" -> coupon.location,
            "price" -> coupon.price,
            "discount" -> coupon.discount,
            "image" -> coupon.image))

        // ok, let's do the update
        collection.flatMap(_.update(Json.obj("_id" -> id), modifier).
          map { _ => Redirect(routes.Coupons.indexAdmin()) })
      })
  }

  def delete(id: String) = Action.async {
    // let's collect all the attachments matching that match the coupon to delete
    (for {
      fs <- gridFS
      files <- fs.find[JsObject, JSONReadFile](Json.obj("coupon" -> id)).
        collect[List](-1, Cursor.FailOnError[List[JSONReadFile]]())
      _ <- {
        // for each attachment, delete their chunks and then their file entry
        def deletions = files.map(fs.remove(_))

        Future.sequence(deletions)
      }
      coll <- collection
      _ <- {
        // now, the last operation: remove the coupon
        coll.remove(Json.obj("_id" -> id))
      }
    } yield Ok).recover { case _ => InternalServerError }
  }

  // save the uploaded file as an attachment of the coupon with the given id
  def saveAttachment(id: String) = {
    lazy val fs = Await.result(gridFS, Duration("5s"))

    Action.async(gridFSBodyParser(fs)) { request =>
      // here is the future file!
      val futureFile = request.body.files.head.ref.andThen {
        case Failure(cause) => Logger.error("Fails to save file", cause)
      }

      // when the upload is complete, we add the coupon id to the file entry (in order to find the attachments of the coupon)
      val futureUpdate = for {
        file <- futureFile
        // here, the file is completely uploaded, so it is time to update the coupon
        updateResult <- fs.files.update(
          Json.obj("_id" -> file.id),
          Json.obj("$set" -> Json.obj("coupon" -> id)))
      } yield Redirect(routes.Coupons.showEditForm(id))

      futureUpdate.recover {
        case e => InternalServerError(e.getMessage())
      }
    }
  }

  def getAttachment(id: String) = Action.async { request =>
    gridFS.flatMap { fs =>
      // find the matching attachment, if any, and streams it to the client
      val file = fs.find[JsObject, JSONReadFile](Json.obj("_id" -> id))

      request.getQueryString("inline") match {
        case Some("true") =>
          serve[JsString, JSONReadFile](fs)(file, CONTENT_DISPOSITION_INLINE)

        case _            => serve[JsString, JSONReadFile](fs)(file)
      }
    }
  }

  def removeAttachment(id: String) = Action.async {
    gridFS.flatMap(_.remove(Json toJson id).map(_ => Ok).
      recover { case _ => InternalServerError })
  }

  private def getSort(request: Request[_]): Option[JsObject] =
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if (field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "title" || order._1 == "location"|| order._1 == "price"|| order._1 == "discount" || order._1 == "creationDate" || order._1 == "updateDate"
      } yield order._1 -> implicitly[Json.JsValueWrapper](Json.toJson(order._2))

      Json.obj(sortBy: _*)
    }

}
