package net.cazadescuentos.background

import java.util.UUID

import io.circe.syntax._
import net.cazadescuentos.Config
import net.cazadescuentos.background.alarms.ProductUpdaterAlarm
import net.cazadescuentos.background.models.{Command, Event}
import net.cazadescuentos.background.services.DataMigrationService
import net.cazadescuentos.background.services.browser.BrowserNotificationService
import net.cazadescuentos.background.services.http.HttpMigrationService
import net.cazadescuentos.background.services.storage.{ProductStorageService, StorageMigrationService, StorageService}
import net.cazadescuentos.common.I18NMessages
import net.wiringbits.cazadescuentos.api.PushNotificationService
import net.wiringbits.cazadescuentos.api.http.ProductHttpService
import org.scalajs.dom
import org.scalajs.dom.experimental.serviceworkers.toServiceWorkerNavigator

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class Runner(
    pushNotificationService: PushNotificationService,
    dataMigrationService: DataMigrationService,
    commandProcessor: CommandProcessor,
    productUpdaterAlarm: ProductUpdaterAlarm
)(implicit ec: ExecutionContext) {

  def run(): Unit = {
    log("This was run by the background script")
    dataMigrationService
      .migrate()
      .foreach { buyerId =>
        processExternalMessages(buyerId)
        productUpdaterAlarm.register(buyerId)

        val result = for {
          _ <- registerServiceWorker()
            .recover {
              case NonFatal(ex) =>
                println(s"Failed to register service worker: ${ex.getMessage}")
                throw ex
            }
          _ <- pushNotificationService.enableNotifications(buyerId)
        } yield ()

        result.onComplete {
          case Success(_) =>
            println("Push notifications enabled")

          case Failure(ex) =>
            println(s"Failed to enable push notifications: ${ex.getMessage}")
        }
      }
  }

  private def registerServiceWorker(): Future[Unit] = {
    dom.window.navigator.serviceWorker
      .register("/service-worker.js")
      .toFuture
      .map(_ => ())
  }

  private def processExternalMessages(buyerId: UUID): Unit = {
    chrome.runtime.Runtime.onMessage.listen { message =>
      message.value.foreach { any =>
        val response = Future
          .fromTry { Try(any.asInstanceOf[String]).flatMap(Command.decode) }
          .map { cmd =>
            log(s"Got command = $cmd")
            cmd
          }
          .flatMap(x => commandProcessor.process(buyerId, x))
          .recover {
            case NonFatal(ex) =>
              log(s"Failed to process command, error = ${ex.getMessage}")
              Event.CommandRejected(ex.getMessage)
          }
          .map(_.asJson.noSpaces)

        /**
         * NOTE: When replying on futures, the method returnins an async response is the only reliable one
         * otherwise, the sender is getting no response, the way use the async method is to pass a response
         * in case of failures even if that case was already handled with the CommandRejected event.
         */
        message.response(response, "Impossible failure")
      }
    }
  }

  private def log(msg: String): Unit = {
    println(s"background: $msg")
  }
}

object Runner {

  def apply(config: Config)(implicit ec: ExecutionContext): Runner = {
    val legacyStorageService = new ProductStorageService()
    val storageService = new StorageService()

    implicit val sttpBackend = sttp.client.FetchBackend()
    val http = new ProductHttpService.DefaultImpl(config.httpConfig)

    val pushNotificationService = PushNotificationService(http)
    val messages = new I18NMessages
    val browserNotificationService = new BrowserNotificationService(messages)

    val productUpdaterAlarm = new ProductUpdaterAlarm(
      config.productUpdaterConfig
    )

    val commandProcessor = new CommandProcessor(storageService, http, browserNotificationService)
    val storageMigrationService = new StorageMigrationService(storageService, legacyStorageService)
    val httpMigrationService = new HttpMigrationService(http)
    val dataMigrationService =
      new DataMigrationService(storageMigrationService, httpMigrationService, legacyStorageService)
    new Runner(
      pushNotificationService,
      dataMigrationService,
      commandProcessor,
      productUpdaterAlarm
    )
  }
}
