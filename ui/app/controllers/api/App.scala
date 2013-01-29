package controllers.api

import language.existentials
import play.api.mvc.{ Action, Controller }
import play.api.libs.json.{ JsString, JsObject, JsArray, JsNumber }
import play.api.Play
import sys.process.Process
import java.io.File
import snap.RootConfig
import snap.ProjectConfig
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.Play.current
import com.typesafe.sbtchild.SbtChildProcessMaker
import scala.concurrent.Promise
import play.api.libs.json.JsObject

object App extends Controller {

  // this is supposed to be set by the main() launching the UI
  @volatile var sbtChildProcessMaker: SbtChildProcessMaker = _

  // TODO as a first cut, just cache one app. As long as only one tab
  // is open this should be right, but of course it will be pure
  // fail when two tabs are open... the real solution should be
  // to tie one snap.App to each websocket, which will be associated
  // with a tab, then we kill the App along with the socket.
  @volatile var singleAppCache: Option[(File, Future[snap.App])] = None

  private def loadApp(location: String): Future[snap.App] = {
    // main() is supposed to set this
    require(sbtChildProcessMaker ne null)

    val locationFile = (new File(location)).getAbsoluteFile()

    singleAppCache match {

      case Some((file, cached)) if file == locationFile =>
        cached

      case whatever => {
        val projectConfigFuture =
          RootConfig.user.projects.find(p => p.location.getAbsoluteFile() == locationFile) match {
            case Some(project) => Promise.successful(project).future
            case None => {
              val config = ProjectConfig(locationFile)
              // add ourselves to history
              RootConfig.rewriteUser { root =>
                root.copy(projects = (config +: root.projects))
              } map { Unit => config }
            }
          }
        val appFuture = for (projectConfig <- projectConfigFuture)
          yield new snap.App(projectConfig, play.api.libs.concurrent.Akka.system, sbtChildProcessMaker)
        synchronized {
          singleAppCache.foreach(_._2.map(_.close()))
          singleAppCache = Some((locationFile, appFuture))
        }

        // TODO initially kick off loading the app's name from sbt

        appFuture
      }
    }
  }

  // load info on an app at the given location.
  // As a side effect, this fires up an sbt child pool on the app.
  def openApp(location: String) = Action { request =>
    Async(loadApp(location).map(app => Ok(app.config.toJson)))
  }

  // list all apps in the config
  def getHistory = Action { request =>
    Ok(JsArray(RootConfig.user.projects.map(_.toJson)))
  }

  // TODO this is just a stub. we need to figure out
  // how we actually compute and store the plugin list.
  // probably this should be merged with openApp above.
  // NOTE - This event even used
  def getDetails(location: String) = Action { request =>
    val plugins = JsArray(
      Seq(
        JsObject(
          Seq(
            "id" -> JsString("code"),
            "name" -> JsString("Code"))),
        JsObject(
          Seq(
            "id" -> JsString("run"),
            "name" -> JsString("Run"))),
        JsObject(
          Seq(
            "id" -> JsString("test"),
            "name" -> JsString("Test"))),
        JsObject(
          Seq(
            "id" -> JsString("console"),
            "name" -> JsString("Console")))))

    Ok(
      JsObject(
        Seq(
          "name" -> JsString("My First Amazing App"),
          "plugins" -> plugins)))
  }

  def openLocation(location: String) = Action { request =>
    if ((new File(location)).exists()) {

      val command = System.getProperty("os.name") match {
        case "Linux" => "/usr/bin/xdg-open file://" + location
      }

      Runtime.getRuntime.exec(command)

      Ok("")
    } else {
      NotAcceptable("The location was not found: " + location)
    }
  }

  def startApp(location: String) = Action { request =>

    Process(Seq(Play.current.path.getParentFile + "/snap", "~run"), new File(location)).run()

    Ok("")

  }

}
